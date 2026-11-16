# Chat con Skyny

Una cortina que baja desde la barra superior, con Skyny grande y un prompt para usar Skynet hablando: «¿Qué hay en marcha?», «Lanza revisar-codigo en la rama de la PR 340», «¿Qué decidimos sobre la autenticación en akademia?». Es el hito CH.

Lo que se decidió antes de empezar (estudio de viabilidad y boceto, 15 de noviembre de 2026):

- Contesta **un agente de Claude Code en el runner**, con la suscripción, la cuota y la auditoría de siempre. Cada turno del chat es un `agent_run` de tipo `chat`, sin fase ni worktree (CH-C).
- **Haiku** por defecto.
- Las consultas se hacen sin preguntar; las acciones que cambian algo se **proponen** y solo se hacen cuando la persona pulsa Confirmar en la web (CH-B).
- Las conversaciones **se guardan** y se pueden retomar; su resumen va a la memoria del proyecto (CH-F).
- La cortina **se superpone** a la web con el fondo atenuado, y Skyny sale siempre grande (CH-D, CH-E).

| Parte | Qué hace |
|---|---|
| CH-A ([#335](https://github.com/guilu/skynet/issues/335)) | Conversaciones y mensajes en la base de datos, y su API. Todavía no contesta nadie. |
| CH-B ([#336](https://github.com/guilu/skynet/issues/336)) | El MCP de Skynet y las acciones por confirmar. |
| CH-C ([#337](https://github.com/guilu/skynet/issues/337)) | El turno de Skyny en el runner. |
| CH-D ([#338](https://github.com/guilu/skynet/issues/338)) | La cortina en la web. |
| CH-E ([#339](https://github.com/guilu/skynet/issues/339)) | Skyny vivo en la cortina. |
| CH-F ([#340](https://github.com/guilu/skynet/issues/340)) | El resumen a la memoria del proyecto. |

## Conversaciones

Una **conversación** (tabla `conversation`) tiene:

| Campo | Qué es |
|---|---|
| Título | El que se le da, o la primera línea del primer mensaje (hasta 80 caracteres). Sin ninguno, «Conversación nueva». |
| Contexto | El proyecto y el trabajo desde los que se abrió, si los hay. Con un trabajo basta: el proyecto sale de él. Si se purga el proyecto o el trabajo, la conversación sigue, sin contexto. |
| Proveedor y modelo | `claude-code` y `haiku`. |
| Estado | `IDLE` (nadie contesta ni falta nada), `ANSWERING` (Skyny está contestando, desde CH-C) u `OFFLINE` (el último mensaje no tiene quien lo conteste). |
| Archivada | Una conversación archivada sale de la lista y ya no admite mensajes, pero se sigue leyendo. |

Sus **mensajes** (tabla `conversation_message`) van en orden (`seq`, desde 1). Cada uno tiene su autor (`USER` o `SKYNY`), su texto (hasta 8 000 caracteres) y sus tarjetas (`cards`: una ejecución, una entrada de memoria, una acción por confirmar). El texto pasa por la misma redacción de secretos que los eventos y la memoria.

Mientras Skyny contesta, no se puede escribir otro mensaje en la misma conversación: va uno cada vez.

**En CH-A todavía no contesta nadie:** cada mensaje deja la conversación en `OFFLINE`.

## API

| Llamada | Qué hace |
|---|---|
| `POST /api/conversations` | Crea una conversación. Cuerpo: `projectId`, `workItemId`, `title` y `message`, todos opcionales. Devuelve `{conversation, messages}`. |
| `GET /api/conversations` | Las conversaciones, la de actividad más reciente primero. `projectId` filtra por proyecto; `archived` es `false` (por defecto), `true` o `all`; `limit` va de 1 a 100 (20 por defecto). |
| `GET /api/conversations/{id}` | La conversación con todos sus mensajes. |
| `POST /api/conversations/{id}/messages` | Escribe un mensaje: `{"body": "…"}`. Devuelve `{message, conversation}`. 409 si está archivada o si Skyny aún contesta el anterior. |
| `POST /api/conversations/{id}/archive` | La archiva. Archivar una ya archivada no hace nada. |

## Eventos

Cada cambio deja un evento en el registro, con `aggregate_type = conversation` y la conversación como agregado, y la web lo recibe por el flujo en vivo de siempre (`/api/events/stream?types=chat.*`):

| Evento | Cuándo | Datos |
|---|---|---|
| `chat.conversation.created` | Al crearla | `conversationId`, `title`, `projectId`, `workItemId`, `actor` |
| `chat.message.created` | Con cada mensaje | `conversationId`, `messageId`, `seq`, `author` |
| `chat.conversation.archived` | Al archivarla | `conversationId`, `actor` |

Los eventos no llevan el texto de los mensajes: la web lo lee de la API.

## El MCP de Skynet

Skyny consulta Skynet con el **MCP de Skynet**, `skynet-runner skynet-mcp --url <control plane> --token-file <fichero>`, un servidor MCP por stdio como el de memoria (CH-B). Cada herramienta es una o varias llamadas `GET` a la API de siempre, así que Skyny ve lo mismo que la web:

| Herramienta | Qué devuelve | Llama a |
|---|---|---|
| `estado` | Lo que está en marcha, lo que espera, lo que falló y los avisos | `/api/dashboard` |
| `buscar` | Proyectos, trabajos y ejecuciones que casan con un texto (hasta 10 de cada) | `/api/projects`, `/api/work-items`, `/api/workflow-runs?q=` |
| `proyecto` | Un proyecto y cómo va | `/api/projects/{id}/overview` |
| `trabajo` | Un trabajo y sus ejecuciones | `/api/work-items/{id}/summary` y `/runs` |
| `ejecuciones` | Las últimas 10, por estado, proyecto o texto | `/api/workflow-runs` |
| `ejecucion` | Una ejecución con sus hallazgos | `/api/workflow-runs/{id}` y `/findings` |
| `aprobaciones` | Las que esperan una decisión | `/api/approvals?status=PENDING` |
| `workflows` y `workflow` | Los publicados, o uno con sus fases | `/api/workflows`, `/api/workflows/{clave}` |
| `uso` | La cuota de cada proveedor y el coste por agente en 24h, 7d o 30d | `/api/usage/windows`, `/api/usage/agents` |
| `memoria_buscar` y `memoria_leer` | La memoria de un proyecto, o una entrada | `/api/projects/{id}/memory`, `/api/memory/{id}` |
| `wiki` | Las páginas de la wiki de un proyecto, o una entera | `/api/projects/{id}/wiki[/{página}]` |

Los ids se comprueban antes de ponerlos en la ruta y lo que pasa de 30 000 caracteres se recorta.

### El token de una conversación

El MCP llama con `Authorization: Bearer skc_…`, el **token de la conversación** (tabla `conversation_token`, solo su hash):

- **Solo lee:** vale para `GET` en una lista cerrada de rutas (`ChatTokens.READABLE`, las de la tabla de arriba). Cualquier otra petición, también un `POST`, da 403 antes de llegar a la API.
- **Caduca** a los 30 minutos y tiene un tope de 200 llamadas.
- Deja de valer al pedir otro para la misma conversación, al revocarlo y al archivar la conversación.
- No abre sesión: no deja cookie de sesión.
- La API del runner (`/api/runner/**`) no pasa por este filtro: comprueba sus propios tokens.

El turno de Skyny (CH-C) lo pedirá al empezar y lo revocará al terminar. Para probar el MCP a mano, `POST /api/conversations/{id}/chat-token` da uno desde la web.
