# Probar ME-D: la memoria en la web, E2E y documentación

ME-D ([#153](https://github.com/guilu/skynet/issues/153), parte de [#141](https://github.com/guilu/skynet/issues/141)) cierra el hito ME.

Qué trae:
- **Pestaña Memoria en cada proyecto:**
  - una línea de tiempo con lo fijado primero y después por días;
  - búsqueda y filtros por repositorio, tipo, fijadas y archivadas;
  - el detalle en un panel lateral, con su procedencia (enlaces a la ejecución y a la invocación), todas sus versiones y el aviso si se enmascaró un secreto;
  - las acciones de editar (crea una versión nueva), fijar o desfijar, y archivar o restaurar.
- **Pestaña Memoria en el detalle de cada agente:** lo que recibió, lo que buscó o leyó y lo que guardó, cada entrada en la versión que vio y enlazada a la pestaña del proyecto. La sirve un endpoint nuevo, `GET /api/agent-runs/{id}/memory`.
- **Workflow inicial `investigar-mejoras`:** un agente de Claude Code con `memory: read` que repasa la bitácora y los hallazgos guardados, comprueba en el código qué sigue abierto y propone mejoras por prioridad como resultado estructurado, sin cambiar nada.
- **E2E `memory.e2e.ts`:**
  - una ejecución guarda una decisión con `mem_save`;
  - otra nueva la recibe en el prompt y la encuentra con `mem_search`;
  - la web enseña las dos cosas.

  Para eso, el runner de las E2E pasa `FAKE_MCP_CALLS=@.memoria.json` a los fakes.
- **Documentación:** [`docs/memoria.md`](../memoria.md) y la [ADR-0002](../adr/0002-memoria-de-proyecto.md).

No hay migraciones nuevas ni dependencias nuevas, y no cambia la exposición de red.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 a 4 usan la web y la API del control plane local. El paso 5 lanza un agente real sobre demo-repo (proyecto DEMO), nunca sobre skynet.

## 1. Tests

```bash
./gradlew build --no-daemon
cd web && npm run lint && npm run format:check && npm run typecheck && npx vitest run
./scripts/e2e.sh e2e/memory.e2e.ts
```

- `MemoryAgentIT`: `GET /api/agent-runs/{id}/memory` da el modo y el proyecto, y para cada acceso su vía y su versión. Lo que guardó una invocación sale en `SAVE`; lo que recibió, buscó y leyó la otra, en `PROMPT`, `SEARCH`, `GET` y `CONTEXT`. Una invocación que no existe da 404.
- `SeedWorkflowsTest`: `investigar-mejoras` se puede publicar con los modelos por defecto y con todos rellenos.
- Web: `ProjectMemory.test.tsx` (línea de tiempo y filtros; detalle con procedencia, versiones y fijar; editar) y `MemoryTab.test.tsx` (los tres apartados del agente, y una invocación sin memoria).
- E2E `memory.e2e.ts`: el criterio de aceptación del hito, de punta a punta con fake-claude y el servidor MCP del runner de verdad.

## 2. La pestaña Memoria del proyecto

Con el control plane y el runner en marcha (`http://localhost:8080`) y alguna ejecución ya hecha en DEMO después de ME-C, abre el proyecto DEMO y la pestaña **Memoria**:

1. Sale la bitácora de cada ejecución («Ejecución … : terminada bien»), con «de Skynet», y lo que guardaron los agentes, con «de un agente».
2. Escribe `squash` (o una palabra de una entrada) en la búsqueda: la lista se filtra. Prueba también los filtros de repositorio y de tipo, y el chip **Fijadas**.
3. Abre una entrada:
   - se ven su contenido, la procedencia con «Ver la ejecución» (lleva a `/runs/<id>`) y «Ver la invocación», y sus versiones;
   - **Fijar**: el botón pasa a «Desfijar» y, al cerrar el panel, la entrada sale arriba, en «Fijadas»;
   - **Editar**: cambia el «Qué» y guarda. Sale `v2` (o la siguiente), y en «Versiones» están las dos;
   - **Archivar**: desaparece de la lista. Con el chip **Archivadas** vuelve a salir; ábrela y **Restaurar**.
4. La URL lleva `?tab=memory&entry=<id>`: al recargar se abre la misma entrada.

## 3. Secreto enmascarado

```bash
api() { curl -s -u "admin:$SKYNET_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
P=$(api localhost:8080/api/projects | jq -r '.[] | select(.key=="DEMO") | .id')
api -X POST localhost:8080/api/projects/$P/memory -d '{"kind":"CONFIG","title":"Clave de pruebas",
  "what":"La clave es AKIAABCDEFGHIJKLMNOP"}' | jq '{what, redacted}'
```

Sale `"what": "La clave es [REDACTED]"` y `redacted: true`. En la web, la entrada tiene el icono de escudo y, al abrirla, el aviso «Se enmascaró un secreto al guardarla». Archívala al terminar.

## 4. La pestaña Memoria del agente

Abre una ejecución de DEMO posterior a ME-C, elige su agente y la pestaña **Memoria** (puede estar en el menú «Más»):
- «Lo que recibió» lista las entradas del bloque del prompt, con su versión;
- «Lo que buscó o leyó» y «Lo que guardó» dicen lo que hizo con las herramientas;
- cada título lleva a la entrada en la pestaña del proyecto.

En un agente de una fase con `memory: off`, o en un comando, dice que no usó la memoria.

## 5. `investigar-mejoras` con un agente real (demo-repo)

Publica los workflows iniciales si aún no está (`scripts/seed-workflows.sh`) y lanza **Investigar mejoras** en un trabajo de DEMO sobre demo-repo, con el foco vacío:
- el agente busca en la memoria (en su pestaña Memoria salen búsquedas y lecturas) y no guarda nada;
- su resultado estructurado tiene `summary` y una lista de `improvements` con prioridad, esfuerzo y las entradas de la memoria en las que se basa;
- no cambia ningún fichero del repositorio.
