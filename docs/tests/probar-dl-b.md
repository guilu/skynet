# Probar DL-B - Eventos de despliegue desde GitHub Deployments

DL-B (#238) es la segunda PR del hito DL. Los despliegues llegan a Skynet por **GitHub Deployments**: los listeners avisan a GitHub, y GitHub manda los webhooks `deployment` y `deployment_status` a la App de Skynet por el canal firmado de siempre. Lo cuenta [`docs/despliegues.md`](../despliegues.md) §3.

- **Recepción**: `WebhookProcessor` pasa los `deployment.created` y `deployment_status.created` de un repositorio enlazado a `DeploymentWebhooks`, que registra el despliegue y aplica el estado (DL-A).
  - Un `deployment_status` basta aunque el `deployment` no llegue.
  - Las entregas repetidas o desordenadas no duplican ni pisan nada.
  - `inactive` se ignora.
  - El error pasa por el redactor.
  - Un entorno con caracteres raros se limpia.
- **Evento** `github.deployment*.created` en el repositorio, con un resumen sin secretos.
- **`scripts/notify-deployment.sh`** para los listeners:
  - `start` crea el despliegue del SHA exacto, con la PR opcional;
  - `status` pone `in_progress`, `success`, `failure` o `error`, con la URL;
  - reintenta red y 5xx y nunca imprime el token.
  - Su test (`scripts/tests/notify-deployment.test.sh`) corre en CI.
- **App**: permiso nuevo **Deployments: lectura** y eventos **Deployment** y **Deployment status**.

Sin migraciones, sin endpoints nuevos y sin secretos nuevos. No cambia la política de aprobación y fusión, ni el despliegue actual, ni el aviso por Telegram.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes. La sección 3 crea despliegues en el entorno `prueba-dl-b` y los borra al final. La sección 4 es manual y solo la hacen Diego o Hermes.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*GitHubWebhooksIT' --tests '*DeploymentsIT' --tests '*ModularityTest'
scripts/tests/notify-deployment.test.sh
```

`GitHubWebhooksIT` (los tests de despliegues), con entregas firmadas de `fixtures/github/deployment*.json`:
- un `deployment` y sus estados `in_progress` y `success` dejan un despliegue `SUCCEEDED` con PR, rama, creador, URL y horas de GitHub; la entrega repetida no añade estados;
- un `success` que llega antes que su `in_progress` y que el propio `deployment` termina igual de bien, con el `in_progress` guardado como no aplicado;
- un `error` guarda su descripción sin el token, y `inactive` no añade estado;
- un despliegue más nuevo deja superado al anterior y es el desplegado del entorno;
- una firma mala da 401 y un repositorio que no está en Skynet queda `IGNORED` sin despliegues;
- `Omarchy (compartido)` se guarda como `Omarchy-compartido-`.

`notify-deployment.test.sh`, contra un GitHub falso en Python:
- `start` imprime el id y manda el SHA exacto, el entorno, la PR, `required_contexts: []` y `auto_merge: false`;
- `status` reintenta un 502 y manda el estado y la URL;
- un 422 falla sin reintentar y dice el código;
- un SHA corto, un estado inventado, una PR no numérica o la falta de token fallan sin llamar a GitHub;
- el token no aparece nunca en la salida.

## 2. La App y el webhook en el entorno desplegado

```bash
B=http://localhost:8080; AUTH=admin:<contraseña>
curl -s -u $AUTH $B/api/github | jq '{configured, installations: [.installations[].id]}'
```

La App sigue configurada, igual que antes de la PR.

## 3. Entregas firmadas a mano

Con el secreto de webhooks del despliegue y un repositorio de Skynet enlazado a `guilu/demo-repo` (su id en `REPO`):

```bash
SECRET=$(grep '^SKYNET_GITHUB_WEBHOOK_SECRET=' deploy/.env | cut -d= -f2-)
envia() {   # envia <evento> <fichero> <id de entrega>
  sig=$(openssl dgst -sha256 -hmac "$SECRET" -binary "$2" | xxd -p -c 256)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
    -H 'Content-Type: application/json' -H "X-GitHub-Event: $1" -H "X-GitHub-Delivery: $3" \
    -H "X-Hub-Signature-256: sha256=$sig" --data-binary @"$2"
}
mkdir -p /tmp/dl-b
sed 's/"omarchy"/"prueba-dl-b"/' fixtures/github/deployment-created.json > /tmp/dl-b/d.json
sed 's/"omarchy"/"prueba-dl-b"/' fixtures/github/deployment_status-created.json > /tmp/dl-b/ok.json
sed -e 's/"success"/"in_progress"/' -e 's/950002/950001/' -e 's/18:01:30Z/18:00:05Z/' /tmp/dl-b/ok.json > /tmp/dl-b/curso.json
```

1. El final antes que el inicio, y el `deployment` al final:
   ```bash
   envia deployment_status /tmp/dl-b/ok.json dl-b-ok        # 202
   envia deployment_status /tmp/dl-b/curso.json dl-b-curso  # 202
   envia deployment /tmp/dl-b/d.json dl-b-d                 # 202
   envia deployment_status /tmp/dl-b/ok.json dl-b-ok        # 200: ya estaba
   sleep 3
   curl -s -u $AUTH "$B/api/repositories/$REPO/deployments?environment=prueba-dl-b" | jq '.[] | {externalId, status, pullRequestNumber, url, startedAt, finishedAt}'
   ```
   Esperado: un solo despliegue `900001`, `SUCCEEDED`, PR 12, URL `http://omarchy.local:8081`, `startedAt` 18:00:05 y `finishedAt` 18:01:30.
2. La historia:
   ```bash
   ID=$(curl -s -u $AUTH "$B/api/repositories/$REPO/deployments?environment=prueba-dl-b" | jq -r '.[0].id')
   curl -s -u $AUTH $B/api/deployments/$ID | jq '.statuses[] | {state, applied}'
   ```
   Esperado: `IN_PROGRESS` con `applied: false` y `SUCCEEDED` con `applied: true`.
3. Casos negativos:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
     -H 'Content-Type: application/json' -H 'X-GitHub-Event: deployment' -H 'X-GitHub-Delivery: dl-b-mala' \
     -H 'X-Hub-Signature-256: sha256=00' --data-binary @/tmp/dl-b/d.json      # 401
   sed 's#guilu/demo-repo#guilu/no-existe#' /tmp/dl-b/d.json > /tmp/dl-b/otro.json
   envia deployment /tmp/dl-b/otro.json dl-b-otro                             # 202
   curl -s -u $AUTH $B/api/github/deliveries | jq '.[] | select(.deliveryId=="dl-b-otro") | {status, error}'
   ```
   Esperado: la entrega `dl-b-otro` queda `IGNORED` con «Ningún repositorio de Skynet es guilu/no-existe».
4. Limpieza:
   ```bash
   docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc \
     "DELETE FROM deployment WHERE environment = 'prueba-dl-b'"
   rm -rf /tmp/dl-b
   ```

## 4. Con GitHub de verdad (manual, opcional, solo Diego o Hermes)

1. En la App de Skynet:
   - añade **Deployments: lectura** y los eventos **Deployment** y **Deployment status**;
   - acepta el cambio en la instalación (Settings → Applications).
2. Con un token fino con **Deployments: lectura y escritura** en `guilu/demo-repo`:
   ```bash
   SHA=$(git ls-remote https://github.com/guilu/demo-repo HEAD | cut -f1)
   id=$(GITHUB_TOKEN=<token> DEPLOY_ENVIRONMENT=prueba-dl-b scripts/notify-deployment.sh start guilu/demo-repo "$SHA" main)
   GITHUB_TOKEN=<token> scripts/notify-deployment.sh status guilu/demo-repo "$id" in_progress
   GITHUB_TOKEN=<token> scripts/notify-deployment.sh status guilu/demo-repo "$id" success "prueba DL-B"
   ```
   En Skynet, `prueba-dl-b` muestra ese SHA como `SUCCEEDED`, y en GitHub, `guilu/demo-repo` → Environments muestra el despliegue.
3. Para adaptar los listeners de Omarchy, añade alrededor de cada despliegue la llamada a `start`, `in_progress` y `success`/`failure`, como en `docs/despliegues.md` §3. Un fallo del script no debe parar el despliegue ni el aviso por Telegram.
4. Limpieza: borra el entorno `prueba-dl-b` en GitHub (Settings → Environments) y en Skynet, como en la sección 3.

## 5. Checkout limpio

```bash
git status --porcelain   # vacío
```
