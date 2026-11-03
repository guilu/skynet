# Probar VA-D - La guía de pruebas como fase

VA-D (#221) es la cuarta y última PR del hito VA (validación independiente). Hasta ahora, la guía de pruebas de un cambio (`docs/tests/probar-*.md`) la ejecutaba alguien a mano después. Con VA-D, una fase `verification` puede llevar una `guide`: un agente ejecuta, en una copia del worktree, la guía que trae el cambio, paso a paso, con la evidencia de cada uno. Si falla un paso, la fase de `fix` lo corrige y la guía se vuelve a ejecutar entera.

- **YAML**: el bloque `guide` en una fase `verification`, con `fix` y `maxRounds` de la fase:
  - `path`, obligatorio: un glob o una lista de globs;
  - `agent`, obligatorio;
  - `skip`: las secciones que no se ejecutan, por cómo empieza su título;
  - `ifMissing`: `fail` por defecto, o `skip`.

  Validación:
  - es un error que falte `path` o `agent`, un `agent` que no está en `agents`, un `ifMissing` desconocido, o `judge` y `guide` en la misma fase;
  - es un aviso un agente con `permissionMode: plan`, porque no podrá ejecutar los pasos.
- **Motor**: se lleva como un juez de un solo agente, con las mismas rondas.
  - Quien ejecuta la guía trabaja en una copia del worktree, sin memoria y con el `permissionMode` del agente (no se fuerza `plan`).
  - Ejecuta las guías que cumplen los globs y que cambia el diff.
  - Cada paso queda como `passed`, `failed`, `blocked` o `skipped`.
  - La fase pasa si no falla ningún paso; un `blocked` solo avisa. Sin guía en el cambio, falla con «No hay guía de pruebas en el cambio», salvo con `ifMissing: skip`.
  - A `fix` le llegan los pasos con su evidencia y «No cambies la guía para que pase: corrige el código». Es solo una indicación: la guía no queda protegida.
- **Eventos**: `stage.guide.started` y `stage.guide.verdict`. Las rondas usan los del juez (`stage.judge.fix.started`, `stage.judge.round.started`, `stage.judge.failed`).
- **Migración `V43`**: `agent_run.role` admite `guide`, y `stage_verdict.guide` guarda el recorrido de cada ronda. Se aplica sola al arrancar.
- **Web**:
  - la fase dice «Ejecutando la guía de pruebas», «implementar corrige lo que falla de la guía (ronda 1 de 2)» o su nota, p. ej. «2 de 2 pasos pasan · 1 saltado»;
  - al abrirla, el bloque **Guía de pruebas** tiene las guías ejecutadas y los pasos por sección, con su estado (Pasa, Falla, No se pudo, Saltado) y la evidencia plegada;
  - en el grafo, «Guía de pruebas · probador».
- **PR (EN)**:
  - en la tabla de evidencias, «Guía de pruebas: 2 de 2 pasos pasan · 1 saltado, en la ronda 2»;
  - debajo de la tabla, un bloque plegado con los pasos y su evidencia.
- **CLIs falsos**: `FAKE_CLAUDE_GUIDE`, `FAKE_CODEX_GUIDE` y `FAKE_AGY_GUIDE`, un fichero testigo. Responden cuando se les pide la salida de una guía:
  - buscan las guías del prompt que cambian desde el commit base (también las nuevas sin seguimiento);
  - cada elemento de lista de primer nivel bajo un `##` es un paso;
  - sin el testigo, pasan todos los pasos;
  - con el testigo, falla el primer paso que contiene su texto (vacío, el primero) y el testigo se borra.

Ninguna prueba de esta guía modifica ficheros versionados de skynet: lo que se crea va a `/tmp/va-d`. Nada gasta cuota real. La sección 4 usa `guilu/demo-repo` y nunca skynet.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*GuideStepsTest' --tests '*DefinitionParserTest' \
  --tests '*PullRequestBodyTest' --tests '*WorkflowEngineIT' --tests '*ContractIT' \
  --tests '*MigrationsIT' \
  :tools:fake-claude:test
cd web && npx vitest run src/components/run src/format.test.ts src/components/graph
```

- `GuideStepsTest`, con datos sintéticos:
  - un paso que falla bloquea, uno `blocked` solo avisa, y un estado raro cuenta como fallido;
  - la nota, p. ej. «1 de 4 pasos pasan · 1 no se pudo · 1 saltado»;
  - sin guía, falla salvo con `ifMissing: skip`;
  - el prompt (globs, `git diff --name-only <base>`, secciones saltadas, ronda) y el Markdown de los pasos.
- `DefinitionParserTest`: `guide` con `fix` y `maxRounds`, sus errores y el aviso de `plan`.
- `PullRequestBodyTest`: el bloque plegado debajo de la tabla de evidencias.
- `WorkflowEngineIT`:
  - `aGuideThatFailsAStepSendsItToTheFixStageAndRunsAgain`: en la ronda 1 falla un paso. La corrección recibe el paso con su evidencia y «No cambies la guía…», y la ronda 2 pasa. Quien ejecuta la guía va en una copia y no en `plan`;
  - `withoutAGuideInTheChangeTheStageFails`: sin guía, la fase falla tras sus rondas con «No hay guía de pruebas en el cambio».
- `FakeGuideTest`: la guía guionizada, que solo ejecuta las guías que cambian, salta secciones y falla una vez.
- La E2E `web/e2e/guide.e2e.ts` hace la sección 2.2 de punta a punta.

## 2. En local, con los CLIs falsos

```bash
rm -rf /tmp/va-d && mkdir -p /tmp/va-d/repo
cd /tmp/va-d/repo && git init -q -b main
printf 'def add(a, b):\n    return a + b\n' > calc.py
git add -A && git -c user.email=t@t -c user.name=t commit -qm base
cd -

SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/va-d/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CODEX_GUIDE \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
FAKE_CODEX_GUIDE=/tmp/va-d/guia-falla \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

En la web, registra `/tmp/va-d/repo` en un proyecto y crea un trabajo en él.

### 2.1 Al publicar

En **Workflows → Nuevo**, publica (la fase `command` hace de autor de la guía, porque el Claude falso solo toca `calc.py`):

```yaml
id: va-d-guia
name: Guía de VA-D
agents:
  probador:
    provider: codex
    permissionMode: dontAsk
stages:
  - id: implementar
    type: agent
    prompt: Arregla add() para que trate los negativos
  - id: escribir-guia
    type: command
    dependsOn: [implementar]
    command: "mkdir -p docs/tests && printf '# Probar\n\n## 1. Tests\n\n1. Pasan los tests\n2. add() suma los negativos\n\n## 4. En GitHub\n\n1. La PR sale en verde\n' > docs/tests/probar-suma.md"
  - id: guia
    type: verification
    dependsOn: [escribir-guia]
    guide:
      path: "docs/tests/probar-*.md"
      agent: probador
      skip: ["## 4."]
    fix: implementar
```

1. Al publicar no hay avisos. En el grafo, la caja de `guia` dice «Guía de pruebas · probador · corrige implementar».
2. En un borrador nuevo, prueba:
   - sin `path`: error «A la guía le falta `path`…»;
   - con `ifMissing: quizas`: error «`ifMissing: quizas` no se conoce. Admite: fail, skip»;
   - con `judge` y `guide` en la misma fase: error «Una fase `verification` lleva `judge` o `guide`, no los dos…»;
   - con `permissionMode: plan` en `probador`: aviso «… no podrá ejecutar los pasos de la guía…».

### 2.2 Un paso que falla manda corregir

1. `echo negativos > /tmp/va-d/guia-falla`.
2. Lanza el workflow sobre el trabajo.
3. Mientras va, `guia` pasa por:
   - «Ejecutando la guía de pruebas»;
   - «implementar corrige lo que falla de la guía (ronda 1 de 2)»;
   - «Ejecutando la guía de pruebas (ronda 2)».
4. En el **Registro** de la corrección de `implementar`, el prompt tiene:
   - «La guía de pruebas de la fase `guia` no pasa.»;
   - «- ✗ add() suma los negativos»;
   - «No cambies la guía para que pase: corrige el código».
5. La ejecución termina **bien** en la ronda 2:
   - `guia` dice «2 de 2 pasos pasan · 1 saltado»;
   - `implementar` tiene dos invocaciones (START y RESUME).
6. Abre `guia`. En el bloque **Guía de pruebas**:
   - sale `docs/tests/probar-suma.md` y «ronda 2»;
   - bajo «1. Tests», los dos pasos con «Pasa»;
   - bajo «4. En GitHub», «Saltado»;
   - al desplegar **Evidencia**, «Sale lo que dice la guia (fake).».
7. En **Actividad**, en orden:
   - «guia: probador ejecuta la guía de pruebas»;
   - «guia: no pasa la guía · 1 de 2 pasos pasan · 1 saltado»;
   - «implementar corrige lo que falla de la guía (ronda 1 de 2)»;
   - «guia vuelve a ejecutar la guía (ronda 2)»;
   - «guia: probador ejecuta la guía de pruebas (ronda 2)»;
   - «guia: pasa la guía · 2 de 2 pasos pasan · 1 saltado».

### 2.3 Sin guía en el cambio

1. Publica una versión nueva de `va-d-guia` sin la fase `escribir-guia`, con `guia` en `dependsOn: [implementar]` y `maxRounds: 1`.
2. Lánzalo. `guia` falla con «No pasa la guía de pruebas: No hay guía de pruebas en el cambio tras 1 ronda de corrección».
3. Con `ifMissing: skip` en `guide`, la misma ejecución termina bien y la fase dice «Sin guía en el cambio: se omite».

## 3. La API

```bash
api() { curl -s -u admin:secreto "$@"; }
RUN=<id-de-la-ejecución-de-2.2>
api http://localhost:8080/api/workflow-runs/$RUN \
  | jq '.stages[] | select(.stageKey == "guia") | .judge | {status, round, note: .guide.note, guides: .guide.guides, steps: [.guide.steps[] | .status]}'
# {"status": "PASSED", "round": 2, "note": "2 de 2 pasos pasan · 1 saltado",
#  "guides": ["docs/tests/probar-suma.md"], "steps": ["passed", "passed", "skipped"]}
```

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa los CLIs falsos, así que no se gasta cuota. Añade al entorno del runner `FAKE_CODEX_GUIDE=/tmp/va-d/guia-falla` (también en `SKYNET_AGENT_ENV`) y reinícialo.

1. Con el despliegue en la versión de la PR, publica `va-d-demo`: el YAML de 2.1 con `id: va-d-demo` y dos fases más al final:

   ```yaml
     - id: pr
       type: github-pr
       dependsOn: [guia]
       pullRequest:
         title: "{{workItem.key}}: prueba de VA-D"
         draft: true
   ```

2. En la máquina del runner: `mkdir -p /tmp/va-d && echo negativos > /tmp/va-d/guia-falla`.
3. Lanza **va-d-demo** sobre un trabajo de `demo-repo`.
4. Comprueba:
   - `guia` falla un paso en la ronda 1, `implementar` corrige y la ronda 2 pasa, como en 2.2;
   - la PR (borrador) que abre `pr` en `demo-repo` lleva en la tabla de evidencias, en la fila de `guia`, «Guía de pruebas: 2 de 2 pasos pasan · 1 saltado, en la ronda 2»;
   - debajo de la tabla hay un bloque plegado `guia`. Al abrirlo salen «## 1. Tests», «- ✓ add() suma los negativos» con su evidencia, y «- – La PR sale en verde».
5. **Deja todo como estaba**:
   - cierra la PR de `demo-repo` sin fusionarla y borra su rama `skynet/<clave>`;
   - archiva el workflow `va-d-demo`;
   - quita `FAKE_CODEX_GUIDE` del runner y reinícialo;
   - `rm -rf /tmp/va-d` en esa máquina.

## 5. Al terminar

- `rm -rf /tmp/va-d`.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
