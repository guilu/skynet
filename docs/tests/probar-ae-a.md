# Probar AE-A en local

AE-A añade **archivar y restaurar** en el backend (#49): proyectos, repositorios, trabajos, ejecuciones y runners. Lo archivado sale de las listas, del dashboard y de las métricas, y es de solo lectura. La web todavía no tiene botones (llegan en AE-C), así que esta guía usa `curl`. Eliminar llega en AE-B.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

Hay una migración nueva, `V10__archive.sql`, que añade `archived_at` a `project`, `repository`, `work_item`, `workflow_run` y `runner`. Flyway la aplica sola al arrancar. No hay dependencias nuevas.

Los ejemplos usan `admin:secreto`; cámbialo por tu usuario y tu `SKYNET_ADMIN_PASSWORD`. Para no repetirlo:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

## 2. Qué mirar

1. **Una ejecución.** Elige una ejecución en curso y otra terminada en la web (copia su id de la URL).
   - `api -X POST $B/workflow-runs/<en curso>/archive` responde 409: «sigue en curso».
   - `api -X POST $B/workflow-runs/<terminada>/archive` responde `{id, archivedAt}`. Recarga **Ejecuciones** y el **Dashboard**: ya no está, y las métricas no la cuentan.
   - Su página se sigue abriendo. En «Reintentar» o «Enviar mensaje» sale el error «está archivada: restáurala para continuar».
   - `api "$B/workflow-runs?archived=true" | jq '.items[].id'` la enseña.
   - `api -X POST $B/workflow-runs/<terminada>/restore` la devuelve a las listas.
2. **Un trabajo.** Con todas sus ejecuciones terminadas:
   - `api -X POST $B/work-items/<id>/archive`. El trabajo desaparece de la lista del proyecto y sus ejecuciones de **Ejecuciones** y del dashboard.
   - En su página, «Lanzar agente» falla con «está archivado: restáuralo para lanzar agentes».
   - `api -X POST $B/work-items/<id>/restore` lo devuelve todo.
3. **Un proyecto.** Usa uno de prueba sin ejecuciones en curso:
   - `api -X POST $B/projects/<id>/archive`. Sale de **Proyectos**, de ⌘K y de los filtros, y con él sus trabajos y ejecuciones.
   - Con una ejecución en curso responde 409.
   - Su página se abre, pero crear un trabajo, registrar un repositorio o cambiar la verificación o la política responde 409 «está archivado».
   - `api "$B/projects?archived=all" | jq '.[] | {key, archivedAt}'` lo enseña con su fecha.
   - `api -X POST $B/projects/<id>/restore` lo devuelve todo tal como estaba.
4. **Un repositorio.** `api -X POST $B/projects/<p>/repositories/<r>/archive`.
   - Deja de salir en la pestaña de repositorios y al lanzar un agente.
   - Lanzar con él por la API responde 409.
   - `…/restore` lo devuelve.
5. **Olvidar un runner.** En **Runners**, elige uno «sin latido» que ya no exista:
   - `api -X POST $B/runners/<id>/archive`. Desaparece de la página y del aviso del dashboard.
   - Con un agente en marcha responde 409.
   - Si olvidas un runner que sigue vivo, en su siguiente latido recibe un 401, se vuelve a registrar solo y reaparece. Es lo esperado: olvidar es para los que ya no existen.
6. **Actividad.** En **Actividad** aparecen los eventos `project.archived`, `workitem.restored`, `workflow.archived`, `runner.archived`, etc.
7. **Filtro inválido.** `api -o /dev/null -w '%{http_code}\n' "$B/projects?archived=quizá"` responde 400.

## 3. Comprobaciones automáticas

```bash
./gradlew build
cd web && npm run typecheck && npm test
```

- `ArchiveIT` cubre lo de arriba:
  - archivar y restaurar cada entidad, con sus eventos;
  - los 409 por algo en curso o por un padre archivado;
  - las listas con `archived=false|true|all`, las métricas y el dashboard;
  - el runner olvidado: pierde el token y vuelve al registrarse.
- Los contratos (`fixtures/contracts/`) llevan el campo nuevo `archivedAt` en ejecuciones y runners.

Los IT de Java también se pueden pasar en local, sin Docker, contra un PostgreSQL vacío:

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_it ./gradlew :control-plane:test
```
