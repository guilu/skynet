# `gemini --output-format stream-json`

Cómo ejecuta Skynet los agentes con `provider: gemini` (PV-C, [#79](https://github.com/guilu/skynet/issues/79)). Como con Codex, las sesiones de [`fixtures/gemini/`](../fixtures/gemini) **no están grabadas** con el CLI real: están escritas a mano a partir del código fuente de Gemini CLI (rama `main`, versión `0.65.0-nightly` del 2026-10-06: `packages/core/src/output/types.ts`, `stream-json-formatter.ts`, `packages/cli/src/nonInteractiveCli.ts`, `config.ts` y `utils/errors.ts`). Hay que contrastarlas con una sesión real en cuanto haya una cuenta de Google para grabarla.

## Línea de comandos

| Skynet | Gemini CLI |
|---|---|
| Arranque | `gemini --output-format stream-json --approval-mode M [--model X] --session-id <id> --prompt=<prompt>` |
| Mensaje (reanudar) | `gemini … --resume <id> --prompt=<prompt>` |
| Bifurcar | no se puede: el control plane responde 409 («Gemini CLI no permite bifurcar una sesión») |
| `permissionMode: plan` | `--approval-mode plan` (solo lectura) |
| `permissionMode: bypassPermissions` | `--approval-mode yolo` |
| Cualquier otro modo | `--approval-mode auto_edit`: aprueba las ediciones; lo que pediría confirmación (comandos de shell, salvo los que permita la política de Gemini) se deniega |
| `model` | `--model`; sin él, `auto` (Gemini elige el modelo de cada petición) |
| `output` | instrucción al final del prompt (ver abajo) |

El id de sesión lo fija el control plane, como con Claude Code (`--session-id`). Gemini guarda las sesiones por carpeta de proyecto (`~/.gemini/tmp/<carpeta>/chats`), así que reanudar funciona porque el mensaje sigue en el mismo worktree; bifurcar a otro worktree no tendría la sesión. `--resume` admite el id completo además de `latest` o un número.

Sin `maxTurns` ni lista de herramientas por línea de comandos: `limits.maxTurns` y `tools` se ignoran (el validador lo avisa), y el presupuesto lo hace cumplir el runner con su estimación al final.

**Entorno:** además de las variables básicas, el agente hereda `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, `GOOGLE_GENAI_USE_VERTEXAI`, `GOOGLE_GENAI_USE_GCA`, `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_GEMINI_BASE_URL` y `GEMINI_CLI_HOME` (y las de `SKYNET_AGENT_ENV`). Con la sesión de Google iniciada en el CLI no hace falta ninguna. El runner añade `GEMINI_CLI_TRUST_WORKSPACE=true`: cada worktree es una carpeta nueva y, sin confiar en ella, Gemini fuerza el modo `default`.

## Eventos

Una línea JSON por evento, todas con `type` y `timestamp`.

| Gemini | Evento normalizado |
|---|---|
| `init {session_id, model}` | `SESSION_STARTED {provider: gemini, sessionId, model}` (`model` solo si se pidió uno o no es `auto`) |
| `message` de `user` | — |
| `message` de `assistant` (`delta: true`) | se juntan los fragmentos en un `MESSAGE_RECEIVED {messageId, text}` hasta el siguiente evento que no sea un mensaje |
| `tool_use {tool_name, tool_id, parameters}` | `TOOL_STARTED {toolUseId, name, input}` |
| `tool_result {tool_id, status, output?, error?}` | `TOOL_COMPLETED {toolUseId, name, isError, output, details.errorType}` |
| `tool_result` correcto de `write_file` o `replace` | además, `FILE_CHANGED {path: parameters.file_path, change: modified}` |
| `error {severity, message}` | `RAW` (avisos como «Loop detected» o «Maximum session turns exceeded») |
| `result {status, error?, stats}` | `RESULT {subtype, isError, usage, models, costUsd, result, errors?, structuredOutput?}` |

`run_shell_command` se llama `Bash` en Skynet, como el resto de comandos; las demás herramientas conservan su nombre (`read_file`, `replace`, `write_file`, `glob`, `google_web_search`…). Una herramienta denegada por la política llega como `tool_result` con `error.type: policy_violation`: no es fatal, el modelo sigue.

**Códigos de salida:** 0 bien; 1 error genérico; 41 autenticación; 42 entrada (p. ej. reanudar una sesión que no existe en esa carpeta); 44 sandbox; 52 configuración; 53 límite de turnos (`maxSessionTurns` de la configuración de Gemini); 54 error fatal de herramienta; 55 carpeta no confiable; 130 cancelado.

## Coste

`result.stats` trae los tokens en total y por modelo (`models`): `input_tokens` incluye los leídos de la caché (`cached`), e `input` es el resto. El runner suma cada modelo a su precio (`gemini-2.5-*`, `gemini-3*` en `ModelPrices`, sin el recargo de los prompts de más de 200k tokens; sobrescribibles con `SKYNET_MODEL_PRICES`). Va en `RESULT.costUsd` como coste de la invocación. Los precios salen de agregadores, no de la página de Google: conviene comprobarlos.

## Salida estructurada

Gemini CLI no tiene `--output-schema`. Si el agente tiene `output`, el runner añade al prompt una instrucción con el schema para que termine con un bloque ```` ```json ````; al terminar, toma el último bloque JSON (o el último objeto) del último mensaje y comprueba que tiene todos los campos con su tipo (`StructuredOutput`). Si no lo hay o no cumple, el resultado es un error `error_structured_output` con el motivo, y el agente falla igual que cuando Claude Code o Codex no devuelven la salida.

## fake-gemini

`tools/fake-claude` instala también `bin/fake-gemini`, que reproduce estos fixtures:

| Fixture | Escenario | Exit |
|---|---|---|
| `01-simple-text` | Respuesta de texto en dos fragmentos | 0 |
| `02-tools` | `read_file`, `replace`, un comando y otro denegado por la política; dos modelos (`auto`) | 0 |
| `03-resume` | Lo que reproduce `--resume` | 0 |
| `04-turn-limit` | Se pasa del límite de turnos | 53 |
| `05-cancelled` | Se corta sin `result` (se queda esperando hasta SIGTERM) | 143 |

Variables: `FAKE_GEMINI_FIXTURE`, `FAKE_GEMINI_RESUME_FIXTURE`, `FAKE_GEMINI_DELAY_MS`, `FAKE_GEMINI_HANG` (`false` para salir con 143 en vez de esperar) y `FAKE_GEMINI_STRUCTURED_OUTPUT` (si el prompt pide un bloque JSON, el texto que añade como último mensaje, o `@fichero`). Con `--model`, todo el uso del `result` pasa a ese modelo. Con `GEMINI_CLI_HOME` guarda las sesiones por carpeta: `--resume` falla (42) si no la encuentra y `--session-id` (42) si ya existe.
