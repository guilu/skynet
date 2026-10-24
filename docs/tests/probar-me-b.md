# Probar ME-B: puente MCP de memoria para los agentes

ME-B ([#151](https://github.com/guilu/skynet/issues/151), parte de [#141](https://github.com/guilu/skynet/issues/141)) da a los agentes las herramientas de memoria de engram por MCP:

| Herramienta | Qué hace |
|---|---|
| `mem_context` | Lo fijado, los últimos resúmenes de sesión, la bitácora y lo último guardado del repositorio y su proyecto (hasta 4 000 caracteres, entradas enteras). |
| `mem_search` | Busca en la memoria del repositorio y de su proyecto; devuelve vistas previas. |
| `mem_get` | Una observación entera. |
| `mem_save` | Guarda una decisión, un bug resuelto, un descubrimiento, un patrón, configuración, una trampa o una restricción. Con el mismo `topic_key` crea una versión nueva. |
| `mem_session_summary` | El resumen de la sesión (objetivo, qué se hizo, descubrimientos, siguientes pasos, ficheros). Llamarla otra vez lo actualiza. |

## Cómo funciona

1. Al arrancar una invocación de agente, el **runner pide un token de memoria** para ella al control plane (`POST /api/runner/agent-runs/{id}/memory-token`, con el token del runner). Solo lo da si la invocación es de ese runner y sigue viva, y nunca a un comando.
2. El runner deja el token en `logs/<id>.memory-token`, con permisos `0600`. El token **no va en la línea de comandos, ni en el prompt, ni en la configuración MCP**, y el control plane solo guarda su hash.
3. El CLI del agente lanza el servidor MCP del runner, `skynet-runner memory-mcp --url … --token-file … --mode write`. Este servidor habla JSON-RPC por stdio y reenvía cada llamada a `/api/runner/memory/*`.
4. El **ámbito lo decide el control plane** a partir del token: el proyecto y el repositorio de la invocación. El agente no puede pedir otro ni leer lo de otro proyecto o repositorio (404).
5. El token **caduca al terminar la invocación** (401), y el runner borra su fichero. Pedir otro token para la misma invocación invalida el anterior.
6. Por invocación hay un máximo de 500 llamadas y 100 guardados (429 al pasarse; `SKYNET_MEMORY_MAX_CALLS` y `SKYNET_MEMORY_MAX_SAVES`). Lo que se guarda pasa por la redacción de secretos de ME-A. Cada contexto, búsqueda, lectura o guardado queda en `memory_access`.
7. Si el control plane no da el token, el agente **arranca igual, sin memoria** (aviso en el log del runner).

Configuración en cada CLI:
- **Claude Code:** `--mcp-config logs/<id>.mcp.json` con el servidor `skynet_memoria`, y `mcp__skynet_memoria` se añade a `--allowedTools`.
- **Codex:** `-c mcp_servers.skynet_memoria.command=…`, `-c mcp_servers.skynet_memoria.args=[…]` y `-c mcp_servers.skynet_memoria.default_tools_approval_mode="approve"` tras `exec --json`. Sin esta última, `codex exec` rechaza las herramientas con «MCP tool call requires approval, but approval policy is never», porque en modo headless no puede preguntar.
- **Antigravity:** todavía sin herramientas de memoria; arranca sin ellas. No he podido comprobar aquí si `agy` admite servidores MCP en modo headless. En ME-C recibirá el bloque de contexto inicial en el prompt, como los demás.

En ME-B **todos los agentes tienen lectura y escritura**. La clave `memory: off | read | write` del YAML y el bloque de contexto al principio del prompt llegan en ME-C.

Hay una migración nueva, la **V28** (`memory_token`). No hay dependencias nuevas ni cambia la exposición de red: el servidor MCP es un proceso hijo del agente que solo habla por stdio y llama a la misma URL del control plane que ya usa el runner.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. El paso 2 necesita un agente real y se hace sobre demo-repo (proyecto DEMO), nunca sobre skynet.

## 1. Tests

```bash
./gradlew build --no-daemon
```

- `MemoryAgentIT` (control plane):
  - una invocación guarda una decisión y un resumen, y otra invocación del mismo proyecto los encuentra, los lee y los recibe en `mem_context`, con su procedencia y su `memory_access`;
  - una invocación solo ve su proyecto y su repositorio, y no puede guardar `RUN_LOG` ni `SESSION_SUMMARY` con `mem_save`;
  - el token deja de valer al terminar la invocación o al pedir otro, solo lo pide el runner que la tiene y solo se guarda su hash;
  - los límites dan 429 y el modo de solo lectura no puede guardar (403);
  - un comando no tiene memoria.
- `MemoryMcpServerTest` (runner): negociación de `initialize`, herramientas en modo lectura y escritura, paso de argumentos (`topic_key` → `topicKey`), errores como resultados `isError` y errores de JSON-RPC.
- `ClaudeCodeProviderTest` y `CodexProviderTest`: la línea de comandos con y sin memoria; la configuración MCP no lleva el token.
- `AgentMemoryTest` (runner, de punta a punta): fake-claude lanza el servidor MCP del runner de verdad, llama a `mem_context` y `mem_save` contra un control plane de mentira con el token de la invocación, y el fichero del token se borra al terminar. Si el token no llega, el agente arranca sin memoria.

## 2. Con un agente real (demo-repo)

Con el control plane y el runner en marcha (`http://localhost:8080`, contraseña de admin en `$SKYNET_ADMIN_PASSWORD`) y el proyecto DEMO con demo-repo:

```bash
api() { curl -s -u "admin:$SKYNET_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
P=$(api localhost:8080/api/projects | jq -r '.[] | select(.key=="DEMO") | .id')
```

1. **Claude Code guarda.** Lanza en un work item de DEMO una ejecución de un solo agente de Claude Code con este prompt:

   > Mira cómo se ejecutan los tests de este repositorio. Guarda en la memoria, con `mem_save`, un descubrimiento con `topic_key` `tests/como-ejecutar` y cómo se ejecutan. Antes de terminar, llama a `mem_session_summary`.

   Al terminar:

   ```bash
   api "localhost:8080/api/projects/$P/memory?q=tests" | jq '.items[] | {kind,title,topicKey,origin}'
   ```

   Debe salir un `DISCOVERY` con `topicKey` `tests/como-ejecutar` y `origin` `AGENT`, y un `SESSION_SUMMARY` con el tema `sesion/<id de la invocación>`. En el log del agente (`~/.skynet-runner/logs/<id>.ndjson` o la pestaña de eventos) se ven las llamadas a `mcp__skynet_memoria__mem_save`.

2. **Otro agente lo encuentra.** Lanza otra ejecución en DEMO con el prompt:

   > Usa `mem_search` para buscar en la memoria cómo se ejecutan los tests y dime qué encuentras, sin ejecutar nada.

   La respuesta debe citar lo guardado en el paso 1.

3. **Codex.** Repite los pasos 1 y 2 con un agente `provider: codex`. En su log, las llamadas a `mem_context`, `mem_search`, `mem_save` y `mem_session_summary` deben terminar bien, sin «requires approval». Si tu versión de Codex no conoce `default_tools_approval_mode`, avisará al arrancar de que ignora la clave: dime la versión (`codex --version`).

4. **El token caduca y no se queda en disco.** Al terminar cualquiera de las ejecuciones:

   ```bash
   ls ~/.skynet-runner/logs/*.memory-token 2>/dev/null | wc -l   # 0
   grep -l skm_ ~/.skynet-runner/logs/*.mcp.json | wc -l           # 0: ninguno tiene el token
   ```

   (Ajusta la ruta si `SKYNET_RUNNER_HOME` apunta a otro sitio.)

5. **Antigravity.** Una ejecución con `provider: antigravity` debe funcionar como antes, sin herramientas de memoria. El log del runner dice «arranca sin memoria: antigravity no admite el servidor MCP de memoria».
