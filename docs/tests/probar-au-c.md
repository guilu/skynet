# Probar AU-C - Revisión y fusión según el riesgo

AU-C (#265) es la última PR del hito AU. El nivel de riesgo del cambio (AU-A) decide ahora cuánto se revisa y si la fusión necesita a una persona. Lo explica [`docs/workflows.md`](../workflows.md), en «Revisión y fusión según el riesgo (AU-C)».

- **`judge.minRisk`**: por debajo de ese nivel, la fase `judge` pasa sin juez («Sin juez: nivel 0 (juzga desde el nivel 1)»). Por defecto 0: siempre se juzga.
- **`judge.refuteMinRisk`**: los escépticos de `refute` solo corren desde ese nivel, también sobre el delta de AU-B. Por defecto 0: siempre.
- **`autoApprove`** en una puerta de fusión: se aprueba sola si el repositorio lo permite, el nivel es ≤ `maxRisk`, las líneas de código ≤ `maxLines`, la CI está en verde, lo que se fusiona está juzgado y no se ha llegado a 10 al día. Si no, pregunta y dice por qué.
- **Interruptor** por repositorio, «Fusionar sola lo de riesgo 0-1» en **Configurar verificación** (`autoMerge`), **apagado** por defecto.
- **Migración `V51`**: `stage_judge.risk`, `stage_judge.min_risk`, el estado `SKIPPED` del juez, el estado `REFUTING` del delta y `repository.auto_merge`. Se aplica sola al arrancar.
- **Seeds**: las tres `aprobar-fusion` llevan `autoApprove: {maxRisk: 1, maxLines: 400}` y sus `juzgar`, `minRisk: 1` y `refuteMinRisk: 2`.
- **Eventos**: `stage.judge.skipped`, `stage.judge.delta.refute.started` y `approval.granted` con `auto: true` y actor `skynet`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*CiCheckIT' --tests '*DefinitionParserTest' \
  --tests '*SeedWorkflowsTest' --tests '*ContractIT' --tests '*MigrationsIT' --tests '*WorkflowEngineIT'
cd web && npx vitest run src/format.test.ts src/contracts.test.ts src/components/run/StageJudge.test.tsx
```

- `CiCheckIT`, con un workflow implementar → juzgar (dos jueces, `minRisk: 1`, `refute: 1`, `refuteMinRisk: 2`) → pr → ci → aprobar (`autoApprove: true`) → fusionar:
  - **nivel 1, pequeño, interruptor encendido**: la puerta se aprueba sola; decide `skynet` con «Se aprueba sola (AU-C):» y la lista (nivel, líneas, CI, juzgado);
  - **interruptor apagado**: la puerta espera con «No se aprueba sola: la autonomía del repositorio está apagada»;
  - **nivel 2**: espera con «No se aprueba sola: nivel 2: `db/migration/V2__x.sql` (ruta)»;
  - **nivel 0**: `juzgar` pasa sin juez (`stage.judge.skipped`, estado `SKIPPED`) y la puerta se aprueba sola con «Nivel 0: sin juez»;
  - **nivel 1 con un hallazgo que bloquea**: no se llama a los escépticos y la fase dice «Nivel 1: sin escépticos (refutan desde el nivel 2)»;
  - **delta con `refuteMinRisk: 1`**: un juez del delta bloquea, un escéptico lo refuta y la CI pasa.
- `DefinitionParserTest`: `minRisk`, `refuteMinRisk` y `autoApprove` (`true`, `false` y con límites) y sus errores.
- `SeedWorkflowsTest`: los workflows iniciales se siguen pudiendo publicar.
- `StageJudge.test.tsx` y `format.test.ts`: «Sin juez» y los eventos nuevos.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_TOUCH=/tmp/au-c/tocar` y `FAKE_CLAUDE_JUDGE=/tmp/au-c/suspender` (y los dos nombres en `SKYNET_AGENT_ENV`) y reinícialo. `mkdir -p /tmp/au-c` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo** (con dos agentes que existan en el despliegue como jueces):

```yaml
id: au-c-riesgo
name: Juzgar según el riesgo
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [juez, revisor]
      refute: 1
      minRisk: 1
      refuteMinRisk: 2
```

### 2.1 Solo documentación: sin juez

1. `echo 'README.md=Probando AU-C' > /tmp/au-c/tocar` y lanza `au-c-riesgo` en un trabajo nuevo de `demo-repo`.
2. Comprueba:
   - `implementar` tiene **Riesgo 0**;
   - `juzgar` termina bien y dice «Sin juez: nivel 0 (juzga desde el nivel 1)»; al abrirla, «Sin juez · nivel 0: juzga desde el nivel 1»;
   - en **Actividad**, `stage.judge.skipped` y ningún `stage.judge.started`.

### 2.2 Código con un hallazgo: sin escépticos en el nivel 1

1. `echo 'calc_au_c.py=VALOR = 1' > /tmp/au-c/tocar` y `echo 'calc_au_c.py no tiene test' > /tmp/au-c/suspender`, y lanza en un trabajo nuevo.
2. Comprueba:
   - `implementar` tiene **Riesgo 1** y `juzgar` llama a los dos jueces;
   - uno suspende con el hallazgo y la fase falla;
   - no hay `stage.judge.refute.started`, y la fase dice «⚠ Nivel 1: sin escépticos (refutan desde el nivel 2)».

### 2.3 Nivel 2: con escépticos

1. `echo 'db/migration/V9__au_c.sql=CREATE TABLE au_c (id int);' > /tmp/au-c/tocar` y `echo 'V9__au_c.sql sin índice' > /tmp/au-c/suspender`, y lanza en un trabajo nuevo.
2. Comprueba que `implementar` tiene **Riesgo 2** y que, cuando un juez bloquea, sale `stage.judge.refute.started` con un escéptico.

### 2.4 El interruptor

1. En la ficha de `demo-repo`, **Configurar verificación**: la casilla «Fusionar sola lo de riesgo 0-1» está **desmarcada**.
2. Márcala y guarda: sale «Guardado.» y, al recargar, sigue marcada. En **Actividad**, `repository.verification.configured` lleva `autoMerge: true`.
3. Desmárcala y guarda.

## 3. Con la App real y `guilu/demo-repo` (manual, opcional)

Esta parte abre PRs de verdad en `guilu/demo-repo`. Hermes solo la ejecuta si:
- su despliegue tiene la App `skynet-guilu` configurada;
- `demo-repo` está registrado con la URL remota `https://github.com/guilu/demo-repo.git`;
- `guilu/demo-repo` tiene una CI que pasa los tests.

Si no, salta a la sección 4.

Publica `au-c-fusion`: el YAML de §2 más, al final:

```yaml
  - id: pr
    type: github-pr
    dependsOn: [juzgar]
  - id: ci
    type: github-check
    dependsOn: [pr]
    fix: implementar
  - id: aprobar
    type: human-approval
    dependsOn: [ci]
    action: Fusionar
    autoApprove: true
  - id: fusionar
    type: github-merge
    dependsOn: [aprobar]
```

Enciende «Fusionar sola lo de riesgo 0-1» en `demo-repo` antes de los pasos 1 y 2.

1. **Solo docs, se fusiona sola sin juez**: `echo 'docs/au-c.md=# AU-C' > /tmp/au-c/tocar` y lanza. `juzgar` pasa sin juez, la CI pasa, `aprobar` se aprueba sola (en **Actividad**, `approval.granted` de `skynet`, «aprobar se aprobó sola…») y `fusionar` fusiona la PR. En la página de la aprobación, «Se aprobó sola» con todas las condiciones en ✓.
2. **Código pequeño, se fusiona sola con juez**: `echo 'au_c.py=LISTO = True' > /tmp/au-c/tocar` y lanza. Los jueces aprueban, la CI pasa y la puerta se aprueba sola; la PR se fusiona.
3. **Migración, te espera**: `echo 'db/migration/V9__au_c.sql=CREATE TABLE au_c (id int);' > /tmp/au-c/tocar` y lanza. La puerta espera en **Aprobaciones** con «No se aprueba sola» y, en ✗, «Nivel 2: `db/migration/V9__au_c.sql` (ruta)». **Recházala**.
4. **Interruptor apagado, todo pregunta**: apágalo, `echo 'au_c2.py=OTRO = 1' > /tmp/au-c/tocar` y lanza. La puerta espera con «✗ La autonomía del repositorio está apagada». **Recházala**.
5. **Deja GitHub como estaba**: revierte en `guilu/demo-repo` los commits que fusionaron los pasos 1 y 2 (una PR de reversión desde la web de GitHub), cierra las PRs de los pasos 3 y 4 sin fusionar y borra sus ramas `skynet/…`.

## 4. Al terminar

- Archiva `au-c-riesgo` (y `au-c-fusion` si se publicó).
- Deja **apagado** «Fusionar sola lo de riesgo 0-1» en `demo-repo`.
- Quita `FAKE_CLAUDE_TOUCH` y `FAKE_CLAUDE_JUDGE` del runner, borra `/tmp/au-c` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
