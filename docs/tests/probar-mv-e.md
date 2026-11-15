# Probar MV-E - Curador de la memoria

MV-E (#310) es la quinta PR del hito MV «Memoria viva». Lo explica [`docs/memoria.md`](../memoria.md), en «El curador».

- **`memory: curate`**, un valor nuevo de la clave `memory`, solo en fases `agent`:
  - el agente lee como con `read` (su token de memoria es de lectura y no guarda);
  - su bloque inicial lleva «Para curar: lo confirmado desde la última curación» y cómo devolver lo que escribe.
- **Al terminar bien**, su salida estructurada se aplica sola:
  - `pages` escribe páginas de la wiki de su repositorio (o del proyecto con `scope: project`) con origen `CURATOR`;
  - `proposals` deja propuestas de archivar o fusionar entradas, pendientes;
  - `summary` es el resumen del informe.
- **Lo que no puede:** tocar las páginas que genera Skynet ni las de una persona, usar un slug que no valga o proponer sobre entradas de fuera o archivadas. Todo eso queda en el informe, en `rejected`, sin hacer fallar nada.
- **Las propuestas las decide una persona:** `POST /api/memory/proposals/{id}/accept` o `…/reject`.
- **Los informes** se leen en `GET /api/projects/{id}/memory/curations`. La curación siguiente parte de la fecha del último.
- **Workflow nuevo** `curar-memoria` en `workflows/`: escribe Arquitectura, Módulos y Convenciones y revisa duplicados, contradicciones y caducadas. Se lanza a mano o con un disparador programado.
- **Migración V60:** `memory_curation` y `memory_proposal`, y `CURATE` en `agent_run.memory` y en `memory_token.mode`.
- La web todavía no enseña los informes (MV-F).

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. El curador es fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*MemoryCurationIT' --tests '*DefinitionParserTest' \
    --tests '*SeedWorkflowsTest' --tests '*MemoryAgentIT' --tests '*MigrationsIT' --tests '*ModularityTest'
./gradlew :runner:test --tests '*Memory*'
```

`MemoryCurationIT`:
- en un proyecto con dos decisiones repetidas, una configuración vieja y una página `convenciones` escrita por una persona:
  - el bloque del curador dice «Para curar: lo confirmado desde la última curación (es la primera)», con las tres entradas y cómo devolver su salida;
  - su token es `READ` y guardar da 403;
- al terminar bien con su salida:
  - el informe tiene el resumen, `arquitectura` (repositorio) y `modulos` (proyecto) escritas, y seis cosas rechazadas: `indice`, `convenciones`, `mal nombre`, una entrada que no existe, una propuesta sin motivo y una acción que no se conoce;
  - quedan dos propuestas pendientes, la de fusionar y la de archivar;
  - `arquitectura` tiene origen `CURATOR`, la firma `curador` y una sola fuente (la que existe); `convenciones` sigue como la dejó la persona; el índice nombra las páginas nuevas;
- aceptar la fusión archiva la repetida y deja la buena; rechazar el archivo no archiva nada; decidir otra vez da 409 y una propuesta que no existe, 404;
- la curación siguiente dice «No hay nada confirmado nuevo desde la última curación», y una entrada nueva sí sale;
- un curador que no devuelve nada deja un informe con «El curador no devolvió nada.».

`DefinitionParserTest` comprueba que `memory: curate` vale en una fase `agent` y da error en una `parallel`. `SeedWorkflowsTest` publica `curar-memoria` con los modelos de serie.

## 2. Skynet desplegado, sin GitHub

Trabaja sobre `demo-repo`. El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_STRUCTURED_OUTPUT=@/tmp/mv-e/salida.json`, pon el nombre en `SKYNET_AGENT_ENV` y reinícialo, como en [`probar-es-c.md`](probar-es-c.md). Haz `mkdir -p /tmp/mv-e` en esa máquina.

Las llamadas a la API van con tu sesión (`$API` y `$AUTH`, como en las otras guías).

### 2.1 Prepara la memoria

1. Publica los workflows de serie con `scripts/seed-workflows.sh` (o publica `workflows/curar-memoria.yaml` en **Workflows → Nuevo**).
   **Esperado:** sale `curar-memoria`, «Curar la memoria».
2. En la pestaña **Memoria** del proyecto de `demo-repo`, guarda como persona:
   - una **Decisión** «MV-E fusionar con squash» («Qué»: «Las PRs van con squash»);
   - otra **Decisión** «MV-E las PRs van con squash» («Qué»: «Squash siempre»);
   - una **Configuración** «MV-E usar Java 17» («Qué»: «Compila con 17»).
3. Apunta sus ids:

   ```bash
   curl -s $AUTH "$API/api/projects/<proyecto>/memory?q=MV-E" | jq -r '.items[] | "\(.id) \(.title)"'
   ```

### 2.2 El curador recibe lo nuevo y escribe

1. En la máquina del runner, deja la salida del curador con esos ids:

   ```bash
   cat > /tmp/mv-e/salida.json <<'JSON'
   {"summary": "MV-E: dos decisiones repetidas y una configuración vieja.",
    "pages": [
      {"slug": "arquitectura", "title": "Arquitectura", "body": "MV-E: demo-repo es una sola aplicación.", "sources": ["<id fusionar con squash>"]},
      {"slug": "convenciones", "title": "Convenciones", "body": "MV-E: ramas por tarea y squash."},
      {"slug": "indice", "title": "Índice", "body": "No vale"}],
    "proposals": [
      {"action": "merge", "into": "<id fusionar con squash>", "observations": ["<id las PRs van con squash>"], "reason": "Dicen lo mismo"},
      {"action": "archive", "observations": ["<id usar Java 17>"], "reason": "demo-repo ya compila con 21"}]}
   JSON
   ```

2. Crea un trabajo «Curar la memoria» en `demo-repo` y lanza `curar-memoria` sobre él. Espera a que termine bien.
3. El prompt que se guarda en la fase es el del workflow; el bloque de memoria va en la orden de arranque. Míralo en la base de datos **de pruebas**, nunca en la de producción, con el id del agente de la fase `curar`:

   ```sql
   SELECT payload->>'prompt' FROM runner_command WHERE agent_run_id = '<agente>' AND type = 'START';
   ```

   **Esperado:**
   - lleva «## Para curar: lo confirmado desde la última curación (es la primera)», con las tres entradas MV-E y su id;
   - lleva «Eres el curador de la memoria…».
4. Pide el informe:

   ```bash
   curl -s $AUTH "$API/api/projects/<proyecto>/memory/curations" | jq '.[0] | {summary, pages, rejected, proposals: [.proposals[] | {id, action, status}]}'
   ```

   **Esperado:**
   - el resumen «MV-E: dos decisiones…»;
   - `arquitectura` y `convenciones` en `pages`, con `scope: repository` y versión 1;
   - en `rejected`, `indice` con «Esta página la genera Skynet…»;
   - dos propuestas `PENDING`: `MERGE` y `ARCHIVE`.
5. Lee la página: `curl -s $AUTH "$API/api/projects/<proyecto>/wiki/arquitectura?repositoryId=<repo>" | jq '{origin, updatedBy, sources}'`.
   **Esperado:** `CURATOR`, `curador` y una fuente con el título «MV-E fusionar con squash».
6. Lee el índice: `curl -s $AUTH "$API/api/projects/<proyecto>/wiki/indice" | jq -r .body`.
   **Esperado:** en «Otras páginas» están Arquitectura y Convenciones.

### 2.3 Una persona decide

1. Acepta la fusión: `curl -s $AUTH -X POST "$API/api/memory/proposals/<id merge>/accept" | jq '{status, decidedBy}'`.
   **Esperado:** `ACCEPTED` y tu usuario. En la pestaña Memoria, «MV-E las PRs van con squash» está archivada y «MV-E fusionar con squash» sigue.
2. Rechaza la otra: `…/<id archive>/reject`. **Esperado:** `REJECTED`, y «MV-E usar Java 17» no se archiva.
3. Acepta otra vez la misma. **Esperado:** 409, «La propuesta ya está decidida: REJECTED».

### 2.4 La siguiente curación parte de esta

1. Guarda otra entrada, «MV-E nueva tras curar», y lanza `curar-memoria` otra vez sobre el mismo trabajo.
   **Esperado:** el bloque de «Para curar» lleva solo «MV-E nueva tras curar», sin «(es la primera)».
2. Mira su informe (2.2, paso 4). Con la misma salida de 2.2:
   - la fusión sale en `rejected` («… no es del proyecto o está archivada»), porque la repetida ya está archivada;
   - el archivo de «MV-E usar Java 17» vuelve a proponerse, porque el anterior se rechazó y no queda ninguno igual pendiente;
   - `arquitectura` y `convenciones` siguen en la versión 1, con `changed: false`.

### 2.5 Programado (opcional)

En la ficha de `demo-repo`, **Disparadores → Nuevo disparador → «A una hora (cron)»** con `curar-memoria` y `0 0 9 * * MON`, y pulsa **Disparar ya**.
**Esperado:** en unos segundos sale una ejecución de «curar-memoria (programado)» y, al terminar, un informe nuevo en `…/memory/curations`.

Al acabar, quita `FAKE_CLAUDE_STRUCTURED_OUTPUT` del runner, borra `/tmp/mv-e` y reinícialo. Archiva las entradas MV-E si no las quieres.

## 3. Lo que no cambia

- `memory: read`, `write` y `off` van igual, y el bloque de las demás fases no lleva la sección del curador.
- Las páginas que genera Skynet y su API (MV-C), y `wiki_index` y `wiki_read` (MV-D).
- Una fase con `memory: curate` que falla o se cancela no aplica nada.
