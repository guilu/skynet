# Probar MV-B - ¿Ya está hecho?

MV-B (#307) es la segunda PR del hito MV «Memoria viva». Lo explica [`docs/memoria.md`](../memoria.md), en «¿Ya está hecho?».

- **Antes de lanzar**, Skynet compara el trabajo con los ya entregados del proyecto, que son los que tienen una PR fusionada. No usa ningún modelo:
  - toma las palabras del título y la descripción, cortadas a sus cinco primeras letras;
  - calcula su parecido (Jaccard).
- **Parecido fuerte** (desde 0,6, `SKYNET_MEMORY_DONE_SIMILARITY`), o el mismo trabajo ya entregado:
  - lanzar desde la API responde 409 con `code: DONE_MATCH`, el mensaje «Esto parece ya hecho: …» y `doneMatches`;
  - con `confirmDone: true` se lanza;
  - desde una issue de GitHub no se lanza, y lo comenta («No lo lanzo: parece ya hecho: …»).
- **Parecido leve** (desde 0,3, `SKYNET_MEMORY_DONE_HINT`): se lanza sin preguntar.
- **En la ejecución:** el evento `memory.done_match`, con los parecidos y si se confirmó.
- **En el agente:**
  - su bloque `<memoria-del-proyecto>` lleva «Trabajos parecidos que ya se entregaron en este proyecto», con su PR y su parecido;
  - hay una herramienta MCP nueva, `mem_done`, de solo lectura.
- **API:** `GET /api/work-items/{id}/done-matches`.
- **Sin migración.** Los disparadores programados y de despliegue no se comparan.
- **La web todavía no tiene el botón de confirmar.** Desde la web, un parecido fuerte enseña el error con el mensaje. Lanzarlo igualmente, por ahora, se hace por la API; la web llega en la PR siguiente.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*DoneWorkIT' --tests '*IssueTriggerIT' \
  --tests 'dev.skynet.controlplane.memory.*' :runner:test --tests '*Memory*'
```

- `DoneWorkIT`:
  - «Añadir exportación de notas en PDF» se parece fuerte a «Exportar las notas a PDF», ya entregado en la PR #40;
  - «Exportar notas a Excel» se parece leve, y «Arreglar el login con Google», nada;
  - sin confirmar: 409 `DONE_MATCH`, `doneMatches` y ninguna ejecución creada;
  - confirmado: se lanza con `memory.done_match` (`confirmed: true`). Lo leve se lanza con `confirmed: false`;
  - relanzar el mismo trabajo entregado también pide confirmarlo («este mismo trabajo»);
  - el bloque del agente lleva «- AKA-1 «Exportar las notas a PDF» se entregó en la PR #40 … (parecido 50 %)», y sin parecidos no lleva nada.
- `IssueTriggerIT.whatLooksAlreadyDeliveredIsNotLaunchedFromGitHub`:
  - la issue «Añadir la resta», con «Añadir resta()» ya entregada, queda `REJECTED` con «parece ya hecho: DEMO-1 … lanza DEMO-2 desde Skynet»;
  - se comenta en la issue;
  - relabelar la issue ya entregada tampoco la relanza.
- `MemoryMcpServerTest` y `AgentMemoryTest`: `mem_done` está en la lista, como herramienta de solo lectura, también con `memory: read`.

## 2. Skynet desplegado, sin GitHub

Trabaja sobre `demo-repo`. «Entregado» quiere decir PR fusionada, y sin GitHub no hay ninguna. Para probarlo, marca a mano como fusionada la PR de una ejecución de prueba. Si no tiene PR, crea la fila. Hazlo en la base de datos del despliegue **de pruebas**, nunca en la de producción:

```sql
-- La ejecución de prueba (de un trabajo «Exportar las notas a PDF» en demo-repo):
INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id, agent_run_id,
    full_name, branch, title, body, status, number, url, created_at, updated_at)
SELECT gen_random_uuid(), s.workflow_run_id, r.repository_id, s.id, a.id, 'guilu/demo-repo',
    'b', 't', 'b', 'MERGED', 40, 'https://github.com/guilu/demo-repo/pull/40', now(), now()
FROM stage_run s JOIN agent_run a ON a.stage_run_id = s.id
JOIN workflow_run r ON r.id = s.workflow_run_id
WHERE s.workflow_run_id = '<id de la ejecución>' LIMIT 1;
```

### 2.1 Lo parecido

1. Crea un trabajo «Añadir exportación de notas en PDF» en el mismo proyecto.
2. Pide sus parecidos con `curl -s $AUTH "$API/api/work-items/<id>/done-matches" | jq`.

**Esperado:** el trabajo de «Exportar las notas a PDF», con `strong: true`, `pullRequestNumber: 40` y su URL.

### 2.2 Lanzar sin confirmar y confirmando

1. Lánzalo desde la web. **Esperado:** no se lanza, y el error dice «Esto parece ya hecho: DEMO-… «Exportar las notas a PDF» se entregó en la PR #40 el …».
2. Lánzalo por la API con `confirmDone`:

   ```bash
   curl -s $AUTH -H 'Content-Type: application/json' \
     -d '{"repositoryId":"<repo>","prompt":"haz algo","confirmDone":true}' \
     "$API/api/work-items/<id>/runs" | jq .id
   ```

   **Esperado:** se lanza. En el historial de la ejecución aparece `memory.done_match` con `confirmed: true`.
3. Abre el agente de esa ejecución y mira su prompt. **Esperado:** el bloque `<memoria-del-proyecto>` termina con «Trabajos parecidos que ya se entregaron en este proyecto…» y la línea del trabajo entregado.

### 2.3 Lo leve no pregunta

Crea y lanza desde la web «Exportar notas a Excel».

**Esperado:**
- se lanza sin preguntar;
- `memory.done_match` con `confirmed: false`;
- el bloque lo avisa «(parecido 50 %)».

### 2.4 `mem_done` desde un agente

Con fake-claude y `FAKE_MCP_CALLS=@.memoria.json` (ver la guía de MV-A), publica un workflow cuyo guion sea `[{"tool":"mem_done","arguments":{}}]` y lánzalo sobre el trabajo del Excel.

**Esperado:** en la conversación del agente, la respuesta de `mem_done` lista el trabajo entregado, con `similarity: 0.5` y `strong: false`.

### 2.5 (Opcional) Con GitHub, sobre `demo-repo`

Con una regla de disparador activa, pon la etiqueta a una issue nueva cuyo título se parezca mucho al de una issue ya entregada con PR fusionada.

**Esperado:**
- en la issue: «No lo lanzo: parece ya hecho: … Si hay que hacerlo igualmente, lanza DEMO-… desde Skynet»;
- en `GET /api/triggers/launches`, `REJECTED` con ese motivo.

## 3. Lo que no cambia

- Lanzar trabajos que no se parecen a nada entregado va igual.
- Las ejecuciones programadas y de despliegue se lanzan igual.
- Las demás herramientas de memoria no cambian.
