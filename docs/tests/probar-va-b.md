# Probar VA-B - Juez con rúbrica

VA-B (#219) es la segunda PR del hito VA (validación independiente). Una fase `verification` puede llevar ahora un `judge`: uno o varios agentes de solo lectura que, si pasan las comprobaciones de la fase, puntúan el cambio de 1 a 5 con una rúbrica. Si suspende y la fase tiene `fix`, el agente de esa fase corrige en su sesión con lo que encontraron, y la fase vuelve a comprobar y a juzgar.

- **YAML**:
  - `judge` en `verification`, con `agents` (de 1 a 5, cada uno con su `lens` opcional), `rubric` o `rubricFile` y `threshold` (3,5 por defecto);
  - `fix` y `maxRounds` también en una `verification` con `judge`;
  - sin `rubric` ni `rubricFile`, la rúbrica de siempre: `resuelve` (peso 3, obligatorio), `tests` (2) y `alcance` (1);
  - `rubricFile` queda protegido en todas las fases de agente del workflow.
- **Avisos al publicar**:
  - un juez con el mismo proveedor y modelo que el autor;
  - la reserva de un juez (CQ-B) en el proveedor del autor;
  - varios jueces sin lente.

  **Errores al publicar**: un juez que no está en `agents`, el mismo juez dos veces, `rubric` junto con `rubricFile`, y un `fix` que no es una fase `agent` anterior.
- **Motor**:
  - los jueces juzgan a la vez, cada uno en una copia del worktree, con `permissionMode: plan` y sin memoria;
  - su prompt lleva la tarea, el commit desde el que mirar, las comprobaciones con sus avisos, la lente y la rúbrica, y **nada de lo que dijo el autor**;
  - la fase pasa si aprueba la mayoría y nadie encuentra nada que bloquee;
  - si suspende, la fase de `fix` corrige en su sesión (como `github-check`) como mucho `maxRounds` veces (2 por defecto).
- **Eventos**: `stage.judge.started`, `stage.judge.verdict`, `stage.judge.fix.started`, `stage.judge.round.started` y `stage.judge.failed`.
- **Condiciones y prompts**: `juzgar.score`, `juzgar.passed`, `juzgar.findings` y `juzgar.criteria.<id>.score`.
- **Web**:
  - la fase dice «Juzgando: 1 juez», «implementar corrige lo que suspendió el juez (ronda 1 de 2)» o su nota, «3,8 / 5 · aprobado por 2 de 2»;
  - al abrirla, el bloque **Juez** tiene la tabla de criterios por juez, los hallazgos con su fichero y el resumen de cada juez;
  - en el grafo, la caja de la fase dice cuántos jueces, el umbral y quién corrige.
- **PR (EN)**: la descripción lleva «Juez: 5 / 5 · aprobado por 1 de 1, en la ronda 2» y los hallazgos que queden.
- **Migración `V41`**:
  - `agent_run.role` (`judge`);
  - las tablas `stage_judge` (el estado del juez de cada fase) y `stage_verdict` (el veredicto de cada juez en cada ronda).

  Se aplica sola al arrancar.
- **fake-claude y fake-codex**: con `FAKE_CLAUDE_JUDGE` o `FAKE_CODEX_JUDGE` (la ruta de un fichero testigo), cuando se les pide la salida de un juez:
  - sin el testigo, aprueban con un 5 en cada criterio;
  - con él, suspenden una vez con un 2 en cada criterio y un hallazgo que bloquea, y lo borran. El testigo puede decir el hallazgo: `<fichero>[:<línea>] <texto>`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet: lo que se crea va a `/tmp/va-b`. Nada gasta cuota real. La sección 4 usa `guilu/demo-repo` y nunca skynet.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*JudgeVerdictsTest' --tests '*JudgePromptTest' \
  --tests '*DefinitionParserTest' --tests '*WorkflowEngineIT' --tests '*ContractIT' \
  --tests '*MigrationsIT' --tests '*PurgeIT' --tests '*WorkflowDefinitionsIT' \
  :tools:fake-claude:test
cd web && npx vitest run src/contracts.test.ts src/components/run src/components/graph
```

- `JudgeVerdictsTest`, con datos sintéticos:
  - un juez aprueba con la media ponderada por encima del umbral (23/6 = 3,83) y suspende por debajo;
  - un criterio obligatorio con menos de 3 suspende, sea cual sea la media;
  - un hallazgo `blocking` suspende y uno `minor` no;
  - un criterio sin puntuar cuenta como 1, y las notas fuera de 1-5 se recortan;
  - con varios jueces, decide la mayoría (un empate suspende) y un solo hallazgo que bloquea suspende el panel;
  - el informe para quien corrige lleva lo que hay que arreglar y los criterios con nota baja.
- `JudgePromptTest`: el prompt lleva la tarea, el `git diff <commit>`, las comprobaciones con sus avisos, la lente y la rúbrica, y no lleva nada de la salida del autor.
- `DefinitionParserTest`: los errores y avisos de la lista de arriba, y que `rubricFile` queda protegido.
- `WorkflowEngineIT`:
  - `aJudgeThatFailsSendsItsFindingsToTheFixStageAndJudgesAgain`: el juez suspende, `implement` corrige con el hallazgo en su prompt y la ronda 2 aprueba;
  - `aJudgeThatKeepsFailingFailsTheStageAfterItsRounds`: sin más rondas, la fase falla con «… tras 2 rondas de corrección».
- `fake-claude:test` (`FakeJudgeTest`): el testigo suspende una vez con el hallazgo que dice y luego aprueba; sin la salida de un juez no hace nada.
- `ContractIT` y `contracts.test.ts`: las fases llevan `judge` (en la definición y en la ejecución).
- La E2E `web/e2e/judge.e2e.ts` hace lo mismo que la sección 2 de punta a punta.

## 2. En local, con los CLIs falsos

```bash
rm -rf /tmp/va-b && mkdir -p /tmp/va-b/repo
cd /tmp/va-b/repo && git init -q -b main
printf 'def add(a, b):\n    return a + b\n' > calc.py
git add -A && git -c user.email=t@t -c user.name=t commit -qm base
cd -

SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/va-b/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CODEX_JUDGE \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 FAKE_CODEX_JUDGE=/tmp/va-b/juez-suspende \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

En la web, registra `/tmp/va-b/repo` en un proyecto y crea un trabajo en él.

### 2.1 Al publicar

En **Workflows → Nuevo**, pega esto y no lo publiques todavía:

```yaml
id: va-b-juez
name: Juez de VA-B
agents:
  autor:
    provider: codex
  juez:
    provider: codex
  otro:
    provider: claude-code
stages:
  - id: implementar
    type: agent
    agent: autor
    prompt: Arregla add() para que trate los negativos
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [juez, otro]
    fix: implementar
```

1. Salen dos avisos:
   - «El juez `juez` usa el mismo modelo que `implementar`: es menos independiente…» (los dos son Codex sin modelo);
   - «Hay 2 jueces sin lente…».
2. Cambia `agents: [juez, otro]` por `agents: [nadie]`: es un error, «No hay ningún agente `nadie` en `agents`». Con `agents: [juez, juez]`, «`juez` ya es juez de esta fase…».
3. Pon `fix: juzgar`: es un error (`fix` tiene que ser una fase `agent` anterior).
4. Deja el YAML así, sin avisos, y publícalo:

   ```yaml
   id: va-b-juez
   name: Juez de VA-B
   agents:
     juez:
       provider: codex
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
           - {id: tests, text: "Tiene un test"}
       fix: implementar
   ```

5. En el grafo, la caja de `juzgar` dice «1 juez», el umbral 3,5 y que corrige `implementar`.

### 2.2 Suspende, corrige y aprueba

1. `echo 'calc.py:2 add() no trata los negativos' > /tmp/va-b/juez-suspende` y lanza el workflow sobre el trabajo.
2. Mientras va, la fase `juzgar` pasa por «Juzgando: 1 juez», luego «implementar corrige lo que suspendió el juez (ronda 1 de 2)» y vuelve a juzgar.
3. La ejecución termina **bien**:
   - `juzgar` dice «5 / 5 · aprobado por 1 de 1»;
   - `implementar` tiene dos invocaciones: la primera y la corrección (`RESUME`);
   - `/tmp/va-b/juez-suspende` ya no existe.
4. En el **Registro** de la corrección de `implementar`, el prompt empieza por «Nota: 2 / 5 · suspenso: aprueba 0 de 1.», tiene «## Lo que hay que arreglar» con «- `calc.py:2`: add() no trata los negativos (juez)» y acaba con «Corrige lo que señala (ronda de corrección 1 de 2)…».
5. Abre la fase `juzgar`. En el bloque **Juez**:
   - la nota, «ronda 2» y «corrige implementar (hasta 2 rondas)»;
   - la tabla con `resuelve` y `tests`, cada uno con un 5 y su justificación;
   - el resumen del juez.
6. En **Actividad** salen, en orden:
   - «juzgar llama a 1 juez»;
   - «juzgar: 2 / 5 · suspenso, aprueba 0 de 1 · 1 bloquea»;
   - «implementar corrige lo que suspendió el juez (ronda 1 de 2)»;
   - «juzgar vuelve a comprobar y juzgar (ronda 2)»;
   - «juzgar llama a 1 juez (ronda 2)»;
   - «juzgar: 5 / 5 · aprobado, aprueba 1 de 1».
7. El agente juez trabajó en solo lectura: su `agent.spawned` lleva `permissionMode: plan`, y `git -C /tmp/va-b/repo status` sigue limpio.

### 2.3 Sin rondas

1. Publica una versión nueva con `maxRounds: 1` en `juzgar`.
2. `echo 'calc.py:2 sigue mal' > /tmp/va-b/juez-suspende` y lánzala. Antes de que acabe la corrección, vuelve a crear el testigo: `echo 'calc.py:2 sigue mal' > /tmp/va-b/juez-suspende`.
3. La fase `juzgar` falla con «Suspende el juez: 2 / 5 · suspenso: aprueba 0 de 1 tras 1 ronda de corrección», y en Actividad sale el `stage.judge.failed`.

Si la corrección acaba antes de crear el testigo otra vez, la ronda 2 aprueba: repítelo con `FAKE_CLAUDE_DELAY_MS=20000` en el runner (y en `SKYNET_AGENT_ENV`) para tener tiempo.

## 3. La API

```bash
api() { curl -s -u admin:secreto "$@"; }
RUN=<id-de-la-ejecución-de-2.2>
api http://localhost:8080/api/workflow-runs/$RUN \
  | jq '.stages[] | select(.stageKey == "juzgar") | .judge | {status, round, score, passed, approvals, judges}'
# {"status": "PASSED", "round": 2, "score": 5, "passed": true, "approvals": 1, "judges": 1}
api http://localhost:8080/api/workflow-runs/$RUN \
  | jq '.stages[] | select(.stageKey == "juzgar") | .judge.judgements[0].criteria | map({id, score})'
# [{"id": "resuelve", "score": 5}, {"id": "tests", "score": 5}]
```

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa fake-claude y fake-codex, así que no se gasta cuota. Añade al entorno del runner `FAKE_CODEX_JUDGE=/tmp/va-b/juez-suspende` (y `FAKE_CODEX_JUDGE` en `SKYNET_AGENT_ENV`) y reinícialo.

1. Con el despliegue en la versión de la PR, publica este workflow, una versión corta de «Implementar una tarea» con juez y entrega por PR, sin CI ni fusión:

   ```yaml
   id: va-b-demo
   name: Implementar con juez (VA-B)
   agents:
     desarrollador:
       permissionMode: acceptEdits
     juez:
       provider: codex
       permissionMode: plan
   stages:
     - id: implementar
       type: agent
       agent: desarrollador
       prompt: |
         Implementa {{workItem.key}}: {{workItem.title}}.

         {{workItem.description}}
       checks: [tests, diff]
     - id: juzgar
       type: verification
       dependsOn: [implementar]
       judge:
         agents: [{agent: juez, lens: correctness}]
       fix: implementar
     - id: pr
       type: github-pr
       dependsOn: [juzgar]
       condition: repository.github
   ```

   Al publicar no sale ningún aviso: el autor es Claude Code y el juez, Codex.
2. En la máquina del runner, `mkdir -p /tmp/va-b && echo 'README.md:1 falta explicar el cambio' > /tmp/va-b/juez-suspende`.
3. Lanza **Implementar con juez (VA-B)** sobre un trabajo de `demo-repo`.
4. Comprueba:
   - `juzgar` suspende en la ronda 1 con «2 / 5 · suspenso: aprueba 0 de 1» y el hallazgo `README.md:1`;
   - el prompt de la corrección de `implementar` lleva ese hallazgo y «ronda de corrección 1 de 2»;
   - la ronda 2 vuelve a pasar `tests` y `diff` de `implementar` y aprueba con «5 / 5 · aprobado por 1 de 1»;
   - la rúbrica es la de siempre: `resuelve`, `tests` y `alcance`;
   - la PR que abre `pr` en `demo-repo` lleva en su descripción, en la evidencia de `juzgar`, «Juez: 5 / 5 · aprobado por 1 de 1, en la ronda 2».
5. **Deja todo como estaba**:
   - cierra la PR de `demo-repo` sin fusionarla y borra su rama `skynet/<clave>`;
   - quita `FAKE_CODEX_JUDGE` del runner y reinícialo;
   - archiva el workflow `va-b-demo`.

## 5. Al terminar

- `rm -rf /tmp/va-b`.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
