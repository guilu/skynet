# Skynet

Plataforma web de control, ejecución, observabilidad y auditoría para flujos agénticos de desarrollo de software.

## Documentación

La definición funcional y técnica inicial se encuentra en:

- [`docs/agentic-orchestration-system.md`](docs/agentic-orchestration-system.md)
- [`docs/implementation-plan.md`](docs/implementation-plan.md): plan por hitos y lo implementado en cada uno.
- [`docs/mvp-checklist.md`](docs/mvp-checklist.md): los 12 criterios del MVP y cómo comprobarlos con Claude.
- [`docs/ui.md`](docs/ui.md): guía de estilo de la interfaz (tokens, componentes, movimiento, accesibilidad y capturas).
- [`docs/github.md`](docs/github.md): conectar Skynet con una GitHub App propia (permisos, variables, el túnel para los webhooks y la seguridad).
- [`docs/arranque-local.md`](docs/arranque-local.md): empezar de cero en local y los workflows iniciales, con qué proveedor y modelo hace cada fase.

## Arquitectura prevista

- **Backend:** Java 21, Spring Boot, PostgreSQL, Flyway, Spring Security y REST/SSE.
- **Frontend:** React, TypeScript, Vite, React Flow, TanStack Query y Monaco Editor.
- **Runner local:** Java, `ProcessBuilder`, NDJSON, supervisión de procesos y `git worktree`.
- **Infraestructura inicial:** aplicación web, API, PostgreSQL y runner local mediante Docker Compose; Temporal queda como evolución opcional.

El workflow y su estado pertenecen al orquestador; los agentes ejecutan trabajo y devuelven resultados estructurados que deben verificarse de forma independiente.

## Estructura

| Directorio | Contenido |
|---|---|
| `protocol/` | Contrato compartido entre control plane y runner (estados, eventos, órdenes) |
| `control-plane/` | API Spring Boot (monolito modular con Spring Modulith) |
| `runner/` | Daemon local que ejecuta agentes en worktrees aislados |
| `tools/fake-claude/` | Sustituto determinista del CLI de Claude que reproduce `fixtures/claude/` |
| `web/` | Frontend React + Vite |
| `build-logic/` | Convenciones de Gradle compartidas |
| `deploy/` | Docker Compose para desarrollo |

## Desarrollo

Requisitos: JDK 21, Node 22 y Docker.

```bash
# PostgreSQL
docker compose -f deploy/docker-compose.yml up -d

# Build y tests de Java (incluye formato con Spotless; los tests con Testcontainers necesitan Docker)
./gradlew build
./gradlew spotlessApply   # formatear

# Control plane en :8080 (sin SKYNET_ADMIN_PASSWORD, genera una contraseña en data/admin-password)
SKYNET_ADMIN_PASSWORD=<contraseña> ./gradlew :control-plane:bootRun

# Web en :5173 (redirige /api y /actuator a :8080)
cd web && npm ci && npm run dev
```

### Tests de integración

Los tests del control plane (`*IT`) arrancan la aplicación contra PostgreSQL. Usan Testcontainers si hay Docker; si no, una base de datos existente indicada por variables de entorno (los tests **vacían sus tablas**, así que debe ser una base de datos exclusiva para tests):

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
SKYNET_TEST_DB_USER=skynet SKYNET_TEST_DB_PASSWORD=skynet ./gradlew build
```

Sin Docker ni `SKYNET_TEST_DB_URL`, los tests de integración se omiten.

### E2E

`scripts/e2e.sh` compila y arranca el control plane y un runner con fake-claude, sirve la web con `vite preview` y ejecuta Playwright (`web/e2e/`): crea proyecto, repositorio y trabajo, lanza un agente, sigue herramientas y mensajes en vivo, corta la conexión del navegador y comprueba que al reconectar no se pierde ni se repite nada, que termina con coste, que la pestaña Artefactos muestra el archivo modificado, su diff y el commit, que la verificación del repositorio pasa y se puede reejecutar, y que un mensaje continúa la conversación en una invocación nueva. Otra prueba cancela un agente a mitad y comprueba que no queda ningún proceso de fake-claude; dos más reinician el control plane a mitad de una ejecución (que termina bien) y matan el runner con `kill -9` (el agente acaba «Fallida» y no queda ningún proceso), con `scripts/e2e-service.sh`; y otra comprueba el login: una contraseña incorrecta no entra, «Salir» cierra la sesión y una petición sin token CSRF se rechaza. `web/e2e/review.e2e.ts` pasa axe (WCAG 2.1 A y AA) por las páginas principales y comprueba que ningún secreto llega al DOM: `scripts/e2e.sh` mete dos secretos falsos (`E2E_SECRET_GITHUB`, `E2E_SECRET_AWS`) en el repositorio de prueba, uno en `calc.py` y otro en la salida de `check.sh`. Necesita un PostgreSQL en `localhost:5432` (el de `docker compose`) y Chromium para Playwright:

```bash
docker compose -f deploy/docker-compose.yml up -d
(cd web && npm ci && npx playwright install chromium)
./scripts/e2e.sh                    # logs en build/e2e/
```

En CI corre en su propio workflow (`.github/workflows/e2e.yml`), con PostgreSQL como servicio. Las pruebas de más de ~2 minutos llevan la etiqueta `@lento` (aprobaciones, «Revisión en paralelo», revisión con Codex y Antigravity, «Especificar, planificar e implementar»). En una PR corren solo las rápidas, en 6 shards, y solo si la PR toca algo que afecta a las E2E (código, `fixtures/`, Gradle o los scripts de E2E; no `docs/`, `deploy/`, `workflows/` ni los `.md`). Las `@lento`, en 4 shards, corren en cada push a `main`, a mano (Actions → E2E → Run workflow) o en una PR con la etiqueta `e2e-completo`. Lo decide `scripts/ci/e2e-alcance.sh` y el check `E2E` resume el resultado: verde también cuando no tocaba lanzarlas. En local, `./scripts/e2e.sh --grep-invert @lento` corre solo las rápidas.

### Contratos entre backend y web

`fixtures/contracts/` guarda el JSON de referencia de cada vista de lectura de la API. `ContractIT` comprueba que el backend lo produce tal cual y `web/src/contracts.test.ts` que los tipos de `web/src/api.ts` tienen las mismas claves. Tras un cambio intencionado de una vista, regenera los ficheros y actualiza los tipos:

```bash
./gradlew :control-plane:test --tests '*ContractIT' -Dskynet.contracts.update=true
```

### Aplicación completa con Docker

```bash
cp deploy/.env.example deploy/.env      # rellena SKYNET_ADMIN_PASSWORD, SKYNET_DB_PASSWORD y SKYNET_RUNNER_REGISTRATION_TOKEN
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

`deploy/.env.example` explica cada variable; `docker compose` lee `deploy/.env` solo (también valen variables de entorno). Los datos quedan en los volúmenes `postgres-data` y `artifacts-data`, los logs de los contenedores rotan (5 × 10 MB por servicio) y los servicios vuelven a arrancar solos tras un reinicio de Docker. PostgreSQL solo escucha en `127.0.0.1`.

Con la aplicación levantada, `scripts/compose-smoke.sh` comprueba la instalación de punta a punta: arranca un runner en el host con fake-claude, que se registra a través de la web, lanza un agente en un repositorio de juguete y espera a que termine «Completada» con su diff subido. CI lo ejecuta en cada cambio. Usa una instalación recién levantada: con otros runners conectados, el agente podría ir a uno de ellos.

```bash
SKYNET_ADMIN_PASSWORD=<contraseña> SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> scripts/compose-smoke.sh
```

| Servicio | URL | Variable para cambiar el puerto |
|---|---|---|
| Web (nginx; redirige `/api` y `/actuator` a la API) | http://localhost:8081 | `SKYNET_WEB_PORT` |
| API | http://localhost:8080/actuator/health | `SKYNET_API_PORT` |
| PostgreSQL | localhost:5432 | `SKYNET_DB_PORT` |
| Webhooks de GitHub (solo `POST /api/github/webhooks`; el único que se abre con el túnel) | http://127.0.0.1:8082 | `SKYNET_WEBHOOK_PORT` |

**Acceso.** La web pide usuario y contraseña: `SKYNET_ADMIN_USER` (por defecto `admin`) y `SKYNET_ADMIN_PASSWORD`, obligatoria con Docker Compose. Fuera de Docker, sin contraseña, el control plane genera una al arrancar y la guarda en `data/admin-password` (`SKYNET_ADMIN_PASSWORD_FILE`), legible solo por su usuario; el log dice dónde está, nunca la contraseña. Tras 10 fallos seguidos de login, HTTP Basic o registro del runner desde un mismo origen, ese origen espera 5 minutos (429 con `Retry-After`; `SKYNET_MAX_FAILED_ATTEMPTS` y `SKYNET_LOCKOUT`). La sesión caduca tras `SKYNET_SESSION_TIMEOUT` (12 h) sin uso; detrás de HTTPS, pon `SKYNET_SECURE_COOKIES=true`. Los scripts pueden llamar a la API con HTTP Basic (`curl -u admin:<contraseña> …`), sin cookies ni token CSRF. La API del runner usa su propio token y no necesita usuario. CORS está cerrado: la web va por el mismo origen; para abrirlo a otros dominios, `SKYNET_CORS_ORIGINS` (separados por comas).

Credenciales de la base de datos: `SKYNET_DB_USER` / `SKYNET_DB_PASSWORD`. Con el perfil `app` la contraseña es obligatoria (la API no arranca sin ella); solo PostgreSQL y el control plane fuera de Docker usan `skynet`/`skynet`, con PostgreSQL escuchando únicamente en este equipo. La web va en nginx sin privilegios, con CSP, `nosniff`, `X-Frame-Options: DENY` y `Referrer-Policy: no-referrer`. Los artefactos (diff, logs, informes de tests) se guardan en el volumen `artifacts-data`; fuera de Docker, en `SKYNET_ARTIFACTS_DIR` (por defecto `data/artifacts`), con un máximo por artefacto de `SKYNET_ARTIFACT_MAX_SIZE` (20 MB) y por subida de `SKYNET_ARTIFACT_MAX_UPLOAD` (64 MB). El comando de verificación tiene un tiempo máximo de `SKYNET_VERIFICATION_TIMEOUT` (30 min). Si un runner no confirma que ha parado un agente cancelado en `SKYNET_CANCEL_TIMEOUT` (70 s), el control plane lo da por cancelado. Para que un runner pueda registrarse, define `SKYNET_RUNNER_REGISTRATION_TOKEN` con un secreto compartido; sin él, el registro de runners está desactivado. Para parar: `docker compose -f deploy/docker-compose.yml --profile app down` (añade `-v` para borrar los datos). El runner no va en contenedor: se ejecuta en el host porque necesita `claude` y los repositorios.

**GitHub.** Con una GitHub App propia (`SKYNET_GITHUB_APP_ID`, `SKYNET_GITHUB_PRIVATE_KEY_FILE` y `SKYNET_GITHUB_WEBHOOK_SECRET`), Skynet:
- sabe a qué repositorios llega;
- pide tokens de instalación limitados a un repositorio;
- recibe webhooks firmados y los publica como eventos `github.*`.

Ajustes → GitHub explica cómo crear la App y muestra sus instalaciones, el acceso de cada repositorio y las últimas entregas. Sin esas variables todo funciona igual, sin GitHub. Los detalles están en [`docs/github.md`](docs/github.md).

### Probar la aplicación

Con la aplicación levantada, abre la web. La navegación lateral da acceso al **Dashboard** (lo que requiere atención: ejecuciones activas, fallos recientes, agentes sin actividad y runners sin latido; y al pie, las métricas de las últimas 24 horas, 7 o 30 días, con cada cifra enlazada a la lista de ejecuciones filtrada), **Proyectos**, **Workflows**, **Ejecuciones** (filtrables por estado), **Runners** y **Actividad**. El tema claro/oscuro sigue al sistema o se elige arriba a la derecha.

1. **Proyectos** → crea un proyecto (p. ej. clave `TKM`).
2. En el proyecto, **registra un repositorio** (ruta absoluta en la máquina del runner) y **crea un trabajo** (`TKM-1`).
3. En el trabajo, **Lanzar workflow**: elige el workflow (por defecto `adhoc`, un agente con el prompt que escribas), rellena sus datos y, si quieres, el proveedor y el modelo del agente (Claude Code, Codex o Antigravity CLI) y límites de turnos, presupuesto o tiempo (vacíos = valores por defecto). Se crea la ejecución con todas sus fases y cada agente queda **en cola** hasta que un runner conectado (ver abajo) lo recoge, crea un worktree y ejecuta Claude Code en él. Para un workflow propio, mira el ejemplo de abajo.
4. La ejecución abre con una **cabecera operativa**: estado, duración, agente y herramienta en curso, runner, tokens (con caché), coste y estado de la conexión en vivo, con el workflow que sigue y el botón **Cancelar agente** (o **Cancelar ejecución** si tiene varias fases), que pide confirmación. Cancelar mata el agente y todos sus procesos (en Linux, su grupo de procesos entero). Debajo hay tres paneles:
   - **Fases** y sus agentes; al elegir uno se abre en el inspector (por defecto, el que está en curso). Una reanudación, un reintento o un fork enlaza con el agente del que parte.
   - **Inspector** con pestañas: Resumen (modelo, sesión, rama, tokens, coste y respuesta final), Prompt, Conversación, Herramientas (cada llamada con su entrada, su salida y su duración) y Evento original (el evento guardado, leído de la API). Los textos largos salen recortados con «Ver completo». Las flechas recorren las pestañas.
   - **Timeline en vivo** (SSE), agrupado: cada herramienta es una entrada con su inicio y su fin, y las llamadas seguidas a la misma herramienta se agrupan («Leídos 14 ficheros»). Cada entrada se abre para ver sus eventos originales, y al elegir uno se abre en el inspector. Se filtra por fase, agente, tipo, severidad y origen, y sigue lo último que llega salvo que hayas elegido un evento.

   **Conversación** muestra la sesión entera: cada invocación (lanzamiento, reanudaciones) con su estado, su coste, tu mensaje y las respuestas del agente. Al pie, cuando la última invocación ha terminado, una caja de mensaje continúa la sesión (`--resume`) en el mismo worktree y runner, que se indican; enviar crea una ejecución nueva enlazada y te lleva a ella. Sobre las pestañas, un agente terminado ofrece **Reintentar…** (mismo prompt y límites, worktree y sesión nuevos) y **Bifurcar…** (una copia de la conversación con tu mensaje, en un worktree nuevo desde el estado del original). Las dos explican su alcance antes de confirmar. Si la acción no es posible (otra invocación en curso en el worktree, sesión sin arrancar), se muestra el motivo.

   Con el teclado: ↑/↓ recorren los agentes y las filas del timeline, → y ← abren y cierran una entrada, y las flechas cambian de pestaña en el inspector.

   El agente, la pestaña, el evento elegido y los filtros van en la URL (`?agent=…&tab=tools&seq=123&f.kind=tool`), así que se puede compartir el enlace o recargar sin perder la vista. Los secretos reconocibles (claves de API, tokens, contraseñas) se guardan como `[REDACTED]`.
5. **Actividad** muestra todos los eventos del sistema en tiempo real; los de una ejecución abren su inspector. Si recargas o se corta la conexión, el stream continúa desde el último evento recibido. Cada cliente del stream tiene su propia cola (`skynet.events.subscriber-queue`, 1000 eventos): si se queda atrás, el servidor cierra su conexión y el navegador se pone al día desde el histórico, sin frenar a los demás.

### Empezar de cero

`scripts/reset-local.sh` borra la base de datos y los artefactos (con `--runner`, también los worktrees y las ramas `skynet/*` que dejó el runner), vuelve a levantar Skynet y publica los **workflows iniciales** de [`workflows/`](workflows). Pide escribir «borrar»; `--dry-run` solo dice lo que borraría. Para publicar los workflows sin borrar nada: `scripts/seed-workflows.sh`. Reparten el trabajo entre Claude Code, Codex y Antigravity CLI, con los modelos de `workflows/modelos.env`: ver [`docs/arranque-local.md`](docs/arranque-local.md).

### Un workflow de ejemplo

En **Workflows → Nuevo workflow**, pega este YAML y publícalo. Revisa y prueba en paralelo, y después corrige en el worktree de la revisión con un agente que solo puede leer y editar:

```yaml
id: revisar-y-corregir
name: Revisar y corregir
description: Revisión y tests en paralelo; después, la corrección.

inputs:
  objetivo:
    type: string
    required: true
    description: Qué hay que revisar.

agents:
  corrector:
    tools: [Read, Edit]
    limits:
      maxTurns: 20

stages:
  - id: revision
    type: agent
    prompt: "Revisa {{inputs.objetivo}} en {{workItem.key}} y anota los problemas en REVIEW.md"
  - id: tests
    type: agent
    prompt: "Escribe tests que cubran {{inputs.objetivo}}"
    workspace: isolated-worktree
  - id: correccion
    type: agent
    agent: corrector
    prompt: "Corrige lo que dice REVIEW.md"
    dependsOn: [revision, tests]
    workspaceFrom: revision
```

Al lanzarlo desde un trabajo, la web pide `Objetivo` y enseña lo que se le permitirá a cada fase. En la ejecución, `correccion` sale desde el principio «Pendiente» con «Espera a revision y tests», y arranca cuando las dos terminan. El formato completo está en [`docs/workflows.md`](docs/workflows.md).

### Runner local

El runner se ejecuta en la máquina donde están los repositorios y `claude`. Se conecta al control plane (no hace falta abrir puertos) y guarda su estado en `~/.skynet-runner` (journal SQLite, logs NDJSON y worktrees). Necesita Java 21, `git` y al menos un CLI de agentes: Claude Code con sesión iniciada (`claude` y después `/login`) o `ANTHROPIC_API_KEY`, o Codex (`codex login` o `OPENAI_API_KEY`) para los agentes con `provider: codex`, o Antigravity CLI (`agy` con la sesión de Google iniciada) para los de `provider: antigravity`.

```bash
./gradlew :runner:installDist
SKYNET_URL=http://localhost:8080 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<el mismo secreto que el control plane> \
runner/build/install/skynet-runner/bin/skynet-runner
```

#### Runner con CLIs reales

El runner usa los CLIs reales siempre que no apuntes `SKYNET_CLAUDE_BIN`, `SKYNET_CODEX_BIN` o `SKYNET_AGY_BIN` a los simuladores. Para tener los tres:

1. Instala cada CLI y comprueba, con el usuario del runner, que responde y tiene sesión: `claude --version` (y `/login` dentro de `claude`), `codex --version` (y `codex login`), `agy --version` (y entra con tu cuenta de Google al abrir `agy`).
2. Quita de tu entorno o de `runner.env` los `SKYNET_*_BIN` que apunten a `fake-claude`, `fake-codex` o `fake-agy`, y las variables `FAKE_*`. Si los CLIs no están en el `PATH` del servicio, añade sus directorios o apunta cada `SKYNET_*_BIN` a su ejecutable real.
3. Reinicia el runner. Al conectarse escribe `proveedores: claude-code 2.1.x, codex 0.1xx, antigravity 1.4.x`, y en **Runners** salen sin la píldora «Simulado». Si uno falta, su `--version` no respondió: pruébalo a mano con el usuario del runner.

Para volver a los simuladores (probar sin gastar cuota), apunta las tres variables a `tools/fake-claude/build/install/fake-claude/bin/fake-claude`, `fake-codex` y `fake-agy` y reinicia: la web vuelve a marcarlos como «Simulado». Qué admite cada CLI y cómo se traducen los permisos: [`docs/providers.md`](docs/providers.md).

#### Instalar el runner como servicio

Para que arranque solo con el equipo, instálalo en `~/.local/share/skynet-runner` con su configuración en `~/.config/skynet-runner/runner.env`. Los ficheros de ejemplo están en `deploy/runner/`.

```bash
./gradlew :runner:installDist
mkdir -p ~/.local/share ~/.config/skynet-runner
rm -rf ~/.local/share/skynet-runner
cp -R runner/build/install/skynet-runner ~/.local/share/
cp deploy/runner/skynet-runner.sh ~/.local/share/skynet-runner/
cp -n deploy/runner/runner.env.example ~/.config/skynet-runner/runner.env
chmod 600 ~/.config/skynet-runner/runner.env    # y rellénalo: SKYNET_URL, el secreto y el PATH
~/.local/share/skynet-runner/skynet-runner.sh  # prueba en primer plano; Ctrl+C cuando se registre
```

Un servicio no hereda el `PATH` de tu shell: pon en `runner.env` el directorio de cada CLI de agentes (`dirname $(command -v claude)`, y lo mismo con `codex` y `agy`), el de `git` y el de `java` (o `JAVA_HOME`). Después:

- **Linux (systemd, servicio de usuario):**
  ```bash
  mkdir -p ~/.config/systemd/user
  cp deploy/runner/skynet-runner.service ~/.config/systemd/user/
  systemctl --user daemon-reload
  systemctl --user enable --now skynet-runner
  loginctl enable-linger "$USER"      # sigue corriendo sin sesión abierta
  journalctl --user -u skynet-runner -f
  ```
- **macOS (launchd):**
  ```bash
  sed "s/TU_USUARIO/$USER/g" deploy/runner/dev.skynet.runner.plist > ~/Library/LaunchAgents/dev.skynet.runner.plist
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.skynet.runner.plist
  tail -f ~/Library/Logs/skynet-runner.log
  ```
  Parar: `launchctl bootout gui/$(id -u)/dev.skynet.runner`. Reiniciar: `launchctl kickstart -k gui/$(id -u)/dev.skynet.runner`.

Para actualizarlo, repite los cinco primeros comandos de la instalación y reinicia el servicio: la configuración y el estado (`~/.skynet-runner`, con su token) se conservan. Si paras el servicio con agentes en curso, al volver acaban en «Fallida» («El runner se reinició durante la ejecución»).

| Variable | Por defecto | Uso |
|---|---|---|
| `SKYNET_URL` | `http://localhost:8080` | URL del control plane |
| `SKYNET_RUNNER_REGISTRATION_TOKEN` | — | Secreto de registro; solo hace falta la primera vez (luego usa el token guardado) |
| `SKYNET_RUNNER_NAME` | nombre del host | Nombre del runner |
| `SKYNET_RUNNER_CAPACITY` | `2` | Agentes simultáneos |
| `SKYNET_RUNNER_HOME` | `~/.skynet-runner` | Journal, logs y worktrees |
| `SKYNET_CLAUDE_BIN` | `claude` | Ejecutable de Claude Code (o fake-claude para probar) |
| `SKYNET_CODEX_BIN` | `codex` | Ejecutable de Codex (o fake-codex para probar) |
| `SKYNET_AGY_BIN` | `agy` | Ejecutable de Antigravity CLI (o fake-agy para probar) |
| `SKYNET_PROVIDERS` | — | Proveedores de agentes que declara el runner, separados por comas (p. ej. `claude-code,codex,antigravity`). Sin definir, los que respondan a `--version` |
| `SKYNET_AGENT_ENV` | — | Variables extra que hereda el agente, separadas por comas |
| `SKYNET_MODEL_PRICES` | — | Fichero que amplía la tabla de precios con la que se estima el coste (ver abajo) |
| `SKYNET_LOG_RETENTION_DAYS` | `7` | Días que se conservan los logs locales (`SKYNET_RUNNER_HOME/logs`); ya están subidos como artefactos |

Al arrancar, el runner prueba `--version` de cada CLI de agentes que conoce (Claude Code con `SKYNET_CLAUDE_BIN`, Codex con `SKYNET_CODEX_BIN` y Antigravity CLI con `SKYNET_AGY_BIN`) y declara al control plane los que responden, al registrarse y en cada latido; los ves con su versión en **Runners**. Un agente solo se entrega a un runner que tiene su proveedor; si ninguno en línea lo tiene, se queda en cola y el Dashboard lo avisa en «Necesita tu atención». `SKYNET_PROVIDERS` fija la lista a mano, también si un CLI no responde bien a `--version`. Los comandos de las fases `command` van a cualquier runner.

En **Runners**, «Revocar token…» invalida el token de un runner al momento. Si es el tuyo, se vuelve a registrar solo con `SKYNET_RUNNER_REGISTRATION_TOKEN`; quien tenga solo el token robado se queda fuera. Si el secreto de registro también se ha filtrado, cámbialo en el control plane y en tus runners.

El agente solo hereda una lista corta de variables (`PATH`, `HOME`, idioma, proxy y las de su proveedor: `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL` y `CLAUDE_CONFIG_DIR` para Claude Code; `OPENAI_API_KEY`, `CODEX_API_KEY`, `OPENAI_BASE_URL` y `CODEX_HOME` para Codex; Antigravity no necesita ninguna propia) más las de `SKYNET_AGENT_ENV`. La política del repositorio puede reducir estas últimas, nunca ampliarlas. El comando de verificación del repositorio se ejecuta con ese mismo entorno: si necesita `JAVA_HOME`, `GRADLE_USER_HOME` o similares, añádelas a `SKYNET_AGENT_ENV`. Los artefactos pendientes de subir se guardan en `SKYNET_RUNNER_HOME/artifacts`. Para probar sin gastar, usa fake-claude:

```bash
./gradlew :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE FAKE_CLAUDE_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

El runner hace cumplir el presupuesto de cada invocación: estima su coste según llegan los tokens y la termina si se pasa («Presupuesto agotado»). Trae los precios de los modelos de Claude, de los `gpt-5*` de OpenAI y de los `gemini-2.5-*` y `gemini-3*` de Google (para Codex y Antigravity, solo si el agente fija `model`: si no, no se sabe qué modelo usa); para uno que no esté (se avisa en el fin del proceso y no cuenta para el presupuesto), crea un fichero con líneas `prefijo = entrada, salida, lectura de caché`, en US$ por millón de tokens, y apúntalo con `SKYNET_MODEL_PRICES`:

```text
# Modelo propio: 3 US$ entrada, 15 salida, 0,30 lectura de caché
mi-modelo = 3, 15, 0.30
```

**Worktrees y recuperación.** Cada invocación trabaja en un worktree de `SKYNET_RUNNER_HOME/workspaces`. Se conservan para poder continuar, bifurcar o verificar, y se eliminan solos cuando pasan `SKYNET_WORKTREE_RETENTION` (variable del control plane, 7 días por defecto) desde que terminó lo último que los usó; también a mano, con «Eliminar worktree…» en el agente. La rama se conserva en el repositorio. Si el runner se reinicia a mitad de una invocación, al arrancar mata el proceso huérfano y el agente acaba en «Fallida» («El runner se reinició durante la ejecución»). Si el runner deja de declarar en sus latidos una invocación que confirmó (por ejemplo, porque se borró su journal), el control plane la da por perdida tras dos latidos: «El proceso ya no existe en el runner».

### Política de los agentes

Cada repositorio tiene una política de agentes (en el proyecto, «Política de agentes»): herramientas permitidas (`Bash(git:*)` mejor que `Bash`), modo de permisos, variables de entorno y máximos de turnos, presupuesto y tiempo. Sin política propia se usa la global (`skynet.agent.*`). Al lanzar se ven la política y sus máximos; los límites se pueden bajar, no subir.

### fake-claude

```bash
./gradlew :tools:fake-claude:installDist
FAKE_CLAUDE_FIXTURE=02-tools tools/fake-claude/build/install/fake-claude/bin/fake-claude \
  -p "..." --output-format stream-json --session-id <uuid>
```

Variables: `FAKE_CLAUDE_FIXTURE` (nombre de fixture o ruta), `FAKE_CLAUDE_RESUME_FIXTURE` y `FAKE_CLAUDE_FORK_FIXTURE` (con `--resume`, por defecto `03-resume` y `04-fork`), `FAKE_CLAUDE_DELAY_MS` (pausa entre líneas), `FAKE_CLAUDE_APPLY` (`1` para aplicar de verdad los `Edit`, `Write` y `Bash` de la fixture en el directorio de trabajo), `FAKE_CLAUDE_HANG` (`false` para no quedarse esperando en fixtures sin `result`) y `FAKE_CLAUDE_STRUCTURED_OUTPUT` (con `--json-schema`, el JSON que devuelve como salida estructurada, o `@fichero` para leerlo de ese fichero del directorio de trabajo; sin ella no devuelve ninguna). Con `CLAUDE_CONFIG_DIR` guarda las sesiones como el CLI real y `--resume` falla si no encuentra la sesión. El formato del stream está documentado en [`docs/claude-code-stream-json.md`](docs/claude-code-stream-json.md).

El mismo módulo instala `bin/fake-codex`, que imita `codex exec --json` con los fixtures de `fixtures/codex` (`FAKE_CODEX_FIXTURE`, `FAKE_CODEX_RESUME_FIXTURE`, `FAKE_CODEX_DELAY_MS`, `FAKE_CODEX_HANG`, `FAKE_CODEX_STRUCTURED_OUTPUT`). Arráncalo con `SKYNET_CODEX_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-codex`. Cómo traduce Skynet los modos de permisos, los eventos y el coste de Codex: [`docs/codex-exec-json.md`](docs/codex-exec-json.md). Qué admite cada proveedor, cómo se traducen los permisos y qué credenciales usa: [`docs/providers.md`](docs/providers.md).

Y `bin/fake-agy`, que imita `agy --output-format stream-json` con las sesiones grabadas en `fixtures/agy` (`FAKE_AGY_FIXTURE`, `FAKE_AGY_RESUME_FIXTURE`, `FAKE_AGY_DELAY_MS`, `FAKE_AGY_HANG`, `FAKE_AGY_STRUCTURED_OUTPUT`, `FAKE_AGY_HOME`) y responde a `/usage`: `SKYNET_AGY_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-agy`. El formato y cómo se traduce: [`docs/antigravity-stream-json.md`](docs/antigravity-stream-json.md).
