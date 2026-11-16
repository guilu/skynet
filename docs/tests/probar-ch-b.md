# Probar CH-B - Consultas del MCP de Skynet

CH-B (#336) es la segunda parte del hito CH «Chat con Skyny» y va en dos PRs. Esta es la primera: las **consultas**. Las acciones que se confirman desde la web llegan en la segunda. Lo explica [`docs/chat.md`](../chat.md), en «El MCP de Skynet».

- **`skynet-runner skynet-mcp`:** un servidor MCP por stdio con 13 herramientas de solo lectura (`estado`, `buscar`, `proyecto`, `trabajo`, `ejecuciones`, `ejecucion`, `aprobaciones`, `workflows`, `workflow`, `uso`, `memoria_buscar`, `memoria_leer` y `wiki`). Cada una llama a la API de siempre.
- **El token de la conversación** (migración V63, `conversation_token`): `Bearer skc_…`, solo `GET` en una lista cerrada de rutas, caduca a los 30 minutos, 200 llamadas como mucho, y sin sesión.
- **`POST /api/conversations/{id}/chat-token`** da uno desde la web, para probarlo a mano.
- Todavía no lo usa nadie: el turno de Skyny llega con CH-C.

Ninguna prueba de esta guía lanza agentes, gasta cuota, modifica ficheros versionados de skynet ni abre PRs.

## 1. Pruebas automáticas

```bash
./gradlew :runner:test --tests '*SkynetMcpServerTest'
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*ChatTokensIT' --tests '*SecurityIT' --tests '*MigrationsIT' --tests '*ModularityTest'
```

`SkynetMcpServerTest`:
- ofrece las 13 herramientas, todas con `readOnlyHint`;
- cada herramienta llama a sus rutas con sus parámetros (p. ej. `ejecuciones` con `estado: failed` pide `status=FAILED&size=10`);
- `buscar` filtra proyectos y trabajos por clave, nombre o título, sin distinguir mayúsculas;
- un id que no es un id (`../runners`), una clave o una página con barras, un estado o un periodo inventados no llegan a la API;
- un 404 de la API vuelve como error legible, y una respuesta de más de 30 000 caracteres se recorta.

`ChatTokensIT`:
- con el token se leen el dashboard, los proyectos, la vista de un proyecto, las ejecuciones y el uso;
- un `POST`, `/api/runners`, `/api/conversations`, `/api/settings/quota` o el contenido de un artefacto dan 403; un token inventado, 401;
- pedir otro deja sin valor el anterior, y revocarlo también;
- un token caducado, uno que llega a 200 llamadas o el de una conversación archivada dan 401, y una archivada no recibe token (409);
- no deja cookie de sesión, y la API del runner no recibe el 403 del filtro.

`SecurityIT` sigue en verde: la web y HTTP Basic no cambian.

## 2. Skynet desplegado

Con Skynet en marcha:

```bash
API=http://localhost:8080; AUTH="-u admin:<contraseña>"
```

### 2.1 Un token y lo que puede leer

1. Crea una conversación y pide su token:

   ```bash
   C=$(curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' -d '{"title":"Probar el MCP"}' | jq -r .conversation.id)
   T=$(curl -s $AUTH -X POST "$API/api/conversations/$C/chat-token" | jq -r .token); echo $T
   ```

   **Esperado:** un token que empieza por `skc_`.
2. Lee con él: `curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" "$API/api/dashboard"`.
   **Esperado:** `200`.
3. Intenta algo que no está en la lista o que cambia algo:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" "$API/api/runners"
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" -X POST "$API/api/projects" -H 'Content-Type: application/json' -d '{"key":"NO","name":"no"}'
   ```

   **Esperado:** `403` las dos veces, y no se crea ningún proyecto.

### 2.2 El MCP a mano

1. Guarda el token en un fichero y arranca el MCP con el runner instalado:

   ```bash
   echo "$T" > /tmp/skc && chmod 600 /tmp/skc
   printf '%s\n' \
     '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
     '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
     '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"estado","arguments":{}}}' \
     '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"buscar","arguments":{"texto":"demo"}}}' \
     | skynet-runner skynet-mcp --url "$API" --token-file /tmp/skc | jq -c '{id, tools: (.result.tools // [] | map(.name)), error: .result.isError, text: (.result.content[0].text // "" | .[0:120])}'
   ```

   **Esperado:**
   - `id` 2 lista las 13 herramientas;
   - `id` 3 devuelve el estado, sin error;
   - `id` 4 devuelve el proyecto y los trabajos de `demo-repo` que casan con «demo».
2. Pide una ejecución con un id inventado (`"name":"ejecucion","arguments":{"id":"../runners"}`).
   **Esperado:** `isError: true` con «id no es un id».

### 2.3 Caduca con la conversación

1. Archiva la conversación: `curl -s $AUTH -X POST "$API/api/conversations/$C/archive" > /dev/null`.
2. Repite el paso 2 de 2.1.
   **Esperado:** `401`.
3. Borra el fichero: `rm /tmp/skc`.

## 3. Lo que no cambia

- La web y el acceso con usuario y contraseña.
- Los agentes de los workflows y su MCP de memoria.
- Nadie contesta todavía en el chat (CH-C).
