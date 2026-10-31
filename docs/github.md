# GitHub

Skynet se conecta a GitHub con una **GitHub App propia** (hito GH). Con ella, el control plane:

- sabe a qué repositorios llega;
- pide tokens de instalación de corta vida, limitados a un repositorio;
- recibe webhooks firmados y los publica como eventos `github.*`.

Con EN, un workflow entrega su trabajo como una PR: la fase `github-pr` sube la rama y abre la PR (EN-A), la fase `github-check` espera su CI y la corrige (EN-B) y la fase `github-merge` la fusiona tras una puerta, solo con el commit aprobado (EN-C). El recorrido completo está en [`entrega-por-pr.md`](entrega-por-pr.md) y cada fase, en [`workflows.md`](workflows.md). Lanzar workflows desde issues o comentarios llega con DS.

## 1. Crear la App

**Ajustes → GitHub** (`/settings?tab=github`) muestra estos mismos pasos, con un botón para copiar cada valor.

1. En GitHub, ve a **Settings → Developer settings → GitHub Apps → New GitHub App**. Ponle un nombre como `skynet-<tu usuario>` y cualquier URL de inicio.
2. **Webhook:**
   - activo;
   - URL `https://<tu túnel>/api/github/webhooks` (ver §3);
   - content type `application/json`;
   - como secreto, un texto largo al azar (`openssl rand -hex 32`).
3. **Permisos del repositorio**, y ninguno más:

   | Permiso | Nivel | Para qué |
   |---|---|---|
   | Metadata | lectura | obligatorio |
   | Contents | lectura y escritura | subir las ramas de los agentes y borrarlas al fusionar (EN) |
   | Pull requests | lectura y escritura | abrir y fusionar las PR (EN) |
   | Checks | lectura | esperar la CI (EN) |
   | Commit statuses | lectura | la CI que usa statuses (EN) |
   | Actions | lectura | leer los logs de la CI que falla (EN-B) |
   | Issues | lectura y escritura | leer etiquetas y comentar (DS) |

   Se piden ya los permisos de EN y DS para no tener que volver a aceptar la instalación más adelante. **Actions: lectura** llegó con EN-B: si la App ya estaba creada, añádelo en sus permisos y acepta el cambio en la instalación (Settings → Applications). Hasta entonces, la CI roja se corrige solo con las anotaciones. **Sin el permiso `Workflows`**: así ningún agente puede tocar `.github/workflows`.
4. **Eventos:**
   - Pull request
   - Pull request review
   - Check suite
   - Check run
   - Status
   - Issues
   - Issue comment

   `installation` e `installation_repositories` llegan siempre.
5. Instalable **solo en tu cuenta**. Crea la App y genera una **clave privada** (un `.pem`).
6. **Instálala** solo en los repositorios con los que vaya a trabajar Skynet.

## 2. Configurar Skynet

Añade a `deploy/.env` y reinicia (`docker compose -f deploy/docker-compose.yml --profile app up -d`):

| Variable | Qué es |
|---|---|
| `SKYNET_GITHUB_APP_ID` | el id de la App (en su página de ajustes) |
| `SKYNET_GITHUB_PRIVATE_KEY_FILE` | la ruta al `.pem` en el host; Compose lo monta de solo lectura en la API |
| `SKYNET_GITHUB_WEBHOOK_SECRET` | el secreto del webhook |
| `SKYNET_GITHUB_API_URL` | por defecto `https://api.github.com` |
| `SKYNET_GITHUB_WEB_URL` | el host de las URL remotas; por defecto `https://github.com` |
| `SKYNET_GITHUB_GIT_URL` | adónde sube las ramas el runner; por defecto, `SKYNET_GITHUB_WEB_URL`. Solo las E2E lo cambian, a remotos locales (`file://…`) |
| `SKYNET_WEBHOOK_PORT` | el puerto que solo sirve el webhook (8082) |
| `SKYNET_WEBHOOK_BIND` | dónde escucha ese puerto (por defecto `127.0.0.1`) |

Si falta alguna de las tres primeras, Skynet arranca igual: Ajustes → GitHub dice cuáles faltan y el webhook responde 503.

Con la App configurada, Skynet sincroniza al arrancar las instalaciones y sus repositorios. Después se mantienen al día con los webhooks `installation*` y con el botón **Sincronizar**.

Cada repositorio de Skynet se enlaza solo, por su **URL remota** (`https://github.com/owner/nombre.git` o `git@github.com:owner/nombre.git`, sin distinguir mayúsculas). Su tarjeta, en la página del proyecto, dice si tiene acceso:
- «Con acceso · instalación de `guilu`»;
- «Sin acceso»: hay que instalar la App en ese repositorio y sincronizar;
- sin URL remota de GitHub, no se enlaza.

## 3. El túnel

El control plane vive en tu equipo, así que GitHub no llega a él directamente. nginx abre un **segundo puerto**, `SKYNET_WEBHOOK_PORT`, que solo sirve `POST /api/github/webhooks` y responde 404 a todo lo demás. Ese es el **único** puerto que se abre a internet. Funnel y Cloudflare Tunnel exponen puertos enteros, no rutas: si se expusiera el puerto normal, se verían también el login, la web y la API.

- **Tailscale Funnel:** `tailscale funnel 8082`. La URL del webhook es `https://<equipo>.<tailnet>.ts.net/api/github/webhooks`.
- **Cloudflare Tunnel:** un `ingress` con `service: http://localhost:8082`.

Si el túnel o el equipo se caen, no se pierde nada. Al arrancar, y después cada 10 minutos (`SKYNET_GITHUB_REDELIVERY_INTERVAL`), Skynet pide a GitHub las últimas entregas de la App y vuelve a solicitar las que no llegaron.

## 4. Webhooks

- La firma `X-Hub-Signature-256` (HMAC-SHA256 del cuerpo exacto con el secreto) se compara en tiempo constante. Si no vale, la respuesta es **401** y la entrega no se guarda.
- Cada entrega se guarda una vez, por su `X-GitHub-Delivery`. Una nueva responde **202** y una repetida, **200**, sin procesarse otra vez.
- La entrega se procesa después, en una cola:
  - se reintenta con espera creciente (5 s, 10 s…);
  - si falla 5 veces, queda **Fallida** con el error;
  - las entregas se guardan 30 días (`SKYNET_GITHUB_WEBHOOK_RETENTION`).
- `installation` e `installation_repositories` actualizan las instalaciones.
- Las demás entregas se publican como `github.<evento>.<acción>` (por ejemplo `github.pull_request.opened`) en cada repositorio de Skynet enlazado. El evento lleva un **resumen** (número, título, rama, SHA, conclusión, autor, URL y el texto de un comentario redactado); el cuerpo completo se queda en la entrega. Se ven en **Actividad**.
- Una entrega de un repositorio que Skynet no tiene queda **Ignorada**, con el motivo. Un `ping` también.

Ajustes → GitHub enseña las últimas 50 entregas con su estado y su error (`GET /api/github/deliveries`).

## 5. Seguridad

- La clave privada se lee del fichero. **Nunca** va a la base de datos ni sale por la API.
- Los tokens de instalación:
  - viven en memoria hasta 5 minutos antes de caducar;
  - se piden limitados a un repositorio y a los permisos necesarios;
  - nunca se escriben en la base de datos, en los logs ni en los eventos.
- El webhook no lleva sesión: lo protege solo la firma. No cuenta para el bloqueo por intentos fallidos de login.
- Los textos libres que llegan de GitHub (comentarios y revisiones) pasan por el mismo redactor de secretos que los eventos de los agentes.
- El token del push (EN-A) no viaja en la orden del runner: el runner lo pide justo antes y solo para su invocación en curso. Se lo da a git como cabecera (`http.extraHeader` en `GIT_CONFIG_*`), así que no queda en la URL, en los argumentos, en `.git/config` ni en el disco. El título y la descripción de la PR pasan por el redactor antes de guardarse.
- Lo que se lee de una CI que falla (resúmenes, anotaciones y logs) pasa por el redactor antes de llegar al agente. El log se descarga de la URL firmada a la que redirige GitHub, sin mandarle el token.
- Nada de la CI llama al GitHub real:
  - los IT usan `FakeGitHub` (JDK `HttpServer`);
  - las E2E usan `web/e2e/fake-github.ts`;
  - las entregas de ejemplo están en `fixtures/github/`.

## 6. API

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/github` | estado de la App, instalaciones y enlace de cada repositorio |
| `POST` | `/api/github/sync` | vuelve a pedir las instalaciones. Da 409 si la App no está configurada y 502 si GitHub falla |
| `GET` | `/api/github/deliveries?limit=` | últimas entregas, sin el cuerpo (`limit` de 1 a 200) |
| `POST` | `/api/github/webhooks` | el webhook, sin sesión y firmado |
| `GET` | `/api/workflow-runs/{id}/pull-requests` | la PR que ha abierto la ejecución: rama, estado, número, URL y commit |
| `GET` | `/api/workflow-runs/{id}/ci-checks` | la espera de la CI de cada fase `github-check`: estado, commit, ronda y cada check |
| `POST` | `/api/runner/agent-runs/{id}/push-token` | solo el runner de un push en curso: su token, limitado al repositorio y a `contents: write` |
