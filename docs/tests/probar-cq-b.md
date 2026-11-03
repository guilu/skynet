# Probar CQ-B - Proveedor de reserva

CQ-B (#213) es la segunda y última PR del hito CQ (continuar tras la cuota). Con CQ-A, un agente sin cuota espera a que se renueve. Con CQ-B, si tiene **proveedor de reserva** y la espera es larga, no espera: sigue la reserva en el mismo worktree. Lo cuenta «Sin cuota» en [`docs/workflows.md`](../workflows.md).

- **YAML**:
  - `fallback: {provider, model}` por agente. El proveedor tiene que ser otro (`claude-code`, `codex` o `antigravity`); el modelo es opcional;
  - `onQuota: fallback` (lo que se hace por defecto si hay reserva). `onQuota: wait` o `fail` con reserva dan un aviso: la reserva no se usa nunca;
  - el esquema JSON y el editor lo conocen.
- **Cuándo cambia**:
  - si la cuota del principal se renueva en más de 15 minutos (Ajustes → Ejecución, «Cambiar a la reserva si la espera pasa de») o más allá de la espera máxima;
  - si es menos, espera al principal como en CQ-A;
  - si la reserva también está sin cuota, espera a la primera que se renueve.
- **Cómo sigue**: un agente nuevo de la reserva en el **mismo worktree**, con el prompt original, los mismos permisos y la misma salida estructurada, y la nota «un agente de Claude Code empezó esta tarea y se quedó sin cuota…».
  - Las fases nuevas de ese agente arrancan ya con la reserva mientras el principal siga sin cuota.
  - Si la reserva se queda sin cuota, se vuelve al principal en cuanto la tenga.
- **Web**:
  - en la fase, «Sustituido por Codex (Claude Code sin cuota hasta las HH:MM)»;
  - el aviso «Codex sustituye a Claude Code»;
  - en Actividad, «Sustituye a Claude Code, sin cuota: sigue Codex» y «… se quedó sin cuota; sigue ya con el otro proveedor del agente»;
  - en **Ajustes → Ejecución**, el campo de los minutos.
- **API**:
  - `fallback` en el agente de la vista de la ejecución (`provider`, `window`, `resetsAt`);
  - `fallback` en los agentes de la definición;
  - `fallbackAfterMinutes` en `/api/settings/quota`.
- **Workflows iniciales**: cada agente de Claude Code lleva Codex de reserva y cada uno de Codex o de Antigravity lleva Claude Code. Las equivalencias están en `workflows/modelos.env`; Antigravity no es reserva de nadie.
- **Migración `V39`**: `agent_run.fallback_from`, `fallback_window` y `fallback_until`. Se aplica sola al arrancar.

Ninguna prueba de esta guía modifica ficheros versionados de skynet: lo que se crea va a `/tmp/cq-b`. Las secciones 1 a 3 no gastan cuota real. La 4 usa `guilu/demo-repo` y nunca skynet.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT' --tests '*DefinitionParserTest' \
  --tests '*SchemaConsistencyTest' --tests '*SeedWorkflowsTest' --tests '*ContractIT'
cd web && npx vitest run src/components/run/stageNotes.test.ts src/components/companion/notices.test.ts src/contracts.test.ts
scripts/e2e.sh quota-fallback.e2e.ts quota-wait.e2e.ts
```

- `WorkflowEngineIT`:
  - con dos horas de espera, sigue ya Codex con su modelo, en el mismo worktree, con la nota, y una ejecución nueva arranca ya con Codex;
  - con diez minutos de espera, espera a Claude Code;
  - con «cambiar» a 0 minutos, si Codex también se queda sin cuota, vuelve a Claude Code en cuanto lo recupera, en el mismo worktree.
- `DefinitionParserTest`: `fallback` con y sin modelo, `onQuota: fallback` sin reserva, la reserva del mismo proveedor, sin `provider`, Gemini y claves desconocidas.
- `SeedWorkflowsTest`: los seis workflows iniciales, con sus reservas, se publican con los modelos de `modelos.env` vacíos y rellenos.

La E2E `quota-fallback.e2e.ts` hace de punta a punta lo de la sección 2: fake-claude se queda sin cuota, fake-codex sigue y la fase dice «Sustituido por Codex». Corre en CI.

## 2. En local, con los CLIs falsos

Arranca como en la sección 2 de [`probar-cq-a.md`](probar-cq-a.md), con fake-codex también en `SKYNET_AGENT_ENV`:

```bash
SKYNET_QUOTA_MARGIN=0s SKYNET_QUOTA_JITTER=5s \
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
rm -rf /tmp/cq-b && mkdir -p /tmp/cq-b/codex
SKYNET_RUNNER_HOME=/tmp/cq-b/runner CODEX_HOME=/tmp/cq-b/codex \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_QUOTA \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 FAKE_CLAUDE_QUOTA=/tmp/cq-b/claude-sin-cuota \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

SKYNET_ADMIN_PASSWORD=secreto scripts/seed-workflows.sh --actualizar
cd web && npm run dev
```

`seed-workflows.sh --actualizar` publica las versiones nuevas de los workflows iniciales, con sus reservas.

### 2.1 Cambia a la reserva

1. **Ajustes → Ejecución**: sale «Cambiar a la reserva si la espera pasa de (minutos)» con 15.
2. En **Workflows → Implementar una tarea**, la versión nueva: el `desarrollador` tiene `fallback` con `provider: codex`.
3. `echo 1800 > /tmp/cq-b/claude-sin-cuota`: el próximo agente de Claude Code se queda sin cuota y la ventana se renueva en 30 minutos.
4. Lanza **Implementar una tarea** sobre un trabajo de `demo-repo`. Cuando llega al desarrollador:
   - el agente de Claude Code acaba «se quedó sin cuota»;
   - enseguida, sin pausa, arranca uno de **Codex** en el mismo worktree: entre sus ficheros cambiados sigue `fake-claude-parcial.txt`;
   - la fase dice «Sustituido por Codex (Claude Code sin cuota hasta las HH:MM)», con la hora de dentro de 30 minutos;
   - sale el aviso «Codex sustituye a Claude Code»;
   - en Actividad, «Sustituye a Claude Code, sin cuota: sigue Codex»;
   - en la pestaña Coste del agente nuevo, el coste es de Codex.
5. La ejecución termina como sin la cuota.
6. **Mientras Claude Code siga sin cuota** (30 minutos), lanza otra vez el mismo workflow: el desarrollador arranca directamente con Codex, sin un agente de Claude Code que falle antes.

### 2.2 Una espera corta no cambia

1. Espera a que pasen los 30 minutos (o reinicia la base de datos) y pon `echo 300 > /tmp/cq-b/claude-sin-cuota`: 5 minutos.
2. Lanza el workflow: la fase dice «Esperando la cuota de Claude Code · se renueva a las …», como en CQ-A, sin Codex. Pulsa **Reintentar ahora** para no esperar.
3. En **Ajustes → Ejecución** pon los minutos a `0` y **Guardar**. `echo 300 > /tmp/cq-b/claude-sin-cuota` y lánzalo otra vez: ahora cambia a Codex aunque solo falten 5 minutos. Vuelve a poner `15`.

### 2.3 El editor

En **Workflows → Nuevo**, prueba estos agentes y mira el mensaje de cada uno:

```yaml
id: cq-b-errores
agents:
  mismo:
    fallback: {provider: claude-code}   # error: tiene que ser otro proveedor
  sin-reserva:
    onQuota: fallback                   # error: necesita `fallback`
  inutil:
    fallback: {provider: codex}
    onQuota: wait                       # aviso: no usa nunca su reserva
stages:
  - id: a
    type: agent
    agent: mismo
    prompt: hola
```

## 3. La API

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
api http://localhost:8080/api/settings/quota | jq .fallbackAfterMinutes            # 15
api -X PUT -d '{"fallbackAfterMinutes": -1}' -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/settings/quota   # 400
api "http://localhost:8080/api/workflow-runs/<id de la ejecución de 2.1>" \
  | jq '[.stages[].agents[] | {provider, failure, fallback}]'
```

El último dice `claude-code` con `quota` y luego `codex` con `fallback.provider: claude-code`.

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa fake-claude y fake-codex, así que se prueba sin gastar cuota real. Hace falta lo mismo que en la sección 4 de [`probar-cq-a.md`](probar-cq-a.md): `FAKE_CLAUDE_QUOTA=/tmp/cq-b/claude-sin-cuota` en el entorno del runner, y `FAKE_CLAUDE_QUOTA` en `SKYNET_AGENT_ENV`.

1. Con el despliegue en la versión de la PR, publica los workflows iniciales nuevos: `scripts/seed-workflows.sh --actualizar`.
2. `mkdir -p /tmp/cq-b && echo 1200 > /tmp/cq-b/claude-sin-cuota` en la máquina del runner: 20 minutos, más que los 15 de «cambiar».
3. Lanza **Implementar una tarea** sobre `demo-repo`, desde una issue con su etiqueta si los disparadores están configurados, o desde la web.
4. Comprueba lo de la sección 2.1, pasos 4 y 5: el desarrollador lo sustituye fake-codex sin esperar y la ejecución termina en su PR en `demo-repo`.
5. Durante esos 20 minutos, las fases de Claude Code con reserva arrancan con Codex. Para no mezclarlo con otras pruebas, espera a que pasen.
6. **Deja GitHub como estaba**: cierra la PR sin fusionarla, borra su rama y, si la hubo, cierra la issue. Quita `FAKE_CLAUDE_QUOTA` del runner y reinícialo.

Con los CLIs de verdad no hace falta forzar nada: la próxima vez que la cuenta de Claude Code se quede sin su ventana para más de 15 minutos, el agente debe pasar a Codex. Para eso, Codex tiene que estar instalado y con sesión en el runner.

## 5. Al terminar

- `rm -rf /tmp/cq-b`.
- **Ajustes → Ejecución**: encendido, 6 h, 4 esperas y 15 minutos.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
