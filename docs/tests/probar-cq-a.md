# Probar CQ-A - Esperar a que se renueve la cuota

CQ-A (#212) es la primera PR del hito CQ (continuar tras la cuota). Cuando la cuenta de un CLI se queda sin su ventana de 5 h o semanal, la fase ya no falla: espera a que se renueve y sigue donde lo dejó.

- **Detección**:
  - Claude Code: el `rate_limit_event` con `status: rejected`, sin uso de «overage» y en una ventana de la cuenta (`five_hour`, `seven_day`). Los límites de créditos o de un modelo concreto no cuentan;
  - Codex: el mensaje de error o de `turn.failed` con «usage limit» (o `usageLimitExceeded`), sin créditos ni facturación;
  - Antigravity: el mismo patrón en un resultado fallido;
  - de respaldo, para los tres: un agente que acaba con error cuando la última muestra de Uso (CU) tiene su ventana gastada.

  El agente acaba con el fallo nuevo `quota` («se quedó sin cuota»).
- **Espera**:
  - la fase espera a la ventana bloqueada que más tarde se renueva, más 30 s de margen y hasta 60 s de dispersión (`SKYNET_QUOTA_MARGIN`, `SKYNET_QUOTA_JITTER`);
  - sin hora de renovación, espera 30 minutos, como mucho 2 veces;
  - no gasta `maxAttempts` de `retry` ni hace falta `retry` en el YAML; como mucho 4 esperas por fase;
  - si la renovación queda más allá de la espera máxima (6 h por defecto), la fase falla como antes.
- **Al seguir**: el agente reanuda su sesión en el mismo worktree, sin restaurar instantáneas. Si el proveedor no puede reanudar, empieza una sesión nueva en el mismo worktree con una nota de que ya hay trabajo hecho.
- **Comprobación previa**: una fase lista cuyo proveedor está sin cuota en todos sus runners no arranca; espera igual. La ejecución en pausa conserva su hueco en la cola del repositorio.
- **`onQuota`** por agente en el YAML: `wait` (por defecto) o `fail`. `fallback` llegará con CQ-B y hoy da un error que lo dice.
- **Web**:
  - en la fase, «Esperando la cuota de Claude Code · se renueva a las 18:05 (ventana de 5 h)» y el botón **Reintentar ahora**;
  - el aviso «Pausada por la cuota de Claude Code»;
  - frases nuevas en Actividad;
  - **Ajustes → Ejecución**: interruptor, espera máxima (6 h, 24 h o sin límite), esperas por fase y la lista «En pausa ahora»;
  - en **Uso**, «N fases esperando a que se renueve» en la tarjeta del proveedor.
- **Aviso en la issue** de una ejecución lanzada desde GitHub (DS): «En pausa hasta las 18:05 por la cuota de Claude Code (ventana de 5 h): **CLAVE** sigue sola cuando se renueve.»
- **API**:
  - `GET/PUT /api/settings/quota`;
  - `GET /api/quota/waits`;
  - `POST /api/workflow-runs/{id}/stages/{stageKey}/skip-quota-wait`;
  - `quotaWait` en cada fase de la vista de la ejecución.
- **Migración `V38`**: el fallo `QUOTA` en `agent_run`, sus columnas `quota_limited_at`, `quota_window` y `quota_resets_at`, y en `stage_run` las columnas `quota_provider`, `quota_window`, `quota_resets_at` y `quota_skipped_by`. Se aplica sola al arrancar.
- **fake-claude**: con `FAKE_CLAUDE_QUOTA=<fichero>`, si el fichero existe, la invocación se queda sin cuota una vez. Deja `fake-claude-parcial.txt` en el worktree, avisa de que la ventana de 5 h se renueva en los segundos que diga el fichero (60 si está vacío) y borra el fichero.

Ninguna prueba de esta guía modifica ficheros versionados de skynet: lo que se crea va a `/tmp/cq-a`. Las secciones 1 a 3 no gastan cuota real. La 4 usa `guilu/demo-repo` y nunca skynet.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT' --tests '*PlannerTest' \
  --tests '*DefinitionParserTest' --tests '*ContractIT' \
  :runner:test --tests '*ClaudeStreamParserTest' --tests '*CodexEventParserTest' \
  :tools:fake-claude:test
cd web && npx vitest run src/components/run/stageNotes.test.ts src/notices.test.ts src/contracts.test.ts
scripts/e2e.sh quota-wait.e2e.ts
```

- `WorkflowEngineIT`:
  - un agente sin cuota espera a la renovación y reanuda su sesión en el mismo worktree (el segundo agente es `RESUME`, con el mismo runner y la misma sesión);
  - con `onQuota: fail` la ejecución falla y otra fase del mismo proveedor espera antes de arrancar;
  - una renovación más allá de la espera máxima hace fallar la fase;
  - con el interruptor apagado falla como antes.
- `PlannerTest`: una fase lista que espera la cuota no arranca hasta su hora.
- `DefinitionParserTest`: `onQuota` admite `wait` y `fail`; `fallback` y cualquier otro valor dan error.
- `ClaudeStreamParserTest` y `CodexEventParserTest`: el rechazo de la ventana de 5 h y la semanal (gana la que más tarde se renueva), y que créditos, un modelo o un error cualquiera no bloquean.
- `ContractIT`: las vistas con `quotaWait` y `onQuota` coinciden con `fixtures/contracts/`.

La E2E `quota-wait.e2e.ts` hace de punta a punta lo de la sección 2: fake-claude se queda sin cuota, la fase dice «Esperando la cuota de Claude Code», **Reintentar ahora** la arranca y el agente reanuda la sesión hasta terminar. Corre en CI.

## 2. En local, con los CLIs falsos

Arranca el control plane con poco margen para no esperar tanto, y el runner con fake-claude y el fichero testigo:

```bash
SKYNET_QUOTA_MARGIN=0s SKYNET_QUOTA_JITTER=5s \
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
rm -rf /tmp/cq-a && mkdir -p /tmp/cq-a
SKYNET_RUNNER_HOME=/tmp/cq-a/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_QUOTA \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 FAKE_CLAUDE_QUOTA=/tmp/cq-a/claude-sin-cuota \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

Mientras no exista `/tmp/cq-a/claude-sin-cuota`, fake-claude funciona como siempre.

### 2.1 Espera y sigue sola

1. **Ajustes → Ejecución**: «Continuar tras la cuota» está «Encendido», con espera máxima «6 h», 4 esperas por fase y «En pausa ahora» vacío.
2. `echo 90 > /tmp/cq-a/claude-sin-cuota`: el próximo agente de Claude Code se queda sin cuota y la ventana se renueva en 90 s.
3. Lanza **Implementar tarea** (o cualquier workflow con un agente de Claude Code) sobre un trabajo de `demo-repo`.
4. En unos segundos, en la ejecución:
   - el primer agente acaba «se quedó sin cuota»;
   - la fase dice «Esperando la cuota de Claude Code · se renueva a las HH:MM (ventana de 5 h)», con la hora de dentro de 90 s, y el botón **Reintentar ahora**;
   - la ejecución sigue «En curso» (no «Fallida»);
   - sale el aviso «Pausada por la cuota de Claude Code»;
   - en Actividad, «Fase implement: Claude Code se quedó sin cuota; sigue cuando se renueve, en 1 min…».
5. Mientras espera:
   - **Ajustes → Ejecución → En pausa ahora** la lista, con su trabajo, su fase y la hora;
   - en **Uso**, la tarjeta de Claude Code dice «1 fase esperando a que se renueve».
6. A los 90-95 s la fase sigue sola. El segundo agente:
   - es una «Reanudación» (su tipo en la conversación y en Coste), en el mismo runner;
   - trabaja en el mismo worktree: entre sus ficheros cambiados sigue `fake-claude-parcial.txt`, que dejó el primero.

   La ejecución termina como sin la cuota (con su PR si el workflow la abre).
7. El fichero testigo ya no existe: `ls /tmp/cq-a/claude-sin-cuota` falla.

### 2.2 Reintentar ahora

1. `echo 3600 > /tmp/cq-a/claude-sin-cuota` y lanza otra vez el mismo workflow.
2. La fase espera «se renueva a las …» dentro de una hora. Pulsa **Reintentar ahora**.
3. El agente arranca enseguida, reanuda la sesión y termina. En Actividad, «admin pidió reintentar ya la fase implement sin esperar a la cuota».

Ahora la cuenta cuenta como sin cuota hasta dentro de una hora. Cualquier fase nueva de Claude Code espera antes de arrancar (comprobación previa): lanza un agente de Claude Code y mira que dice «Esperando la cuota de Claude Code» sin haber gastado ningún agente. Pulsa **Reintentar ahora** para no esperar.

### 2.3 `onQuota: fail` y la espera máxima

1. Publica este workflow en **Workflows → Nuevo**:

   ```yaml
   id: cq-a-impaciente
   name: Impaciente de CQ-A
   agents:
     impaciente:
       provider: claude-code
       onQuota: fail
   stages:
     - id: implement
       type: agent
       agent: impaciente
       prompt: Arregla add()
   ```

   Cambia `onQuota: fail` por `onQuota: fallback` antes de publicar: el editor dice que `fallback` llega con el proveedor de reserva (CQ-B). Vuelve a `fail`.
2. `echo 60 > /tmp/cq-a/claude-sin-cuota` y lánzalo: la ejecución falla con «se quedó sin cuota», sin esperar.
3. **La espera máxima**: `echo 30000 > /tmp/cq-a/claude-sin-cuota` (más de 8 h) y lanza **Implementar tarea**. Con la espera máxima de 6 h, la fase falla «se quedó sin cuota». En **Ajustes → Ejecución** elige «24 h», **Guardar**, `echo 30000 > /tmp/cq-a/claude-sin-cuota` y lánzala otra vez: ahora sí espera. Pulsa **Reintentar ahora** y vuelve a poner «6 h».

### 2.4 Apagado

1. **Ajustes → Ejecución → Apagar**: sale «Apagado».
2. `echo 60 > /tmp/cq-a/claude-sin-cuota` y lanza **Implementar tarea**: falla «se quedó sin cuota», como antes de CQ (y, si la fase tiene `retry` con `error`, no se reintenta por esto).
3. **Encender** otra vez.

## 3. La API

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
api http://localhost:8080/api/settings/quota
api -X PUT -d '{"maxWaitHours": 0}' http://localhost:8080/api/settings/quota | jq .maxWaitHours   # 0, sin límite
api -X PUT -d '{"maxWaits": 0}' -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/settings/quota   # 400
api -X PUT -d '{"maxWaitHours": 6}' http://localhost:8080/api/settings/quota > /dev/null
api http://localhost:8080/api/quota/waits | jq length   # 0 si no hay nada en pausa
```

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa fake-claude, así que se prueba igual sin gastar cuota real. Hace falta añadir al entorno del runner `FAKE_CLAUDE_QUOTA=/tmp/cq-a/claude-sin-cuota` (y `FAKE_CLAUDE_QUOTA` en `SKYNET_AGENT_ENV`) y reiniciarlo.

1. Con el despliegue en la versión de la PR, `mkdir -p /tmp/cq-a && echo 120 > /tmp/cq-a/claude-sin-cuota` en la máquina del runner.
2. Si los disparadores de DS están configurados, lanza **Implementar tarea** desde una issue de `guilu/demo-repo` con la etiqueta `skynet:<clave>`. Si no, lánzala desde la web sobre `demo-repo`.
3. Comprueba lo de la sección 2.1, pasos 4 a 6. Si se lanzó desde la issue, en ella aparece el comentario «En pausa hasta las HH:MM por la cuota de Claude Code (ventana de 5 h): **CLAVE** sigue sola cuando se renueve.»
4. La ejecución sigue sola a los 2 minutos y abre su PR en `demo-repo`.
5. **Deja GitHub como estaba**: cierra la PR sin fusionarla, borra su rama y cierra la issue. Quita `FAKE_CLAUDE_QUOTA` del runner y reinícialo.

Con los CLIs de verdad no hace falta forzar nada: la próxima vez que una cuenta se quede sin su ventana, la fase debe esperar y seguir sola. Si Claude Code, Codex o agy avisan de otra forma de la que se espera (el aviso de Claude se ha escrito a partir de su formato documentado, no de una captura real), la fase fallará como antes con «terminó con error». En ese caso, guarda el `stream-json` del agente (pestaña Registro) para ajustar el parser.

## 5. Al terminar

- `rm -rf /tmp/cq-a`.
- **Ajustes → Ejecución**: encendido, 6 h y 4 esperas.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
