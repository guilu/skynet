# Skynet

Plataforma web de control, ejecución, observabilidad y auditoría para flujos agénticos de desarrollo de software.

## Documentación

La definición funcional y técnica inicial se encuentra en:

- [`docs/agentic-orchestration-system.md`](docs/agentic-orchestration-system.md)

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

# Control plane en :8080 (sin SKYNET_ADMIN_PASSWORD, genera una contraseña y la escribe en el log)
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

En CI corre como job propio, con PostgreSQL como servicio.

### Contratos entre backend y web

`fixtures/contracts/` guarda el JSON de referencia de cada vista de lectura de la API. `ContractIT` comprueba que el backend lo produce tal cual y `web/src/contracts.test.ts` que los tipos de `web/src/api.ts` tienen las mismas claves. Tras un cambio intencionado de una vista, regenera los ficheros y actualiza los tipos:

```bash
./gradlew :control-plane:test --tests '*ContractIT' -Dskynet.contracts.update=true
```

### Aplicación completa con Docker

```bash
SKYNET_ADMIN_PASSWORD=<contraseña> docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

| Servicio | URL | Variable para cambiar el puerto |
|---|---|---|
| Web (nginx; redirige `/api` y `/actuator` a la API) | http://localhost:8081 | `SKYNET_WEB_PORT` |
| API | http://localhost:8080/actuator/health | `SKYNET_API_PORT` |
| PostgreSQL | localhost:5432 | `SKYNET_DB_PORT` |

**Acceso.** La web pide usuario y contraseña: `SKYNET_ADMIN_USER` (por defecto `admin`) y `SKYNET_ADMIN_PASSWORD`, obligatoria con Docker Compose. Fuera de Docker, sin contraseña, el control plane genera una al arrancar y la escribe en el log. La sesión caduca tras `SKYNET_SESSION_TIMEOUT` (12 h) sin uso; detrás de HTTPS, pon `SKYNET_SECURE_COOKIES=true`. Los scripts pueden llamar a la API con HTTP Basic (`curl -u admin:<contraseña> …`), sin cookies ni token CSRF. La API del runner usa su propio token y no necesita usuario. CORS está cerrado: la web va por el mismo origen; para abrirlo a otros dominios, `SKYNET_CORS_ORIGINS` (separados por comas).

Credenciales de la base de datos: `SKYNET_DB_USER` / `SKYNET_DB_PASSWORD` (por defecto `skynet`/`skynet`). Los artefactos (diff, logs, informes de tests) se guardan en el volumen `artifacts-data`; fuera de Docker, en `SKYNET_ARTIFACTS_DIR` (por defecto `data/artifacts`), con un máximo por artefacto de `SKYNET_ARTIFACT_MAX_SIZE` (20 MB) y por subida de `SKYNET_ARTIFACT_MAX_UPLOAD` (64 MB). El comando de verificación tiene un tiempo máximo de `SKYNET_VERIFICATION_TIMEOUT` (30 min). Para que un runner pueda registrarse, define `SKYNET_RUNNER_REGISTRATION_TOKEN` con un secreto compartido; sin él, el registro de runners está desactivado. Para parar: `docker compose -f deploy/docker-compose.yml --profile app down` (añade `-v` para borrar los datos). El runner no va en contenedor: se ejecuta en el host porque necesita `claude` y los repositorios.

### Probar la aplicación (estado actual: M4)

Con la aplicación levantada, abre la web. La navegación lateral da acceso al **Dashboard** (lo que requiere atención: ejecuciones activas, fallos recientes, agentes sin actividad y runners sin latido; y al pie, las métricas de las últimas 24 horas, 7 o 30 días, con cada cifra enlazada a la lista de ejecuciones filtrada), **Proyectos**, **Workflows**, **Ejecuciones** (filtrables por estado), **Runners** y **Actividad**. El tema claro/oscuro sigue al sistema o se elige arriba a la derecha.

1. **Proyectos** → crea un proyecto (p. ej. clave `TKM`).
2. En el proyecto, **registra un repositorio** (ruta absoluta en la máquina del runner) y **crea un trabajo** (`TKM-1`).
3. En el trabajo, **lanza un agente** con un prompt y, si quieres, límites de turnos, presupuesto o tiempo (vacíos = valores por defecto). Se crea la ejecución con su fase y el agente queda **en cola** hasta que un runner conectado (ver abajo) lo recoge, crea un worktree y ejecuta Claude Code en él.
4. La ejecución abre con una **cabecera operativa**: estado, duración, agente y herramienta en curso, runner, tokens (con caché), coste y estado de la conexión en vivo, con el botón **Cancelar agente**, que pide confirmación. Cancelar mata el agente y todos sus procesos (en Linux, su grupo de procesos entero). Debajo hay tres paneles:
   - **Fases** y sus agentes; al elegir uno se abre en el inspector (por defecto, el que está en curso). Una reanudación, un reintento o un fork enlaza con el agente del que parte.
   - **Inspector** con pestañas: Resumen (modelo, sesión, rama, tokens, coste y respuesta final), Prompt, Conversación, Herramientas (cada llamada con su entrada, su salida y su duración) y Evento original (el evento guardado, leído de la API). Los textos largos salen recortados con «Ver completo». Las flechas recorren las pestañas.
   - **Timeline en vivo** (SSE), agrupado: cada herramienta es una entrada con su inicio y su fin, y las llamadas seguidas a la misma herramienta se agrupan («Leídos 14 ficheros»). Cada entrada se abre para ver sus eventos originales, y al elegir uno se abre en el inspector. Se filtra por fase, agente, tipo, severidad y origen, y sigue lo último que llega salvo que hayas elegido un evento.

   **Conversación** muestra la sesión entera: cada invocación (lanzamiento, reanudaciones) con su estado, su coste, tu mensaje y las respuestas del agente. Al pie, cuando la última invocación ha terminado, una caja de mensaje continúa la sesión (`--resume`) en el mismo worktree y runner, que se indican; enviar crea una ejecución nueva enlazada y te lleva a ella. Sobre las pestañas, un agente terminado ofrece **Reintentar…** (mismo prompt y límites, worktree y sesión nuevos) y **Bifurcar…** (una copia de la conversación con tu mensaje, en un worktree nuevo desde el estado del original). Las dos explican su alcance antes de confirmar. Si la acción no es posible (otra invocación en curso en el worktree, sesión sin arrancar), se muestra el motivo.

   Con el teclado: ↑/↓ recorren los agentes y las filas del timeline, → y ← abren y cierran una entrada, y las flechas cambian de pestaña en el inspector.

   El agente, la pestaña, el evento elegido y los filtros van en la URL (`?agent=…&tab=tools&seq=123&f.kind=tool`), así que se puede compartir el enlace o recargar sin perder la vista. Los secretos reconocibles (claves de API, tokens, contraseñas) se guardan como `[REDACTED]`.
5. **Actividad** muestra todos los eventos del sistema en tiempo real; los de una ejecución abren su inspector. Si recargas o se corta la conexión, el stream continúa desde el último evento recibido. Cada cliente del stream tiene su propia cola (`skynet.events.subscriber-queue`, 1000 eventos): si se queda atrás, el servidor cierra su conexión y el navegador se pone al día desde el histórico, sin frenar a los demás.

### Runner local

El runner se ejecuta en la máquina donde están los repositorios y `claude`. Se conecta al control plane (no hace falta abrir puertos) y guarda su estado en `~/.skynet-runner` (journal SQLite, logs NDJSON y worktrees).

```bash
./gradlew :runner:installDist
SKYNET_URL=http://localhost:8080 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<el mismo secreto que el control plane> \
runner/build/install/skynet-runner/bin/skynet-runner
```

| Variable | Por defecto | Uso |
|---|---|---|
| `SKYNET_URL` | `http://localhost:8080` | URL del control plane |
| `SKYNET_RUNNER_REGISTRATION_TOKEN` | — | Secreto de registro; solo hace falta la primera vez (luego usa el token guardado) |
| `SKYNET_RUNNER_NAME` | nombre del host | Nombre del runner |
| `SKYNET_RUNNER_CAPACITY` | `2` | Agentes simultáneos |
| `SKYNET_RUNNER_HOME` | `~/.skynet-runner` | Journal, logs y worktrees |
| `SKYNET_CLAUDE_BIN` | `claude` | Ejecutable de Claude Code (o fake-claude para probar) |
| `SKYNET_AGENT_ENV` | — | Variables extra que hereda el agente, separadas por comas |
| `SKYNET_MODEL_PRICES` | — | Fichero que amplía la tabla de precios con la que se estima el coste (ver abajo) |
| `SKYNET_LOG_RETENTION_DAYS` | `7` | Días que se conservan los logs locales (`SKYNET_RUNNER_HOME/logs`); ya están subidos como artefactos |

En **Runners**, «Revocar token…» invalida el token de un runner al momento. Si es el tuyo, se vuelve a registrar solo con `SKYNET_RUNNER_REGISTRATION_TOKEN`; quien tenga solo el token robado se queda fuera. Si el secreto de registro también se ha filtrado, cámbialo en el control plane y en tus runners.

El agente solo hereda una lista corta de variables (`PATH`, `HOME`, idioma, proxy, `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `CLAUDE_CONFIG_DIR`…) más las de `SKYNET_AGENT_ENV`. La política del repositorio puede reducir estas últimas, nunca ampliarlas. El comando de verificación del repositorio se ejecuta con ese mismo entorno: si necesita `JAVA_HOME`, `GRADLE_USER_HOME` o similares, añádelas a `SKYNET_AGENT_ENV`. Los artefactos pendientes de subir se guardan en `SKYNET_RUNNER_HOME/artifacts`. Para probar sin gastar, usa fake-claude:

```bash
./gradlew :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE FAKE_CLAUDE_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

El runner hace cumplir el presupuesto de cada invocación: estima su coste según llegan los tokens y la termina si se pasa («Presupuesto agotado»). Trae los precios de los modelos de Claude; para uno que no esté (se avisa en el fin del proceso y no cuenta para el presupuesto), crea un fichero con líneas `prefijo = entrada, salida, lectura de caché`, en US$ por millón de tokens, y apúntalo con `SKYNET_MODEL_PRICES`:

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

Variables: `FAKE_CLAUDE_FIXTURE` (nombre de fixture o ruta), `FAKE_CLAUDE_RESUME_FIXTURE` y `FAKE_CLAUDE_FORK_FIXTURE` (con `--resume`, por defecto `03-resume` y `04-fork`), `FAKE_CLAUDE_DELAY_MS` (pausa entre líneas), `FAKE_CLAUDE_APPLY` (`1` para aplicar de verdad los `Edit`, `Write` y `Bash` de la fixture en el directorio de trabajo) y `FAKE_CLAUDE_HANG` (`false` para no quedarse esperando en fixtures sin `result`). Con `CLAUDE_CONFIG_DIR` guarda las sesiones como el CLI real y `--resume` falla si no encuentra la sesión. El formato del stream está documentado en [`docs/claude-code-stream-json.md`](docs/claude-code-stream-json.md).
