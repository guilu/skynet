# Probar GH-B - Webhooks firmados

GH-B es la segunda PR del hito GH (GitHub conectado, issue #194). Skynet recibe los webhooks de la GitHub App, los guarda sin duplicados y los publica como eventos. Ningún workflow reacciona todavía a ellos: eso llega con EN y DS.

- **Recepción:** `POST /api/github/webhooks` no lleva sesión, porque la protege la firma `X-Hub-Signature-256` (HMAC-SHA256 con `SKYNET_GITHUB_WEBHOOK_SECRET`).
  - Una firma que no vale da 401 y no se guarda nada.
  - Una entrega nueva da 202 y una repetida (mismo `X-GitHub-Delivery`) da 200, sin procesarse otra vez.
  - Un cuerpo que no es JSON da 400 y uno de más de 25 MB da 413.
  - Si la App no está configurada responde 503, y GitHub la marcará fallida.
- **Cola:** la tabla `github_webhook_delivery` hace de cola, con `FOR UPDATE SKIP LOCKED` y un alquiler de 2 minutos. Una entrega que falla se reintenta con espera doblada (5 s, 10 s…) y queda `FAILED` al quinto intento. Las entregas se guardan 30 días.
- **Proceso:**
  - `installation` e `installation_repositories` actualizan las instalaciones y sus repositorios, sin esperar a un `sync`.
  - Los demás eventos se publican como `github.<evento>.<acción>` (por ejemplo `github.pull_request.opened`) en cada repositorio de Skynet cuya URL remota sea ese repositorio de GitHub. El payload es un resumen redactado: número, rama, SHA, conclusión del check, autor y un trozo del comentario.
  - Una entrega de un repositorio que Skynet no tiene queda `IGNORED`, con el motivo. Un `ping` también.
- **Red de seguridad:** al arrancar y cada 10 minutos, Skynet pide a GitHub las últimas entregas de la App (`GET /app/hook/deliveries`) y vuelve a solicitar las que no llegaron y no tiene.
- **Entregas:** `GET /api/github/deliveries?limit=` (con sesión) lista las últimas, sin el cuerpo.
- **Despliegue:** nginx sirve en un segundo puerto (`SKYNET_WEBHOOK_PORT`, por defecto 8082, solo en `127.0.0.1`) **únicamente** `POST /api/github/webhooks`. Ese es el puerto que se abre a internet con el túnel (Tailscale Funnel o Cloudflare Tunnel). La web, el login y el resto de la API se quedan en el puerto de siempre.
- **Migración V31:** añade `published_events` a `github_webhook_delivery`.

La web no cambia: la lista de entregas en Ajustes → GitHub llega en GH-C. Ninguna prueba de esta guía modifica ficheros versionados de skynet ni llama al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*GitHub*' --tests '*MigrationsIT' --tests '*ModularityTest'
./gradlew :control-plane:spotlessCheck
```

Los IT necesitan PostgreSQL, con Docker o con `SKYNET_TEST_DB_URL`. `GitHubWebhooksIT` firma las entregas de `fixtures/github/` y comprueba:

- una firma mala, de otro secreto o ausente da 401 y no deja fila;
- un `pull_request` publica un único `github.pull_request.opened` en cada repositorio enlazado (dos, en la prueba). La primera vez da 202, repetida da 200 y no hay eventos de más;
- `check_run` y `issue_comment` llevan lo que necesitan EN y DS (conclusión, SHA, PR, autor y cuerpo), con los secretos del comentario redactados;
- un repositorio que Skynet no tiene queda `IGNORED` con el motivo, y un `ping` queda `IGNORED` sin procesarse;
- `installation.created`, `installation_repositories.removed` y `installation.deleted` cambian las tablas de instalaciones;
- una entrega que no se puede procesar se reintenta y queda `FAILED` con el error tras el último intento;
- un cuerpo que no es JSON da 400;
- la red de seguridad solo pide otra vez las entregas que nunca llegaron con 2xx y que Skynet no tiene, y no repite la petición en la pasada siguiente.

`GitHubUnconfiguredIT` comprueba además que, sin App, el webhook responde 503.

## 2. Skynet desplegado, sin App

Con Skynet en marcha y sin las variables `SKYNET_GITHUB_*`:

1. El webhook responde 503 por los dos puertos:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8081/api/github/webhooks
   curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8082/api/github/webhooks
   ```
   Escribe `503` dos veces. (8081 es `SKYNET_WEB_PORT`; usa los tuyos si los cambiaste.)
2. El puerto de webhooks no sirve nada más:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/
   curl -s -o /dev/null -w '%{http_code}\n' -u admin:<contraseña> http://localhost:8082/api/github
   curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/api/github/webhooks
   ```
   Escribe `404`, `404` y `403`.
3. El puerto solo escucha en este equipo: `docker compose -f deploy/docker-compose.yml port web 8082` escribe `127.0.0.1:8082`.
4. Con sesión, la lista de entregas está vacía:
   ```bash
   curl -s -u admin:<contraseña> http://localhost:8080/api/github/deliveries
   ```
   Escribe `[]`.

## 3. Entregas firmadas a mano, con una App de mentira

Esto prueba la recepción de punta a punta sin GitHub. La App no existe, así que la sincronización y la red de seguridad fallarán contra api.github.com con un 401: es lo esperado, y solo deja un aviso en el log.

1. Crea una clave de usar y tirar **fuera del checkout** y añade a `deploy/.env` (no está versionado):
   ```bash
   openssl genrsa -out ~/skynet-gh-b.pem 2048
   cat >> deploy/.env <<'EOF'
   SKYNET_GITHUB_APP_ID=1
   SKYNET_GITHUB_PRIVATE_KEY_FILE=/home/<usuario>/skynet-gh-b.pem
   SKYNET_GITHUB_WEBHOOK_SECRET=secreto-gh-b
   EOF
   docker compose -f deploy/docker-compose.yml up -d
   ```
2. Para que el `pull_request` tenga dónde publicarse, `demo-repo` tiene que tener como URL remota un repositorio de github.com. Mira cuál es:
   ```bash
   curl -s -u admin:<contraseña> http://localhost:8080/api/github | jq -r '.repositories[] | select(.name=="demo-repo") | "\(.id) \(.remoteUrl)"'
   ```
   Si es `https://github.com/<owner>/demo-repo(.git)`, apunta `<owner>/demo-repo` y el id. Si no tiene URL de GitHub, salta el paso 5.
3. Define una función que firma y envía por el puerto de webhooks:
   ```bash
   envia() { # envia <evento> <fichero> <delivery>
     sig=$(openssl dgst -sha256 -hmac secreto-gh-b -binary "$2" | xxd -p -c 256)
     curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
       -H 'Content-Type: application/json' -H "X-GitHub-Event: $1" -H "X-GitHub-Delivery: $3" \
       -H "X-Hub-Signature-256: sha256=$sig" --data-binary @"$2"
   }
   ```
4. Firma mala y ping:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
     -H 'Content-Type: application/json' -H 'X-GitHub-Event: ping' -H 'X-GitHub-Delivery: gh-b-mala' \
     -H 'X-Hub-Signature-256: sha256=00' --data-binary @fixtures/github/ping.json
   envia ping fixtures/github/ping.json gh-b-ping
   ```
   Escribe `401` y `202`.
5. Un `pull_request` en `demo-repo`. El fixture es de `guilu/demo-repo`; si tu owner es otro, cópialo cambiado a `/tmp`:
   ```bash
   sed 's#guilu/demo-repo#<owner>/demo-repo#g' fixtures/github/pull_request-opened.json > /tmp/gh-b-pr.json
   envia pull_request /tmp/gh-b-pr.json gh-b-pr-1
   envia pull_request /tmp/gh-b-pr.json gh-b-pr-1
   sleep 3
   curl -s -u admin:<contraseña> "http://localhost:8080/api/events?aggregateId=<id de demo-repo>&types=github.*" | jq '.[] | {type, payload}'
   ```
   La primera escribe `202` y la segunda `200`. Sale **un** evento `github.pull_request.opened` con `number: 12`, `headRef: "skynet/demo-7"` y el `deliveryId` `gh-b-pr-1`.
6. Una entrega de un repositorio que Skynet no tiene:
   ```bash
   sed 's#guilu/demo-repo#nadie/no-existe#g' fixtures/github/check_run-completed.json > /tmp/gh-b-check.json
   envia check_run /tmp/gh-b-check.json gh-b-check-1
   sleep 3
   curl -s -u admin:<contraseña> http://localhost:8080/api/github/deliveries | jq '.[] | {deliveryId, event, status, error, events}'
   ```
   Salen, de la más nueva a la más vieja: `gh-b-check-1` `IGNORED` con «Ningún repositorio de Skynet es nadie/no-existe», `gh-b-pr-1` `PROCESSED` con `events: 1` (si hiciste el paso 5) y `gh-b-ping` `IGNORED`. No sale `gh-b-mala`.
7. Ningún secreto en el log: `docker compose -f deploy/docker-compose.yml logs api | grep -c secreto-gh-b` escribe `0`.
8. Deja el entorno como estaba: borra las tres líneas de `deploy/.env`, `rm ~/skynet-gh-b.pem /tmp/gh-b-*.json` y `docker compose -f deploy/docker-compose.yml up -d`.

## 4. Con una App real y un túnel (opcional, manual)

Esta parte no la ejecuta Hermes.

1. Abre el puerto 8082 con un túnel, por ejemplo `tailscale funnel 8082`, y apunta la URL pública.
2. En la App de GH-A, activa el webhook con la URL `https://<túnel>/api/github/webhooks`, Content type `application/json` y el mismo secreto que `SKYNET_GITHUB_WEBHOOK_SECRET`. Suscríbela a Pull request, Check run, Check suite, Status, Issue comment, Pull request review y Pull request review comment.
3. En GitHub, Advanced → Recent Deliveries muestra el `ping` con 202.
4. Abre una PR en el repositorio de GitHub de `demo-repo`. En `GET /api/events?aggregateId=<id>&types=github.*` sale `github.pull_request.opened`.
5. Para el túnel, abre otra PR y vuelve a abrir el túnel. En 10 minutos como mucho el log dice `Pedidas otra vez 1 entregas de GitHub que no llegaron` y aparece el evento.

## 5. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
