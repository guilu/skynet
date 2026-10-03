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

### fake-claude

```bash
./gradlew :tools:fake-claude:installDist
FAKE_CLAUDE_FIXTURE=02-tools tools/fake-claude/build/install/fake-claude/bin/fake-claude \
  -p "..." --output-format stream-json --session-id <uuid>
```

Variables: `FAKE_CLAUDE_FIXTURE` (nombre de fixture o ruta), `FAKE_CLAUDE_DELAY_MS` (pausa entre líneas) y `FAKE_CLAUDE_HANG` (`false` para no quedarse esperando en fixtures sin `result`). El formato del stream está documentado en [`docs/claude-code-stream-json.md`](docs/claude-code-stream-json.md).
