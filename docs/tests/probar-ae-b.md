# Probar AE-B en local

AE-B añade **eliminar** en el backend (#50). Eliminar no tiene vuelta atrás y solo se admite sobre lo archivado (AE-A). Se borra todo lo que cuelga de lo eliminado y en Actividad queda un evento lápida. La web todavía no tiene botones (llegan en AE-C), así que esta guía usa `curl`.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

Hay una migración nueva, `V11__purge.sql`. Cambia el trigger de `event` para que solo se pueda borrar dentro de una purga y añade dos índices. Flyway la aplica sola al arrancar. No hay dependencias nuevas.

Como en AE-A:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

## 2. Qué mirar

1. **Solo lo archivado.** Elige una ejecución terminada sin archivar y lanza `api -X DELETE $B/workflow-runs/<id>`. Responde 409: «no está archivada».
2. **Vista previa.** Archívala (`api -X POST $B/workflow-runs/<id>/archive`) y mira qué se borraría:

   ```bash
   api $B/workflow-runs/<id>/deletion-preview | jq
   ```

   - `counts` dice cuántas ejecuciones, agentes y artefactos se borrarían, con sus bytes y sus eventos.
   - Si su worktree sigue en el runner, `deletable` es `false`, `liveWorkspaces` vale 1 y `blockers` lo explica.
3. **Worktrees.** Lanza `api -X POST $B/workflow-runs/<id>/workspaces/cleanup`.
   - Responde `{"requested": 1}` y el runner borra el worktree, como en M6-C.
   - Vuelve a pedir la vista previa: ya sale `deletable: true`.
   - Lo mismo funciona con `work-items/<id>` y `projects/<id>`.
4. **Eliminar la ejecución.** Lanza `api -X DELETE $B/workflow-runs/<id> | jq`.
   - Devuelve lo borrado.
   - Su página da 404 y desaparece de todas las listas, también de `?archived=all`.
   - El trabajo sigue ahí.
   - En **Actividad** aparece `workflow.purged`, con la clave del trabajo y las cuentas. Los eventos de la ejecución ya no están.
   - Si un reintento o un mensaje la continuaba desde otra ejecución, esa otra se conserva. La vista previa lo avisa en `warnings`.
5. **Un trabajo o un proyecto de prueba.** Archívalo y elimínalo:

   ```bash
   api -X POST $B/projects/<id>/archive
   api $B/projects/<id>/deletion-preview | jq .counts
   api -X DELETE $B/projects/<id>
   ```

   - Se van con él sus repositorios, trabajos y ejecuciones.
   - Queda `project.purged` en Actividad.
   - Con una ejecución en curso no deja ni archivar ni eliminar.
6. **Repositorios y runners con historial.**
   - Un repositorio archivado en el que trabajó algún agente responde 409 «se queda archivado».
   - Uno que no se usó nunca se elimina.
   - Lo mismo pasa con un runner olvidado: si ejecutó algo, se queda olvidado; si no, se elimina.
7. **Espacio.** Los artefactos de lo eliminado se borran del disco (`skynet.artifacts.root`). Si otro artefacto que se conserva comparte contenido con ellos, ese fichero se queda.

## 3. Comprobaciones automáticas

```bash
./gradlew build
```

`PurgeIT` cubre lo siguiente:
- eliminar una ejecución con worktree, artefacto y eventos: el bloqueo, la limpieza, la lápida y el blob borrado;
- un blob compartido y una sesión continuada en otra ejecución, que sobreviven;
- eliminar un proyecto y un trabajo;
- repositorios y runners con historial, que se quedan archivados;
- que fuera de una purga el historial sigue sin poder borrarse ni modificarse.

Los IT de Java también se pueden pasar en local, sin Docker, contra un PostgreSQL vacío:

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_it ./gradlew :control-plane:test
```
