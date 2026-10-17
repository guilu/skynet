# Probar CU-A en local

CU-A (#110) trae la **lectura de cuotas**: el runner averigua cuánto llevan gastado las ventanas de 5 h y semanal de Claude Code, Codex y Antigravity (`agy`) y se lo manda al control plane. Aún no hay página Uso (llega en CU-C): se comprueba por la API y en el timeline.

- **Claude Code:** cada agente manda un evento `agent.quota` con las dos ventanas, sacadas de los `rate_limit_event` de su salida.
- **Codex:** al terminar cada agente, el runner lee la cuota del fichero de la sesión. Además, la consulta cada 10 min con `codex app-server`.
- **agy:** el runner la consulta cada 10 min con `agy -p /usage`. Skynet aún no lanza agentes con agy.
- `SKYNET_QUOTA_POLL` cambia el intervalo (`30s`, `1h`…; `off` lo apaga) y `SKYNET_AGY_BIN`, el ejecutable de agy.
- API: `GET /api/usage/windows` y `POST /api/usage/refresh`.

Hay una migración nueva, `V24` (`quota_snapshot`). El latido del runner responde ahora con un cuerpo (`refreshQuotas`).

## 1. Con los CLIs falsos

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm install && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_QUOTA_POLL=30s CODEX_HOME=/tmp/cu-a-codex \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CODEX_QUOTA FAKE_CLAUDE_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

1. Antes de 30 s, la API ya tiene las cuotas que consultó el runner:

   ```bash
   api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
   api http://localhost:8080/api/usage/windows | jq -c '.runners[0].windows[] | [.provider, .bucket, .window, .usedFraction, .source]'
   ```

   - `codex`, `default`: `five_hour` 0.23 y `seven_day` 0.13, `poll`.
   - `antigravity`: 5 ventanas (`3p` 5 h y semanal; `gemini` 5 h y semanal; `gemini-argon-wtu` semanal), `poll`.

2. Lanza un agente de Claude Code en un trabajo. En su timeline sale «Cuota de Claude Code: 4 % de 5 h, 16 % semanal», y su estado no cambia por ello. En la API aparecen `claude-code` `five_hour` y `seven_day` con `source` `invocation`.
3. Lanza un agente con Codex (proveedor Codex al lanzar). Justo antes de «Proceso terminado» sale «Cuota de Codex: 23 % de 5 h, 13 % semanal».
4. Con el runner parado y otro `FAKE_CODEX_QUOTA=50,20`, arráncalo con `SKYNET_QUOTA_POLL=off`. Haz `api -X POST http://localhost:8080/api/usage/refresh`: responde `{"runners":1}`, y tras dos latidos (unos 30 s) Codex dice 0.5 y 0.2.

## 2. En la máquina de Skynet, con los CLIs de verdad

Con el runner de siempre (sin `SKYNET_*_BIN` falsos) y la sesión iniciada en `claude`, `codex` y `agy`:

1. A los pocos segundos de arrancar, `GET /api/usage/windows` tiene Codex y agy con los mismos porcentajes que dan `codex` (`/status`) y `agy -p /usage`.
2. Tras lanzar un agente de Claude Code, Claude Code tiene sus dos ventanas con los mismos porcentajes que `/usage` en `claude`.
3. Si algún CLI no está o no da su cuota, el runner sigue funcionando y ese CLI simplemente no aparece.

## 3. Comprobaciones automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test ./gradlew check
cd web && npx tsc -b && npx oxlint && npx vitest run
```

Los tests del runner leen las salidas grabadas en la máquina de Diego (`fixtures/quota/`, `fixtures/agy/usage.json`) y hablan con `fake-codex app-server` y `fake-agy`. `RunnerProtocolIT` comprueba que la cuota llega por los eventos y por el latido, y que «Actualizar» se avisa una sola vez.
