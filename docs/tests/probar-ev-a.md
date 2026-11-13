# Probar EV-A - Escapes

EV-A (#290) es la primera PR del hito EV (Banco de evaluaciones del juez). Lo explica [`docs/workflows.md`](../workflows.md), en «Escapes del juez (EV-A)».

Un **escape** es un cambio que el juez aprobó y Skynet fusionó, y que luego se revirtió en la rama por defecto o una persona marcó porque se le escapó un fallo. Hay uno como mucho por ejecución.

- **Se ve de tres maneras:**
  - Una PR de revert fusionada: el título empieza por `Revert "` y la descripción tiene la línea `Reverts owner/repo#N`, que es lo que escribe el botón «Revert» de GitHub. También vale si nombra el commit que se fusionó («This reverts commit …»).
  - Un push a la rama por defecto con un commit «This reverts commit {sha}» que coincide con lo que fusionó Skynet. Esto necesita el evento **Push** de la App, que es opcional y no pide permisos nuevos.
  - A mano, desde la tarjeta de la PR de la ejecución: **Marcar como escape**, con una nota.
- **Se quita** con «No fue un fallo del juez», p. ej. si se revirtió por otro motivo.
- **Migración V55:** `judge_escape`.
- **API:**
  - `GET` y `POST /api/workflow-runs/{id}/escapes`;
  - `DELETE /api/escapes/{id}`;
  - `GET /api/factory/escapes?days=`.
- **Eventos:**
  - `run.escaped` sale en Actividad y, si no es a mano, como aviso de Skyny («Se ha revertido …»);
  - `run.escape.removed`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. La sección 3 prepara una PR fusionada en la base de datos y la borra al final.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*EscapesTest' --tests '*MergeIT'
cd web && npx vitest run src/components/run/PullRequestCard.test.tsx src/format.test.ts src/components/companion/notices.test.ts
```

- `EscapesTest`:
  - `Reverts guilu/demo-repo#12` da la 12, sin distinguir mayúsculas, solo con `Revert "` en el título y solo para el mismo repositorio;
  - «This reverts commit …» da los SHA, de 7 cifras o más.
- `MergeIT.aRevertInTheDefaultBranchIsAnEscapeOfTheJudge`, con webhooks firmados:
  - la PR de revert #13 deja un escape `revert-pr` de la PR #12, con `#13`, su enlace y el SHA fusionado, y emite `run.escaped`;
  - el push del mismo revert no crea otro, y marcarlo a mano da 409;
  - quitarlo emite `run.escape.removed`;
  - un push con «This reverts commit EEEEEEEEEE» lo vuelve a crear como `revert-push`, con el commit corto;
  - un push a otra rama no cuenta;
  - a mano guarda la nota y quién lo marcó, y sale en `/api/factory/escapes`.
- `MergeIT.aRunThatMergedNothingCannotHaveAnEscape`: sin PR fusionada, 409. El revert de una PR que no es de Skynet no hace nada.
- `PullRequestCard.test.tsx`:
  - la banda «Se revirtió en main con la PR #13», con su enlace, y el botón «No fue un fallo del juez»;
  - **Marcar como escape** manda la nota.
- `format.test.ts` y `notices.test.ts`: los textos de Actividad y el aviso «Se ha revertido …», que no sale cuando el escape se marca a mano.

## 2. Preparar una PR fusionada

Hace falta un repositorio de Skynet enlazado a `guilu/demo-repo` en la App (como en DL-B), con su id en `REPO`, y una ejecución terminada de ese repositorio, p. ej. una de fake-claude, con su id en `RUN`.

```bash
B=http://localhost:8080; AUTH="admin:$SKYNET_ADMIN_PASSWORD"
psql_() { docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc "$1"; }
psql_ "INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id, agent_run_id,
  full_name, branch, base, title, body, status, head_sha, number, url, merged_sha, created_at, updated_at)
  SELECT gen_random_uuid(), r.id, r.repository_id, s.id, a.id, 'guilu/demo-repo', 'skynet/ev-a', 'main',
    'Prueba EV-A', '', 'MERGED', repeat('c', 40), 9012, 'https://github.com/guilu/demo-repo/pull/9012',
    repeat('e', 40), now(), now()
  FROM workflow_run r JOIN stage_run s ON s.workflow_run_id = r.id JOIN agent_run a ON a.stage_run_id = s.id
  WHERE r.id = '$RUN' LIMIT 1"
```

## 3. Entregas firmadas a mano

```bash
SECRET=$(grep '^SKYNET_GITHUB_WEBHOOK_SECRET=' deploy/.env | cut -d= -f2-)
envia() {   # envia <evento> <fichero> <id de entrega>
  sig=$(openssl dgst -sha256 -hmac "$SECRET" -binary "$2" | xxd -p -c 256)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
    -H 'Content-Type: application/json' -H "X-GitHub-Event: $1" -H "X-GitHub-Delivery: $3" \
    -H "X-Hub-Signature-256: sha256=$sig" --data-binary @"$2"
}
mkdir -p /tmp/ev-a
INST=$(curl -s -u "$AUTH" $B/api/github | jq '.installations[0].id')
cat > /tmp/ev-a/revert.json <<EOF
{"action":"closed","number":9013,"installation":{"id":$INST},
 "repository":{"full_name":"guilu/demo-repo","default_branch":"main"},
 "pull_request":{"number":9013,"merged":true,"merge_commit_sha":"$(printf 'f%.0s' {1..40})",
  "title":"Revert \"Prueba EV-A\"","body":"Reverts guilu/demo-repo#9012",
  "html_url":"https://github.com/guilu/demo-repo/pull/9013"}}
EOF
cat > /tmp/ev-a/push.json <<EOF
{"ref":"refs/heads/main","installation":{"id":$INST},
 "repository":{"full_name":"guilu/demo-repo","default_branch":"main"},
 "commits":[{"id":"a1b2c3d000000000000000000000000000000000","message":"Revert\n\nThis reverts commit eeeeeee.",
  "url":"https://github.com/guilu/demo-repo/commit/a1b2c3d"}]}
EOF
```

### 3.1 La PR de revert

1. `envia pull_request /tmp/ev-a/revert.json ev-a-1` da 202.
2. Unos segundos después, `curl -s -u "$AUTH" $B/api/workflow-runs/$RUN/escapes | jq '.[0] | {origin, seenRef, pullRequestNumber, createdBy}'` da `revert-pr`, `#9013`, `9012` y `skynet`.
3. En la web, en la ejecución, la tarjeta de la PR tiene la banda roja «Se revirtió en main con la PR #9013 · visto por Skynet». El enlace lleva a la PR del revert.
4. Skyny avisa «Se ha revertido …». **Actividad** dice «Se revirtió la PR #9012 en la rama por defecto con la PR #9013: escape del juez».

### 3.2 El push del mismo revert no lo duplica

1. `envia push /tmp/ev-a/push.json ev-a-2` da 202. La ejecución sigue con un solo escape.

### 3.3 Quitarlo y que lo vea el push

1. En la banda, **No fue un fallo del juez**. La banda desaparece y sale **Marcar como escape**. **Actividad** dice «admin dijo que no fue un fallo del juez y quitó el escape».
2. `envia push /tmp/ev-a/push.json ev-a-3`: ahora es un escape `revert-push`, con la banda «Se revirtió en main con el commit a1b2c3d».

### 3.4 A mano

1. Quítalo otra vez. Pulsa **Marcar como escape**, escribe `Rompió la exportación` y **Guardar**.
2. La banda dice «Se le escapó un fallo al juez · Rompió la exportación · admin el …». Skyny no avisa: lo has marcado tú.
3. `curl -s -u "$AUTH" "$B/api/factory/escapes?days=7" | jq length` da 1.
4. En una ejecución que no fusionó ninguna PR, `curl -s -o /dev/null -w '%{http_code}\n' -u "$AUTH" -X POST -H 'Content-Type: application/json' -d '{}' $B/api/workflow-runs/<otra>/escapes` da 409.

## 4. Con GitHub de verdad (manual, opcional, solo Diego o Hermes)

1. En la App de Skynet, añade el evento **Push**. No pide permisos nuevos: Contents ya es de lectura y escritura.
2. En `guilu/demo-repo`, sobre una PR que fusionó Skynet, pulsa **Revert** y fusiona la PR que abre GitHub. La ejecución de la PR original enseña la banda.

## 5. Al terminar

```bash
psql_ "DELETE FROM judge_escape WHERE workflow_run_id = '$RUN'"
psql_ "DELETE FROM github_pull_request WHERE number = 9012 AND full_name = 'guilu/demo-repo'"
rm -rf /tmp/ev-a
```

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
