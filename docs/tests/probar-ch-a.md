# Probar CH-A - Conversaciones

CH-A (#335) es la primera PR del hito CH «Chat con Skyny». Lo explica [`docs/chat.md`](../chat.md).

- **Conversaciones guardadas** (migración V62: `conversation` y `conversation_message`), con el proyecto o el trabajo de contexto si los hay.
- **API:** crear, listar por actividad, leer con sus mensajes, escribir y archivar (`/api/conversations…`).
- **Eventos** `chat.conversation.created`, `chat.message.created` y `chat.conversation.archived`.
- **Todavía no contesta nadie:** cada mensaje deja la conversación en `OFFLINE`. El turno de Skyny llega con CH-C y la cortina con CH-D.

Ninguna prueba de esta guía lanza agentes, gasta cuota, modifica ficheros versionados de skynet ni abre PRs.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*ConversationsIT' --tests '*MigrationsIT' --tests '*ModularityTest'
```

`ConversationsIT`:
- una conversación creada con un primer mensaje de dos líneas toma la primera como título, es de `claude-code` con `haiku` y la crea `admin`; un segundo mensaje lleva `seq` 2, sale sin los espacios de los extremos y deja la conversación en `OFFLINE` con 2 mensajes; al leerla salen los dos en orden, y en el registro hay `chat.conversation.created` y dos `chat.message.created`;
- con un trabajo de contexto, el proyecto sale de él (con su clave y el título del trabajo); un trabajo de otro proyecto da 400 y un proyecto que no existe, 404; la lista de un proyecto solo trae las suyas;
- la lista va por la última actividad (escribir en una la sube arriba) y respeta `limit`; una archivada sale solo con `archived=true` o `all`, no admite mensajes (409) pero se lee, y archivarla dos veces no hace nada;
- un mensaje vacío, uno de más de 8 000 caracteres o sin `body` dan 400; una conversación que no existe, 404; `limit=0`, 400; un token de GitHub en un mensaje se guarda como `[REDACTED]`;
- un primer mensaje muy largo da un título de 80 caracteres terminado en «…».

`MigrationsIT` aplica V62 sobre la base vacía y `ModularityTest` comprueba que el módulo `chat` solo usa la API pública de `project`, `workitem`, `event` y `shared`.

## 2. Skynet desplegado

Con Skynet en marcha (`./scripts/reset-local.sh` o el despliegue de pruebas):

```bash
API=http://localhost:8080; AUTH="-u admin:<contraseña>"
```

### 2.1 Una conversación sin contexto

1. Créala con un primer mensaje:

   ```bash
   curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' \
     -d '{"message": "¿Qué hay en marcha?"}' | jq '{id: .conversation.id, title: .conversation.title, state: .conversation.state, model: .conversation.model, messages: [.messages[].body]}'
   ```

   **Esperado:** título «¿Qué hay en marcha?», estado `IDLE`, modelo `haiku` y un mensaje. Apunta el `id`.
2. Escribe otro:

   ```bash
   curl -s $AUTH -X POST "$API/api/conversations/<id>/messages" -H 'Content-Type: application/json' \
     -d '{"body": "¿Y qué falló ayer?"}' | jq '{seq: .message.seq, author: .message.author, state: .conversation.state}'
   ```

   **Esperado:** `seq` 2, autor `USER` y estado `OFFLINE`: todavía no contesta nadie.
3. Léela: `curl -s $AUTH "$API/api/conversations/<id>" | jq '[.messages[] | {seq, author, body}]'`.
   **Esperado:** los dos mensajes, en orden.

### 2.2 Con el contexto de un trabajo

1. Toma un trabajo del proyecto de `demo-repo` en la web (su id está en la URL de la página del trabajo) y crea una conversación desde él:

   ```bash
   curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' \
     -d '{"workItemId": "<trabajo>"}' | jq '.conversation | {title, projectKey, workItemKey}'
   ```

   **Esperado:** título «Conversación nueva», la clave del proyecto de `demo-repo` y la del trabajo.
2. Lista las del proyecto: `curl -s $AUTH "$API/api/conversations?projectId=<proyecto>" | jq '.[].title'`.
   **Esperado:** solo la del paso anterior, no la de 2.1.

### 2.3 Lista, archivo y eventos

1. Lista todas: `curl -s $AUTH "$API/api/conversations" | jq '.[] | {title, state, messageCount}'`.
   **Esperado:** las dos, la de actividad más reciente primero.
2. Archiva la de 2.1: `curl -s $AUTH -X POST "$API/api/conversations/<id>/archive" | jq .archivedAt`.
   **Esperado:** una fecha. Ya no sale en la lista y sí con `?archived=true`.
3. Intenta escribir en ella (como en 2.1, paso 2).
   **Esperado:** 409 «La conversación está archivada».
4. Mira sus eventos: `curl -s $AUTH "$API/api/events?aggregateId=<id>" | jq '.[].type'`.
   **Esperado:** `chat.conversation.created`, dos `chat.message.created` y `chat.conversation.archived`.

### 2.4 Secretos

1. En una conversación sin archivar, escribe un mensaje con un token falso de GitHub: `{"body": "mi token es ghp_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"}`.
   **Esperado:** el mensaje se guarda como «mi token es [REDACTED]».

## 3. Lo que no cambia

- La web: no hay cortina todavía (CH-D).
- Los runners y los agentes: nadie contesta (CH-C).
- Las vistas de ejecuciones y de uso.
