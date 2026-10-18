# Proveedores de agentes

Skynet ejecuta cada agente con uno de tres CLIs: **Claude Code** (por defecto), **Codex** de OpenAI o **Antigravity CLI** (`agy`) de Google. Gemini CLI se retiró en AR-B: publicar un agente con `provider: gemini` es un error, las versiones publicadas con él no se lanzan y sus ejecuciones antiguas se ven como «Gemini CLI (retirado)». El resto de Skynet (motor, eventos, vista de la ejecución, condiciones y presupuesto) no nota la diferencia: el runner traduce la salida de cada CLI a los mismos eventos normalizados.

Un agente elige su proveedor con `provider` y `model` en el YAML ([`workflows.md`](workflows.md)). Las fases sin agente con nombre, como la de `adhoc`, usan los que se eligen al lanzar en **Lanzar workflow**.

## Qué admite cada uno

| | Claude Code | Codex | Antigravity CLI |
|---|---|---|---|
| `provider` | `claude-code` | `codex` | `antigravity` |
| Línea de comandos | `claude -p --output-format stream-json` | `codex exec --json` | `agy --output-format stream-json` |
| Enviar mensajes (reanudar) | sí (`--resume`) | sí (`codex exec resume`) | sí (`--conversation`) |
| Bifurcar | sí (`--fork-session`) | sí (`codex exec fork`) | **no**: responde 409 y la web desactiva el botón |
| Salida estructurada (`output`) | `--json-schema` | `--output-schema` (modo estricto) | `--json-schema` |
| `tools` | sí | se ignora (aviso al publicar) | se ignora (aviso al publicar) |
| `limits.maxTurns` | sí | se ignora (aviso al publicar) | se ignora (aviso al publicar) |
| Presupuesto | lo vigila el runner con los tokens | igual | igual |
| Modelo sin `model` | `skynet.agent.model` | el de `~/.codex/config.toml` (sin precio) | el de la cuenta (sin precio) |

`GET /api/providers` devuelve esta lista con las capacidades de cada uno (`RESUME`, `FORK`, `STRUCTURED_OUTPUT`, `MAX_TURNS`) y cuántos runners en línea lo tienen. La web la usa para el selector de **Lanzar workflow**, para desactivar lo que un proveedor no admite y para tachar en la política efectiva lo que ignora.

El formato de cada CLI y cómo se traduce cada evento: [`claude-code-stream-json.md`](claude-code-stream-json.md), [`codex-exec-json.md`](codex-exec-json.md) y [`antigravity-stream-json.md`](antigravity-stream-json.md).

## Permisos

La política del repositorio tiene `permissionMode` y `allowedTools`, que son de Claude Code. A los demás se les traduce el modo:

| `permissionMode` | Claude Code | Codex | Antigravity CLI |
|---|---|---|---|
| `plan` (solo lectura) | `plan` | `--sandbox read-only` | `--mode plan` |
| `default`, `dontAsk`, `acceptEdits` | el mismo | `--sandbox workspace-write` | `--mode accept-edits` (los comandos se deniegan) |
| `bypassPermissions` | el mismo | `--dangerously-bypass-approvals-and-sandbox` | `--dangerously-skip-permissions` |

`allowedTools` no tiene equivalente en Codex ni en Antigravity: se ignora en los dos, y la política efectiva lo enseña tachado con «no lo admite Codex».

## Credenciales

Cada CLI usa la cuenta con la que se haya iniciado sesión en la máquina del runner, o sus variables. Cada uno solo hereda las suyas: un agente de Antigravity no recibe la clave de OpenAI ni la de Anthropic.

| Proveedor | Sesión | Variables que hereda |
|---|---|---|
| Claude Code | `claude` y `/login` | `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `CLAUDE_CONFIG_DIR` |
| Codex | `codex login` (cuenta de ChatGPT) | `OPENAI_API_KEY`, `CODEX_API_KEY`, `OPENAI_BASE_URL`, `CODEX_HOME` |
| Antigravity CLI | `agy` e iniciar sesión con Google | ninguna propia: la sesión vive en el `HOME` |

Además, todos heredan las variables básicas (`PATH`, `HOME`, idioma y proxy) y las de `SKYNET_AGENT_ENV`, que la política del repositorio puede reducir.

## Instalar un CLI en el runner

1. Instala el CLI y comprueba que responde a `--version` con el usuario del runner:
   - Claude Code: `npm install -g @anthropic-ai/claude-code`.
   - Codex: `npm install -g @openai/codex`.
   - Antigravity CLI: el instalador de Antigravity (`agy install` configura el `PATH`).
2. Inicia sesión (tabla de arriba) o define sus variables.
3. Si no está en el `PATH`, apúntalo con `SKYNET_CLAUDE_BIN`, `SKYNET_CODEX_BIN` o `SKYNET_AGY_BIN`.
4. Reinicia el runner. Al conectarse escribe `proveedores: …` con los que ha encontrado, y en **Runners** salen con su versión.

El runner también lee las ventanas de uso (5 h y semanal) de Claude Code, Codex y Antigravity (`agy`): ver [`usage-windows.md`](usage-windows.md).

Un agente solo se entrega a un runner que tiene su proveedor. Si ningún runner en línea lo tiene, se queda en cola y el Dashboard lo avisa en «Necesita tu atención». `SKYNET_PROVIDERS=claude-code,codex` limita a mano lo que declara un runner, por ejemplo si tiene un CLI instalado pero sin cuenta.

## Probar sin cuentas

Los simuladores anuncian `(fake-…)` en `--version` (`2.1.0 (fake-claude)`). El runner marca esos proveedores como simulados, y la web lo enseña con la píldora «Simulado» en **Runners**, en la barra lateral, en «Necesita tu atención» del Dashboard y en cada agente que se ejecutó con uno (la cabecera de la ejecución y el Resumen). Así no se confunde una respuesta grabada con la del agente real.

`tools/fake-claude` instala lanzadores que imitan cada CLI con fixtures: `fake-claude`, `fake-codex` y `fake-agy` (ver el README y [`antigravity-stream-json.md`](antigravity-stream-json.md)); `fake-agy` también responde a `/usage`. `fake-codex` escribe su cuota en el fichero de la sesión y responde a `app-server` (`FAKE_CODEX_QUOTA=23,13`). Las E2E (`scripts/e2e.sh`) arrancan el runner con los tres, y `web/e2e/providers.e2e.ts` comprueba la plantilla «Revisión con Codex y Antigravity» y un agente suelto lanzado con Antigravity.
