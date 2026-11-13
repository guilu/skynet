# Probar EV-C - Repetir un caso con otro juez (A/B)

EV-C (#292) es la tercera y última PR del hito EV (Banco de evaluaciones del juez). Lo explica [`docs/workflows.md`](../workflows.md), en «Banco de evaluaciones (EV-C)».

**Fábrica → Evaluaciones** guarda cambios ya juzgados para volver a juzgarlos con otro juez o con otro modelo:

- **Un caso** guarda el repositorio, `base_sha` y `head_sha`, la tarea y la fase de juez de la ejecución. Lo esperado es `debe-suspender` para un escape, que entra solo, y `debe-aprobar` para un control, que se añade a mano.
- **Repetir con…** lanza uno o dos jueces (A/B) en una **ejecución de evaluación** propia:
  - cada juez trabaja en solo lectura, sin memoria y sin escépticos;
  - lo hace en un worktree nuevo desde `head_sha`, con `git diff base_sha`;
  - se guarda su nota, si aprueba, sus hallazgos y si acertó;
  - la ejecución no sale en las listas ni en las métricas de los trabajos.
- **Runner:** crea el worktree desde un commit y, si el repositorio no lo tiene, lo trae con `git fetch origin <sha>`.
- **Migración V56:** `eval_case`, `eval_run`, `workflow_run.purpose` y el rol `eval` en `agent_run`. Se aplica sola al arrancar.
- **API:**
  - `GET /api/factory/evals?repositoryId=`;
  - `GET` y `POST /api/workflow-runs/{id}/eval-case`;
  - `GET` y `DELETE /api/eval-cases/{id}`;
  - `GET /api/eval-cases/{id}/judges`;
  - `POST /api/eval-cases/{id}/runs`;
  - `POST /api/factory/evals/runs`.
- **Eventos:** `eval.case.created`, `eval.case.removed`, `eval.started` y `eval.judged`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Todo lo que se crea va a `/tmp/ev-c`. Los agentes son fake-claude y fake-codex, así que no se gasta cuota.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT' --tests '*MergeIT' \
  :runner:test --tests '*WorkspaceManagerTest'
cd web && npx vitest run src/EvalsPage.test.tsx src/components/run/PullRequestCard.test.tsx
```

- `WorkflowEngineIT.aJudgedChangeIsRepeatedWithTwoJudgesInAnEvalRunOfItsOwn`:
  - una ejecución juzgada entra como control y guarda la fase `juzgar`, la base `abc1234` y el commit juzgado;
  - otra vez da 409, y un `expected` desconocido da 400;
  - el A/B lanza dos jueces con el mismo `pairId`, en una ejecución nueva. Cada uno trabaja:
    - en un worktree nuevo con `baseBranch` = el commit juzgado;
    - en `plan`;
    - con `git diff abc1234` en el prompt.
  - El que aprueba acierta y el que suspende falla. El acierto sale 100 % y 0 %.
  - La ejecución de evaluación termina en `SUCCEEDED` y no sale en las ejecuciones del trabajo.
  - Al quitar el caso, el banco queda vacío.
  - Un escape crea solo un caso `debe-suspender`, con el `head_sha` de la PR.
- `WorkspaceManagerTest.createsAWorktreeFromACommitAndFetchesItWhenMissing`:
  - un commit que solo está en otra rama del remoto se trae y el worktree lo tiene;
  - un commit que no existe falla con «no tiene el commit …».
- `EvalsPage.test.tsx`:
  - muestra la tabla **Acierto en el banco** («1 de 1 (100 %)») y el caso con «Debe suspender» y «escape»;
  - el A/B sale junto, con «Aprueba · Falló · nota 4,8» y «Suspende · Acertó» con su hallazgo;
  - **Repetir con…** dice «Será 1 invocación…» y, con A/B, «Serán 2…», y manda los dos jueces.
- `PullRequestCard.test.tsx`: **Añadir al banco como control** manda `debe-aprobar`.

## 2. Preparar

Como en [`probar-va-b.md`](probar-va-b.md) §2: un repositorio de prueba, el control plane, un runner con fake-claude y fake-codex, y la web.

```bash
mkdir -p /tmp/ev-c/repo && cd /tmp/ev-c/repo && git init -q -b main
printf 'def add(a, b):\n    return a + b\n' > calc.py
git add -A && git -c user.email=t@t -c user.name=t commit -qm base
cd -

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/ev-c/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CODEX_JUDGE \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 FAKE_CODEX_JUDGE=/tmp/ev-c/juez-suspende \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

En la web, registra `/tmp/ev-c/repo` en un proyecto, crea un trabajo y publica este workflow:

```yaml
id: ev-c-juez
name: Juez de EV-C
agents:
  juez:
    provider: codex
  otro:
    provider: claude-code
stages:
  - id: implementar
    type: agent
    prompt: Arregla add() para que trate los negativos
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [{agent: juez, lens: correctness}]
      rubric:
        - {id: resuelve, text: "Arregla add()", weight: 2, required: true}
```

Lánzalo sobre el trabajo, sin el testigo `/tmp/ev-c/juez-suspende`, y espera a que termine. El juez aprueba con un 5. Guarda el id de la ejecución en `RUN`.

## 3. El banco

```bash
B=http://localhost:8080; AUTH="admin:$SKYNET_ADMIN_PASSWORD"
```

### 3.1 Añadir un caso

1. Añade la ejecución como control:

   ```bash
   curl -s -u "$AUTH" -H 'Content-Type: application/json' \
     -d '{"expected":"debe-aprobar","note":"Control"}' $B/api/workflow-runs/$RUN/eval-case \
     | jq '{id, stageKey, baseSha, headSha, expected}'
   ```

   Debe salir lo siguiente:
   - la fase es `juzgar`;
   - hay dos SHA;
   - lo esperado es `debe-aprobar`.

   Guarda el `id` en `CASE`.
2. Repetir la misma llamada da 409.
3. Abre **Fábrica → Evaluaciones** y comprueba el caso:
   - lleva la clave del trabajo con el enlace a su ejecución;
   - lleva la pastilla verde «Debe aprobar»;
   - dice «fase `juzgar` · `xxxxxxx` contra `yyyyyyy` · Control»;
   - dice «Aún no se ha repetido».

### 3.2 A/B

1. Crea el testigo: `touch /tmp/ev-c/juez-suspende`. El próximo juez de Codex suspenderá una vez.
2. Pulsa **Repetir con…** en el caso:
   - **Juez A** es `juez (juez de la fase)`;
   - el texto dice «Será 1 invocación real del modelo».
3. Marca **Comparar con otro juez (A/B)**, elige `otro` como **Juez B** y comprueba que el texto dice «Serán 2 invocaciones reales del modelo». Pulsa **Lanzar**.
4. Salen las dos repeticiones juntas, primero «En cola» o «Juzgando» y, al rato, con su resultado:
   - `juez · codex` dice «Suspende · Falló», con su hallazgo y su nota;
   - `otro · claude-code` dice «Aprueba · Acertó».
5. Arriba, **Acierto en el banco** tiene `otro` con «1 de 1 (100 %)» y `juez` con «0 de 1 (0 %)».
6. El enlace de la fecha lleva a la ejecución de evaluación:
   - tiene una sola fase, `juzgar`, con los dos agentes;
   - está **Completada**;
   - en **Actividad** salen «eval.started» y dos «eval.judged».
7. En la página del trabajo y en **En curso**, la ejecución de evaluación no sale; la original, sí.
8. En el runner, `git -C /tmp/ev-c/runner/... worktree list` (o mirando `/tmp/ev-c/runner/workspaces/<ejecución de evaluación>/`) tiene dos worktrees nuevos en el commit juzgado.

### 3.3 Un escape entra solo

1. Haz que la ejecución tenga una PR fusionada con el `INSERT` de [`probar-ev-a.md`](probar-ev-a.md) §2, con `RUN`.
2. Quita el caso: `curl -s -o /dev/null -w '%{http_code}\n' -u "$AUTH" -X DELETE $B/api/eval-cases/$CASE` da 204.
3. En la tarjeta de la PR, pulsa **Marcar como escape** y luego **Guardar**. En **Evaluaciones** sale el caso con «Debe suspender» y «escape», y en la tarjeta de la PR, «En el banco de evaluaciones: debe suspender».

### 3.4 El banco entero

Elige el repositorio en **Repositorio** (solo sale con más de uno) y usa **Repetir el banco de …**. El texto dice «Serán N invocaciones», con N = casos × jueces. No hace falta lanzarlo.

## 4. Al terminar

```bash
rm -rf /tmp/ev-c
```

Archiva el proyecto de prueba desde la web. En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
