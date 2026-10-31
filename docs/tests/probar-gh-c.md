# Probar GH-C - Ajustes de GitHub, web y E2E

GH-C es la última PR del hito GH (GitHub conectado, issue #195). Lleva a la web lo que hicieron GH-A y GH-B, y cierra el hito con la E2E y la documentación.

- **Ajustes → GitHub** (`/settings?tab=github`):
  - estado de la App: «Sin configurar» con las variables que faltan, «Conectada» con su enlace, o «Sin respuesta» con el error;
  - los pasos para crearla, con permisos, eventos, URL del webhook y variables, y un botón para copiar cada valor;
  - las instalaciones con el botón **Sincronizar**;
  - los repositorios de Skynet con su acceso;
  - las últimas entregas con su estado y su error, que se refrescan cada 10 s.
- **Página del proyecto:** cada repositorio tiene una sección **GitHub**:
  - «Con acceso» e «instalación de …»;
  - «Sin acceso», con el enlace para sincronizar;
  - o su URL remota cuando la App no está configurada.

  El formulario de registrar repositorio admite ahora una **URL remota** opcional.
- **Actividad:** los eventos `github.*` salen legibles, por ejemplo «GitHub guilu/demo-repo: PR #12 opened (…)».
- **Contratos:** `fixtures/contracts/github-status.json` y `github-deliveries.json`, generados por `ContractIT` y comprobados por la web.
- **E2E** `web/e2e/github.e2e.ts`, contra un GitHub falso (`web/e2e/fake-github.ts`) que `scripts/e2e.sh` arranca con una clave de usar y tirar. Todas las E2E corren ahora con la App configurada contra ese falso.
- **Documentación:** `docs/github.md`, el README y §8 y §12 de `docs/implementation-plan.md`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni llama al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*ContractIT' --tests '*GitHub*'
cd web && npm run typecheck && npm run lint && npm run format:check && npx vitest run
```

- `ContractIT` genera los dos JSON de GitHub sin diferencias.
- `contracts.test.ts` comprueba que los tipos de la web tienen las mismas claves.
- `GitHubSettings.test.tsx` cubre los dos casos de la pestaña:
  - sin App: dice qué variables faltan, enseña los pasos con los permisos y la URL para copiar, y deja **Sincronizar** desactivado;
  - con App: enseña el enlace a la App, el acceso «Con acceso», las entregas y «Sincronizado.» al sincronizar.
- `format.test.ts` comprueba las frases de Actividad de `pull_request` (abierta y fusionada), `check_run`, `issue_comment` y un evento sin frase propia.

E2E, con PostgreSQL y Chromium para Playwright:

```bash
./scripts/e2e.sh github.e2e.ts
```

La E2E hace lo siguiente:
- registra un repositorio con remoto `https://github.com/Guilu/Demo-Repo.git`;
- en Ajustes → GitHub ve la App «Conectada» (`skynet-e2e`), sincroniza y el repositorio sale «Con acceso»;
- envía un `pull_request` firmado: con firma mala da 401, la primera vez 202 y repetido 200;
- lo ve «Procesada» en la lista de entregas;
- pasa axe en claro y en oscuro;
- comprueba el evento `github.pull_request.opened` en la API, la sección GitHub del repositorio (también con axe) y la frase en Actividad.

## 2. Skynet desplegado, sin App

Con Skynet en marcha y sin las variables `SKYNET_GITHUB_*`:

1. Abre **Ajustes**. Hay una pestaña **GitHub**. Al elegirla, la URL pasa a `/settings?tab=github`.
2. «GitHub App» dice **Sin configurar**, y las tres variables que faltan son `SKYNET_GITHUB_APP_ID`, `SKYNET_GITHUB_PRIVATE_KEY_FILE` y `SKYNET_GITHUB_WEBHOOK_SECRET`.
3. «Crear la App» sale abierta:
   - seis pasos;
   - la tabla de permisos (Metadata, Contents, Pull requests, Checks, Commit statuses e Issues);
   - la URL del webhook y las variables, cada una con su botón **Copiar**, que pasa a «Copiado» al pulsarlo.
4. «Instalaciones» dice «Configura la App para ver dónde está instalada», y **Sincronizar** está desactivado. No hay sección «Últimas entregas».
5. «Repositorios de Skynet» lista los repositorios sin archivar. `demo-repo` sale «No es de GitHub» si no tiene URL remota, o «Sin acceso» si apunta a github.com.
6. En la página del proyecto de `demo-repo`, en la pestaña Repositorios, su tarjeta tiene una sección **GitHub**. Sin URL remota dice «Sin URL remota: Skynet no lo enlaza con GitHub». Con ella, dice su URL y «la GitHub App no está configurada», con un enlace a Ajustes → GitHub.
7. En **Proyectos → Nuevo repositorio**, el formulario tiene el campo «URL remota (opcional)». No hace falta registrar nada.
8. Cambia al tema oscuro, arriba a la derecha. La pestaña GitHub se lee bien, sin texto de bajo contraste. Vuelve al tema de antes.

## 3. Con una App de mentira y entregas firmadas a mano

Igual que en la guía de GH-B: la App no existe y la API de GitHub responderá 401. Solo deja avisos en el log.

1. Crea una clave fuera del checkout y añade las variables a `deploy/.env`, que no está versionado:
   ```bash
   openssl genrsa -out ~/skynet-gh-c.pem 2048
   cat >> deploy/.env <<'EOF'
   SKYNET_GITHUB_APP_ID=1
   SKYNET_GITHUB_PRIVATE_KEY_FILE=/home/<usuario>/skynet-gh-c.pem
   SKYNET_GITHUB_WEBHOOK_SECRET=secreto-gh-c
   EOF
   docker compose -f deploy/docker-compose.yml --profile app up -d
   ```
2. En Ajustes → GitHub, la App sale **Sin respuesta**, con un error que contiene «GitHub respondió 401». «Crear la App» sale cerrada y se abre al pulsarla.
3. Pulsa **Sincronizar**. Sale un error que contiene «GitHub respondió 401», y nada más cambia.
4. «Últimas entregas» dice «Todavía no ha llegado ningún webhook…».
5. Envía un `ping` y un `check_run` firmados por el puerto de webhooks:
   ```bash
   envia() { # envia <evento> <fichero> <delivery>
     sig=$(openssl dgst -sha256 -hmac secreto-gh-c -binary "$2" | xxd -p -c 256)
     curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
       -H 'Content-Type: application/json' -H "X-GitHub-Event: $1" -H "X-GitHub-Delivery: $3" \
       -H "X-Hub-Signature-256: sha256=$sig" --data-binary @"$2"
   }
   envia ping fixtures/github/ping.json gh-c-ping
   sed 's#guilu/demo-repo#nadie/no-existe#g' fixtures/github/check_run-completed.json > /tmp/gh-c-check.json
   envia check_run /tmp/gh-c-check.json gh-c-check
   ```
   Escribe `202` dos veces.
6. En 10 s como mucho, sin recargar, «Últimas entregas» muestra dos filas:
   - `check_run.completed`, de `nadie/no-existe`, **Ignorada**, con «Ningún repositorio de Skynet es nadie/no-existe»;
   - `ping`, **Ignorada**, con «ping: GitHub comprueba el webhook».

   Al pasar el ratón por el detalle sale el id de la entrega.
7. **Actividad, solo si `demo-repo` tiene como remoto un repositorio de github.com** (`https://github.com/<owner>/demo-repo`). Si no lo tiene, salta este paso.
   ```bash
   sed 's#guilu/demo-repo#<owner>/demo-repo#g' fixtures/github/pull_request-opened.json > /tmp/gh-c-pr.json
   envia pull_request /tmp/gh-c-pr.json gh-c-pr
   ```
   - En **Actividad** aparece «GitHub <owner>/demo-repo: PR #12 opened (Corrige add() con negativos)».
   - En «Últimas entregas», `pull_request.opened` sale **Procesada** con «1 evento».
8. Deja el entorno como estaba:
   - borra las tres líneas de `deploy/.env`;
   - borra los ficheros: `rm ~/skynet-gh-c.pem /tmp/gh-c-*.json`;
   - vuelve a levantarlo: `docker compose -f deploy/docker-compose.yml --profile app up -d`.

## 4. Con una App real y un túnel (opcional, manual)

Esta parte no la ejecuta Hermes. Es la prueba de punta a punta del hito, con `demo-repo` en GitHub.

1. Crea la App siguiendo Ajustes → GitHub o `docs/github.md`, e instálala **solo** en el repositorio de GitHub de `demo-repo`.
2. Abre el túnel al 8082 (`tailscale funnel 8082`) y pon su URL en el webhook de la App.
3. Configura las tres variables con los valores reales y reinicia.
4. En Ajustes → GitHub:
   - la App sale **Conectada**, con su enlace;
   - **Sincronizar** muestra tu instalación con `demo-repo`;
   - «Últimas entregas» tiene el `ping` de GitHub, Ignorada.
5. Si `demo-repo` aún no tiene URL remota, regístralo otra vez con ella en un proyecto de prueba. Su sección GitHub dice «Con acceso · instalación de <tu usuario>».
6. Abre una PR en el repositorio de GitHub. En unos segundos sale en Actividad y en «Últimas entregas» como Procesada.

## 5. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
