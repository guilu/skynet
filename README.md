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

# Control plane en :8080
./gradlew :control-plane:bootRun

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

### Contratos entre backend y web

`fixtures/contracts/` guarda el JSON de referencia de cada vista de lectura de la API. `ContractIT` comprueba que el backend lo produce tal cual y `web/src/contracts.test.ts` que los tipos de `web/src/api.ts` tienen las mismas claves. Tras un cambio intencionado de una vista, regenera los ficheros y actualiza los tipos:

```bash
./gradlew :control-plane:test --tests '*ContractIT' -Dskynet.contracts.update=true
```

### Aplicación completa con Docker

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

| Servicio | URL | Variable para cambiar el puerto |
|---|---|---|
| Web (nginx; redirige `/api` y `/actuator` a la API) | http://localhost:8081 | `SKYNET_WEB_PORT` |
| API | http://localhost:8080/actuator/health | `SKYNET_API_PORT` |
| PostgreSQL | localhost:5432 | `SKYNET_DB_PORT` |

Credenciales de la base de datos: `SKYNET_DB_USER` / `SKYNET_DB_PASSWORD` (por defecto `skynet`/`skynet`). Para que un runner pueda registrarse, define `SKYNET_RUNNER_REGISTRATION_TOKEN` con un secreto compartido; sin él, el registro de runners está desactivado. Para parar: `docker compose -f deploy/docker-compose.yml --profile app down` (añade `-v` para borrar los datos). El runner no va en contenedor: se ejecuta en el host porque necesita `claude` y los repositorios.

### Probar la aplicación (estado actual: M2)

Con la aplicación levantada, abre la web y:

1. **Proyectos** → crea un proyecto (p. ej. clave `TKM`).
2. En el proyecto, **registra un repositorio** (ruta absoluta en la máquina del runner) y **crea un trabajo** (`TKM-1`).
3. En el trabajo, **lanza un agente** con un prompt. Se crea la ejecución con su fase y el agente queda **en cola** hasta que un runner conectado (ver abajo) lo recoge, crea un worktree y ejecuta Claude Code en él. La tarjeta del agente muestra modelo, turnos, tokens, coste y el error si falla; la vista rica del agente llega en M3.
4. En la ejecución verás el **timeline en vivo** (SSE): sesión, herramientas, ficheros modificados, resultado y fin del proceso. Pulsa **Cancelar** para matar el agente y todos sus procesos. Los secretos reconocibles (claves de API, tokens, contraseñas) se guardan como `[REDACTED]`.
5. **Actividad** muestra todos los eventos del sistema en tiempo real. Si recargas o se corta la conexión, el stream continúa desde el último evento recibido.

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

El agente solo hereda una lista corta de variables (`PATH`, `HOME`, idioma, proxy, `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `CLAUDE_CONFIG_DIR`…) más las de `SKYNET_AGENT_ENV`. Para probar sin gastar, usa fake-claude:

```bash
./gradlew :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE FAKE_CLAUDE_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

### fake-claude

```bash
./gradlew :tools:fake-claude:installDist
FAKE_CLAUDE_FIXTURE=02-tools tools/fake-claude/build/install/fake-claude/bin/fake-claude \
  -p "..." --output-format stream-json --session-id <uuid>
```

Variables: `FAKE_CLAUDE_FIXTURE` (nombre de fixture o ruta), `FAKE_CLAUDE_DELAY_MS` (pausa entre líneas) y `FAKE_CLAUDE_HANG` (`false` para no quedarse esperando en fixtures sin `result`). El formato del stream está documentado en [`docs/claude-code-stream-json.md`](docs/claude-code-stream-json.md).
