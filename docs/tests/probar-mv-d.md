# Probar MV-D - Contexto relevante

MV-D (#309) es la cuarta PR del hito MV «Memoria viva». Lo explica [`docs/memoria.md`](../memoria.md), en «Qué recibe cada agente».

- **El bloque `<memoria-del-proyecto>` se reparte** el tope (4 000 caracteres) en secciones, en este orden:
  - el **índice de la wiki** (hasta el 20 %), entero o nada;
  - los **errores conocidos que tocan este trabajo** (hasta el 20 %): trampas y bugs confirmados y hallazgos corregidos del repositorio que comparten palabras o ficheros con el trabajo;
  - **lo más pertinente para este trabajo** (hasta el 25 %): las demás entradas confirmadas que más se le parecen;
  - **lo fijado y lo último**, con lo que quede: lo de antes.
- **Cada entrada va una sola vez**, y lo que no cabe se deja fuera entero.
- **Dos herramientas MCP nuevas, de solo lectura:** `wiki_index` (el índice y la lista de páginas) y `wiki_read` (una página por su `slug`).
- **`memory_access` registra la wiki** (migración V59: `wiki_page_id`): el índice cuando va en el bloque (`PROMPT`) o en `mem_context` (`CONTEXT`), y cada página leída (`GET`). `GET /api/agent-runs/{id}/memory` lo trae en `wiki`.
- La web todavía no enseña la wiki (MV-F).

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WikiContextIT' --tests '*MemoryAgentIT' --tests '*MemoryTrustIT' \
    --tests '*MigrationsIT' --tests '*ModularityTest'
./gradlew :runner:test --tests '*MemoryMcpServerTest' --tests '*AgentMemoryTest'
```

`WikiContextIT`:
- con una trampa sobre `src/Pdf.java`, una nota que comparte palabras con el trabajo, otra que no tiene que ver, un hallazgo corregido en `src/Pdf.java` y la wiki generada, el trabajo «Exportar las notas con tildes en src/Pdf.java» empieza con:
  - el índice de la wiki, con «- [Lo hecho](lo-hecho)»;
  - en «Errores conocidos que tocan este trabajo», la trampa y «[HALLAZGO CORREGIDO] src/Pdf.java: Falta escapar las tildes (alta)»;
  - en «Lo más pertinente para este trabajo», la nota parecida;
  - en «Lo fijado y lo último», la que no tiene que ver;
  - cada una una sola vez y en ese orden;
- `mem_context` da lo mismo; `wiki-index` devuelve «La wiki de notas…» y las cuatro páginas; `wiki-read` de `errores-conocidos` trae la página entera; una página que no existe da 404 y sin `slug`, 400;
- `memory_access` tiene `indice PROMPT`, `indice CONTEXT`, `indice GET` y `errores-conocidos GET`, y `GET /api/agent-runs/{id}/memory` los lleva en `wiki`;
- sin wiki, el bloque es el de antes y `wiki_index` dice «La wiki de este proyecto todavía está vacía.».

`MemoryMcpServerTest` comprueba que `wiki_index` y `wiki_read` están en las dos modalidades (`read` y `write`) y se anuncian con `readOnlyHint: true`.

## 2. Skynet desplegado, sin GitHub

Trabaja sobre `demo-repo`. El runner usa fake-claude con `FAKE_MCP_CALLS=@.memoria.json` y `FAKE_MCP_LOG=.memoria.log`, como en [`probar-mv-a.md`](probar-mv-a.md#2-skynet-desplegado-sin-github): el agente hace las llamadas MCP que deja la fase `guion` y apunta las respuestas en `.memoria.log` del worktree. Si no las tiene, añádelas a su entorno y a `SKYNET_AGENT_ENV`, y reinícialo.

Las llamadas a la API van con tu sesión (`$API` y `$AUTH`, como en las otras guías).

### 2.1 Prepara el proyecto

1. En la pestaña **Memoria** del proyecto de `demo-repo`, guarda como persona (quedan confirmadas):
   - una **Trampa** «MV-D tildes en el PDF», con «Qué»: «Pasa en src/Pdf.java al escribir»;
   - un **Descubrimiento** «MV-D las notas se exportan por alumno», con «Qué»: «El exportador agrupa»;
   - un **Descubrimiento** «MV-D login con Google», con «Qué»: «Usa OAuth».
2. Regenera la wiki: `curl -s $AUTH -X POST "$API/api/projects/<proyecto>/wiki/refresh" | jq '.[].slug'`.
   **Esperado:** las cuatro páginas, entre ellas `indice`.
3. Crea un trabajo de `demo-repo` titulado «Exportar las notas con tildes en src/Pdf.java».

### 2.2 El bloque va repartido

Publica en **Workflows → Nuevo**:

```yaml
id: mv-d-contexto
name: MV-D contexto
stages:
  - id: guion
    type: command
    command: |
      cat > .memoria.json <<'JSON'
      [{"tool":"mem_context","arguments":{}},
       {"tool":"wiki_index","arguments":{}},
       {"tool":"wiki_read","arguments":{"slug":"errores-conocidos"}},
       {"tool":"wiki_read","arguments":{"slug":"no-existe"}}]
      JSON
  - id: agente
    type: agent
    prompt: Usa la memoria del proyecto
    memory: read
    dependsOn: [guion]
  - id: ver
    type: command
    command: cat .memoria.log
    dependsOn: [agente]
```

Lánzalo sobre el trabajo de 2.1 y espera a que termine bien. Abre la fase `ver` y su salida: cada línea es la respuesta de una llamada (la primera es `tools/list`).

**Esperado:**
1. En `tools/list` están `wiki_index` y `wiki_read`, aunque la fase va con `memory: read`, y no están `mem_save` ni `mem_session_summary`.
2. La respuesta de `mem_context` lleva, en este orden:
   - «## Índice de la wiki (lee una página con wiki_read)» con las líneas de las páginas;
   - «## Errores conocidos que tocan este trabajo» con «MV-D tildes en el PDF»;
   - «## Lo más pertinente para este trabajo» con «MV-D las notas se exportan por alumno»;
   - «## Lo fijado y lo último» con «MV-D login con Google» (y la bitácora de las ejecuciones anteriores);
   - ninguna entrada repetida.
3. `wiki_index` empieza por «La wiki de …» y acaba con «Páginas (léelas con wiki_read):» y las cuatro.
4. El primer `wiki_read` empieza por «# Errores conocidos (errores-conocidos, v…)» y lleva «MV-D tildes en el PDF».
5. El segundo `wiki_read` es un error de la herramienta (`isError: true`) con el 404, y el agente sigue.

### 2.3 Queda registrado

Con el id del agente de la fase `agente`:

```bash
curl -s $AUTH "$API/api/agent-runs/<agente>/memory" | jq '.wiki[] | {slug, via, version}'
```

**Esperado:** `indice` con `PROMPT`, `CONTEXT` y `GET`, y `errores-conocidos` con `GET`, en la versión que tenían. `no-existe` no aparece. En `.accesses`, «MV-D tildes en el PDF» sale como `PROMPT` y `CONTEXT`.

### 2.4 Un trabajo que no tiene que ver

Crea otro trabajo, «Cambiar el color del botón», y lanza `mv-d-contexto` sobre él.

**Esperado:** en `mem_context` no están las secciones de errores ni de lo pertinente; salen el índice y «Lo fijado y lo último» con las tres entradas MV-D.

## 3. Lo que no cambia

- El tope del bloque (`SKYNET_MEMORY_CONTEXT_MAX_CHARS`), lo de no llevar nada sin confirmar, archivado o en cuarentena, y el aviso de «¿ya está hecho?».
- Una continuación (mensaje, reanudación o bifurcación) sigue sin repetir el bloque.
- Las páginas de la wiki y su API (MV-C) no cambian, ni la web.
