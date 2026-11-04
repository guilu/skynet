# Probar FS-B - Datos operativos de la fábrica

FS-B es la segunda PR del hito FS (issue #227, milestone 86). Prepara los datos que necesitan las pantallas nuevas (Inicio, proyecto como centro de operaciones y trabajo con intentos). Casi no cambia lo que se ve.

- **Repositorio del trabajo:** la migración V44 añade `work_item.repository_id`. Rellena los trabajos existentes con el repositorio de su última ejecución, de su último lanzamiento por disparador o de la regla que lo creó. Si no hay ninguno, queda vacío.
- **Al crear un trabajo:**
  - si el proyecto tiene un solo repositorio activo, el trabajo lo hereda;
  - si tiene varios, el formulario «Nuevo trabajo» ofrece el selector «Repositorio», con la opción «Lo decido al lanzarlo».
- **Al lanzar:** un trabajo sin repositorio adopta el de su primera ejecución. El formulario de lanzar del trabajo propone su repositorio.
- **Fase derivada:** cada trabajo tiene una fase, que no se guarda sino que se calcula:
  - Pendiente (`TODO`), En curso (`RUNNING`), Esperándote (`WAITING`), Atascado (`STUCK`), En revisión (`REVIEW`) y Hecho (`DONE`);
  - el orden de prioridad es: cerrado → Hecho; aprobación pendiente → Esperándote; ejecución activa → En curso; PR abierta → En revisión; PR fusionada o última ejecución correcta → Hecho; última ejecución fallida → Atascado; si no, Pendiente.
- **API nueva**, toda de solo lectura y con los trabajos archivados excluidos:
  - `GET /api/projects/{id}/overview`: cifras del proyecto (30 días) y desglose por repositorio;
  - `GET /api/projects/{id}/changes`: las PR del proyecto con su trabajo y su ejecución;
  - `GET /api/results`: las PR de todos los proyectos;
  - `GET /api/work-items?phase=…&projectId=…`: trabajos con fase, intentos, coste y PR (sin `phase`, todos menos los hechos);
  - `GET /api/work-items/{id}/summary`: el resumen de un trabajo.
- **`GET /api/work-items/{id}`** y la lista del proyecto añaden `repositoryId` y `phase`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. Las ejecuciones de la §3 usan fake-claude sobre demo-repo.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_it \
  ./gradlew :control-plane:test --tests '*WorkPhaseTest' --tests '*WorkspaceIT' \
  --tests '*ContractIT' --tests '*MigrationsIT' --tests '*ModularityTest'
./gradlew :control-plane:spotlessCheck :control-plane:check
cd web && npm run typecheck && npm run lint && npm run format:check && npm test
```

- `WorkPhaseTest` comprueba el orden de prioridad de las fases.
- `WorkspaceIT` comprueba:
  - que el trabajo hereda el único repositorio y adopta el de su primera ejecución;
  - que el resumen del proyecto cuenta trabajos, PR abiertas y fusionadas, y suma el coste;
  - que una fase inválida devuelve 400.
- `ContractIT` genera `fixtures/contracts/project-overview.json`, `work-item-summaries.json` y `changes.json`, y `web/src/contracts.test.ts` los compara con los tipos de la web.

## 2. Migración sobre una base con datos

1. Antes de actualizar, anota cuántos trabajos tienen ejecuciones con repositorio:

   ```bash
   docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc \
     "select count(distinct work_item_id) from workflow_run where repository_id is not null"
   ```

2. Actualiza y arranca Skynet. En el log, Flyway aplica `44 - work item repository`.
3. Comprueba que esos trabajos tienen ahora repositorio:

   ```bash
   docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc \
     "select count(*) from work_item where repository_id is not null"
   ```

   La cifra es igual o mayor que la del paso 1. Puede ser mayor por los trabajos creados por disparadores.

## 3. API y web

```bash
api() { curl -s -u admin:secreto "$@"; }
P=<id-del-proyecto-de-demo-repo>
```

1. Resumen del proyecto:

   ```bash
   api http://localhost:8080/api/projects/$P/overview \
     | jq '{openWorkItems, activeRuns, openPullRequests, mergedPullRequests, pendingApprovals, costUsd, repos: [.repositories[].name]}'
   ```

   Las cifras cuadran con lo que ves en la página del proyecto. `repos` incluye `demo-repo`.

2. Trabajos pendientes y su fase:

   ```bash
   api "http://localhost:8080/api/work-items?projectId=$P" | jq '.[] | {key, phase, attempts, costUsd, repositoryName}'
   api "http://localhost:8080/api/work-items?phase=DONE&projectId=$P" | jq 'length'
   api "http://localhost:8080/api/work-items?phase=NADA" -o /dev/null -w '%{http_code}\n'   # 400
   ```

3. En la web, abre el proyecto y pulsa **Nuevo trabajo**:
   - con un solo repositorio no sale selector, y el trabajo creado tiene ese `repositoryId` (`api http://localhost:8080/api/work-items/<id> | jq .repositoryId`);
   - si añades un segundo repositorio al proyecto, sale «Repositorio» con «Lo decido al lanzarlo».
4. Crea un trabajo con «Lo decido al lanzarlo». Su fase es `TODO` y `repositoryId` es `null`. Lánzalo con fake-claude sobre demo-repo:
   - mientras corre, `phase` es `RUNNING`;
   - al terminar, el trabajo tiene el `repositoryId` de demo-repo;
   - si la ejecución abre PR, `phase` pasa a `REVIEW` y `api http://localhost:8080/api/projects/$P/changes | jq '.[0] | {number, status, workItemKey}'` la muestra.
5. Abre el trabajo y pulsa **Lanzar**: el formulario propone su repositorio.
6. Archiva el trabajo: desaparece de `/api/work-items` y `/api/work-items/<id>/summary` devuelve 404.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
