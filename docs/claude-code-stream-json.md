# Spike: `claude -p --output-format stream-json`

Resultado del spike de M0 ([#3](https://github.com/guilu/skynet/issues/3)). Hemos grabado 9 sesiones reales con Claude Code **2.1.288** (2026-10-03, coste total ≈ 0,58 USD). Las grabaciones están en [`fixtures/claude/`](../fixtures/claude) y `tools/fake-claude` las reproduce.

Las rutas absolutas se han sustituido por `/workspaces/demo` y `/home/dev`. En los deltas `input_json_delta` se ha reconstruido y vuelto a trocear el JSON parcial para que la concatenación siga siendo válida.

## Sesiones grabadas

| Fixture | Escenario | Flags relevantes | `result.subtype` | Exit |
|---|---|---|---|---|
| `01-simple-text` | Respuesta de texto sin herramientas | — | `success` | 0 |
| `02-tools` | Read → Edit → `git commit` | `--session-id`, `--allowedTools`, `--permission-mode acceptEdits` | `success` | 0 |
| `03-resume` | Continúa la sesión de `02` con un mensaje nuevo | `--resume <id>` | `success` | 0 |
| `04-fork` | Bifurca la sesión de `02`/`03` | `--resume <id> --fork-session` | `success` | 0 |
| `05-max-turns` | Corta por límite de turnos | `--max-turns 1` | `error_max_turns` | 1 |
| `06-permission-denied` | Herramientas denegadas | `--permission-mode dontAsk` | `success` | 0 |
| `07-cancelled` | Proceso terminado con SIGTERM a mitad | — | *(sin línea `result`)* | 124/143 |
| `08-budget-exceeded` | Corta por presupuesto | `--max-budget-usd 0.001` | `error_max_budget_usd` | 1 |
| `09-json-schema` | Salida estructurada validada | `--json-schema '{…}'` | `success` | 0 |

Todas las grabaciones usan `--output-format stream-json --verbose --include-partial-messages`.

## Flags validados

Todos los flags previstos en el plan existen y funcionan: `--session-id`, `--resume`, `--fork-session`, `--max-turns`, `--max-budget-usd`, `--include-partial-messages`, `--allowedTools`, `--permission-mode`. Notas:

- `--max-turns` funciona aunque **no aparece en `--help`**.
- `--permission-mode` admite `acceptEdits`, `auto`, `bypassPermissions`, `manual`, `dontAsk` y `plan`. Para agentes no supervisados, `dontAsk` deniega todo lo que no esté en `--allowedTools`, sin bloquearse.
- `--json-schema <schema>` produce `result.structured_output` ya validado. **Es el mecanismo natural para el contrato de resultado de §9.4.** Así no hay que parsear el texto final.

## Hallazgos que cambian el diseño

1. **Hay que redirigir stdin a `/dev/null`.** Si no, el CLI espera 3 s a recibir datos y escribe un aviso en stderr. El `ProcessSupervisor` debe lanzar siempre con stdin cerrado.
2. **`total_cost_usd` y `modelUsage` son acumulados por sesión.** Con `--resume` el coste incluye las invocaciones anteriores, y una sesión bifurcada con `--fork-session` también hereda el coste de la sesión original. En las grabaciones: `02` = 0,0574, `03` = 0,0815 (+0,024) y `04` = 0,0867 (+0,005). Por tanto:
   - El coste de un `AgentRun` es la **diferencia** respecto a la invocación anterior en el mismo linaje de sesión.
   - Guardamos ambos valores: `cost_usd_cumulative` (el que reporta el CLI) y `cost_usd`, calculado.
3. **`--max-budget-usd` no es un límite duro.** Se comprueba al terminar un turno: con un límite de 0,001 la sesión gastó 0,064. El runner debe aplicar su propio límite (coste estimado a partir de `usage` en `message_delta`) y cancelar si se supera con margen.
4. **`--max-turns N` cuenta de forma distinta a `num_turns`.** Con `--max-turns 1` el resultado reportó `num_turns: 2`. Tomamos `num_turns` del resultado como dato informativo; no lo usamos para validar límites.
5. **Una cancelación no deja línea `result`.** El estado final de una ejecución cancelada lo deduce el runner a partir de la señal y el exit code, nunca del stream.
6. **Las denegaciones de permisos no hacen fallar la ejecución.** En `06` el resultado es `success` con `permission_denials: [2 elementos]`. Se ven como `system/permission_denied` y como `tool_result` con `is_error: true`. Hay que mostrarlas en la UI y en los criterios de salida.
7. **Hay un evento `assistant` por bloque de contenido, no por mensaje.** Varios `assistant` comparten `message.id`. Para agrupar mensajes, el timeline debe usar `message.id`.
8. **`tool_use_result` trae datos estructurados útiles.** `Edit` incluye `structuredPatch`, `filePath`, `originalFile`. `Bash` incluye `stdout`, `stderr`, `interrupted` y `gitOperation`. Con `Edit` podemos generar eventos de archivo modificado sin parsear texto.
9. **`system/vcs_state_changed`** (`kind: "commit"`, `branch`) avisa de commits hechos por el agente. Sirve de señal, pero se verifica con git (§4.5) Llega **antes** del `tool_result` del `Bash` que hizo el commit.

## Tipos de evento observados

| `type` / `subtype` | Contenido relevante |
|---|---|
| `system/init` | `session_id`, `model`, `cwd`, `tools[]`, `permissionMode`, `claude_code_version`, `mcp_servers[]` |
| `system/status` | `status: "requesting"`: hay una petición al modelo en curso |
| `system/thinking_tokens` | `estimated_tokens`, `estimated_tokens_delta` |
| `system/permission_denied` | `tool_name`, `tool_use_id`, `decision_reason_type`, `message` |
| `system/vcs_state_changed` | `kind` (`commit`), `branch`, `cwd` |
| `stream_event` | Eventos SSE de la API: `message_start`, `content_block_start/delta/stop`, `message_delta` (incluye `usage`), `message_stop` |
| `assistant` | Un bloque completo (`text` o `tool_use` con `input` completo), `message.id`, `message.model` |
| `user` | `tool_result` (`tool_use_id`, `is_error`, `content`) y `tool_use_result` estructurado |
| `rate_limit_event` | Estado de límites de uso de la cuenta |
| `result` | `subtype`, `is_error`, `errors[]`, `terminal_reason`, `num_turns`, `total_cost_usd`, `usage`, `modelUsage`, `permission_denials[]`, `structured_output`, `result` |

## Mapeo a eventos normalizados (actualiza §4.2 del plan)

| NDJSON | Evento normalizado | Estado observable |
|---|---|---|
| `system/init` | `agent.session.started` (session, modelo, versión CLI, herramientas, permission mode) | `STARTING → THINKING` |
| `system/status` (`requesting`) | — (solo actualiza `last_activity_at`) | `THINKING` |
| `system/thinking_tokens` | — (agregado en vivo por SSE) | `THINKING` |
| `stream_event` (deltas de texto) | `agent.message.delta`, solo en vivo; no se persiste uno a uno | `THINKING` |
| `stream_event/message_delta` | actualiza tokens en vivo | — |
| `assistant` con `text` | `agent.message.received` (agrupado por `message.id`) | `THINKING` |
| `assistant` con `tool_use` | `agent.tool.started` | `EXECUTING` |
| `user` con `tool_result` | `agent.tool.completed` (`is_error`, resumen de `tool_use_result`) | `THINKING` |
| `user` con `tool_result` de `Edit`/`Write` | además `agent.file.changed` (ruta, patch) | — |
| `system/permission_denied` | `agent.permission.denied` | — |
| `system/vcs_state_changed` | `agent.vcs.changed` (pendiente de verificar con git) | — |
| `rate_limit_event` | `agent.rate_limit` (solo si `status != "allowed"`) | — |
| `result` | `agent.result` (subtype, terminal_reason, turnos, tokens, coste acumulado, structured_output, errors) | `COMPLETED` / `FAILED` |
| fin de proceso | `agent.process.exited` (exit code, señal) | estado terminal; `CANCELLED` si lo cancelamos nosotros |
| línea desconocida | `agent.raw` | — |

El parser que implementa este mapeo es `runner/.../provider/claude/ClaudeStreamParser`, probado con todos los fixtures. Los bloques `thinking` no producen eventos y la salida de las herramientas se trunca a 16 KB (el NDJSON completo se guarda como artefacto).

## Implicaciones para M2

- `ProcessSupervisor`: stdin desde `/dev/null` y grupo de procesos propio. Al cancelar se envía SIGTERM y, tras un tiempo de gracia, SIGKILL.
- `ClaudeCodeProvider`: llamar siempre con `--output-format stream-json --verbose --include-partial-messages --permission-mode dontAsk --allowedTools …`. En fases con salida estructurada, añadir `--json-schema`.
- Validar `claude_code_version` de `system/init` contra un rango soportado y mostrar un aviso si no coincide.
- Calcular el coste por invocación como diferencia del coste acumulado. Aplicar un presupuesto propio en el runner.
