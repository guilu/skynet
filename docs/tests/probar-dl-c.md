# Probar DL-C - Verificación de salud y reconciliación

DL-C (#239) es la tercera PR del hito DL. Separa «el despliegue terminó» de «la aplicación responde», detecta los despliegues atascados y recupera de GitHub lo que no llegó por webhook. Lo cuenta [`docs/despliegues.md`](../despliegues.md) §4.

- **Comprobación de salud por entorno**:
  - el admin la configura con `PUT /api/repositories/{id}/health-checks/{entorno}` (URL, plazo, intentos, intervalo, retraso inicial, `expectSha`); nunca se toma la URL de un evento;
  - al pasar a `SUCCEEDED`, la salud pasa a `CHECKING`, y luego a `HEALTHY` al primer intento bueno, o a `UNHEALTHY`/`TIMEOUT` al agotar los intentos;
  - cada intento queda como evidencia en `probes`;
  - si el despliegue queda sustituido a mitad, la comprobación se abandona;
  - `POST /api/deployments/{id}/health-check` comprueba ya;
  - no se revierte nada.
- **Atascados**: sin estado nuevo en 30 minutos, `stuckAt` y el evento `deployment.stuck`; el siguiente estado lo borra.
- **Reconciliación** cada 10 minutos o con `POST /api/repositories/{id}/deployments/reconcile`: los últimos 20 despliegues de GitHub, y los estados solo de los que no han terminado.
- **Migración `V46`**: `deployment_health_check`, `deployment_health_probe` y columnas `health_detail`, `health_checked_at`, `health_next_at`, `health_attempts` y `stuck_at` en `deployment`.
- **Eventos nuevos**: `deployment.health` y `deployment.stuck`.

No cambia la política de aprobación y fusión, ni el despliegue de Hermes, ni el aviso por Telegram. Sin rollback.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes. Lo que se crea está en el entorno `prueba-dl-c` y se borra al final.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*DeploymentsIT' --tests '*GitHubWebhooksIT' \
  --tests '*MigrationsIT' --tests '*ModularityTest'
```

`DeploymentsIT` (DL-C), con una aplicación de mentira en local:
- sana y con el SHA en el cuerpo: `HEALTHY` al primer intento, con su evidencia (`OK`, 200) y los eventos `deployment.health` `CHECKING` y `HEALTHY`;
- responde 503: `UNHEALTHY` tras 2 intentos con «Intento 2: Responde 503», y el despliegue sigue `SUCCEEDED`. Con `expectSha` y otra versión en el cuerpo, `UNHEALTHY` con «no con el SHA»;
- no contesta en el plazo: `TIMEOUT` con «Sin respuesta en 1 s»;
- sustituido mientras espera: vuelve a `UNKNOWN` («Sustituido antes de terminar la comprobación») y se comprueba el nuevo. «Comprobar ya» da 409 con el sustituido y se salta el retraso con el nuevo;
- sin comprobación no se comprueba nada, y «comprobar ya» da 409;
- validación: 400 con `ftp://`, con usuario, con fragmento, sin esquema, `attempts: 0` y un entorno con espacios. Los valores por defecto, el cambio parcial y el borrado (204 y luego 404);
- atascados: solo el viejo `IN_PROGRESS`, una sola vez, y el siguiente estado borra la marca.

`GitHubWebhooksIT`, la reconciliación con un GitHub falso:
- sin la App, 409;
- con la App, recupera un despliegue terminado y otro a medias que nunca llegaron por webhook;
- la segunda vez no cambia nada ni pide los estados del terminado.

## 2. Comprobación de salud en el entorno desplegado

Con Skynet desplegado y un repositorio cualquiera (su id en `REPO`). El contenedor de la API se comprueba a sí mismo en `http://localhost:8080/actuator/health`:

```bash
B=http://localhost:8080; AUTH=admin:<contraseña>
PSQL="docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc"
curl -s -u $AUTH -H 'Content-Type: application/json' -X PUT $B/api/repositories/$REPO/health-checks/prueba-dl-c \
  -d '{"url": "http://localhost:8080/actuator/health", "initialDelaySeconds": 600, "attempts": 2, "intervalSeconds": 2}' | jq
$PSQL "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, status, requested_at, finished_at, status_at, created_at, updated_at)
 VALUES ('00000000-0000-0000-0000-00000000d1c1', '$REPO', 'prueba-dl-c', 'dlc-1', repeat('1', 40), 'SUCCEEDED', now(), now(), now(), now(), now())"
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -X POST $B/api/deployments/00000000-0000-0000-0000-00000000d1c1/health-check   # 202
sleep 5
curl -s -u $AUTH $B/api/deployments/00000000-0000-0000-0000-00000000d1c1 | jq '{health: .deployment.health, detail: .deployment.healthDetail, probes: [.probes[] | {outcome, statusCode}]}'
```

Esperado: `HEALTHY`, «Intento 1: Responde 200 en N ms» y un intento `OK` con 200.

Casos negativos:

```bash
# Una URL que no responde: tras 2 intentos, UNHEALTHY con ERROR (o TIMEOUT).
curl -s -u $AUTH -H 'Content-Type: application/json' -X PUT $B/api/repositories/$REPO/health-checks/prueba-dl-c -d '{"url": "http://localhost:1/nada"}' >/dev/null
curl -s -o /dev/null -u $AUTH -X POST $B/api/deployments/00000000-0000-0000-0000-00000000d1c1/health-check
sleep 8
curl -s -u $AUTH $B/api/deployments/00000000-0000-0000-0000-00000000d1c1 | jq '{health: .deployment.health, detail: .deployment.healthDetail, status: .deployment.status}'
# Esperado: health UNHEALTHY, status sigue SUCCEEDED (no se revierte nada).

# URL con usuario o sin http(s): 400.
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -H 'Content-Type: application/json' -X PUT $B/api/repositories/$REPO/health-checks/prueba-dl-c -d '{"url": "http://u:p@localhost/"}'
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -H 'Content-Type: application/json' -X PUT $B/api/repositories/$REPO/health-checks/prueba-dl-c -d '{"url": "file:///etc/passwd"}'
```

## 3. Atascados

```bash
$PSQL "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, status, requested_at, status_at, created_at, updated_at)
 VALUES ('00000000-0000-0000-0000-00000000d1c2', '$REPO', 'prueba-dl-c', 'dlc-2', repeat('2', 40), 'IN_PROGRESS', now() - interval '2 hours', now() - interval '2 hours', now(), now())"
sleep 70   # la pasada es cada minuto
curl -s -u $AUTH $B/api/deployments/00000000-0000-0000-0000-00000000d1c2 | jq '.deployment.stuckAt'   # una hora, no null
```

## 4. Reconciliación (solo si la App tiene Deployments: lectura)

Con un repositorio enlazado con la App (`curl -s -u $AUTH $B/api/github | jq '.repositories'`):

```bash
curl -s -u $AUTH -X POST $B/api/repositories/$REPO/deployments/reconcile | jq   # {deployments, statuses, changed}
```

- Si la App todavía no tiene el permiso, la respuesta es un error de GitHub (422 al pedir el token) y no cambia nada. Es lo esperado hasta hacer la §4 de [`probar-dl-b.md`](probar-dl-b.md).
- Con un repositorio sin la App, 409.

## 5. Limpieza y checkout limpio

```bash
$PSQL "DELETE FROM deployment WHERE environment = 'prueba-dl-c'"
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -X DELETE $B/api/repositories/$REPO/health-checks/prueba-dl-c   # 204
git status --porcelain   # vacío
```
