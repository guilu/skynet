# Probar W2-A en local

W2-A añade el **motor de workflows** (#59): una ejecución se lanza con una versión publicada de un workflow y sus datos, y el motor arranca cada fase cuando le toca, en paralelo si puede, continuando el worktree de la fase anterior, cancelando las demás si una falla y retomando el trabajo tras un reinicio. La web todavía no lanza workflows ni dibuja sus fases (llega en W2-B), así que el lanzamiento se hace con `curl` y el resto se ve en la web.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude. El retardo entre líneas deja tiempo para ver las fases en marcha:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=1500 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Hay una migración nueva, `V13__workflow_engine.sql`: `repository_id`, `inputs` y `launch_limits` en `workflow_run` y la tabla `workflow_job`. Flyway la aplica sola. No hay dependencias nuevas. El motor se configura con `SKYNET_ENGINE_WORKERS`, `SKYNET_ENGINE_POLL_INTERVAL`, `SKYNET_ENGINE_LEASE` y `SKYNET_ENGINE_RETRY_DELAY` (ver `docs/workflows.md`).

Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado y un trabajo. Para los `curl`:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

## 2. Un workflow con fases en paralelo

1. En **Workflows**, **Nuevo workflow**, pega esto, **Crear borrador** y **Publicar…**:

   ```yaml
   id: paralelo
   version: 1
   name: Planificar y probar en paralelo
   inputs:
     issue:
       type: string
       required: true
   agents:
     fixer:
       prompt: "Arregla {{inputs.issue}} de {{workItem.key}} ({{workItem.title}})"
       tools: [Read, Edit, WebFetch]
       permissionMode: plan
   stages:
     - id: plan
       type: agent
       prompt: "Planifica {{inputs.issue}}"
     - id: tests
       type: agent
       prompt: "Escribe tests para {{inputs.issue}}"
     - id: fix
       type: agent
       agent: fixer
       dependsOn: ["plan?", tests]
       workspaceFrom: tests
   ```

2. Copia los ids: el de la versión publicada sale en la URL al abrir la v1 (o con `api $B/workflow-definitions | jq '.[] | {key, version, id}'`), y los del trabajo y el repositorio en sus páginas.
3. Lánzalo:

   ```bash
   api -X POST $B/work-items/<trabajo>/runs \
     -d '{"repositoryId":"<repo>","definitionId":"<versión>","inputs":{"issue":"#42"}}' | jq '{id, status, stages: [.stages[] | {stageKey, status, agents: (.agents | length)}]}'
   ```

   Responde `RUNNING` con `plan` y `tests` en `READY` y un agente cada una, y `fix` en `PENDING` sin agente.
4. Abre la ejecución en la web: los dos agentes corren a la vez en el mismo runner. En **Actividad** salen «Fase fix en espera de plan?, tests», «Fase plan preparada (intento 1)» y las de tests.
5. Cuando terminan los dos, `fix` arranca sola. Su agente:
   - recibe el prompt «Arregla #42 de <clave> (<título>)»;
   - trabaja en el **mismo worktree y la misma rama** que `tests` (compáralos en la cabecera de cada agente);
   - en su evento «Agente claude-code en cola» lleva las herramientas `Read`, `Edit` (sin `WebFetch` si el repositorio no lo permite) y el modo `plan`.
6. Cuando termina `fix`, la ejecución pasa a **Correcta**.

## 3. Espera a la verificación

1. Configura un comando de verificación en el repositorio (pestaña de repositorios, p. ej. `sleep 20 && true`) y lanza otra vez el workflow del paso 2.
2. Al terminar `tests` sale su verificación automática. `fix` se queda en `READY` sin agente mientras dura y arranca en cuanto termina, en el mismo worktree.

## 4. Fail-fast y cancelar

1. Lanza el workflow otra vez y, con `plan` y `tests` en marcha, cancela el agente de `plan` desde su página. Al terminar su proceso, `plan` queda **Cancelada**, `fix` se cancela sin llegar a empezar, a `tests` se le ordena terminar y la ejecución acaba **Cancelada**.
2. Lanza otra y cancélala entera: `api -X POST $B/workflow-runs/<id>/cancel | jq .status`. Todos sus agentes terminan y la ejecución acaba **Cancelada**. Pedirlo otra vez responde 409.

## 5. Reinicio a mitad

1. Lanza el workflow y, con `plan` y `tests` en marcha, para el control plane (Ctrl+C) y espera a que acaben los agentes del runner.
2. Arranca el control plane otra vez. El runner le envía lo que quedó pendiente, el motor retoma la ejecución y `fix` arranca **una sola vez** (un único agente en su fase). `select count(*) from workflow_job` queda en 0 cuando la ejecución termina.

## 6. Datos que no cuadran

- Sin `issue`: 400 «Falta el dato obligatorio `issue`».
- Con un dato que no existe (`"inputs":{"issue":"#1","foco":"x"}`): 400 «El dato `foco` no es de este workflow; admite `issue`».
- Con `"prompt":"x"` en vez de `inputs`: 400 «El workflow paralelo no pide un prompt: rellena sus datos».
- Archiva el workflow en **Workflows** y lánzalo: 409 «está archivado: restáuralo para lanzarlo».
- Sin `definitionId`, «Lanzar agente» de la web sigue lanzando `adhoc` como siempre.

## 7. Pruebas automáticas

```bash
./gradlew build                       # incluye PlannerTest, EffectivePolicyTest, LaunchInputsTest y WorkflowEngineIT
cd web && npm run lint && npm run typecheck && npm test
scripts/e2e.sh                        # control plane, runner y Playwright
```
