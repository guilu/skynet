# Probar RH-B - No repetir lo ya visto

RH-B (#270) es la segunda PR del hito RH. El juez deja de repetir lo que ya se decidió. Lo explica [`docs/workflows.md`](../workflows.md), en «No repetir lo ya visto (RH-B)».

- **El juez recibe lo ya visto:** en su prompt va «Lo que ya se ha señalado», con lo de las rondas anteriores y su desenlace, y lo intencionado o que no aplica del repositorio. Desde la ronda 2 lleva además «Mira primero `git diff <commit de la ronda anterior>`».
- **Se descarta antes de los escépticos:** lo que coincide con algo refutado en la fase, o marcado como intencionado o que no aplica en el repositorio, sale como «Ya visto», no bloquea y no gasta escépticos (`stage.judge.findings.filtered`).
- **Duplicados:** si dos jueces dicen lo mismo, se refuta una sola vez y el resultado vale para los dos.
- **Lo que vuelve:** el informe del que corrige marca «⚠ ya se señaló en la ronda 1 y sigue».
- **fake-claude:** con `=` delante, el testigo de `FAKE_CLAUDE_JUDGE` no se borra. El mismo hallazgo sale en cada ronda y en cada juez.
- **Sin migración.** No crea ni cambia workflows iniciales.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT' --tests '*JudgePromptTest' \
  --tests '*JudgeVerdictsTest' --tests '*FindingsTest' --tests '*CiCheckIT' \
  :tools:fake-claude:test --tests '*FakeJudgeTest'
cd web && npx vitest run src/components/run/StageJudge.test.tsx src/format.test.ts
```

- `WorkflowEngineIT`:
  - `duplicatesAreRefutedOnceAndWhatWasRefutedIsNotRefutedAgain`:
    - dos jueces dicen lo mismo y sale un solo escéptico, cuyo resultado vale para los dos;
    - en la ronda 2, el prompt lleva el delta y «Lo que ya se ha señalado»;
    - lo refutado se descarta sin escépticos;
    - el informe dice «Lo que vuelve».
  - `aFindingMarkedAsIntendedIsNotReportedAgain`: un hallazgo marcado como intencionado mientras se corrige llega al juez como «intencionado, no lo señales». Si lo repite, se descarta y la fase pasa.
- `JudgePromptTest`: el delta y lo ya visto en el prompt.
- `FakeJudgeTest`: el testigo con `=` se queda.
- `StageJudge.test.tsx` y `format.test.ts`: «Ya visto» y `stage.judge.findings.filtered`.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno estas tres variables, pon los tres nombres en `SKYNET_AGENT_ENV` y reinícialo:

- `FAKE_CLAUDE_TOUCH=/tmp/rh-b/tocar`
- `FAKE_CLAUDE_JUDGE=/tmp/rh-b/suspender`
- `FAKE_CLAUDE_REFUTE=/tmp/rh-b/refutar`

Haz `mkdir -p /tmp/rh-b` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo**. Usa como jueces dos agentes que existan en el despliegue.

```yaml
id: rh-b-sin-repetir
name: No repetir lo ya visto
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
    fix: implementar
    maxRounds: 2
```

En cada paso, antes de lanzar: `echo 'calc_rh.py=VALOR = 1' > /tmp/rh-b/tocar`.

### 2.1 Dos jueces, un escéptico

1. Escribe los dos testigos y lanza `rh-b-sin-repetir` en un trabajo nuevo de `demo-repo`:

   ```bash
   echo '=!calc_rh.py Redondea mal los negativos' > /tmp/rh-b/suspender
   echo 'Redondea' > /tmp/rh-b/refutar
   ```

2. Los dos jueces dicen lo mismo. Comprueba:
   - en **Actividad**, `stage.judge.refute.started` dice «1 escéptico» con **1 hallazgo**, no 2;
   - la fase pasa;
   - el registro dice «2 hallazgos: 2 refutado».
3. `rm /tmp/rh-b/suspender /tmp/rh-b/refutar`.

### 2.2 Lo que vuelve

1. `echo '=calc_rh.py:3 Divide entre cero sin comprobarlo' > /tmp/rh-b/suspender` y lanza en un trabajo nuevo. Sin `/tmp/rh-b/refutar`, el escéptico no lo refuta.
2. La ronda 1 suspende y `implementar` corrige. La ronda 2 vuelve a suspender. Comprueba:
   - en la conversación de un juez de la ronda 2, el prompt lleva «Mira primero `git diff …`» y «## Lo que ya se ha señalado» con «`calc_rh.py`: Divide entre cero sin comprobarlo (ronda 1: sin corregir)»;
   - en la segunda corrección de `implementar`, el prompt lleva «## Lo que vuelve» y «⚠ `calc_rh.py:3`: Divide entre cero sin comprobarlo (ya se señaló en la ronda 1 y sigue)».
3. Tras la ronda 3 la fase falla. El registro enseña el hallazgo de cada juez «Aparcado» con «salió 3 veces».
4. `rm /tmp/rh-b/suspender`.

### 2.3 Lo intencionado no se repite

1. En el registro de 2.2, marca uno de los dos hallazgos con **Es intencionado**, con el motivo «Probando RH-B».
2. `echo '=!calc_rh.py:3 Divide entre cero sin comprobarlo' > /tmp/rh-b/suspender` y lanza en un trabajo nuevo.
3. Comprueba:
   - los dos jueces lo dicen y los dos hallazgos salen como «Ya visto», con «Ya se marcó como intencionado (<tu usuario>): Probando RH-B»;
   - no hay `stage.judge.refute.started` y la fase pasa a la primera;
   - en **Actividad**, «juzgar: se descartan 2 hallazgos ya vistos antes de los escépticos»;
   - el registro dice «2 hallazgos: 2 intencionado».
4. `rm /tmp/rh-b/suspender`.

## 3. Al terminar

- En el registro de 2.2, pulsa **Deshacer** en el hallazgo intencionado, para que no se quede marcado en `demo-repo`.
- Archiva `rh-b-sin-repetir`.
- Quita `FAKE_CLAUDE_TOUCH`, `FAKE_CLAUDE_JUDGE` y `FAKE_CLAUDE_REFUTE` del runner, borra `/tmp/rh-b` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
