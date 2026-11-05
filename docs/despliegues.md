# Despliegues

Skynet lleva la cuenta de **qué versión de cada repositorio hay en cada entorno** (hito DL). No despliega nada: lo hacen los listeners de Hermes en Omarchy con Git y Docker Compose. Skynet observa lo que pasa y, a partir de DL-C, comprueba si lo desplegado está sano.

| PR | Qué añade |
|---|---|
| DL-A | el modelo, la migración `V45` y la API de lectura |
| DL-B | los eventos `deployment` y `deployment_status` de GitHub Deployments, por el webhook de la App |
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
| `UNKNOWN` | nadie la ha comprobado (todo, hasta DL-C) |
| `CHECKING` | comprobándose |
| `HEALTHY` / `UNHEALTHY` / `TIMEOUT` | el resultado de la comprobación |

Un fallo de salud no revierte nada: se registra y se avisa.

## 2. Reglas

- **Idempotencia.** Un despliegue se identifica por su repositorio y su `externalId` (el id de GitHub Deployments), y un estado por su `externalId` dentro del despliegue. Repetidos, no hacen nada.
- **Desorden.** Cada estado guarda la hora a la que ocurrió. Uno más viejo que el último aplicado se guarda en la historia (`applied: false`) pero no cambia el despliegue. A igual hora, un final (`SUCCEEDED`, `FAILED`) gana a un «en curso».
- **Lo desplegado.** El entorno es único y compartido. Cuando un despliegue termina bien, los anteriores de ese entorno (por la hora a la que se pidieron) quedan `SUPERSEDED`. Si termina bien uno más viejo después de uno más nuevo, es el viejo el que queda superado. Un despliegue que falla no cambia lo desplegado. Así una comprobación de salud nunca se atribuye a un SHA que ya no está.
- **Serializado por destino.** Los cambios de un mismo repositorio y entorno se hacen de uno en uno (bloqueo de la transacción).
- **La URL del despliegue solo se enseña.** La salud (DL-C) usa una URL que configura el admin, nunca la que llega en el evento.

## 3. API

Todas piden la sesión de admin.

| Método y ruta | Qué devuelve |
|---|---|
| `GET /api/repositories/{id}/deployments?environment=&sha=&limit=50` | los despliegues, del más reciente al más viejo; `sha` admite un prefijo de 7 a 40 caracteres; `limit` hasta 200 |
| `GET /api/repositories/{id}/environments` | cada entorno: `deployed` (lo desplegado ahora, o `null`) y `latest` (el último pedido) |
| `GET /api/deployments/{id}` | `deployment` y `statuses`, cada estado recibido con `applied` |

Un despliegue lleva `id`, `repositoryId`, `environment`, `externalId`, `commitSha`, `ref`, `pullRequestNumber`, `url`, `creator`, `status`, `health`, `error`, `requestedAt`, `startedAt`, `finishedAt`, `statusAt`, `supersededBy`, `createdAt` y `updatedAt`.

## 4. Eventos

Con aggregate `deployment`: `deployment.created`, `deployment.status` (con `state`, `previous` y `applied`) y `deployment.superseded` (con `supersededBy`). No llevan el texto del error.
