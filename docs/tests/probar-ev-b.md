# Probar EV-B - Calibración del juez

EV-B (#291) es la segunda PR del hito EV (Banco de evaluaciones del juez). Lo explica [`docs/workflows.md`](../workflows.md), en «Calibración del juez (EV-B)».

**Fábrica → Jueces** cuenta, además del ruido, qué pasó con lo que cada juez **aprobó** en la última ronda de su fase:

- **Desenlaces:**
  - **Se quedó:** la PR se fusionó y no tiene escape.
  - **Escapes:** el cambio se revirtió o se marcó como escape (EV-A).
  - **Rechazos:** alguien lo rechazó o pidió cambios en una puerta después del juez.
  - `cerrada`: la PR se cerró sin fusionar.
- **Lo que no cuenta:** un cambio sin desenlace (con la PR abierta o sin PR) y lo que el juez suspendió.
- **Calibración:** al elegir una fila, lo que aprobó por tramo de nota (`< 4`, `4–4,5` y `≥ 4,5`) y sus últimos escapes.
- **Base de datos:** no cambia.
- **API:** `GET /api/factory/judges` gana `judged`, `kept`, `escaped`, `rejected`, `closed`, `fewOutcomes`, `calibration` y `escapes`. El cambio es aditivo.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. La sección 2 prepara en la base de datos una PR fusionada y unos veredictos, y la sección 4 los borra.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*MergeIT' --tests '*WorkflowEngineIT'
cd web && npx vitest run src/JudgesPage.test.tsx
```

- `MergeIT.theJudgesAreCalibratedWithWhatHappenedToWhatTheyApproved`:
  - dos jueces aprueban, con 4,8 y 4,1, y uno suspende, con 2;
  - mientras la PR está abierta no hay desenlace;
  - al fusionar, `juez` tiene 1 «se quedó» en el tramo `≥ 4,5` y `segundo` lo tiene en `4–4,5`; `estricto` no suma nada;
  - al revertir la PR con la #13, pasa a escape, sale en `escapes` con `#13` y la nota 4,8, y el tramo queda en 0 %;
  - sin el escape y con un `REQUEST_CHANGES` posterior, cuenta como rechazo.
- `WorkflowEngineIT` (RH-C): la tabla de ruido sigue igual.
- `JudgesPage.test.tsx`:
  - las columnas **Se quedó** («9 de 12»), **Escapes** y **Rechazos**, con «—» si no hay desenlaces;
  - el panel «Calibración de …» con la tabla **Por nota** («3 (75 %)») y «PR #12 · nota 4,8 · revertida con la PR #13», que enlaza a la ejecución.

## 2. Preparar una ejecución juzgada y fusionada

Igual que en [`probar-ev-a.md`](probar-ev-a.md) §2:

- `REPO` es un repositorio de Skynet enlazado a `guilu/demo-repo`.
- `RUN` es una ejecución terminada de ese repositorio, por ejemplo una de fake-claude.

```bash
B=http://localhost:8080; AUTH="admin:$SKYNET_ADMIN_PASSWORD"
psql_() { docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc "$1"; }
psql_ "INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id, agent_run_id,
  full_name, branch, base, title, body, status, head_sha, number, url, merged_sha, created_at, updated_at)
  SELECT gen_random_uuid(), r.id, r.repository_id, s.id, a.id, 'guilu/demo-repo', 'skynet/ev-b', 'main',
    'Prueba EV-B', '', 'MERGED', repeat('c', 40), 9022, 'https://github.com/guilu/demo-repo/pull/9022',
    repeat('e', 40), now(), now()
  FROM workflow_run r JOIN stage_run s ON s.workflow_run_id = r.id JOIN agent_run a ON a.stage_run_id = s.id
  WHERE r.id = '$RUN' LIMIT 1"
verdict() {   # verdict <juez> <nota> <aprobó>
  psql_ "INSERT INTO stage_verdict (id, stage_run_id, workflow_run_id, round, judge, lens, agent_run_id,
    status, score, passed, created_at, updated_at)
    SELECT gen_random_uuid(), s.id, s.workflow_run_id, 1, '$1', NULL, a.id, 'DONE', $2, $3, now(), now()
    FROM stage_run s JOIN agent_run a ON a.stage_run_id = s.id WHERE s.workflow_run_id = '$RUN' LIMIT 1
    ON CONFLICT DO NOTHING"
}
verdict ev-b-alto 4.8 true
verdict ev-b-medio 4.2 true
verdict ev-b-suspende 2 false
```

## 3. En la web y en la API

### 3.1 Se quedó

1. Ejecuta `curl -s -u "$AUTH" "$B/api/factory/judges?days=7" | jq '.[] | select(.judge | startswith("ev-b")) | {judge, judged, kept, escaped, rejected}'`. Debe salir:
   - `ev-b-alto` y `ev-b-medio` con `judged` 1 y `kept` 1;
   - `ev-b-suspende` con todo a 0.
2. Abre **Fábrica → Jueces** con «7 días». En la fila de `ev-b-alto`:
   - **Hallazgos** es 0;
   - **Se quedó** dice «1 de 1 pocos datos»;
   - **Escapes** y **Rechazos** están a 0;
   - `ev-b-suspende` dice «—».
3. Pulsa `ev-b-alto · general`. Sale «Calibración de ev-b-alto · general»:
   - la tabla **Por nota** tiene la fila `≥ 4,5` con «1 (100 %)»;
   - las otras dos filas dicen «—».

### 3.2 Escape

1. En la ejecución, en la tarjeta de la PR, pulsa **Marcar como escape**, escribe `Rompió la exportación` y pulsa **Guardar**.
2. Vuelve a **Fábrica → Jueces** y comprueba lo siguiente:
   - en `ev-b-alto`, **Escapes** es 1 (en rojo) y **Se quedó** dice «0 de 1»;
   - en su calibración, `≥ 4,5` dice «0 (0 %)» con 1 escape;
   - en «Últimos escapes» sale «PR #9022 · nota 4,8 · marcada a mano · Rompió la exportación», con el enlace a la ejecución.
3. En `ev-b-medio` el escape cae en el tramo `4–4,5`.

### 3.3 Sin escape vuelve a «se quedó»

1. Ejecuta `curl -s -u "$AUTH" "$B/api/workflow-runs/$RUN/escapes" | jq -r '.[0].id'` y borra ese escape con **No fue un fallo del juez**.
2. En la fila de `ev-b-alto`, **Se quedó** vuelve a «1 de 1» y **Escapes** a 0.

## 4. Al terminar

```bash
psql_ "DELETE FROM judge_escape WHERE workflow_run_id = '$RUN'"
psql_ "DELETE FROM stage_verdict WHERE workflow_run_id = '$RUN' AND judge LIKE 'ev-b-%'"
psql_ "DELETE FROM github_pull_request WHERE number = 9022 AND full_name = 'guilu/demo-repo'"
```

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
