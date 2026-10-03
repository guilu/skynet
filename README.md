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
