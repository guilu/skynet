# Despliegues

Skynet lleva la cuenta de **qué versión de cada repositorio hay en cada entorno** (hito DL). No despliega nada: lo hacen los listeners de Hermes en Omarchy con Git y Docker Compose. Skynet observa lo que pasa, comprueba si lo desplegado está sano (DL-C), lo enseña en la web (DL-E) y puede lanzar un workflow al quedar sano o al fallar la salud (DL-D, [`disparadores.md`](disparadores.md) §13).

| PR | Qué añade |
|---|---|
| DL-A | el modelo, la migración `V45` y la API de lectura |
| DL-B | los eventos `deployment` y `deployment_status` de GitHub Deployments, por el webhook de la App, y `scripts/notify-deployment.sh` para los listeners |
| DL-C | las comprobaciones de salud, la reconciliación y los despliegues atascados |
| DL-E | la interfaz por proyecto y repositorio |
| DL-D | los disparadores de workflows por eventos de despliegue |

## 1. Dos ejes que no se mezclan

Cada despliegue tiene un **estado** y una **salud**.

| Estado | Qué significa |
|---|---|
| `PENDING` | pedido, sin empezar |
| `IN_PROGRESS` | el listener está desplegando |
| `SUCCEEDED` | el despliegue terminó bien (Compose arrancó); **no** dice que la aplicación responda |
| `FAILED` | el despliegue falló; `error` lleva el texto, con los secretos ocultos |
| `SUPERSEDED` | lo sustituyó un despliegue posterior que terminó bien en el mismo entorno |

| Salud | Qué significa |
|---|---|
| `UNKNOWN` | nadie la ha comprobado: el entorno no tiene comprobación, o el despliegue no terminó bien o fue sustituido antes de acabar |
| `CHECKING` | comprobándose |
| `HEALTHY` / `UNHEALTHY` / `TIMEOUT` | el resultado de la comprobación |

Un fallo de salud no revierte nada: se registra y se avisa.

## 2. Reglas

- **Idempotencia.** Un despliegue se identifica por su repositorio y su `externalId` (el id de GitHub Deployments), y un estado por su `externalId` dentro del despliegue. Repetidos, no hacen nada.
- **Desorden.** Cada estado guarda la hora a la que ocurrió. Uno más viejo que el último aplicado se guarda en la historia (`applied: false`) pero no cambia el despliegue. A igual hora, un final (`SUCCEEDED`, `FAILED`) gana a un «en curso».
- **Lo desplegado.** El entorno es único y compartido. Cuando un despliegue termina bien, los anteriores de ese entorno (por la hora a la que se pidieron) quedan `SUPERSEDED`. Si termina bien uno más viejo después de uno más nuevo, es el viejo el que queda superado. Un despliegue que falla no cambia lo desplegado. Así una comprobación de salud nunca se atribuye a un SHA que ya no está.
- **Serializado por destino.** Los cambios de un mismo repositorio y entorno se hacen de uno en uno (bloqueo de la transacción).
- **La URL del despliegue solo se enseña.** La salud (DL-C) usa una URL que configura el admin, nunca la que llega en el evento.

## 3. Cómo llegan: GitHub Deployments (DL-B)

Los listeners que despliegan avisan a **GitHub**, no a Skynet: crean un *deployment* y le van poniendo estados. GitHub manda los webhooks `deployment` y `deployment_status` a la App de Skynet, por el mismo canal firmado que el resto ([`github.md`](github.md)).
- No hay un receptor ni un secreto nuevos.
- Una entrega repetida se ignora.
- Si Skynet no estaba (por ejemplo, porque se está desplegando a sí mismo), la red de seguridad de GH-B pide a GitHub las entregas que fallaron.
- La PR muestra «Deployed to omarchy».

**En la App** (Settings → Developer settings → GitHub Apps → la de Skynet):
- añade el permiso **Deployments: lectura**;
- marca los eventos **Deployment** y **Deployment status**;
- acepta el cambio en la instalación.

**En el listener**, con `scripts/notify-deployment.sh` y un `GITHUB_TOKEN` con **Deployments: lectura y escritura** en el repositorio. Puede ser un token fino del usuario; no es el de la App de Skynet.

```bash
id=$(scripts/notify-deployment.sh start guilu/skynet "$SHA" "$RAMA" "$PR")   # PR opcional
scripts/notify-deployment.sh status guilu/skynet "$id" in_progress
# … git checkout + docker compose up -d …
scripts/notify-deployment.sh status guilu/skynet "$id" success "docker compose up -d: OK" http://omarchy:8081
# o, si falla:
scripts/notify-deployment.sh status guilu/skynet "$id" failure "docker compose pull falló"
```

- El entorno es `omarchy` por defecto (`DEPLOY_ENVIRONMENT`).
- El script despliega el **SHA exacto** y no exige checks: eso ya lo decide Hermes.
- Reintenta la red y los 5xx con espera creciente, y nunca imprime el token.
- El aviso por Telegram y el despliegue en sí no cambian. Si GitHub no responde, el listener puede seguir desplegando: el aviso a Skynet no debe parar el despliegue.

Cómo lo traduce Skynet:

| GitHub | Skynet |
|---|---|
| `deployment.id` | `externalId` |
| `deployment.sha`, `ref`, `environment`, `creator`, `created_at` | `commitSha`, `ref`, `environment`, `creator`, `requestedAt` |
| `deployment.payload.pull_request` | `pullRequestNumber` |
| estado `pending`, `queued` | `PENDING` |
| estado `in_progress` | `IN_PROGRESS` |
| estado `success` | `SUCCEEDED` |
| estado `failure`, `error` | `FAILED`, con la descripción como `error` |
| estado `inactive` | se ignora: lo pone GitHub a los anteriores, y eso ya es `SUPERSEDED` |
| `deployment_status.environment_url` | `url`, si es http(s) y sin usuario |

Más detalles:
- Un `deployment_status` basta aunque el `deployment` no haya llegado.
- Un entorno con caracteres que Skynet no admite se limpia: `Omarchy (compartido)` pasa a `Omarchy-compartido-`.
- Cada entrega publica además el evento `github.deployment.created` o `github.deployment_status.created` en el repositorio, como el resto de webhooks.

## 4. Salud, atascados y reconciliación (DL-C)

### Comprobación de salud

Cada entorno de un repositorio puede tener una **comprobación**. La configura el admin y nunca sale de un evento, así que nadie de fuera puede hacer que Skynet llame a otra dirección:

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X PUT http://localhost:8080/api/repositories/<id>/health-checks/omarchy \
  -d '{"url": "http://omarchy:8081/actuator/health"}'
```

| Campo | Por defecto | Qué es |
|---|---|---|
| `url` | (obligatorio) | http(s), sin usuario ni fragmento; Skynet hace un `GET` sin seguir redirecciones |
| `timeoutSeconds` | 5 | plazo de cada intento (1 a 60) |
| `attempts` | 6 | intentos antes de darla por mala (1 a 30) |
| `intervalSeconds` | 10 | espera entre intentos (1 a 600) |
| `initialDelaySeconds` | 15 | espera tras terminar el despliegue, para que arranque (0 a 600) |
| `expectSha` | `false` | si la respuesta tiene que contener el SHA desplegado (sus 7 primeros caracteres bastan), p. ej. `/actuator/info` |
| `enabled` | `true` | si está activa |

Cómo funciona:
- Cuando un despliegue pasa a `SUCCEEDED` en un entorno con comprobación activa, su salud pasa a `CHECKING`.
- Un intento es bueno si la URL responde 2xx y, con `expectSha`, si el cuerpo trae el SHA.
- Al primer intento bueno, `HEALTHY`. Si se agotan los intentos, `UNHEALTHY`, o `TIMEOUT` si el último no respondió a tiempo.
- `healthDetail` resume el último intento («Intento 2: Responde 503»).
- Cada intento queda como evidencia en `probes`, con resultado, código, latencia y hora.
- Si el despliegue queda sustituido mientras se comprueba, la comprobación se abandona: no se atribuye a un SHA que ya no está.
- Un estado nuevo del despliegue deja la salud en `UNKNOWN`.
- `POST /api/deployments/{id}/health-check` vuelve a comprobar ya, sin el retraso inicial. Solo vale con lo desplegado (`SUCCEEDED`) y si el entorno tiene comprobación; si no, 409.
- Las comprobaciones viven en la base de datos: si Skynet se reinicia a mitad (por ejemplo, al desplegarse a sí mismo), las retoma al volver.
- **No se revierte nada.** Una salud mala se registra y se publica como evento `deployment.health`.

### Atascados

- Un despliegue `PENDING` o `IN_PROGRESS` sin estado nuevo en 30 minutos (`SKYNET_DEPLOYMENTS_STUCK_AFTER`) se marca con `stuckAt` y el evento `deployment.stuck`.
- El estado no cambia: puede terminar todavía, y su siguiente estado borra la marca.

### Reconciliación

- Cada 10 minutos (`SKYNET_DEPLOYMENTS_RECONCILE_INTERVAL`), Skynet pide a GitHub los últimos 20 despliegues de cada repositorio con actividad en la última semana o con comprobación de salud. Aplica lo que falte, como si hubiera llegado por webhook.
- Solo pide los estados de los despliegues que no conoce o que aún no han terminado.
- Recupera los eventos perdidos, por ejemplo los de un despliegue de Skynet sobre sí mismo.
- `POST /api/repositories/{id}/deployments/reconcile` lo hace ya y devuelve `deployments`, `statuses` y `changed`. Necesita la App instalada en el repositorio (si no, 409) y su permiso **Deployments: lectura**.

## 5. En la web (DL-E)

**Proyecto → Despliegues**, una tarjeta por repositorio:
- **Cada entorno**: lo desplegado ahora (SHA corto, rama, enlace a la PR, URL del listener y hora), con su **estado y su salud en dos píldoras** distintas («Desplegado» y «Sano», «No sano», «No responde», «Comprobando» o «Salud sin comprobar») y el resumen del último intento.
- **El último pedido**, si no es lo desplegado (uno que falló o que aún está en curso): se ve aparte y **sin salud**, porque la salud del entorno es la de lo desplegado.
- **El historial**: los últimos 20 despliegues. Uno sustituido no enseña salud salvo que llegara a comprobarse.
- **Reconciliar con GitHub**, si el repositorio es de GitHub, pide ya a GitHub lo que falte y dice cuántos cambios aplicó.
- Al pulsar un SHA se abre el **detalle**: todos los datos, la salud de *ese* SHA con cada intento (resultado, código HTTP, latencia y hora), «Volver a comprobar» si está desplegado, y los estados recibidos, marcando los que llegaron tarde y no cambiaron nada.
- Se refresca cada 15 segundos, y cada 3 mientras se comprueba la salud.

**Proyecto → Configuración**, en la ficha de cada repositorio, **Salud de los despliegues**: crear, editar y quitar la comprobación de cada entorno (URL, intentos, espera entre intentos, espera inicial, plazo, «exige el SHA» y activa).

Un proyecto o repositorio archivado se ve, pero sin botones.

## 6. API

Todas piden la sesión de admin.

| Método y ruta | Qué devuelve |
|---|---|
| `GET /api/repositories/{id}/deployments?environment=&sha=&limit=50` | los despliegues, del más reciente al más viejo; `sha` admite un prefijo de 7 a 40 caracteres; `limit` hasta 200 |
| `GET /api/repositories/{id}/environments` | cada entorno: `deployed` (lo desplegado ahora, o `null`) y `latest` (el último pedido) |
| `GET /api/deployments/{id}` | `deployment`, `statuses` (cada estado recibido con `applied`) y `probes` (los intentos de comprobación) |
| `POST /api/deployments/{id}/health-check` | vuelve a comprobar ya la salud (202) |
| `GET /api/repositories/{id}/health-checks` | las comprobaciones de los entornos |
| `PUT /api/repositories/{id}/health-checks/{entorno}` | crea o cambia una; lo que no se manda se conserva |
| `DELETE /api/repositories/{id}/health-checks/{entorno}` | la quita (204) |
| `POST /api/repositories/{id}/deployments/reconcile` | reconcilia ya con GitHub |

Un despliegue lleva `id`, `repositoryId`, `environment`, `externalId`, `commitSha`, `ref`, `pullRequestNumber`, `url`, `creator`, `status`, `health`, `error`, `requestedAt`, `startedAt`, `finishedAt`, `statusAt`, `supersededBy`, `createdAt`, `updatedAt`, `healthDetail`, `healthCheckedAt`, `healthAttempts` y `stuckAt`.

## 7. Eventos

Con aggregate `deployment`:
- `deployment.created`;
- `deployment.status`, con `state`, `previous` y `applied`;
- `deployment.superseded`, con `supersededBy`;
- `deployment.health`, con `health` (`CHECKING` al empezar y el resultado al acabar), `attempts` y `detail`;
- `deployment.stuck`, con `stuckAfterMinutes`.

No llevan el texto del error.
