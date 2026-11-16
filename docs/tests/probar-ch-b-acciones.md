# Probar CH-B - Acciones por confirmar

CH-B (#336) es la segunda parte del hito CH «Chat con Skyny» y va en dos PRs. La primera trajo las consultas del MCP de Skynet ([`probar-ch-b.md`](probar-ch-b.md)); esta trae las **acciones por confirmar**. Lo explica [`docs/chat.md`](../chat.md), en «Acciones por confirmar».

- **Seis herramientas de acción** en `skynet-runner skynet-mcp`: `lanzar`, `crear_trabajo`, `cancelar`, `reintentar`, `decidir` y `memoria_guardar`. No hacen nada: dejan una propuesta.
- **Las propuestas** (migración V64, `conversation_action`): Skyny las deja con el token de la conversación en `POST /api/runner/chat/actions`; se comprueban al proponerlas, caducan a la hora y hay como mucho 5 pendientes por conversación.
- **La persona las confirma o las rechaza** desde la web (`/api/conversations/{id}/actions/…/confirm` y `/reject`); al confirmar se hacen con el servicio de siempre, a nombre de «`<persona> vía chat`». El token del chat no llega a esas rutas.
- Todavía no hay botones: la cortina llega con CH-D. Aquí se prueba con `curl`.

Ninguna prueba de esta guía lanza agentes, gasta cuota, modifica ficheros versionados de skynet ni abre PRs. Lo que se crea va en el proyecto de `demo-repo`.

## 1. Pruebas automáticas

```bash
./gradlew :runner:test --tests '*SkynetMcpServerTest'
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*ChatActionsIT' --tests '*ChatTokensIT' --tests '*MigrationsIT' --tests '*ModularityTest'
```

`SkynetMcpServerTest`:
- las 13 consultas llevan `readOnlyHint` y las 6 acciones no, y su descripción dice que no se hacen hasta que la persona las confirme;
- cada acción manda a `/api/runner/chat/actions` su tipo y sus datos con los nombres de la API (p. ej. `decidir` con `pedir_cambios` manda `REQUEST_CHANGES` y el comentario), y ninguna hace un `GET`;
- un id que no es un id, una decisión, una clave o un tipo inventados no llegan a Skynet; lo que Skynet rechaza vuelve como error con su motivo.

`ChatActionsIT`:
- proponer un lanzamiento no lanza nada; confirmarlo lanza la ejecución, queda `CONFIRMED` a nombre de «admin vía chat» con la tarjeta de la ejecución, y ya no se puede confirmar ni rechazar otra vez (409);
- crear un trabajo, cancelar una ejecución y guardar memoria se hacen al confirmar; la entrada de memoria entra sin confirmar; no se propone cancelar una ejecución terminada;
- una aprobación se decide a nombre de «admin vía chat», y rechazarla sin comentario no se propone;
- reintentar una fase que no existe no se propone (400), y lo que el servicio rechaza al hacerlo queda `FAILED` con el motivo;
- una rechazada queda `REJECTED`, una de hace más de una hora caduca, y la sexta pendiente da 409;
- con el token del chat no se confirma, no se rechaza ni se ve la lista (403); sin token, o con uno inventado, no se propone (401); en una conversación archivada no se confirma (409);
- cada paso deja su evento `chat.action.*`.

## 2. Skynet desplegado

Con Skynet en marcha y el runner instalado:

```bash
API=http://localhost:8080; AUTH="-u admin:<contraseña>"
P=$(curl -s $AUTH "$API/api/projects" | jq -r '.[] | select(.name|test("demo";"i")) | .id' | head -1); echo $P
C=$(curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' -d "{\"projectId\":\"$P\",\"title\":\"Probar acciones\"}" | jq -r .conversation.id)
curl -s $AUTH -X POST "$API/api/conversations/$C/chat-token" | jq -r .token > /tmp/skc && chmod 600 /tmp/skc
mcp() { printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' "$1" \
  | skynet-runner skynet-mcp --url "$API" --token-file /tmp/skc | tail -1 | jq -r '.result.content[0].text'; }
```

`P` es el proyecto de `demo-repo`. En otra terminal, deja abierto el flujo de eventos del chat: `curl -sN -u admin:<contraseña> "http://localhost:8080/api/events/stream?types=chat.*"`.

### 2.1 Skyny propone un trabajo y tú lo confirmas

1. Propón un trabajo con el MCP:

   ```bash
   mcp "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"crear_trabajo\",\"arguments\":{\"proyecto\":\"$P\",\"titulo\":\"Probar acciones del chat\",\"tipo\":\"refactor\"}}}"
   ```

   **Esperado:** «Propuesta: Crear el trabajo «Probar acciones del chat» (REFACTOR) en … No está hecha: espera a que la persona la confirme en la web», con el id de la propuesta. En la web, el trabajo **no** aparece todavía.
2. Mírala y confírmala:

   ```bash
   curl -s $AUTH "$API/api/conversations/$C/actions" | jq '.[] | {id, kind, status, summary}'
   A=$(curl -s $AUTH "$API/api/conversations/$C/actions" | jq -r '.[-1].id')
   curl -s $AUTH -X POST "$API/api/conversations/$C/actions/$A/confirm" | jq '{status, decidedBy, result}'
   ```

   **Esperado:** `PENDING` en la lista; al confirmar, `CONFIRMED`, `decidedBy: "admin vía chat"` y un `result` con la clave del trabajo nuevo y su `url` (`/work-items/…`). El trabajo aparece en la web.
3. Confírmala otra vez.
   **Esperado:** 409, «La acción ya no espera confirmación: está confirmada».

### 2.2 Una propuesta que rechazas

1. Propón lanzar un workflow sobre el trabajo nuevo (`W` es su id, del `result` del paso anterior):

   ```bash
   W=$(curl -s $AUTH "$API/api/conversations/$C/actions" | jq -r '.[-1].result.id')
   mcp "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lanzar\",\"arguments\":{\"trabajo\":\"$W\",\"workflow\":\"implementar-tarea\"}}}"
   ```

   **Esperado:** «Propuesta: Lanzar implementar-tarea (v…) en …». Si el trabajo no tiene repositorio, el error lo dice («no tiene repositorio: di en cuál se lanza»): repite con `"repositorio":"<id de demo-repo>"`.
2. Recházala: `A=$(curl -s $AUTH "$API/api/conversations/$C/actions" | jq -r '.[-1].id'); curl -s $AUTH -X POST "$API/api/conversations/$C/actions/$A/reject" | jq .status`.
   **Esperado:** `"REJECTED"`, y en «En curso» no aparece ninguna ejecución nueva. **No la confirmes**: lanzaría un agente.

### 2.3 Skyny no puede confirmar

1. Con el token del chat: `curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(cat /tmp/skc)" -X POST "$API/api/conversations/$C/actions/$A/confirm"`.
   **Esperado:** `403`.
2. Propón algo que no vale: `mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cancelar","arguments":{"ejecucion":"../runners"}}}'`.
   **Esperado:** «ejecucion no es un id: ../runners».

### 2.4 El registro

1. Mira la otra terminal.
   **Esperado:** `chat.action.proposed` (2), `chat.action.confirmed` y `chat.action.rejected`, con el actor «admin vía chat» en los dos últimos. Ninguno lleva el texto de la conversación.
2. Archiva el trabajo de prueba desde la web, archiva la conversación (`curl -s $AUTH -X POST "$API/api/conversations/$C/archive" > /dev/null`) y borra el fichero: `rm /tmp/skc`.

## 3. Lo que no cambia

- Las consultas del MCP y su token: siguen sin poder hacer un `POST` a la API de la web.
- Los agentes de los workflows y su MCP de memoria.
- Nadie contesta todavía en el chat (CH-C) ni hay cortina (CH-D).
