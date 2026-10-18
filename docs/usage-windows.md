# Cuotas de uso de los CLIs

Con suscripción, cada CLI tiene dos ventanas de uso: una de **5 horas** y otra **semanal**. Skynet guarda cuánto lleva gastado cada una, por runner (cada runner puede tener sesión con una cuenta distinta). Es el hito CU; el plan está en `plans/siguiente-hito-cuotas.md`.

**Los CLIs solo dan porcentajes.** Ninguno dice cuántos tokens caben en una ventana ni cuántos quedan, así que Skynet guarda el % gastado (`usedFraction`, de 0 a 1) y cuándo se renueva.

## De dónde sale cada dato

| CLI                 | Cuándo                   | De dónde                                                                                                                                                                                                                                                                                                         |
| ------------------- | ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Claude Code         | Durante cada agente      | Los `rate_limit_event` de su `stream-json` traen `unifiedWindows.five_hour` y `seven_day` con `utilization` y `resetsAt`, con cualquier estado (`allowed`, `allowed_warning`…). No hay forma oficial de preguntarlo sin ejecutar nada.                                                                           |
| Codex               | Al terminar cada agente  | `codex exec --json` no la da. El runner lee el último `token_count` del fichero de la sesión (`$CODEX_HOME/sessions/AAAA/MM/DD/rollout-…-<thread_id>.jsonl`): `rate_limits.primary` (300 min) y `secondary` (10080 min), con `used_percent`, `resets_at` y `plan_type`.                                          |
| Codex               | Cada `SKYNET_QUOTA_POLL` | `codex app-server`, método `account/rateLimits/read`, sin gastar cuota.                                                                                                                                                                                                                                          |
| Antigravity (`agy`) | Cada `SKYNET_QUOTA_POLL` | `agy -p /usage --output-format json --log-file /dev/null`, sin gastar cuota. Da varios grupos de modelos (`gemini`, `3p` = Claude y GPT, `gemini-argon-wtu`), cada uno con su ventana de 5 h, la semanal o las dos (`remaining_fraction`, `reset_time`). Skynet aún no lanza agentes con agy: solo lee su cuota. |

Las salidas reales están grabadas en `fixtures/quota/` (Claude Code 2.1.301 y codex-cli 0.163.0) y `fixtures/agy/usage.json` (agy 1.4.0). Si un CLI cambia de formato, el runner lo anota en su log y ese CLI se queda sin datos, sin romper nada más.

## Cómo viaja

- Lo que ve un agente va en el evento `agent.quota` de la invocación: `{provider, windows: [{bucket, label?, window, usedFraction, resetsAt?, plan?}]}`. De Claude Code se emite solo cuando cambia; de Codex, uno justo antes del fin. No cambia el estado del agente. En el timeline se lee «Cuota de Codex: 23 % de 5 h, 13 % semanal».
- Lo que consulta el runner por su cuenta va en el latido (`RunnerHeartbeat.quotas`). Si un latido no llega, sus muestras van en el siguiente.
- Todo acaba en `quota_snapshot` (migración `V24`), con `source` = `invocation` o `poll`. Las muestras de más de 35 días se borran.

## Qué gasta cada agente (CU-B)

Entre dos muestras seguidas de una misma ventana (de un CLI en un runner), la subida se reparte:

- entre los agentes de ese CLI que corrieron en ese runner durante el intervalo, en proporción a su **coste** (la parte que cae dentro del intervalo). Si alguno no tiene coste, en proporción a sus tokens; si tampoco, al tiempo que corrió;
- si no corrió ninguno, va a **«Fuera de Skynet»**;
- si la ventana se renovó entre las dos (pasó su `resetsAt`, o bajó más que el redondeo de Claude), la subida se cuenta desde 0 y desde la renovación.

Detalles:

- **Claude Code** da la cuota desde el principio de cada invocación: su primera muestra es el punto de partida (lo de antes no es suyo). **Codex** la da al final: lo que sube después ya no es suyo.
- No cuentan los agentes que no llegaron a arrancar el CLI (fallo `start`).
- Se recalcula al llegar cada muestra (también si llega tarde: parte el intervalo en dos) y cuando un agente termina o se da por perdido, porque hasta entonces no se sabe su coste.
- Si uno usa el CLI a mano mientras corre un agente, esa subida se atribuye al agente: la cuota es de la cuenta.

Se guarda en `quota_interval` (cada subida, con la parte de Skynet) y `agent_run_quota` (la parte de cada agente, `measured` si era el único y `prorated` si se repartió), migración `V25`. Los intervalos se borran a los 35 días con sus muestras; lo atribuido a cada agente se queda.

## Configuración del runner

- `SKYNET_QUOTA_POLL`: cada cuánto pregunta a Codex y a agy (`10m` por defecto; `30s`, `1h`…; `off` lo apaga). Con `off`, «Actualizar» sigue funcionando.
- `SKYNET_AGY_BIN`: el ejecutable de agy (`agy` por defecto). Solo se consulta si responde a `--version`.
- Codex se consulta si el runner lo tiene como proveedor. Lee `CODEX_HOME` (por defecto `~/.codex`).

## API

- `GET /api/usage/windows[?runner=<id>]`: por runner no olvidado, la última muestra de cada CLI, grupo y ventana: `provider`, `providerName`, `bucket`, `label`, `window` (`five_hour` o `seven_day`), `usedFraction`, `resetsAt`, `plan`, `source`, `observedAt` y `stale` (el dato tiene más de 5 h).
  Además, `pace` (dónde estaría gastando igual toda la ventana, o `null` si no se sabe cuándo se renueva) y `skynet` (de lo gastado en la ventana, lo atribuido a agentes de Skynet).
- `GET /api/usage/history?provider=&bucket=default&window=five_hour[&runner=]`: la ventana actual (`windowStart`, `resetsAt`) con sus muestras: `at`, `usedFraction`, `skynet` (lo de Skynet acumulado desde que empezó la ventana) y `source`. Sin `runner`, el que la vio por última vez; 404 si no hay muestras.
- `GET /api/usage/agents?period=24h|7d|30d[&runner=]`: por workflow, agente del YAML (o fase) y CLI, las invocaciones lanzadas en el periodo: `runs`, `succeeded`, `fiveHour`, `sevenDay`, `sevenDayPerRun`, `sevenDayPerSuccess` y tokens, de la que más semana gasta a la que menos.
- `GET /api/usage/runs?period=7d&limit=10[&runner=]`: las ejecuciones que más cuota semanal (luego de 5 h) han gastado, con su `quota` por CLI y ventana.
- En la ejecución, `totals.quota`, y en cada agente, `quota`: `[{provider, bucket, window, share, method}]`.
- `GET /api/dashboard` trae `quotaWarnings`: las ventanas que pasan del 90 % (`limit`) y las semanales que van 15 puntos por encima del ritmo (`pace`). No cuentan las ya renovadas.
- `POST /api/usage/refresh[?runner=<id>]` (202): el runner consulta sus cuotas en cuanto recibe la respuesta a su siguiente latido (`HeartbeatReply.refreshQuotas`), y las manda en el latido de después. La de Claude Code no se puede consultar: llega con el siguiente agente.

## Lo que no se puede saber

- Cuántos tokens caben en una ventana.
- La cuota de Claude Code sin lanzar un agente: si se usa Claude fuera de Skynet, Skynet no se entera hasta el siguiente agente.
- Qué parte gasta uno fuera de Skynet, con exactitud: la cuota es de la cuenta. Mientras corre un agente, todo lo que sube se le atribuye.
- Qué gasta un agente pequeño de Claude Code: `utilization` llega con dos decimales, así que puede no mover la aguja y el siguiente cargar con el 1 %.
