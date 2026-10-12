# `codex exec --json`

Cómo ejecuta Skynet los agentes con `provider: codex` (PV-B, [#78](https://github.com/guilu/skynet/issues/78)). A diferencia de [`claude-code-stream-json.md`](claude-code-stream-json.md), las sesiones de [`fixtures/codex/`](../fixtures/codex) **no están grabadas** con el CLI real: están escritas a mano a partir del código fuente de Codex (`codex-rs/exec`, eventos de `exec_events.rs`) y de su documentación. Hay que contrastarlas con una sesión real en cuanto haya una cuenta de OpenAI para grabarla; si cambia algo, se corrige el fixture y `CodexEventParser`.

## Línea de comandos

| Skynet | Codex |
|---|---|
| Arranque | `codex exec --json <sandbox> [--model M] [--output-schema F] -- <prompt>` |
| Mensaje (reanudar) | `codex exec --json … resume <thread_id> -- <prompt>` |
| Bifurcar | `codex exec --json … fork <thread_id> -- <prompt>` |
| `permissionMode: plan` | `--sandbox read-only` |
| `permissionMode: bypassPermissions` | `--dangerously-bypass-approvals-and-sandbox` |
| Cualquier otro modo | `--sandbox workspace-write` (escribe solo en el worktree) |
| `model` | `--model`; sin él, el de la configuración de Codex |
| `output` | `--output-schema <logs>/<agente>.schema.json` (ver abajo) |

`codex exec` no pide aprobaciones: lo que el sandbox no deja, falla. El proceso se lanza con el worktree como directorio de trabajo, así que no hace falta `-C`. No hay `--max-turns` ni presupuesto en el CLI: `limits.maxTurns` se ignora (el validador lo avisa) y `maxBudgetUsd` lo hace cumplir el runner con su estimación, como con Claude Code. Tampoco hay lista de herramientas: `tools` se ignora y lo que puede hacer el agente lo marca el sandbox.

La sesión la elige Codex: el `thread_id` de `thread.started` es el `sessionId` del agente, y es el que se usa para reanudar o bifurcar. Las sesiones se guardan en `$CODEX_HOME/sessions` (por defecto `~/.codex`), comunes a todos los directorios, así que bifurcar en otro worktree no copia nada.

**Entorno:** además de las variables básicas, el agente hereda `CODEX_HOME`, `OPENAI_API_KEY`, `CODEX_API_KEY` y `OPENAI_BASE_URL` (y las de `SKYNET_AGENT_ENV`). Con `codex login` hecho no hace falta ninguna.

## Eventos

Una línea JSON por evento en stdout. Cada `exec` es un turno, que acaba en `turn.completed` o `turn.failed`.

| Codex | Evento normalizado |
|---|---|
| `thread.started {thread_id}` | `SESSION_STARTED {provider: codex, sessionId, model}` |
| `turn.started` | — |
| `item.started` / `item.updated` de una herramienta | `TOOL_STARTED` (una vez por `id`) |
| `item.completed` `agent_message` | `MESSAGE_RECEIVED {messageId, text}` |
| `item.completed` de una herramienta | `TOOL_COMPLETED {toolUseId, name, isError, output, details.exitCode}` (y `TOOL_STARTED` antes si no se anunció) |
| `item.completed` `file_change` | además, `FILE_CHANGED {path, change}` por fichero (`add` → `created`, `delete` → `deleted`, `update` → `modified`) |
| `reasoning`, `error` sueltos | `RAW` |
| `todo_list` | — |
| `turn.completed {usage}` | `RESULT {subtype: success, usage, costUsd, result, structuredOutput?}` |
| `turn.failed {error}` | `RESULT {subtype: error, isError, errors}` |

Nombres de herramienta, como los pinta la web: `command_execution` → `Bash` (`input.command`), `file_change` → `apply_patch`, `mcp_tool_call` → `mcp__<servidor>__<herramienta>`, `web_search` → `WebSearch`. Una herramienta falla si su `status` es `failed` o `declined`, o su `exit_code` no es 0. La salida viaja recortada a 16 KB.

## Coste

Codex no informa del coste, solo de `usage`: `input_tokens` (incluye los de caché), `cached_input_tokens` y `output_tokens` (incluye el razonamiento). El runner lo estima con la tabla de precios (`gpt-5*` y `*-codex` en `ModelPrices`, sobrescribibles con `SKYNET_MODEL_PRICES`): `(input − cached) × entrada + cached × lectura de caché + output × salida`. Va en `RESULT.costUsd` como coste **de la invocación**; el control plane suma el acumulado de la sesión. Sin `model` en el agente no se sabe qué modelo usa Codex y no hay estimación (el runner lo avisa en el log).

## Salida estructurada

`--output-schema` exige el modo estricto de OpenAI: cada objeto con `additionalProperties: false` y todas sus propiedades en `required`. El runner lo añade al escribir el fichero, así que los `output` de los workflows valen tal cual (un campo deja de ser opcional). El último `agent_message` es el JSON; si no se puede leer como objeto, el resultado es un error `error_structured_output`.

## fake-codex

`tools/fake-claude` instala también `bin/fake-codex`, que reproduce estos fixtures:

| Fixture | Escenario | Exit |
|---|---|---|
| `01-simple-text` | Respuesta de texto | 0 |
| `02-tools` | Comandos, un parche, MCP, búsqueda web y un comando fallido | 0 |
| `03-resume` | Lo que reproduce `exec resume` y `exec fork` | 0 |
| `04-failed` | `turn.failed` por límite de uso | 1 |
| `05-cancelled` | Se corta sin terminar el turno (se queda esperando hasta SIGTERM) | 143 |

Variables: `FAKE_CODEX_FIXTURE`, `FAKE_CODEX_RESUME_FIXTURE`, `FAKE_CODEX_DELAY_MS`, `FAKE_CODEX_HANG` (`false` para salir con 143 en vez de esperar) y `FAKE_CODEX_STRUCTURED_OUTPUT` (con `--output-schema`, el JSON del último mensaje, o `@fichero`). Con `CODEX_HOME` guarda las sesiones y `resume`/`fork` fallan si no la encuentran.
