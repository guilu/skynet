# `agy --output-format stream-json`

Cómo ejecuta Skynet los agentes con `provider: antigravity` (AR-B, [#119](https://github.com/guilu/skynet/issues/119)). Antigravity CLI (`agy`) sustituye a Gemini CLI, que se retira en el mismo hito. Las sesiones de [`fixtures/agy/`](../fixtures/agy) están **grabadas con agy 1.4.2** en la máquina de Diego (`/tmp/prueba`, con un `calc.py` de una línea).

## Línea de comandos

| Skynet | Antigravity CLI |
|---|---|
| Arranque | `agy --output-format stream-json --mode M [--model X] [--json-schema <fichero>] --print=<prompt>` |
| Mensaje (reanudar) | `agy … --conversation <id> --print=<prompt>` |
| Bifurcar | no se puede: el control plane responde 409 («Antigravity CLI no permite bifurcar una sesión») |
| `permissionMode: plan` | `--mode plan`: modo restrictivo, sin ediciones; sin terminal deniega también los comandos de lectura (`find`, `ls`), así que un revisor no puede explorar el repositorio |
| `permissionMode: dontAsk` | `--dangerously-skip-permissions --sandbox`: no pregunta nada, pero siempre dentro del sandbox de agy (RV-A, [#134](https://github.com/guilu/skynet/issues/134)). Es el modo para un agente headless que tiene que leer el código con comandos, como el revisor de seguridad de `revisar-codigo` |
| `permissionMode: bypassPermissions` | igual que `dontAsk` (la política del control plane ya no lo admite): **nunca** se genera `--dangerously-skip-permissions` sin `--sandbox` |
| Cualquier otro modo | `--mode accept-edits`: aprueba las ediciones; lo que pediría permiso (los comandos) se deniega solo, porque sin terminal no puede preguntar |
| `model` | `--model`; sin él, el predeterminado de la cuenta (agy no dice cuál: no hay coste estimado) |
| `output` | `--json-schema` con el schema en un fichero junto a los logs de la invocación; agy lo valida y lo devuelve en `result.structured_output`. Cada `type: array` necesita `items` (`findings: {type: array, items: {type: object, …}}`): sin él Gemini rechaza la petición con `INVALID_ARGUMENT … properties[findings].items: missing field`. Un borrador así no se puede publicar (error); una versión ya publicada sin `items` se sigue leyendo y ejecutando, con un aviso |

El id de la conversación lo asigna agy al arrancar (`init.conversation_id`) y el runner lo guarda como sesión del agente, como con Codex. El prompt va con `=` para que uno que empiece por `-` no se lea como una opción.

Sin `maxTurns` ni lista de herramientas por línea de comandos: `limits.maxTurns` y `tools` se ignoran (el validador lo avisa), y el presupuesto lo hace cumplir el runner con su estimación.

**Entorno:** agy guarda su sesión de Google en el `HOME` del usuario, así que el agente solo hereda las variables básicas y las de `SKYNET_AGENT_ENV`.

## Eventos

Una línea JSON por evento, con `event`. agy cuenta la conversación en pasos (`step_update`, con `step_index` y `state` `ACTIVE` o `DONE`).

| agy | Evento normalizado |
|---|---|
| `init {conversation_id, init.permission_mode}` | `SESSION_STARTED {provider: antigravity, sessionId, model, permissionMode}` (`model` solo si se pidió) |
| `step_update` `agent_response` con `text_delta` | se juntan los fragmentos hasta que el paso llega a `DONE`: `MESSAGE_RECEIVED {messageId: step_N, text, usage}` |
| `usage` de un `agent_response` en `DONE` | viaja en el siguiente `MESSAGE_RECEIVED` o `TOOL_STARTED` (`usage {input, output, cacheRead}`), para que el control plane sume los tokens en vivo. Un agente cancelado (el fail-fast de una `parallel`) no llega al `result`, pero conserva sus tokens: los de un último paso sin texto (solo razonamiento) que ningún evento llevó van en el `usage` del fin del proceso (`PROCESS_EXITED`). Su coste es el que estimó el runner (`estimatedCostUsd`) |
| `step_update` `tool` en `ACTIVE` | `TOOL_STARTED {toolUseId: step_N, name, input}`; `run_command` sale como `Bash` con `input.command` |
| `step_update` `tool` en `DONE` | `TOOL_COMPLETED {toolUseId, name, isError: false, output}` |
| `write_to_file`, `replace_file_content`, `multi_replace_file_content`, `sed_file` en `DONE` | además, `FILE_CHANGED {path: TargetFile, change: created \| modified}` |
| `step_update` `user_input`, `system_message`, `finish` | nada |
| `result.denied_actions[]` | `PERMISSION_DENIED {toolName: display_name, reason: action}` por cada una |
| `result {status, response, num_turns, usage, structured_output}` | `RESULT {subtype, isError, numTurns, sessionId, usage, costUsd, result, structuredOutput}` |
| otra línea | `RAW` |

`status` distinto de `SUCCESS` es un `RESULT` con `isError` y «Antigravity CLI terminó con estado X», y agy sale con código distinto de 0. Si se pidió `output` y el resultado no trae `structured_output`, el `RESULT` sale con `subtype: error_structured_output`.

**Uso y coste.** Cada paso del agente que termina trae su `usage` (`input_tokens` sin la caché, `output_tokens`, `thinking_tokens`, `cache_read_tokens`) y el del `result` es el total. El razonamiento se cobra como salida: `usage.output` = `output_tokens` + `thinking_tokens`. El runner suma el de cada paso según llega para vigilar el presupuesto; con `model` fijado usa su precio (los `gemini-*` de la tabla), y sin él lo anota como modelo sin precio.

## Probar sin cuenta

`fake-agy` (en `tools/fake-claude`) reproduce los fixtures con el id de conversación de la invocación y las rutas del worktree:

- `FAKE_AGY_FIXTURE`: `01-simple-text` (por defecto), `02-tools`, `04-schema`, `05-denied`, `06-cancelled` (sin `result`, se queda esperando hasta que lo maten) o la ruta a un JSONL.
- `FAKE_AGY_RESUME_FIXTURE`: el de `--conversation` (por defecto `03-resume`).
- `FAKE_AGY_DELAY_MS` (por defecto 10) y `FAKE_AGY_HANG=false` para terminar con 143 sin esperar.
- `FAKE_AGY_STRUCTURED_OUTPUT`: con `--json-schema`, el JSON de `structured_output`, o `@ruta` para leerlo del worktree.
- `FAKE_AGY_HOME`: guarda cada conversación en `<dir>/conversations/<id>.jsonl`; `--conversation` falla si no la encuentra.

También responde a `/usage` con la cuota grabada (`fixtures/agy/usage.json`, ver [`usage-windows.md`](usage-windows.md)).
