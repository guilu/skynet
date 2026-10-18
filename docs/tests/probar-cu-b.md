# Probar CU-B en local

CU-B (#111) trae la **atribución**: cuánto de las ventanas de 5 h y semanal ha gastado cada agente y cada ejecución. Aún no hay página Uso (llega en CU-C): se comprueba por la API.

- Entre dos muestras de una ventana, la subida va a los agentes de ese CLI que corrieron en ese intervalo, repartida según su coste. Si no corrió ninguno, va a «Fuera de Skynet».
- Cada agente y cada ejecución traen su `quota`. Los informes nuevos son `GET /api/usage/history`, `/agents` y `/runs`.
- El Dashboard trae `quotaWarnings`: avisa de las ventanas por encima del 90 % y de las semanas que van 15 puntos por encima del ritmo.

Hay una migración nueva, `V25` (`quota_interval` y `agent_run_quota`). Las reglas están en `docs/usage-windows.md`, «Qué gasta cada agente».

## 1. Con los CLIs falsos

`fake-codex` admite ahora `FAKE_CODEX_QUOTA_STEP`: cada sesión gasta esos puntos de cuota (5 h y semanal), como una cuenta de verdad. Con `3,1`, cada agente de Codex sube la de 5 h un 3 % y la semanal un 1 %.

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
rm -rf /tmp/cu-b-codex && mkdir /tmp/cu-b-codex
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_QUOTA_POLL=30s CODEX_HOME=/tmp/cu-b-codex \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CODEX_QUOTA,FAKE_CODEX_QUOTA_STEP \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CODEX_QUOTA=20,10 FAKE_CODEX_QUOTA_STEP=3,1 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080
```

1. Al arrancar, Codex está en 20 % (5 h) y 10 % (semanal), sin nada de Skynet:

   ```bash
   api $B/api/usage/windows | jq -c '.runners[0].windows[] | select(.provider=="codex") | [.window, .usedFraction, .skynet, .pace]'
   ```

   - `["five_hour",0.2,0,…]` y `["seven_day",0.1,0,…]`. `pace` va de 0 a 1.

2. Lanza un agente con **Codex** en un trabajo (en la web o con la API). Cuando termine:

   ```bash
   api $B/api/workflow-runs/<id> | jq -c '.totals.quota[] | [.window, .share, .method]'
   ```

   - `["five_hour",0.03,"measured"]` y `["seven_day",0.01,"measured"]`: toda la subida es suya, porque era el único.
   - En `windows`, Codex está en 0.23 y 0.11, con `skynet` 0.03 y 0.01.

3. Lanza **dos** agentes con Codex a la vez. Al terminar los dos, cada ejecución tiene `method` `prorated`. Entre las dos suman 0.06 de 5 h y 0.02 semanal (el reparto depende del coste de cada uno). En `windows`, `skynet` va por 0.09 y 0.03.

4. La evolución de la ventana de 5 h:

   ```bash
   api "$B/api/usage/history?provider=codex&window=five_hour" | jq -c '.points[] | [.usedFraction, .skynet, .source]'
   ```

   - `[0.2,0,"poll"]`, `[0.23,0.03,"invocation"]`, `[0.26,0.06,…]`, `[0.29,0.09,…]`, más las consultas cada 30 s sin cambios.

5. Lo que cuesta cada agente y las ejecuciones que más han gastado:

   ```bash
   api "$B/api/usage/agents?period=24h" | jq -c '.[] | [.workflowName, .agent, .provider, .runs, .succeeded, .fiveHour, .sevenDay, .sevenDayPerSuccess]'
   api "$B/api/usage/runs?period=24h&limit=3" | jq -c '.[] | [.workItemKey, .status, [.quota[] | .window + "=" + (.share | tostring)]]'
   ```

   - `Agente suelto` / `agent` / `codex`: 3 invocaciones, 3 bien, unos 0.09 de 5 h y 0.03 semanal.
   - Un agente de **Claude Code** con el fake sale con 0: `fake-claude` siempre da la misma cuota (4 % y 16 %), así que no sube.
   - `runs`: las tres de Codex, de la que más ha gastado a la que menos.

6. Avisos: para el runner y arráncalo con `FAKE_CODEX_QUOTA=95,40` y otro `CODEX_HOME` vacío. Tras la primera consulta, `api $B/api/dashboard | jq -c '.quotaWarnings[] | [.providerName, .window, .reason]'` da `["Codex","five_hour","limit"]`.

## 2. En la máquina de Skynet, con los CLIs de verdad

Con el runner de siempre y la sesión iniciada en `claude` y `codex`:

1. Lanza un workflow con agentes de Claude Code. Al terminar, cada agente tiene `quota` con la parte de 5 h y semanal que se le atribuye. Con agentes cortos puede ser 0: Claude da el % con dos decimales.
2. Lanza un agente de Codex. Su `quota` es lo que subió la cuota desde la última consulta del runner (cada 10 min).
3. Usa `codex` a mano un rato sin agentes de Skynet. Tras la siguiente consulta, la subida aparece en `windows` como gasto de fuera: `usedFraction` sube y `skynet` no.

## 3. Comprobaciones automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test ./gradlew check
cd web && npx tsc -b && npx oxlint && npx vitest run
```

`QuotaAttributionIT` cubre estos casos: el reparto por coste entre agentes solapados, una muestra que llega tarde y parte un intervalo, la renovación de la ventana, el redondeo de Claude, el recálculo cuando un agente termina, la primera muestra de Claude, la muestra final de Codex y los informes de la API. Los contratos `fixtures/contracts/usage-*.json` y `dashboard-summary.json` los comprueban el backend y la web.
