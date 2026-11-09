# Probar AU-A - Nivel de riesgo del cambio

AU-A (#263) es la primera PR del hito AU (autonomía según el riesgo del cambio). Skynet mide el riesgo de lo que cambia cada agente **a partir del diff, nunca de lo que diga el agente**, y lo guarda en la fase y en la ejecución. Por ahora **solo mide**: nada de lo que se ejecuta cambia. Lo explica [`docs/workflows.md`](../workflows.md), en «Nivel de riesgo del cambio».

- **Niveles**: 0 si solo cambia documentación, 1 si cambia código (con o sin tests) y 2 si toca una ruta delicada (migraciones, CI, dependencias, despliegue, seguridad, `.env`) o añade una línea delicada (esquema de BD, secretos, autenticación, cookies, `permissions:`).
- **Runner**: `RiskClassifier` mide el worktree al terminar cada agente de una fase `agent` o `parallel` y manda el resultado con `changes.json`, bajo `risk`.
- **Protocolo**: `StartAgent.risk` (las rutas y los patrones de más) y `RiskRules` (los de por defecto).
- **YAML**: `risk: {paths: [...], patterns: [...]}` en la raíz. Solo suma.
- **Repositorio**: «Rutas de riesgo 2» y «Patrones de riesgo 2» en **Configurar verificación** (`riskPaths` y `riskPatterns`).
- **Migración `V49`**: `stage_run.risk`, `workflow_run.risk` y su `risk_detail`, y `repository.risk_paths` y `risk_patterns`. Se aplica sola al arrancar.
- **Web**: insignia **Riesgo 0/1/2** en la cabecera de la ejecución y en cada fase, con el motivo al pasar por encima.
- **Eventos**: `stage.risk.assessed` y `run.risk.raised`.
- **fake-claude**: `FAKE_CLAUDE_TOUCH=<fichero>`. Si el fichero existe, el agente cambia los ficheros que lista (una línea por fichero: `ruta` o `ruta=línea que se añade`) y lo borra.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :runner:test --tests '*RiskClassifierTest' :tools:fake-claude:test --tests '*FakeClaudeTest' \
  :control-plane:test --tests '*VerificationAndArtifactsIT' --tests '*DefinitionParserTest' \
  --tests '*ContractIT' --tests '*MigrationsIT' --tests '*WorkflowEngineIT'
cd web && npx vitest run src/components/run/riskFormat.test.ts src/format.test.ts src/contracts.test.ts
```

- `RiskClassifierTest`:
  - solo docs es nivel 0, y sin cambios también («Nivel 0: sin cambios»);
  - código es nivel 1, y los tests son nivel 1 sin contar líneas;
  - una migración o un workflow de CI es nivel 2 por la ruta, también la ruta vieja de un renombrado;
  - `CREATE TABLE`, `password` o `@PreAuthorize` añadidos en código suben a nivel 2, pero no en un test ni en un `.md`;
  - las rutas y los patrones del repositorio suben el nivel, y un patrón que no compila se avisa y no se usa;
  - los motivos salen con los de más nivel primero y como mucho 20.
- `FakeClaudeTest`: `FAKE_CLAUDE_TOUCH` cambia esos ficheros una vez y no sale del worktree.
- `VerificationAndArtifactsIT`:
  - las reglas del repositorio llegan en la orden `START` del agente;
  - una expresión que no compila es un 400;
  - unos cambios de nivel 1 fijan la fase y la ejecución en 1, otros de nivel 2 las suben, y unos posteriores de nivel 0 no las bajan;
  - salen tres `stage.risk.assessed` y dos `run.risk.raised`.
- `DefinitionParserTest`: `risk` en la raíz con rutas y patrones; un patrón que no compila o una clave desconocida son errores.

## 2. En local, con un repositorio de prueba

Prepara un repositorio con código, docs y tests:

```bash
rm -rf /tmp/au-a && mkdir -p /tmp/au-a/repo/docs /tmp/au-a/repo/tests && cd /tmp/au-a/repo && git init -q -b main
printf 'def add(a, b):\n    return a - b\n' > calc.py
printf 'from calc import add\n\ndef test_add():\n    assert add(1, 2) == 3\n' > tests/test_calc.py
echo '# Demo' > README.md
echo '# Notas' > docs/notas.md
git add -A && git -c user.email=t@t -c user.name=t commit -qm base
cd -
```

Arranca el control plane, el runner con fake-claude y la web:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/au-a/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_TOUCH \
FAKE_CLAUDE_TOUCH=/tmp/au-a/tocar \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

En la web, registra `/tmp/au-a/repo` en un proyecto y crea un trabajo. Publica en **Workflows → Nuevo**:

```yaml
id: au-a-medir
name: Medir el riesgo
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
```

Antes de cada lanzamiento escribe en `/tmp/au-a/tocar` lo que debe cambiar el agente. fake-claude lo borra al usarlo; sin él, el agente no cambia nada.

### 2.1 Los tres niveles

| Paso | `/tmp/au-a/tocar` | La ejecución y la fase `implementar` |
|---|---|---|
| 1 | *(no existe)* | **Riesgo 0**, «Nivel 0: sin cambios» |
| 2 | `README.md` y `docs/notas.md`, una por línea | **Riesgo 0**, «Nivel 0: solo documentación» |
| 3 | `calc.py=# arreglado` | **Riesgo 1**, «Nivel 1: 1 línea de código» |
| 4 | `tests/test_calc.py=# otro test` | **Riesgo 1**, «Nivel 1: 0 líneas de código» (los tests no cuentan) |
| 5 | `db/migration/V2__precio.sql=ALTER TABLE x ADD y int;` | **Riesgo 2**, «Nivel 2: `db/migration/V2__precio.sql` (ruta)» |
| 6 | `config.py=password = "x"` | **Riesgo 2**, «Nivel 2: `config.py` (secretos)» |
| 7 | `docs/seguridad.md=Nunca guardes el password en claro` | **Riesgo 0**: el patrón no cuenta en la documentación |

Por ejemplo, para el paso 2:

```bash
printf 'README.md\ndocs/notas.md\n' > /tmp/au-a/tocar
```

En cada paso comprueba:

- la insignia **Riesgo N** en la cabecera de la ejecución, junto al estado, y en la fase `implementar` al abrirla en el progreso;
- al pasar por encima de la insignia, el resumen y, debajo, los ficheros con su motivo, p. ej. «config.py (secretos: «password»)»;
- en **Actividad**, `stage.risk.assessed` con el nivel, los ficheros, las líneas y el resumen, y `run.risk.raised` («de — a N»);
- la insignia es verde en el nivel 0, gris en el 1 y ámbar en el 2.

### 2.2 Las reglas del repositorio y del workflow

1. En la ficha del repositorio, **Configurar verificación**:
   - pon `infra/**` en «Rutas de riesgo 2» y `\bfeature_flag\b` en «Patrones de riesgo 2», y guarda;
   - la ficha dice las dos;
   - prueba también un patrón roto, `(sin cerrar`: no se guarda y sale el error.
2. `echo 'infra/main.tf=resource "x" "y" {}' > /tmp/au-a/tocar` y lanza: **Riesgo 2**, «Nivel 2: `infra/main.tf` (ruta)».
3. `echo 'app.py=feature_flag = True' > /tmp/au-a/tocar` y lanza: **Riesgo 2**, «Nivel 2: `app.py` (patrón del repositorio)».
4. En **Actividad**, el evento `agent.spawned` de esos agentes lleva `risk: {paths: ["infra/**"], patterns: ["\\bfeature_flag\\b"]}`; sin reglas de más, no lleva `risk`.
5. Publica una versión nueva de `au-a-medir` con, en la raíz:

   ```yaml
   risk:
     paths: ["billing/**"]
   ```

   y lanza con `echo 'billing/tarifa.py=PRECIO = 3' > /tmp/au-a/tocar`: **Riesgo 2** por la ruta.
6. Publica una versión con `risk: {patterns: ['(sin cerrar']}`: el editor da el error en `risk.patterns[0]` y no se publica.
7. Vacía «Rutas de riesgo 2» y «Patrones de riesgo 2» del repositorio.

### 2.3 Solo sube

1. Publica:

   ```yaml
   id: au-a-dos-fases
   name: Dos fases
   stages:
     - id: migrar
       type: agent
       prompt: Añade la migración
     - id: documentar
       type: agent
       prompt: Documenta
       dependsOn: [migrar]
       workspace: isolated-worktree
   ```

2. `echo 'db/migration/V3__x.sql=CREATE TABLE x (id int);' > /tmp/au-a/tocar` y lánzalo. `migrar` usa el fichero; `documentar` ya no lo encuentra y, en su worktree nuevo, no cambia nada.
3. Comprueba:
   - `migrar` tiene **Riesgo 2** y `documentar` **Riesgo 0** («Nivel 0: sin cambios»);
   - la ejecución sigue en **Riesgo 2**, con el motivo de `migrar`;
   - en **Actividad** hay dos `stage.risk.assessed` y un solo `run.risk.raised`.

### 2.4 Lo que no se mide

- En una fase `command`, o en un juez o un escéptico de una fase `verification`, no sale insignia ni `stage.risk.assessed`: los jueces trabajan en una copia que no llega al cambio.
- Una ejecución anterior a esta versión no tiene insignia.

## 3. La API

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
P=<id-del-proyecto>; R=<id-del-repositorio>; RUN=<id-de-una-ejecución>
api -X PUT -d '{"riskPaths": ["infra/**"], "riskPatterns": ["\\bfeature_flag\\b"]}' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification | jq '{riskPaths, riskPatterns}'
# {"riskPaths": ["infra/**"], "riskPatterns": ["\\bfeature_flag\\b"]}
api -X PUT -d '{"riskPatterns": ["(sin cerrar"]}' -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification
# 400
api -X PUT -d '{"riskPaths": ["../fuera"]}' -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification
# 400
api http://localhost:8080/api/workflow-runs/$RUN | jq '.risk, [.stages[] | {stageKey, tier: .risk.tier}]'
# {"tier": 2, "summary": "Nivel 2: …", "files": 1, "lines": 1, "reasons": [...]}
api -X PUT -d '{"riskPaths": [], "riskPatterns": []}' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification | jq '{riskPaths, riskPatterns}'
```

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa fake-claude, así que no se gasta cuota. Añade al entorno del runner `FAKE_CLAUDE_TOUCH=/tmp/au-a/tocar` (y `FAKE_CLAUDE_TOUCH` en `SKYNET_AGENT_ENV`) y reinícialo. Con el despliegue en la versión de la PR:

1. Publica `au-a-medir` (el de §2) en Skynet y lánzalo sobre un trabajo de `demo-repo` tres veces, con `/tmp/au-a/tocar` en la máquina del runner:
   - `README.md`: **Riesgo 0**;
   - el primer fichero de código que salga en `git -C <checkout de demo-repo> ls-files`: **Riesgo 1**;
   - `.github/workflows/au-a.yml=on: push`: **Riesgo 2**, «(ruta)».
2. Lanza **Implementar una tarea** sobre `demo-repo` sin `/tmp/au-a/tocar` y comprueba que todo sigue igual que antes: la fase `implementar` tiene su insignia, y ni el juez ni la puerta de fusión cambian. Rechaza la fusión al final y cierra la PR que abra.
3. **Deja todo como estaba**:
   - archiva `au-a-medir`;
   - borra las ramas `skynet/…` que hayan quedado en `demo-repo`;
   - quita `FAKE_CLAUDE_TOUCH` del runner y reinícialo.
