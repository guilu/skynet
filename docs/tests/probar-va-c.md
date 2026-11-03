# Probar VA-C - Verificación adversarial y workflows iniciales

VA-C (#220) es la tercera PR del hito VA (validación independiente). Hasta ahora, un falso positivo de un juez bastaba para mandar corregir. Con VA-C, cada hallazgo que bloquea puede pasar antes por varios escépticos de solo lectura que intentan refutarlo. Si la mayoría lo refuta, se descarta: sale tachado con el motivo y no se corrige.

- **YAML**: `refute` (de 0 a 5, escépticos por hallazgo que bloquea) y `refuteAgent` (por defecto, el agente del primer juez) en `judge`.
  - Es un error un `refuteAgent` que no está en `agents`.
  - Es un aviso un `refuteAgent` sin `refute`.
- **Motor**:
  - cuando terminan los jueces, si hay hallazgos que bloquean, lanza `refute` escépticos por cada uno, en copias del worktree, con `permissionMode: plan` y sin memoria;
  - el escéptico solo ve la tarea, el hallazgo y de dónde mirar el cambio;
  - un hallazgo se descarta si lo refuta más de la mitad de sus escépticos (un empate no lo descarta, y un escéptico que falla no lo refuta);
  - cada juez y la fase vuelven a decidir sin los descartados, y a `fix` solo le llega lo que sobrevive.
- **Topes**:
  - como mucho 8 hallazgos por ronda y 20 llamadas a agentes por fase (jueces y escépticos);
  - si no caben todos, los demás cuentan sin refutar y la fase lo dice («Se han intentado refutar 8 de 11…»).
- **Eventos**: `stage.judge.refute.started` y `stage.judge.refuted`. `stage.judge.verdict` lleva `refuted`.
- **Migración `V42`**:
  - `agent_run.role` admite `skeptic`, y `stage_judge.status` admite `REFUTING`;
  - `stage_judge.refute_note` guarda el aviso de recorte;
  - la tabla `stage_refutation` guarda lo que dijo cada escéptico de cada hallazgo.

  Se aplica sola al arrancar.
- **Web**:
  - la fase dice «Refutando 2 hallazgos que bloquean»;
  - en el bloque **Juez**, «2 escépticos por hallazgo que bloquea», el aviso de recorte con ⚠, y los hallazgos refutados **tachados** con «Refutado: …»; los que no, con «No se pudo refutar: …»;
  - en el grafo, «refuta ×2».
- **PR (EN)**: los hallazgos refutados van tachados con el motivo, y el aviso de recorte detrás.
- **Workflows iniciales**:
  - «Implementar una tarea», «Corregir un bug» y «Especificar, planificar e implementar» cambian su revisión (y su fase de corregir) por una fase `juzgar`;
  - en esa fase juzgan otros proveedores que Claude Code, con `refute: 2` y `fix` en la fase que implementa;
  - las reservas de los jueces dejan de ser Claude Code;
  - «Revisar código» no cambia.
- **CLIs falsos**:
  - `FAKE_CLAUDE_REFUTE`, `FAKE_CODEX_REFUTE` y `FAKE_AGY_REFUTE` (fichero testigo): con la salida de un escéptico, refutan si el testigo existe y su texto está en el prompt (vacío, cualquiera). No lo borran;
  - `FAKE_AGY_JUDGE`, como los de VA-B;
  - en el testigo del juez, un `!` delante deja las notas en 5 y solo bloquea el hallazgo;
  - el hallazgo `calc.py:2 …` ahora se lee con fichero y línea aunque la ruta no tenga `/`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet: lo que se crea va a `/tmp/va-c`. Nada gasta cuota real. La sección 4 usa `guilu/demo-repo` y nunca skynet.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*JudgeVerdictsTest' --tests '*JudgePromptTest' \
  --tests '*DefinitionParserTest' --tests '*SeedWorkflowsTest' --tests '*WorkflowEngineIT' \
  --tests '*ContractIT' --tests '*MigrationsIT' --tests '*PurgeIT' \
  :tools:fake-claude:test
cd web && npx vitest run src/components/run src/format.test.ts src/components/graph
```

- `JudgeVerdictsTest`, con datos sintéticos:
  - dos de dos escépticos refutan y el juez aprueba, sin el hallazgo en el informe para corregir;
  - un empate o un escéptico que falla no refutan;
  - con 11 hallazgos se refutan 8, y con presupuesto para 5 llamadas y 2 escépticos, 2;
  - el aviso de recorte de cada caso.
- `JudgePromptTest`: el escéptico ve la tarea, el hallazgo con su fichero y el `git diff`, y no la rúbrica ni las comprobaciones.
- `DefinitionParserTest`: `refute` y `refuteAgent`, sus errores y su aviso. `SeedWorkflowsTest`: los workflows iniciales se publican sin avisos.
- `WorkflowEngineIT`:
  - `skepticsDiscardTheFindingsTheyRefuteBeforeAskingForAFix`: de dos hallazgos, uno se refuta (2 de 2) y otro no (empate). A `implement` solo le llega el segundo, y la vista enseña el primero refutado con su motivo;
  - `whenSkepticsRefuteEveryBlockingFindingTheJudgePassesWithoutAFix`: la fase pasa en la ronda 1 y `implement` no vuelve a trabajar.
- `FakeJudgeTest`: el escéptico guionizado, y el `!` del juez.
- La E2E `web/e2e/judge.e2e.ts` («los escépticos refutan el hallazgo…») hace la sección 2.2 de punta a punta.

## 2. En local, con los CLIs falsos

```bash
rm -rf /tmp/va-c && mkdir -p /tmp/va-c/repo
cd /tmp/va-c/repo && git init -q -b main
printf 'def add(a, b):\n    return a + b\n' > calc.py
git add -A && git -c user.email=t@t -c user.name=t commit -qm base
cd -

SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/va-c/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CODEX_JUDGE,FAKE_CODEX_REFUTE \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
FAKE_CODEX_JUDGE=/tmp/va-c/juez-suspende FAKE_CODEX_REFUTE=/tmp/va-c/esceptico-refuta \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

En la web, registra `/tmp/va-c/repo` en un proyecto y crea un trabajo en él.

### 2.1 Al publicar

En **Workflows → Nuevo**, publica:

```yaml
id: va-c-escepticos
name: Escépticos de VA-C
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
      refute: 2
    fix: implementar
```

1. En el grafo, la caja de `juzgar` dice «1 juez · umbral 3,5 · refuta ×2 · corrige implementar».
2. En un borrador nuevo, pon `refuteAgent: nadie`: es un error, «No hay ningún agente `nadie` en `agents`». Con `refuteAgent: juez` y sin `refute`, sale el aviso «`refuteAgent` no se usa sin `refute`…». Con `refute: 9`, «… debe estar entre 0 y 5».

### 2.2 Un falso positivo no manda corregir

1. Prepara los testigos:

   ```bash
   echo '!calc.py:2 add() no trata los negativos' > /tmp/va-c/juez-suspende
   echo 'no trata los negativos' > /tmp/va-c/esceptico-refuta
   ```

2. Lanza el workflow sobre el trabajo.
3. Mientras va, la fase `juzgar` pasa por «Juzgando: 1 juez» y «Refutando 1 hallazgo que bloquea».
4. La ejecución termina **bien** en la ronda 1:
   - `juzgar` dice «5 / 5 · aprobado por 1 de 1»;
   - `implementar` tiene **una sola** invocación;
   - en la fase hay tres agentes: el juez y dos escépticos (`juez`).
5. Abre `juzgar`. En el bloque **Juez**:
   - «2 escépticos por hallazgo que bloquea»;
   - el hallazgo `calc.py:2` sale **tachado**, con «Refutado» y «Refutado: Ya se comprueba antes (fake).».
6. En el **Registro** de un escéptico, el prompt empieza por «Eres un escéptico…» y tiene «En `calc.py:2`: add() no trata los negativos». No sale la rúbrica.
7. En **Actividad**, en orden:
   - «juzgar llama a 1 juez»;
   - «juzgar: 2 escépticos por hallazgo intentan refutar 1 hallazgo»;
   - «juzgar: refutados 1 de 1 hallazgos»;
   - «juzgar: 5 / 5 · aprobado, aprueba 1 de 1».
8. `rm /tmp/va-c/esceptico-refuta`.

### 2.3 Un hallazgo real sí manda corregir

1. `echo '!calc.py:2 add() no trata los negativos' > /tmp/va-c/juez-suspende`. Sin el testigo de los escépticos, no refutan.
2. Lanza el workflow otra vez.
3. `juzgar` pasa por «Refutando…», suspende con «5 / 5 · suspenso: aprueba 0 de 1» y `implementar` corrige («implementar corrige lo que suspendió el juez (ronda 1 de 2)»). El prompt de la corrección tiene «- `calc.py:2`: add() no trata los negativos (juez)».
4. En el bloque **Juez** de la ronda 1, el hallazgo **no** está tachado y dice «No se pudo refutar: El problema es real: no lo he podido refutar (fake).».
5. La ronda 2 aprueba (el testigo del juez ya no existe).

## 3. La API

```bash
api() { curl -s -u admin:secreto "$@"; }
RUN=<id-de-la-ejecución-de-2.2>
api http://localhost:8080/api/workflow-runs/$RUN \
  | jq '.stages[] | select(.stageKey == "juzgar") | .judge | {status, round, refute, refuteNote, findings: [.findings[] | {text, refuted, refutation}]}'
# {"status": "PASSED", "round": 1, "refute": 2, "refuteNote": null,
#  "findings": [{"text": "add() no trata los negativos", "refuted": true, "refutation": "Ya se comprueba antes (fake)."}]}
```

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa los CLIs falsos, así que no se gasta cuota. En «Implementar una tarea» juzgan ahora Codex (`calidad`) y Antigravity (`seguridad`). Añade al entorno del runner:

- `FAKE_CODEX_JUDGE=/tmp/va-c/juez-suspende` y `FAKE_CODEX_REFUTE=/tmp/va-c/esceptico-refuta`;
- `FAKE_AGY_JUDGE=/tmp/va-c/agy-juez`, un fichero que no existe: así Antigravity aprueba;
- las tres en `SKYNET_AGENT_ENV`.

Luego reinicia el runner.

1. Con el despliegue en la versión de la PR, publica los workflows iniciales nuevos: `scripts/seed-workflows.sh --actualizar`. Dice que actualiza `implementar-tarea`, `corregir-bug` y `especificar-planificar-implementar`. Abre «Implementar una tarea»:
   - el grafo ya no tiene `revisar` ni `corregir`;
   - tiene `juzgar`, con «2 jueces · umbral 3,5 · refuta ×2 · corrige implementar»;
   - al publicar no hubo avisos.
2. En la máquina del runner:

   ```bash
   mkdir -p /tmp/va-c
   echo '!README.md:1 falta explicar el cambio' > /tmp/va-c/juez-suspende
   echo 'falta explicar el cambio' > /tmp/va-c/esceptico-refuta
   ```

3. Lanza **Implementar una tarea** sobre un trabajo de `demo-repo` (desde la web o con la etiqueta `skynet:implementar-tarea` en una issue).
4. Comprueba:
   - `juzgar` llama a los dos jueces a la vez y luego a dos escépticos de Codex para el hallazgo de `calidad`;
   - el hallazgo sale tachado y la fase aprueba en la ronda 1, sin que `implementar` vuelva a trabajar;
   - la PR que abre `pr` en `demo-repo` lleva en la evidencia de `juzgar` «Juez: 5 / 5 · aprobado por 2 de 2. Hallazgos: ~~`README.md:1` falta explicar el cambio~~ (refutado: Ya se comprueba antes (fake).)».
5. **Deja todo como estaba**:
   - rechaza la puerta `aprobar-fusion`;
   - cierra la PR de `demo-repo` sin fusionarla y borra su rama `skynet/<clave>`;
   - quita las tres variables del runner y reinícialo;
   - `rm -rf /tmp/va-c` en esa máquina.

## 5. Al terminar

- `rm -rf /tmp/va-c`.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
