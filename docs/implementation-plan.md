# Plan de implementación — Skynet

Este documento traduce la especificación de [`agentic-orchestration-system.md`](agentic-orchestration-system.md) en un plan de trabajo ejecutable: estructura del repositorio, decisiones técnicas, hitos con entregables y criterios de aceptación, y backlog por fases.

---

## 1. Enfoque

- **Vertical primero.** Cada hito termina con algo usable de extremo a extremo (web → API → runner → Claude → web), no con capas aisladas.
- **El MVP (Fase 1) se construye sobre el modelo definitivo.** Aunque en Fase 1 no existan workflows declarativos, cada ejecución se modela como un `WorkflowRun` implícito (`adhoc`) con un único `StageRun`. Así la Fase 2 añade el motor sin migrar datos ni romper la UI.
- **Determinismo verificable desde el día 1.** Un *fake* del CLI de Claude que reproduce NDJSON grabado permite tests de integración y E2E sin coste ni variabilidad.
- **Sin Temporal en Fase 1–2.** PostgreSQL + máquina de estados + tabla de trabajos con `FOR UPDATE SKIP LOCKED` + outbox. La interfaz del motor se aísla para poder sustituirla después.

---

## 2. Decisiones técnicas propuestas

| Tema | Decisión | Motivo |
|------|----------|--------|
| Repositorio | Monorepo | Contrato runner↔API compartido y versionado junto |
| Build Java | Gradle (Kotlin DSL), multi-proyecto | Módulo `protocol` compartido entre API y runner |
| Backend | Java 21, Spring Boot 3, Spring Modulith | Monolito modular con límites entre módulos verificados en tests |
| Persistencia | PostgreSQL 16, Flyway, Spring Data JDBC + SQL explícito (`JdbcClient`) | Sin magia de ORM; control fino para event store, outbox, `SKIP LOCKED` y bloqueo optimista |
| Tiempo real | SSE (`text/event-stream`) con `Last-Event-ID` | Reconexión y *replay* sencillos desde el event store |
| Runner ↔ API | HTTP saliente desde el runner (long-poll para órdenes, POST por lotes para eventos) | El runner puede estar tras NAT; no hay que exponer la máquina de desarrollo |
| Cola local del runner | SQLite embebido (journal de eventos, órdenes y PIDs) | Transaccional; reenvío idempotente y reconciliación tras reinicio |
| Almacenamiento de artefactos | Sistema de ficheros (volumen) detrás de una interfaz `BlobStore` | S3/MinIO más adelante sin cambiar el dominio |
| Frontend | React + TypeScript + Vite, TanStack Query, React Flow, Monaco | Según especificación; Vite por simplicidad (SPA) |
| Auth | Usuario único local con Spring Security (form login) en Fase 1; OIDC después | El MVP es local; no bloquear por auth |
| Runner auth | Token de registro + token por runner (rotable) | Mínimo razonable para un daemon |
| Testing | JUnit 5, Testcontainers, ArchUnit/Modulith; Vitest; Playwright | Integración real con Postgres; E2E con fake Claude |
| CI | GitHub Actions | Build + tests + lint en cada PR |
| Despliegue | Docker Compose (`web`, `api`, `postgres`); runner nativo en el host | El runner necesita el `claude` y los repos del host |

> Decisiones cerradas (2026-10-03): Gradle Kotlin DSL, Spring Data JDBC + SQL, SQLite para el journal del runner y usuario único local en el MVP.

---

## 3. Estructura del repositorio

```text
skynet/
├── protocol/            # DTOs compartidos: eventos normalizados, órdenes, resultado estructurado, JSON Schemas
├── control-plane/       # Spring Boot: API REST + SSE, dominio, motor de workflows, persistencia
│   └── src/main/java/.../
│       ├── project/     # Project, Repository
│       ├── workitem/    # WorkItem
│       ├── workflow/    # WorkflowDefinition/Run, StageRun, máquina de estados, scheduler
│       ├── agent/       # AgentDefinition, AgentRun, estado observable
│       ├── runner/      # registro, heartbeats, asignaciones, ingestión de eventos
│       ├── event/       # event store, outbox, SSE
│       ├── artifact/    # Artifact, BlobStore
│       ├── approval/    # gates humanos (Fase 2)
│       ├── github/      # GitHub App, webhooks (Fase 3)
│       └── cost/        # agregados de tokens/coste
├── runner/              # Daemon Java: supervisor de procesos, worktrees, adapters
│   └── src/main/java/.../
│       ├── supervisor/  # ProcessBuilder, grupos de procesos, timeouts, kill tree
│       ├── workspace/   # git worktree
│       ├── provider/    # AgentProvider + ClaudeCodeProvider (parser NDJSON)
│       ├── command/     # ejecución de comandos de validación (tests)
│       ├── indexer/     # diff, commits, informes JUnit
│       └── transport/   # cliente API, journal local, reenvío
├── web/                 # React + Vite
├── tools/fake-claude/   # CLI falso que reproduce fixtures NDJSON
├── fixtures/claude/     # NDJSON reales grabados (sesiones de ejemplo)
├── deploy/              # docker-compose.yml, Dockerfiles
└── docs/
```

---

## 4. Contratos clave

### 4.1. `AgentProvider` (runner)

```java
interface AgentProvider {
    AgentHandle start(StartRequest req);        // prompt, sessionId, workspace, límites, herramientas permitidas
    AgentHandle resume(ResumeRequest req);      // sessionId + mensaje (+ fork opcional)
    void cancel(AgentHandle h);                 // mata el grupo de procesos completo
    Flow.Publisher<NormalizedEvent> events(AgentHandle h);
    List<ArtifactRef> collectArtifacts(AgentHandle h);
}
```

`sendMessage()` de la especificación se implementa como `resume()` sobre la sesión (sin PTY persistente, según §9.2).

> **Actualizado en el hito PV:** el corte quedó en otro sitio: el proveedor **traduce** (línea de comandos, entorno, parser de su salida, coste y versión) y el runner **ejecuta** (lanzar, supervisar, cancelar, tiempo máximo y artefactos, igual para todos). Hay tres implementaciones: Claude Code, Codex y Gemini CLI. Qué admite cada una, los permisos y las credenciales: [`providers.md`](providers.md).

### 4.2. Eventos normalizados (`protocol`)

Mapeo inicial desde `claude -p --output-format stream-json`:

| NDJSON de Claude | Evento normalizado | Efecto en estado observable |
|---|---|---|
| `system/init` | `agent.session.started` (session_id, modelo, herramientas) | `STARTING → THINKING` |
| `stream_event` (deltas de texto) | `agent.message.delta` (no persistido uno a uno; se agregan) | `THINKING` |
| `assistant` con bloque `text` | `agent.message.received` | `THINKING` |
| `assistant` con bloque `tool_use` | `agent.tool.started` | `EXECUTING` |
| `user` con bloque `tool_result` | `agent.tool.completed` | `THINKING` |
| `result` | `agent.result` (turnos, tokens, coste, is_error) | `COMPLETED` / `FAILED` |
| salida del proceso | `agent.process.exited` (exit code, señal) | terminal |
| sin eventos > umbral | `agent.unresponsive` (calculado en backend) | `UNRESPONSIVE` (informativo) |

Cada evento lleva `eventId` (UUID generado en el runner, clave de idempotencia), `agentRunId`, `seq` local y `occurredAt`. El NDJSON bruto se guarda íntegro como artefacto `LOG` para la vista "log completo".

> **Actualizado tras el spike de M0:** la tabla anterior es la propuesta inicial. El mapeo vigente, con el formato real del NDJSON y los hallazgos que afectan al diseño (coste acumulado por sesión, stdin a `/dev/null`, presupuesto no estricto, cancelación sin `result`), está en [`claude-code-stream-json.md`](claude-code-stream-json.md).

### 4.3. Protocolo runner ↔ control plane

```text
POST /api/runner/register            → runnerId, token
POST /api/runner/heartbeat           → capacidad, procesos vivos
GET  /api/runner/commands?wait=30s   → long-poll: START / RESUME / CANCEL / VERIFY
POST /api/runner/events              → lote de eventos (idempotente por eventId)
POST /api/runner/artifacts           → subida del contenido con sha256 (metadatos en cabecera)
POST /api/runner/commands/{id}/ack
```

Las órdenes se generan desde una tabla `runner_command` (con `FOR UPDATE SKIP LOCKED`), de modo que si el runner se reinicia vuelve a recibir lo no confirmado.

**Implementado (M2, paso 3):**

- `POST /api/runner/register` exige el secreto `skynet.runner.registration-token` (`SKYNET_RUNNER_REGISTRATION_TOKEN`; vacío = registro desactivado) y devuelve un token propio del runner, que se guarda solo como sha256. Registrarse otra vez con el mismo nombre conserva el id y rota el token. El resto de rutas exige `Authorization: Bearer <token>` (401 si falta o no es válido).
- `GET /api/runner/commands?waitSeconds=N` (long-poll, como mucho `skynet.runner.max-wait`, 30 s). Al lanzar un agente se crea un `START` **sin runner**; lo reclama el primer runner con capacidad libre (`capacity` menos sus agentes no terminados), y en la misma transacción el agente y su fase pasan a `STARTING`. Lo entregado y no confirmado con `ack` se vuelve a entregar al mismo runner pasado `skynet.runner.redeliver-after` (30 s).
- Cancelar un agente en cola lo cancela en el acto y retira su `START`. Cancelar uno que ya está en un runner marca `cancel_requested_at` y crea un `CANCEL` para ese runner; el estado `CANCELLED` llega con `agent.process.exited`.
- `POST /api/runner/events` ingiere cada evento en su propia transacción, idempotente por `eventId`, y responde `EventBatchResult` (aceptados, duplicados y rechazados con motivo: agente desconocido o de otro runner). Efectos: `session.started` → `THINKING` (y la fase a `RUNNING`); `tool.started` → `EXECUTING`; `message.received`/`tool.completed` → `THINKING`; `result` guarda turnos, tokens, subtipo y coste (`cost_usd` = acumulado menos el de la invocación padre); `process.exited` (`exitCode`, `signal`) decide el estado final: `CANCELLED` si se pidió, `COMPLETED` con resultado sin error y código 0, `FAILED` en otro caso, y cierra la fase y la ejecución `adhoc`.
- `process.exited` puede traer `error` (el runner no llegó a lanzar el proceso, se agotó el tiempo, se paró o se reinició a mitad): el agente queda `FAILED` con ese error, salvo que se hubiera pedido cancelarlo.

**Implementado (M2, paso 4), daemon del runner (`runner/`):**

- `transport/ControlPlaneClient`: registro, heartbeat, long-poll, `ack` y lotes de eventos sobre `java.net.http`. Un 401 hace que el daemon se registre de nuevo.
- `journal/Journal` (SQLite en `$SKYNET_RUNNER_HOME/journal.db`): credenciales, eventos pendientes con `seq` por invocación, órdenes recibidas (una orden reentregada no se ejecuta dos veces), invocaciones terminadas y PIDs. Un evento solo se borra cuando el control plane responde al lote.
- `supervisor/ProcessSupervisor`: entorno vaciado y rellenado desde una lista permitida, stdin a `/dev/null`, stdout línea a línea al parser y al log bruto (`logs/<agentRunId>.ndjson`), últimos 8 KB de stderr. Terminar = SIGTERM a todo el árbol (descendientes tomados antes de matar al padre), gracia de 10 s y SIGKILL. Al arrancar mata los procesos que dejó vivos un runner anterior (comprobando el instante de arranque para no matar un PID reutilizado).
- `workspace/WorkspaceManager`: `git worktree add -b skynet/<trabajo>/<agente> $SKYNET_RUNNER_HOME/workspaces/<ejecución>/<agente> <rama base>`. Emite `agent.workspace.ready` (ruta, rama, commit base). El worktree se conserva al terminar; se elimina con la orden `CLEANUP` (M6-C).
- `provider/claude/ClaudeCodeProvider`: `claude -p … --output-format stream-json --verbose --include-partial-messages --session-id … --permission-mode … --allowedTools … [--model] [--max-turns] [--max-budget-usd] -- <prompt>` (el prompt al final y tras `--`, por si empieza por `-`).
- `agent/AgentExecutor`: cada `START` termina con exactamente un `agent.process.exited`, también si falla el worktree, si se cancela antes de arrancar, si se agota `limits.timeout` o si el runner se para o se reinicia.
- Pendiente: presupuesto propio en el runner (hace falta la tabla de precios; de momento solo `--max-budget-usd`, que no es estricto). La tabla `workspace` llegó en M4-A y los grupos de procesos en M4-B.

**Implementado (M2, paso 5):**

- `shared/PayloadRedactor`: la ingestión de `POST /api/runner/events` oculta los secretos (`[REDACTED]`) antes de `EventStore.append` y antes de actualizar `agent_run`. Detecta los valores de las variables de entorno del control plane listadas en `skynet.redaction.secret-env` (`SKYNET_REDACTION_SECRET_ENV`; por defecto `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `SKYNET_RUNNER_REGISTRATION_TOKEN`, `SKYNET_DB_PASSWORD`, `GITHUB_TOKEN`, `GH_TOKEN`), formatos de clave conocidos (Anthropic/OpenAI, GitHub, AWS, Slack, Google, JWT, PEM), cabeceras `Authorization`, credenciales en URLs, asignaciones `*_TOKEN=…`/`password: …` y claves del payload con nombre de secreto. Es una red de seguridad: un secreto sin formato reconocible que no esté en el entorno del control plane no se detecta. El prompt que escribe el usuario no se redacta (el agente lo necesita tal cual) y el NDJSON bruto que guarda el runner en disco tampoco (pasará por el redactor cuando se suba como artefacto en M5).
- `RunnerEndToEndIT`: control plane real, runner real en el mismo proceso y fake-claude. Lanzar desde la API deja la secuencia completa en BD (con `runnerSeq` consecutivo) y por SSE hasta `SUCCEEDED`; un corte de red a mitad (proxy TCP) no pierde ni duplica eventos; cancelar desde la API deja `CANCELLED` y ningún proceso del árbol vivo.
- Web: la tarjeta del agente muestra modelo, última actividad, turnos, tokens, coste, código de salida y error, y el timeline resume los eventos `agent.*`.

### 4.4. API para la web (Fase 1)

```text
GET/POST   /api/projects
GET/POST   /api/projects/{id}/repositories
GET/POST   /api/projects/{id}/work-items
POST       /api/work-items/{id}/runs                 # lanza AgentRun (WorkflowRun adhoc)
GET        /api/agent-runs/{id}
POST       /api/agent-runs/{id}/messages             # resume con mensaje
POST       /api/agent-runs/{id}/cancel
POST       /api/agent-runs/{id}/retry                # nuevo AgentRun
POST       /api/agent-runs/{id}/fork
GET        /api/agent-runs/{id}/artifacts
GET        /api/artifacts/{id}/content
```

Implementado en M1:

```text
GET/POST   /api/projects                     GET /api/projects/{id}
GET/POST   /api/projects/{id}/repositories
GET/POST   /api/projects/{id}/work-items     GET /api/work-items/{id}
GET/POST   /api/work-items/{id}/runs         # POST lanza un WorkflowRun adhoc con su agente en cola
GET        /api/workflow-runs/{id}           # ejecución con fases y agentes
GET        /api/agent-runs/{id}              # agente y sus prompts
POST       /api/agent-runs/{id}/cancel
GET        /api/events?workflowRunId=&aggregateId=&types=&after=&limit=
GET        /api/events/stream?workflowRunId=&aggregateId=&types=&after=   # SSE; reanuda con Last-Event-ID
# CO-B: after=latest empieza desde ahora; types= limita los tipos (exactos o prefijos «approval.*»)
```

Añadido en M4-A (cada acción crea una ejecución `adhoc` nueva enlazada al agente padre; la terminada no se modifica):

```text
POST       /api/agent-runs/{id}/messages     # {text}: reanuda la sesión (RESUME) en el mismo worktree y runner
POST       /api/agent-runs/{id}/fork         # {text}: bifurca la sesión (--fork-session) en un worktree nuevo del mismo runner
POST       /api/agent-runs/{id}/retry        # mismo prompt y límites, sesión y worktree nuevos, cualquier runner
GET        /api/agent-runs/{id}/conversation # invocaciones encadenadas de la sesión con sus prompts y mensajes
```

Añadido en M5-A:

```text
PUT        /api/projects/{id}/repositories/{repositoryId}/verification  # {validationCommand, testReportPaths}
GET        /api/agent-runs/{id}/verifications # de la más reciente a la más antigua
POST       /api/agent-runs/{id}/verifications # reejecuta la verificación (409 si el worktree está ocupado o no hay comando)
GET        /api/agent-runs/{id}/artifacts     # ArtifactSummary, sin contenido
GET        /api/artifacts/{id}/content?offset=&limit=   # trozo del contenido; X-Artifact-Size con el total
POST       /api/runner/artifacts              # runner: contenido en el cuerpo, metadatos en X-Artifact-Metadata (JSON en base64url), idempotente
```

Añadido en M6-A (todo lo demás exige sesión con CSRF o HTTP Basic; `/api/runner/**` usa el token del runner):

```text
POST       /api/auth/login                    # {username, password} → {username}; abre la sesión
GET        /api/auth/session                  # {username} o 401
POST       /api/auth/logout                   # 204
POST       /api/runners/{id}/revoke           # invalida el token del runner; 204
```

Añadido en M6-B:

```text
PUT        /api/projects/{id}/repositories/{repositoryId}/agent-policy  # {allowedTools, permissionMode, environment, maxTurns, maxBudgetUsd, timeoutMinutes}
DELETE     /api/projects/{id}/repositories/{repositoryId}/agent-policy  # vuelve a la política global
```

`RepositoryView` incluye `agentPolicy` (la efectiva) y `agentPolicyCustom`. Lanzar con un límite por encima del de la política da 400.

Añadido en M6-C:

```text
POST       /api/agent-runs/{id}/workspace/cleanup   # 202 + AgentRunDetail; 409 si algo usa el worktree
```

`WorkspaceView` incluye `cleanupRequestedAt`, `cleanupError` y `removedAt`. Con el worktree eliminado (o eliminándose), reanudar, bifurcar y verificar dan 409; reintentar sigue funcionando.

Añadido en M6-D:

```text
GET        /api/dashboard/metrics?period=24h|7d|30d&tz=<zona IANA>  # RunMetrics: totales por estado, duración mediana, tokens, coste y tramos por hora (24h) o día
GET        /api/workflow-runs?since=<instante>                       # filtro por fecha de creación, combinable con status y projectId
```

Añadido en AE-A (archivar es reversible; lo archivado es de solo lectura y sale de las listas, del dashboard y de las métricas):

```text
POST       /api/projects/{id}/archive|restore                                 # 409 con ejecuciones en curso
POST       /api/projects/{id}/repositories/{repositoryId}/archive|restore     # 409 con agentes en curso
POST       /api/work-items/{id}/archive|restore                               # 409 con ejecuciones en curso
POST       /api/workflow-runs/{id}/archive|restore                            # solo terminadas
POST       /api/runners/{id}/archive|restore                                  # «olvidar»: invalida el token; 409 con agentes en marcha
GET        …?archived=false|true|all                                          # en proyectos, repositorios, trabajos, ejecuciones y runners; por defecto false
```

Responden `{id, archivedAt}` y son idempotentes. Una ejecución cuenta como archivada si lo está ella, su trabajo o su proyecto; restaurar algo cuyo padre sigue archivado da 409. Un runner olvidado que siga vivo se vuelve a registrar solo y reaparece. Eventos: `project.archived|restored`, `repository.…`, `workitem.…`, `workflow.…` y `runner.…`.

Añadido en AE-B (eliminar no tiene vuelta atrás y solo se admite sobre lo archivado):

```text
GET        …/deletion-preview                                 # {deletable, blockers, warnings, liveWorkspaces, counts}
DELETE     /api/projects/{id}                                 # con sus repositorios, trabajos y ejecuciones
DELETE     /api/projects/{id}/repositories/{repositoryId}     # solo si ningún agente lo usó
DELETE     /api/work-items/{id}                               # con sus ejecuciones
DELETE     /api/workflow-runs/{id}                            # con fases, agentes, prompts, verificaciones, artefactos y eventos
DELETE     /api/runners/{id}                                  # solo olvidado y si nunca ejecutó nada
POST       /api/{projects,work-items,workflow-runs}/{id}/workspaces/cleanup   # pide eliminar los worktrees que lo impiden
```

`DELETE` devuelve lo borrado (`counts`) o 409 con los motivos. Todo va en una transacción: es la única que puede borrar eventos (`SET LOCAL skynet.purge = 'on'`, V11) y deja un evento lápida (`project.purged`, `repository.purged`, `workitem.purged`, `workflow.purged` o `runner.purged`) con lo que se borró. Los blobs que ya no usa nadie se borran después del commit. Lo impiden los worktrees que siguen en su runner y solo usa lo que se elimina (los de un runner olvidado no cuentan). Una sesión que continúa en otra ejecución se conserva, pero pierde el enlace con su origen.

En AE-C la web usa todo esto: menú «⋯» en páginas y filas, aviso en lo archivado, chip «Archivados» en las listas, panel de eliminar con la vista previa y acciones en bloque en la lista de ejecuciones (ver `docs/ui.md`, Patrones).

Los errores siguen RFC 9457 (`ProblemDetail`): 400 validación, 401 sin sesión, 403 sin token CSRF, 404 inexistente, 409 transición no permitida, clave duplicada o conflicto de versión.

---

## 5. Modelo de datos — MVP

Migraciones Flyway iniciales (resto de entidades de §12 en Fase 2):

```text
project(id, key, name, created_at)
repository(id, project_id, name, local_path, remote_url, default_branch)
work_item(id, project_id, key, title, description, type, external_ref, status, created_at)
workflow_definition(id, key, version, source_yaml)            -- en Fase 1 solo 'adhoc'
workflow_run(id, work_item_id, definition_id, status, version, started_at, finished_at)
stage_run(id, workflow_run_id, stage_key, status, attempt, version, started_at, finished_at)
agent_run(id, stage_run_id, parent_agent_run_id, kind[START|RESUME|RETRY|FORK],
          status, observable_status, provider, provider_session_id, runner_id,
          process_id, workspace_id, prompt_id, started_at, last_activity_at,
          finished_at, exit_code, num_turns, input_tokens, output_tokens, cost_usd, error, version)
prompt(id, agent_run_id, role, content, sha256, created_at)
workspace(id, repository_id, path, branch, base_commit, status)
event(id, workflow_run_id, aggregate_type, aggregate_id, event_type,
      payload_json, sequence, source_event_id UNIQUE, occurred_at, recorded_at)
outbox(id, event_id, published_at)
artifact(id, workflow_run_id, stage_run_id, agent_run_id, type, name, uri, sha256, metadata_json, created_at)
runner(id, name, token_hash, status, capacity, last_heartbeat_at)
runner_command(id, runner_id, type, payload_json, status, created_at, acked_at)
```

- `version` → bloqueo optimista en las entidades con transiciones.
- `event.sequence` global y monótona (para timeline y `Last-Event-ID`).
- `source_event_id UNIQUE` → ingestión idempotente.
- Transiciones de estado validadas en el dominio (tabla de transiciones permitidas, test exhaustivo).

**Cambios al implementar M1** (`V2__core_model.sql`):

- **Sin tabla `outbox`.** `event.sequence` es global, sin huecos y en orden de commit: se asigna bloqueando la fila de `event_sequence` hasta el commit. Así, leer `sequence > N` nunca se salta un evento confirmado más tarde, y la propia tabla `event` hace de outbox: un único hilo la recorre con un cursor y difunde a los clientes SSE. Un trigger impide `UPDATE`/`DELETE` sobre `event`.
- **Un único estado en `agent_run`** (`AgentObservableStatus`), con una tabla de transiciones en `protocol`. Los estados activos (`THINKING`, `EXECUTING`, `UNRESPONSIVE`, esperas) se alternan libremente; los terminales no tienen salida.
- **`agent_run.cost_usd_cumulative`** junto a `cost_usd`, porque Claude Code reporta el coste acumulado de la sesión.
- **`agent_run.repository_id`** y sin `prompt_id`: el prompt referencia al agente (`prompt.agent_run_id`).
- **`project.work_item_seq`** para numerar trabajos por proyecto (`TKM-1`, `TKM-2`…).
- `workspace`, `artifact`, `runner` y `runner_command` se crean en las migraciones de M2 y M5, cuando se usan.

---

## 6. Fase 1 — Observabilidad (MVP)

**Estado: terminada el 2026-10-08.** M0–M6 fusionados (M6: PRs #28–#32) y los 12 criterios del MVP comprobados con Claude Code real ([`mvp-checklist.md`](mvp-checklist.md)).

Cada hito incluye los criterios de éxito del MVP (§23 de la especificación) que cubre.

### M0 — Cimientos y spike (≈1 semana)

- Esqueleto Gradle multi-proyecto (`protocol`, `control-plane`, `runner`) y `web` con Vite.
- `deploy/docker-compose.yml` con Postgres; perfil de desarrollo.
- Flyway con migración base; Testcontainers en tests de integración.
- GitHub Actions: build, tests, lint (Spotless/Checkstyle, ESLint/Prettier, `tsc`).
- **Spike Claude Code:** grabar 4–5 sesiones reales (texto simple, con herramientas, con error, cancelada, reanudada) → `fixtures/claude/`.
- `tools/fake-claude`: ejecutable que acepta los mismos flags y reproduce un fixture con tiempos configurables.

**Aceptación:** `./gradlew build` y `npm run build` en verde en CI; fake-claude reproduce los fixtures.

### M1 — Dominio base y event store (≈1–1,5 semanas)

- Entidades y migraciones de §5; servicios `ProjectService`, `WorkItemService`, `AgentService`, `EventService`.
- Máquina de estados de `AgentRun` (y de `StageRun`/`WorkflowRun` simplificada para `adhoc`).
- Event store append-only + outbox + publicador hacia SSE.
- Endpoint SSE con replay desde `Last-Event-ID`.
- API REST de proyectos, repositorios y trabajos.

**Aceptación:** tests de transiciones válidas/inválidas; un evento insertado llega por SSE; reconexión SSE no pierde eventos. **MVP 1, 2.**

### M2 — Runner y adaptador de Claude (≈2 semanas)

- Registro, heartbeats y long-poll de órdenes.
- `WorkspaceManager`: `git worktree add` en `/workspaces/<workflow-run>/<agent-run>/` sobre rama `skynet/<work-item>/<agent-run>`, limpieza según política.
- `ProcessSupervisor`: lanzamiento en grupo de procesos propio, captura de stdout/stderr, timeout, kill del árbol completo, detección de huérfanos al arrancar.
- `ClaudeCodeProvider`: construcción del comando, filtrado de variables de entorno, herramientas permitidas, límites de turnos y presupuesto; parser NDJSON → eventos normalizados (tolerante a líneas desconocidas: se guardan como `agent.raw`).
- Journal local en SQLite + reenvío por lotes con idempotencia.
- Ingestión en el backend: persiste eventos, actualiza `agent_run` (estado observable, `last_activity_at`, tokens, coste, session id).
- Redacción de secretos en la ingestión, antes de `EventStore.append` (`PayloadRedactor`): el event store es append-only, así que un secreto persistido no se puede corregir después ([ADR-0001](adr/0001-control-plane-user-interface.md) §2.1).

**Aceptación:** lanzar un run con fake-claude produce la secuencia completa de eventos en BD; matar la conexión durante el run no pierde eventos; cancelar mata todos los procesos hijos. Prueba manual con `claude` real. **MVP 3, 5, 6, 10.**

### M3 — Control plane de observabilidad en tiempo real (≈2 semanas)

Implementa la Fase A de [ADR-0001](adr/0001-control-plane-user-interface.md) (§6 y la issue propuesta en §10).

- Proyecciones de lectura en backend (`WorkflowRunView`, `StageRunView`, `AgentRunView`, `RunnerView`, `DashboardSummary` mínimo): actividad actual, sesión, tokens, coste y timestamps. El frontend no infiere estado.
- `GET /api/runners`, `GET /api/events/{sequence}` (evento original) y paginación del historial de eventos.
- Detección de `UNRESPONSIVE` en backend (job periódico sobre `last_activity_at`).
- Shell con navegación global: Dashboard, Proyectos, Workflows (solo `adhoc`, lectura), Ejecuciones, Runners y Actividad.
- Formulario para crear trabajo y lanzar un agente (repositorio, prompt, límites).
- Vista de ejecución en tres paneles + timeline: cabecera operativa (estado, duración, runner, tokens, coste, estado SSE), navegación de fases/agentes, inspector contextual (Resumen, Prompt, Mensajes en streaming, Herramientas, Evento original) y timeline inferior. Las fases se muestran como lista/stepper: sin React Flow mientras el workflow sea `adhoc`.
- Timeline semántico (§13.4) con agregación (p. ej. "Read 14 files"), filtros por fase, agente, tipo, severidad y origen, deep link por `sequence` y virtualización.
- Selección, pestaña del inspector y filtros sincronizados con la URL.
- Deltas SSE aplicados sin invalidar todas las queries; revisión del dispatcher SSE en serie.
- Dashboard de excepciones: ejecuciones activas y fallidas, agentes `UNRESPONSIVE` y runners sin heartbeat, cada uno enlazado a una lista filtrada. Las métricas agregadas van en M6.
- Accesibilidad: navegación por teclado y ningún estado comunicado solo con color; responsive básico.

Se entrega en cinco PRs: M3-A contratos y proyecciones; M3-B shell, cabecera y formulario; M3-C navegación e inspector con URL; M3-D timeline y SSE; M3-E dashboard, Runners, accesibilidad y E2E.

**Aceptación:** E2E Playwright con fake-claude: crear trabajo → lanzar → ver mensajes, herramientas y coste en vivo → reconectar SSE sin perder eventos → estado final. Criterios 1–7 de ADR-0001. **MVP 4, 5, 11.**

### M4 — Interacción con sesiones (≈1 semana)

- Enviar mensaje → `RESUME` con `--resume <session>` en el mismo worktree; nuevo `AgentRun` hijo (`kind=RESUME`) para mantener auditoría por invocación.
- Interrumpir (cancel), reintentar (nuevo `AgentRun`, worktree nuevo), fork (`--fork-session`).
- Bloqueo: no permitir dos invocaciones escritoras simultáneas en el mismo worktree.
- La UI muestra la conversación completa encadenando los `AgentRun` de la misma sesión.
- Cada acción (mensaje, cancelar, reintentar, fork) muestra su alcance e impacto y pide confirmación proporcional al riesgo; resumes, reintentos y forks aparecen como entidades enlazadas en la navegación (ADR-0001 §3.8).

**Aceptación:** E2E: run completado → enviar mensaje → nueva actividad en la misma conversación; cancelar a mitad deja estado `CANCELLED` y sin procesos. **MVP 9, 10.**

**Implementado (M4-B), runner:**

- La orden `RESUME` se ejecuta como un `START`, con otro worktree y otro comando:
  - Sin fork: en el worktree de la invocación anterior, que tiene que estar bajo `$SKYNET_RUNNER_HOME/workspaces`, con `claude -p <mensaje> --resume <sesión>`. El CLI no admite `--session-id` junto a `--resume` salvo al bifurcar.
  - Con fork: worktree nuevo en una rama propia desde el `HEAD` del padre, con sus cambios sin confirmar (diff de ficheros versionados y ficheros nuevos no ignorados), y `--resume <sesión> --fork-session --session-id <nueva>`. Antes copia la sesión (`<config>/projects/<cwd>/<sesión>.jsonl` y su directorio auxiliar, con `<config>` = `CLAUDE_CONFIG_DIR` o `~/.claude`) al directorio del worktree nuevo, porque `--resume` solo busca en el del cwd. Pendiente confirmarlo una vez con Claude real.
- Un solo escritor por worktree también en el runner: una segunda invocación en el mismo worktree termina con error.
- Si hay `setsid` en el `PATH`, el agente se lanza en su propio grupo de procesos. Cancelar mata el árbol y el grupo entero, y al terminar el agente se matan los procesos que dejara en segundo plano. Sin `setsid` (macOS) se mata solo el árbol, como antes. Los procesos zombi cuentan como muertos.
- fake-claude elige `03-resume` o `04-fork` al reanudar (`FAKE_CLAUDE_RESUME_FIXTURE`, `FAKE_CLAUDE_FORK_FIXTURE`). Con `CLAUDE_CONFIG_DIR` guarda las sesiones como el CLI y falla si `--resume` no encuentra la sesión.

**Implementado (M4-C), web:**

- La pestaña Mensajes del inspector pasa a ser **Conversación** (`?tab=conversation`; `?tab=messages` sigue funcionando). Une las invocaciones de la sesión de `GET /api/agent-runs/{id}/conversation` con los mensajes en vivo del agente elegido, con un separador por invocación (tipo, estado, coste y enlace).
- Caja de mensaje al pie, activa cuando la última invocación de la cadena ha terminado y su sesión existe. Indica worktree, rama y runner. Enviar no pide confirmación y lleva a la ejecución nueva.
- Reintentar y Bifurcar abren un panel con su alcance antes de confirmar; Cancelar agente pide una confirmación sencilla. Los 409 del backend se muestran tal cual.
- En Fases, los hijos enlazan con su padre («reanudación del…», «reintento del…», «fork del…») a través de `/agent-runs/{id}`, que abre el agente en su ejecución.
- E2E: tras completar, un mensaje crea una reanudación que aparece en la misma conversación con su coste; cancelar a mitad deja `CANCELLED` y ningún proceso de fake-claude.

### M5 — Git, tests y artefactos (≈1,5–2 semanas)

- `BlobStore` en sistema de ficheros; artefactos con `sha256`.
- Indexador del runner al terminar cada invocación: commits nuevos (`base..HEAD`), archivos modificados, diff (incluidos cambios sin commitear), rama.
- Ejecución independiente de un comando de validación configurable por repositorio (p. ej. `./mvnw verify`) → código de salida + parseo de JUnit XML (total/fallidos/omitidos) → artefacto `TEST_REPORT`.
- Prompt efectivo, NDJSON bruto y resultado final como artefactos.
- UI de artefactos (§13.5): archivos modificados, diff con Monaco, commits, tests, logs.
- Proyecciones `ArtifactSummary` y `VerificationResult`; pestañas Artefactos, Verificación y Coste del inspector, con contenido pesado cargado bajo demanda. La UI distingue "declarado por el agente" de "verificado por Skynet" (ADR-0001 criterio 8).
- Acción "reejecutar solo la verificación".
- El NDJSON bruto pasa por el mismo redactor que la ingestión antes de guardarse.

**Aceptación:** tras un run que modifica código se ven archivos, diff y commits; el resultado de tests procede del comando ejecutado por el runner, no del texto del agente. **MVP 7, 8, 12.**

Se entrega en tres PRs (plan aprobado: verificación aparte del agente, comando por repositorio, redacción de todos los artefactos de texto, 20 MB por artefacto y Monaco cargado bajo demanda).

**Implementado (M5-A), control plane y protocolo:**

- Migración `V6`:
  - `repository.validation_command` y `test_report_paths` (por defecto los informes de Gradle y Surefire).
  - Tabla `verification_run`: estado `QUEUED`, `RUNNING`, `PASSED`, `FAILED` o `ERROR`, código de salida, totales de tests y error.
  - Tabla `artifact`, única por (invocación, verificación, tipo, nombre).
- Verificación:
  - Cuando una invocación termina `COMPLETED` y su repositorio tiene comando, se crea una verificación `AUTO` y una orden `VERIFY` (`RunVerification`: worktree, comando, globs y tiempo máximo, `skynet.verification.timeout`, 30 min) para el runner del worktree.
  - `POST /api/agent-runs/{id}/verifications` la reejecuta (`MANUAL`).
  - El runner informa con `agent.verification.started` y `agent.verification.completed`, que se registran con el agregado `verification_run` aunque el agente ya haya terminado y no cambian su estado ni el de la ejecución.
  - Fallar los tests es `FAILED`; no poder ejecutar el comando o agotar el tiempo (`error`) es `ERROR`.
  - Una verificación viva ocupa el worktree igual que una invocación: reanudar, bifurcar o reejecutar responde 409.
- Artefactos:
  - `POST /api/runner/artifacts` recibe el contenido en el cuerpo (como mucho `skynet.artifacts.max-upload`, 64 MB; si no, 413) y los metadatos en la cabecera `X-Artifact-Metadata`. Comprueba el sha256 recibido y que el agente (y la verificación, si la hay) sea de ese runner.
  - Los de texto pasan por el `PayloadRedactor`; lo que supere `skynet.artifacts.max-size` (20 MB) se recorta con una marca.
  - El contenido se guarda en un `BlobStore` de ficheros (`skynet.artifacts.root`) direccionado por su sha256; el original queda en `metadata.originalSha256` si cambió.
  - Cada artefacto nuevo registra `artifact.created`.
  - La web lee el contenido por trozos (como mucho 8 MiB por petición); lo que es texto se sirve como `text/plain` o JSON, nunca como HTML.
- El runner de esta PR todavía no verifica: responde a `VERIFY` con un `error` para que la verificación no quede en cola. Llega en M5-B.

**Implementado (M5-B), runner:**

- Al terminar cada invocación, antes de `agent.process.exited`, el runner sube como artefactos:
  - `PROMPT` (`prompt.txt`), `LOG` (el NDJSON bruto) y `RESULT` (la última línea `result`).
  - `GIT_CHANGES` (`changes.json`): rama, commit base y final, commits nuevos (`base..HEAD`, hasta 500), archivos con estado, líneas añadidas y borradas y la posición de su trozo en el diff (`diffOffset`, `diffLength`). El resumen va en `metadata`.
  - `DIFF` (`changes.diff`): diff contra el commit base, con los cambios sin confirmar y los archivos nuevos no ignorados. Se calcula con un índice temporal (`GIT_INDEX_FILE`), sin tocar el del worktree. Sin cambios no hay diff.
- La base es el `HEAD` al empezar la invocación: el diff de una reanudación cubre solo esa invocación.
- Los artefactos se copian a `~/.skynet-runner/artifacts` y quedan en el journal hasta que el control plane los acepta, así que sobreviven a un corte o a un reinicio. Uno que el control plane rechaza (400, 404, 409, 413) se descarta. Cada copia se recorta a 48 MB; el control plane recorta después a su máximo.
- Orden `VERIFY`:
  - Ejecuta `sh -c` con el comando en el worktree, con el mismo entorno filtrado que el agente, `stderr` junto a `stdout` y el tiempo máximo de la orden.
  - Lee los informes JUnit XML de los globs escritos durante la verificación (Gradle, Surefire y compatibles; sin DTD ni entidades externas).
  - Sube `VERIFICATION_LOG` (`verification.log`) y, si hay informes, `TEST_REPORT` (`tests.json`, con totales y hasta 200 casos fallidos).
  - Termina siempre con un único `agent.verification.completed` (código de salida, señal, totales o `error`). Una orden repetida no se ejecuta dos veces, y un worktree con una invocación en curso o que no es de este runner da `error`.
  - Si el runner se reinicia a mitad, mata el proceso y cierra la verificación con un `error`.
- fake-claude con `FAKE_CLAUDE_APPLY=1` aplica de verdad los `Edit`, `Write` y `Bash` de la fixture en su directorio de trabajo, para que haya diff y commits que indexar.

**Implementado (M5-C), web:**

- Pestaña **Artefactos** del inspector: rama y commits base y final, archivos modificados con su estado y +/−, el diff del archivo elegido (solo su trozo, por `diffOffset`/`diffLength`), los commits y los logs (`PROMPT`, NDJSON, resultado, log de verificación) leídos por páginas de 512 KB.
- Diffs y logs en Monaco de solo lectura con un lenguaje `diff` propio. Va empaquetado (sin CDN), solo el editor base y su worker, y se descarga con `React.lazy` la primera vez que hace falta; si no carga, se muestra el texto plano.
- Pestaña **Verificación**: lo declarado por el agente (subtipo y respuesta final) junto a la última verificación de Skynet (estado, origen, comando, código de salida, duración, totales y tests fallidos del `TEST_REPORT`), las anteriores y el botón «Reejecutar verificación».
- Pestaña **Coste**: tokens y coste de la invocación, acumulado de la sesión y tabla de las invocaciones encadenadas con su total.
- La cabecera muestra la última verificación con su estado y «pasados/total tests». Los eventos `verification_run` y `artifact` llevan `agentRunId`, así que aparecen en el timeline del agente y refrescan sus pestañas en vivo.
- En el proyecto, el registro de repositorio admite comando de verificación y globs de informes, y cada repositorio tiene un formulario «Verificación» para cambiarlos.
- E2E: el repositorio de prueba tiene un `check.sh` que genera un JUnit XML; tras la ejecución se ven `calc.py`, su diff y el commit, la verificación pasa y reejecutarla crea otra.

### M6 — Endurecimiento y cierre del MVP (≈1 semana)

- Spring Security (usuario único local, form login), token de runner, CORS.
- Política de seguridad por agente: herramientas permitidas, `permission-mode`, filtrado de entorno, límites por defecto.
- Recuperación: reinicio del backend y del runner durante un run sin estados inconsistentes (reconciliación de procesos vivos vs `agent_run`).
- Limpieza de worktrees, retención de logs.
- Docker Compose completo + guía de instalación del runner en `README`.
- Métricas agregadas del dashboard (duración, tokens y coste por periodo), enlazadas a listas filtradas. PR y checks de CI en el dashboard quedan para cuando exista integración con GitHub.
- Revisión de "sin secretos en el DOM" y de accesibilidad.
- Repaso de los 12 criterios del MVP con una sesión real de Claude.

**Aceptación:** checklist §23 completo en un repositorio real.

Se entrega en cinco PRs (plan aprobado: login propio con sesión, revocar el token del runner, política por repositorio, presupuesto estimado por el runner, reconciliación con los latidos, limpieza automática de worktrees a los 7 días, métricas por periodo, axe en la E2E y prueba real de los 12 criterios).

**Implementado (M6-A), acceso:**

- Spring Security con un usuario único (`SKYNET_ADMIN_USER`, `SKYNET_ADMIN_PASSWORD`). Sin contraseña genera una y la guarda en `data/admin-password` (`0600`; desde RV-C, el log solo dice dónde está); con `SKYNET_REQUIRE_ADMIN_PASSWORD=true` (Docker Compose) no arranca.
- La web entra con `POST /api/auth/login` (sesión por cookie, `HttpOnly` y `SameSite=Lax`; el identificador cambia al entrar), consulta `GET /api/auth/session` y sale con `POST /api/auth/logout`. Caduca tras `SKYNET_SESSION_TIMEOUT` (12 h) sin uso.
- CSRF por cookie (`XSRF-TOKEN`, cabecera `X-XSRF-TOKEN`) en todo lo que cambia algo. No se pide a peticiones con `Authorization` (HTTP Basic de scripts, o el runner) ni a `/api/runner/**`.
- Un 401 nunca lleva `WWW-Authenticate`, para que el navegador no abra su diálogo. Quedan abiertos `/actuator/health`, el login y la API del runner, que tiene su token.
- CORS cerrado salvo los orígenes de `SKYNET_CORS_ORIGINS`.
- `POST /api/runners/{id}/revoke` invalida el token de un runner (`runner.token.revoked`). El runner legítimo recibe 401 y se vuelve a registrar con el secreto de registro.
- Web: página de login, «Salir» en la barra superior y vuelta al login sin cambiar de página cuando la sesión caduca (también si el stream en vivo falla por eso). «Revocar token…» en Runners, con confirmación.

**Implementado (M6-B), política y presupuesto:**

- Cada repositorio puede tener su política de agentes (migración `V7`): herramientas permitidas (sin comas, p. ej. `Bash(git:*)`), `permission-mode` (`dontAsk`, `acceptEdits`, `default` o `plan`; `bypassPermissions` no, porque ignora las herramientas), variables de entorno y máximos de turnos, presupuesto y tiempo. Sin política propia usa la global (`skynet.agent`), que es también el punto de partida del formulario. Eventos `repository.agent-policy.configured` y `repository.agent-policy.reset`.
- Al lanzar, los límites vacíos toman el máximo de la política y uno mayor se rechaza con 400. Reintentar, reanudar y bifurcar conservan los límites de la invocación de partida, rebajados si la política se ha endurecido. Las herramientas, el modo y el entorno son siempre los de la política actual; `agent.spawned` registra herramientas y modo.
- `StartAgent.environment`: variables que pide la política. El runner solo pasa las que también permite `SKYNET_AGENT_ENV` y avisa en su log de las demás; `null` mantiene todas las permitidas. La verificación no cambia: usa el entorno del runner.
- El runner hace cumplir el presupuesto: estima el coste con los tokens de cada mensaje según se emiten (`message_start`, `message_delta` y `assistant`, sin contar dos veces) y una tabla de precios por modelo (prefijo más largo; ampliable con `SKYNET_MODEL_PRICES`). Escribir en caché cuenta 1,25× la entrada, o 2× con duración de 1 h. Si la estimación pasa del presupuesto, termina el proceso y el agente acaba en `FAILED` con «Presupuesto agotado…». El fin del proceso lleva `estimatedCostUsd` y, si hubo modelos sin precio (que no cuentan), `unpricedModels`; `--max-budget-usd` se sigue pasando. El coste mostrado sigue siendo el del `result`; sin `result` (un agente cancelado o caído antes de terminar), el control plane se queda con `estimatedCostUsd` y suma el `usage` que el fin del proceso trae con los tokens que ningún evento llevó (RV-A).
- Web: «Política de agentes» en cada repositorio del proyecto, y en el lanzamiento la política efectiva y los máximos de cada límite.

**Implementado (M6-C), recuperación y limpieza:**

- Reconciliación con los latidos (migración `V8`): el runner declara en `runningAgentRunIds` las invocaciones en curso y las terminadas con eventos aún por enviar. Cada latido cuenta, por orden `START`/`RESUME` confirmada de un agente activo, las veces seguidas que no aparece (`runner_command.missed_heartbeats`); a las `skynet.runner.lost-after-heartbeats` (2) el agente pasa a `FAILED` con «El proceso ya no existe en el runner» (o a `CANCELLED` si se había pedido cancelarlo). Cubre los agentes que se quedaban en «Arrancando». Una orden entregada y sin confirmar se sigue reentregando.
- Reinicio del runner: como en M2, al arrancar mata los procesos huérfanos y cierra sus invocaciones con «El runner se reinició durante la ejecución». Reinicio del control plane: el runner guarda los eventos en su journal y los envía al volver; la ejecución termina bien.
- Orden `CLEANUP` (`CleanupWorkspace`: id y ruta del worktree): el runner hace `git worktree remove --force` (si falla, borra el directorio) y conserva la rama; responde con `agent.workspace.removed` (`workspaceId`, `path` y, si falla, `error`). No toca un worktree con una invocación o verificación en curso ni rutas fuera de su raíz.
- Retención: cada hora se piden eliminar los worktrees sin nada vivo cuya última invocación o verificación terminó hace más de `SKYNET_WORKTREE_RETENTION` (7 días). Uno que falló al eliminarse no se reintenta solo; su motivo queda a la vista. Eventos `agent.workspace.cleanup.requested` (`trigger`: `manual` o `retention`) y `agent.workspace.removed`.
- El runner borra cada hora los logs locales (`logs/`) de más de `SKYNET_LOG_RETENTION_DAYS` (7) días, salvo los de invocaciones en curso; ya están subidos como artefactos.
- Web: «Eliminar worktree…» en el agente, con confirmación; el agente muestra «Worktree eliminado» (o que se está eliminando, o por qué falló) y desactiva continuar, bifurcar y reejecutar la verificación.
- E2E: reiniciar el control plane a mitad (termina «Completada») y matar el runner con `kill -9` a mitad (termina «Fallida» sin procesos vivos), con `scripts/e2e-service.sh`.

**Implementado (M6-D), métricas y revisión de la web:**

- `GET /api/dashboard/metrics` resume las ejecuciones creadas en el periodo (24 h, 7 o 30 días): total, activas, completadas, fallidas y canceladas, duración mediana de las terminadas, tokens de entrada y salida y coste (suma de sus agentes), y la serie por hora (24 h) o por día (7 y 30 días) en la zona del navegador (`tz`), con los tramos vacíos incluidos. `GET /api/workflow-runs` acepta `since`.
- Web: «Métricas» al pie del dashboard, con el periodo en la URL (`?period=`). Cada cifra enlaza a Ejecuciones filtrada por estado y fecha («Creadas desde…», con «Quitar»). Gráfico de barras propio en SVG con detalle al pasar por encima y «Ver como tabla».
- E2E con `@axe-core/playwright` (WCAG 2.1 A y AA) en el login, todas las pestañas de una ejecución, el dashboard, Ejecuciones, Proyectos, Runners, Actividad, Workflows, un proyecto y un trabajo. Lo que encontró: el verde de «Completada» no llegaba a 4,5:1 sobre blanco (`--ok` pasa a `#1a7a43`).
- E2E «ningún secreto llega al DOM»: el repositorio de prueba lleva un token de GitHub en `calc.py` y su `check.sh` imprime una clave de AWS; ni el HTML de cada pestaña ni la API de eventos los contienen, y el diff y el log de la verificación muestran `[REDACTED]`.

**Implementado (M6-E), cierre del MVP:**

- Docker Compose revisado: variables en `deploy/.env` (plantilla comentada en `deploy/.env.example`; `SKYNET_ADMIN_PASSWORD` obligatoria con el perfil `app`, comprobada por la API al arrancar para que `up` sin perfil siga funcionando sin `.env`), PostgreSQL publicado solo en `127.0.0.1`, `restart` y logs con rotación en todos los servicios, `stop_grace_period` en la API y paso de `SKYNET_SESSION_TIMEOUT`, `SKYNET_CORS_ORIGINS`, `SKYNET_WORKTREE_RETENTION` y `SKYNET_VERIFICATION_TIMEOUT`. nginx admite subidas de 64 MB para los runners que se conectan por la web.
- `scripts/compose-smoke.sh`: un runner en el host con fake-claude se registra a través de la web, completa un agente y sube su diff. Corre en el job de CI «Docker Compose (app)».
- Runner como servicio: `deploy/runner/` con la configuración de ejemplo (`runner.env.example`), el arranque que la carga (`skynet-runner.sh`), un servicio de usuario de systemd y un agente de launchd. Guía de instalación y actualización en el README.
- [`docs/mvp-checklist.md`](mvp-checklist.md): los 12 criterios del §23 con cómo comprobarlos con Claude real y la prueba automática que ya los cubre, comprobaciones de cierre y registro de la prueba.

**Duración estimada Fase 1: 9–11 semanas** para una persona; paralelizable en dos líneas (backend/runner y frontend) a partir de M1.

---

## 7. Fase 2 — Workflow explícito

| Hito | Contenido | Aceptación |
|---|---|---|
| W1 Definiciones | Parser YAML (§11), JSON Schema de la definición, validación de DAG (ciclos, dependencias inexistentes, `dependsOn` opcional `x?`), versionado inmutable, `AgentDefinition` (prompt template, herramientas, límites); `WorkflowDefinitionView` y página Workflows en lectura con estados borrador/validada/publicada | Definiciones inválidas rechazadas con errores claros |
| W2 Motor | Tabla `job` + workers con `SKIP LOCKED`; transiciones `PENDING→READY→STARTING→RUNNING→…`; activación de fases sin dependencias; outbox; interfaz `WorkflowEngine` aislada para migrar a Temporal | Reinicio del backend a mitad de workflow lo retoma correctamente |
| W3 Nodos | `agent`, `command`, `parallel`, `conditional` (expresiones sobre salidas estructuradas, p. ej. `review.hasBlockingFindings`) | Workflow de ejemplo con fase paralela y condicional |
| W4 Resultado estructurado | Contrato §9.4 validado contra `outputSchema`; verificación independiente de artefactos y checks antes de completar la fase (§15), ampliando el `VerificationResult` de M5 | Fase con salida inválida o artefacto inexistente no avanza |
| W5 Human-in-the-loop | Nodo `human-approval`, `ApprovalService`, centro de aprobaciones (§13.6, §14): aprobar una vez/rechazar/pedir cambios/comentar; acción exacta, origen, riesgo, permisos, evidencia y consecuencia (ADR-0001 §3.7); records append-only, sin "aprobar siempre" | Aprobación desbloquea dependientes; rechazo con "pedir cambios" crea ejecución correctiva |
| W6 Retries y cancelación | Política de retry por fase, timeouts, cancelación en cascada del workflow; acciones de reintento por nodo gobernadas por el motor | Fallo transitorio reintenta; cancelar workflow mata todos sus agentes |
| W7 UI DAG | React Flow con estado, duración, agente, coste, reintentos y acciones por nodo (§13.2); minimapa, swimlanes por macrofase, nodos tipados y accesibilidad del grafo (ADR-0001 §3.3) | El DAG representa la definición versionada y refleja el estado en tiempo real vía SSE |
| W8 Editor de workflows | Edición visual del DAG con panel de propiedades, validación visible (ciclos, dependencias inexistentes, configuración incompleta) y publicación de versiones inmutables (ADR-0001 §3.3) | Un workflow creado en el editor se publica, se ejecuta y las ejecuciones previas no cambian |

**Estimación: 8–10 semanas, sin contar W8.**

**Proveedores (decidido el 2026-10-10):** después de W3 va un hito propio, adelantado de la Fase 4: la interfaz `AgentProvider` (§4.1) en el runner, adaptadores headless de Codex (`codex exec --json`) y Gemini (`gemini -p --output-format stream-json`) y runners que declaran qué CLIs tienen. W3-A ya deja `model` por agente y la clave `provider` reservada en el YAML.

**Hito PV (proveedores), planificado el 2026-10-12:** PV-A interfaz `AgentProvider` y reparto por proveedor, PV-B Codex, PV-C Gemini, PV-D web y E2E con revisores de los tres proveedores.

**Hito W4 (resultado estructurado), planificado el 2026-10-13:** W4-A salida de fase y datos entre fases, W4-B verificación de fase (`artifacts`, `checks`) y nodo `verification`, W4-C verificación en la web, plantilla «Especificar, planificar e implementar» y E2E.

**Hito W5 (human-in-the-loop), planificado el 2026-10-16:** W5-A puertas de aprobación en el motor, W5-B pedir cambios, W5-C centro de aprobaciones y E2E.

**Hito W6 (reintentos y cancelación), planificado el 2026-10-17:** W6-A reintentos automáticos, W6-B límites de tiempo y cancelación, W6-C «Reintentar fase», web y E2E.

**Hito W7 (grafo del workflow), planificado el 2026-10-17:** W7-A grafo del workflow, W7-B grafo de la ejecución, W7-C carriles por macrofase (`phase`), accesibilidad y E2E.

**Hito CU (cuotas de uso de los CLIs), aprobado el 2026-10-17:** CU-A lectura de cuotas, CU-B atribución, CU-C página Uso y E2E. Ver [`usage-windows.md`](usage-windows.md).

**Hito CU terminado con CU-C.**

**Hito AR (agentes reales), aprobado el 2026-10-18:** AR-A ver si un runner usa un CLI simulado, AR-B Antigravity CLI sustituye a Gemini CLI, AR-C los tres reales de punta a punta.

**Implementado (AR-C), los tres reales de punta a punta:** README («Runner con CLIs reales» y cómo volver a los simuladores), `deploy/runner/runner.env.example` con los tres ejecutables y guía [`tests/probar-ar-c.md`](tests/probar-ar-c.md) para probar Claude Code, Codex y Antigravity reales en omarchy. La E2E con los tres simuladores llegó en AR-B. **Hito AR terminado.**

**Hito W8 (editor visual de workflows), aprobado el 2026-10-19:** W8-A motor de edición y problemas en el grafo, W8-B edición visual de fases, W8-C agentes, workflow nuevo y aceptación. Plan en `plans/siguiente-hito-w8.md` (espacio del proyecto).

**Implementado (W8-C), agentes, workflow nuevo y aceptación (hito W8 terminado):**

- Paneles **Workflow** (`id` solo en el alta, `name`, `description`), **Agentes** (prompt, `provider`, `model`, `permissionMode`, `tools`, `limits`; renombrar cambia sus usos y no se quita uno en uso) y **Entradas** (tipo, obligatoria, valor por defecto con su tipo, descripción; renombrar cambia sus `{{inputs.…}}`) en el grafo editable, con las operaciones nuevas de `definitionEdits.ts`.
- Plantilla **Desde cero** en el alta, que abre la pestaña Grafo.
- E2E de aceptación `editor.e2e.ts`: un workflow montado en el grafo sin escribir YAML se publica y se ejecuta; su v2, con una fase más añadida desde el grafo, también; la primera ejecución sigue siendo de la v1 con su única fase. axe en claro y oscuro. Cumple el criterio de W8 de §7.

**Implementado (W8-B), edición visual de fases:**

- La pestaña **Grafo** de un borrador se edita (`GraphEditor`): paleta con los seis tipos, unir fases arrastrando (con el ciclo rechazado y su camino), elegir una flecha para hacerla opcional o quitarla, y borrar una fase con confirmación. Cada cambio pasa por `definitionEdits.ts` y entra en Monaco como una edición aparte: Deshacer / Rehacer (y Ctrl+Z) valen igual para el grafo y el YAML. Al añadir o quitar fases se vuelve a ajustar a pantalla.
- Panel de propiedades de fase (`StagePanel`), leído del texto (no espera a la validación): `id` (renombrar con sus referencias), `name`, agente y prompt, `command`, `action` / `risk` / `revise`, condición, macrofase, `workspace` y `workspaceFrom` con solo valores válidos, intentos de `retry`, `timeout`, comprobaciones con nombre, hijos de `parallel` y dependencias. Los problemas de cada campo salen debajo (`aria-invalid`) y los de la fase llevan a su línea; «Editar en YAML» para lo poco común.
- Ruta de teclado para todo; por debajo de 720 px, solo lectura. El grafo ya no desaparece al empezar a editar: hasta la primera validación del texto cambiado se usa la de lo guardado.
- vitest del panel y del editor del grafo (añadir, renombrar, ciclo rechazado, dependencias, borrar, problemas por campo) y de las lecturas nuevas del motor; E2E `workflows.e2e.ts`: añadir una fase, unirla arrastrando, completar su panel, deshacer y rehacer, flecha opcional, axe y guardar.

**Implementado (W8-A), motor de edición y problemas en el grafo:**

- `definitionEdits.ts` (librería `yaml`): añadir, borrar y renombrar fases, unir y quitar dependencias, opcional sí o no, campos de fase e hijos de `parallel`, editando el documento: se conservan los comentarios, el orden y el estilo de cada lista, y las líneas que no cambian salen tal cual. Renombrar actualiza `dependsOn`, `workspaceFrom`, `revise`, las condiciones y `{{stages.…}}`; unir rechaza un ciclo con su camino.
- Grafo de la definición: cada fase lleva una insignia con sus problemas (icono y texto, por la ruta `stages.<id>` del problema); una dependencia que no existe sale como fase fantasma discontinua con su flecha en rojo; las flechas de un ciclo van en rojo.
- vitest del motor y de la asignación de problemas; E2E `workflows.e2e.ts` con un grafo roto, con axe.
- Arreglo del motor de ejecución: `JobQueue.retryLater` fallaba en PostgreSQL (al parámetro de la hora le faltaba el `CAST` a `timestamptz`) y una evaluación que tenía que esperar dejaba la ejecución parada hasta que vencía el alquiler del trabajo. Lo cubre un test nuevo en `WorkflowEngineIT`.

**Implementado (AR-B), Antigravity sustituye a Gemini CLI:**

- `runner/provider/antigravity`: `AntigravityProvider` lanza `agy --output-format stream-json --print=<prompt>` con `--mode plan` o `accept-edits` (o `--dangerously-skip-permissions`), `--model`, `--json-schema <fichero>` y `--conversation <id>` para continuar; bifurcar falla. `AntigravityEventParser` y `AntigravityCostMeter` salen de las sesiones grabadas con agy 1.4.2 (`fixtures/agy`): pasos `step_update`, acciones denegadas sin terminal como `PERMISSION_DENIED`, salida estructurada validada por agy y coste solo con `model`. Formato en [`antigravity-stream-json.md`](antigravity-stream-json.md).
- Gemini CLI se retira: fuera su adaptador, fake-gemini, sus fixtures y `SKYNET_GEMINI_BIN`. `AgentProviderKind.GEMINI` queda como retirado para leer el histórico («Gemini CLI (retirado)»): `provider: gemini` es un error al publicar, las versiones ya publicadas con él no se lanzan (400) y sus agentes no se reintentan ni continúan (409). `StructuredOutput` se queda solo con `resolve`: los tres CLIs validan su salida.
- fake-agy reproduce las sesiones grabadas además de `/usage`. La plantilla pasa a «Revisión con Codex y Antigravity» y `providers.e2e.ts` la prueba con fake-agy; la tarjeta de agy en **Uso** deja de ser de solo lectura.

**Implementado (CU-C), página Uso y E2E:**

- Página `/usage` («Uso» en la navegación, entre Runners y Actividad; en el móvil ocupa el sitio de Runners): una tarjeta por CLI con un medidor por ventana (Skynet, fuera de Skynet rayado y la marca del ritmo uniforme), su estado (Disponible, Justa desde el 75 %, Casi agotada desde el 90 %), versión, plan y de cuándo es el dato; agy por grupos de modelos y en solo lectura. Selector de runner si hay varios y «Actualizar».
- «Evolución de la ventana»: área apilada en escalera por CLI y ventana (recharts, cargado aparte), con la línea de «ahora».
- «Qué cuesta cada agente» por periodo: tabla por agente (% semanal, por ejecución y por éxito) y las ejecuciones que más han gastado.
- KPI «Cuota» en la cabecera de la ejecución (la ventana de 5 h del CLI que más ha gastado y una línea por CLI) y sección «Cuota» en la pestaña Coste del agente (medido o repartido).
- Los `quotaWarnings` salen en «Necesita tu atención» del Dashboard y de la página Uso, y llevan a `/usage`.
- vitest de la página, del formato y de la cabecera; E2E `usage.e2e.ts` (fake-codex gasta 3 puntos por sesión con `FAKE_CODEX_QUOTA_STEP`): atribución medida, medidores, aviso del 90 %, cabecera, pestaña Coste, Dashboard y axe.

**Implementado (CU-B), atribución:**

- `QuotaAttributor`: entre dos muestras seguidas de una ventana, la subida va a los agentes de ese CLI que corrieron en el runner, en proporción a su coste dentro del intervalo (si no, tokens; si no, tiempo); sin agentes, a «Fuera de Skynet». Renovación de la ventana (pasó `resetsAt` o bajó más que el redondeo): cuenta desde 0. La primera muestra de un agente de Claude Code es su punto de partida; la de Codex, su final. Se recalcula con cada muestra (también desordenada) y cuando termina o se pierde un agente.
- `quota_interval` y `agent_run_quota` (migración `V25`); `quota` en cada agente y en `totals` de la ejecución; `pace` y `skynet` en `GET /api/usage/windows`.
- `GET /api/usage/history`, `GET /api/usage/agents` y `GET /api/usage/runs`; `quotaWarnings` en `GET /api/dashboard` (más del 90 %, o la semana 15 puntos por encima del ritmo).
- Contratos `usage-*.json`; `QuotaAttributionIT` (intervalos solapados, muestra tardía, renovación, redondeo, recálculo al terminar, Codex). `fake-codex` gasta cuota con cada sesión (`FAKE_CODEX_QUOTA_STEP`).

**Implementado (CU-A), lectura de cuotas:**

- Evento `agent.quota` y `QuotaSample` en el protocolo. `ClaudeStreamParser` lo emite con las `unifiedWindows` de cada `rate_limit_event` (cualquier estado) cuando cambian; `CodexProvider.afterExit` lo emite al terminar, con el último `token_count` del fichero de la sesión.
- `QuotaPoller` en el runner: `codex app-server` (`account/rateLimits/read`) y `agy -p /usage` cada `SKYNET_QUOTA_POLL` (10 min; `off` lo apaga); las muestras viajan en `RunnerHeartbeat.quotas`. El latido responde `HeartbeatReply` con `refreshQuotas`.
- `quota_snapshot` (migración `V24`, se borra a los 35 días), `GET /api/usage/windows` y `POST /api/usage/refresh`.
- Fixtures reales de la máquina de Diego (`fixtures/quota/`, `fixtures/agy/usage.json`); `fake-codex` escribe la cuota en su sesión y responde a `app-server`, y `fake-agy` responde a `/usage`.

**Implementado (W7-C), carriles, accesibilidad y E2E:**

- Clave `phase` en las fases (`spec`, `plan`, `implement`, `test`, `review`, `deliver`; validada con sugerencia), en el schema y en `StageDefinition`/`StageRunView`; las plantillas «Revisión en paralelo», «Revisión con Codex y Gemini» y «Especificar, planificar e implementar» la usan.
- `lanesOf` y carriles en `layoutStages`: un carril por macrofase usada, de izquierda a derecha; una fase sin `phase` hereda el carril más avanzado de sus dependencias. Lo mismo en el grafo de la definición y en el de la ejecución.
- Accesibilidad: la caja que recibe el foco con el teclado se trae a la vista; con `prefers-reduced-motion`, sin animación.
- vitest de los carriles; E2E: `workflows.e2e.ts` comprueba los carriles del grafo de la definición, y `nodes.e2e.ts` lanza «Revisión en paralelo» desde la web y ve el grafo pasar a completado sin recargar, con sus carriles y axe.

**Hito W7 terminado.**

**Implementado (W7-B), grafo de la ejecución:**

- `RunGraph` en `RunProgress`: el grafo del workflow con el último intento de cada fase encima (estado con icono y texto, actividad o duración, agente o proveedor, coste, intento y nota de la fase) y un nodo por hijo de una `parallel` con el estado de su agente. Cada caja es un botón con la etiqueta del paso de siempre («2. implement: En curso, 3 min 05 s»); el orden de tabulación es el del YAML.
- Conmutador «Grafo / Lista» (`localStorage`), la lista por debajo de 720 px y con una sola fase. El SSE refresca el grafo sin recolocarlo.
- `children` en la fase de la ejecución (`StageRunView`): los hijos de una `parallel` se dibujan antes de tener agente.
- vitest de los nodos de la ejecución; E2E `engine.e2e.ts`: el grafo pasa de «Espera» a completado sin recargar, con axe.

**Implementado (W7-A), grafo del workflow:**

- `@xyflow/react` 12 y `@dagrejs/dagre` en un trozo aparte que solo se descarga al abrir el grafo (`web/src/components/graph/`).
- `layoutStages`: coloca las fases de izquierda a derecha con dagre; una `parallel` es un grupo con sus hijos apilados dentro; no dibuja dependencias a fases que no existen.
- `WorkflowGraph`: el lienzo de solo lectura (zoom, ajustar a pantalla, minimapa con más de 8 fases, flechas discontinuas para las dependencias opcionales). Lo que va en cada caja lo decide quien lo usa, para que W7-B ponga encima el estado de la ejecución.
- `DefinitionGraph`: icono por tipo, nombre, tipo y una línea con lo que hace cada fase; pestaña **Grafo** (`?view=graph`) en el editor de la definición, también para un borrador.
- vitest de la colocación y de los nodos; E2E en `workflows.e2e.ts`: la plantilla «Revisión en paralelo» en el grafo, con axe.

**Implementado (W6-C), «Reintentar fase», web y E2E:**

- `POST /api/workflow-runs/{id}/stages/{key}/retry` (`RunService.retryStage`): la ejecución fallida o cancelada vuelve a `RUNNING` (`WorkflowRun.resume`, sin `finishedAt` ni cancelación pedida) con `run.resumed`. Reciben un intento nuevo la fase, sus dependientes y las demás fallidas o canceladas; una puerta fallida que no depende de ella da 409.
- `stage_run.retried_by` (migración `V23`, `retriedBy` en la API): el intento lo pidió una persona y los reintentos automáticos vuelven a contar desde él (`Retries.seriesStart`).
- Worktree: una fase que se repite porque se repitió otra anterior no vuelve a su foto (`RunSteps.rerunUpstream`); una `parallel` que había terminado bien repite todos sus hijos.
- Web: «Reintentar fase» con su confirmación en el detalle de la fase, «lo pidió …», «intento N de M» solo mientras cuenta para `maxAttempts`, `run.resumed` en el timeline y el «Reintentar…» del agente solo en las ejecuciones ad hoc.
- E2E `cancel-retry.e2e.ts`: cancelar una `parallel` con dos agentes no deja procesos de fake-claude, y «Reintentar fase» termina «Completada» una ejecución fallida (con axe en la confirmación). Con `retries.e2e.ts`, el criterio de aceptación de W6.
- **Hito W6 terminado.**

**Implementado (W6-B), límites de tiempo y cancelación:**

- YAML: `timeout` por fase (`s`, `m` o `h`, hasta 168 h; `StageDefinition.timeout`), en todas salvo `conditional`. Sale de los problemas `UNSUPPORTED`.
- Motor: al pasar a `READY`, el intento guarda su `deadline` (`stage_run.deadline`, migración `V22`; `stage.ready` lo lleva). El `Planner` despierta la ejecución a esa hora y, si la fase sigue sin terminar, da el paso `TimeOut`: `stage.timed.out`, se paran sus agentes (`agent.cancel.requested` con `reason: stage-timeout`) y terminan `FAILED` con `failure: timeout` (`agent_run.timed_out_at`). La fase falla y se reintenta si `timeout` está en `retry.on`. Una fase que se queda sin tiempo esperando un runner pasa de `READY` a `FAILED`.
- Puertas: con `timeout`, la petición pendiente caduca (`EXPIRED`, decisión `EXPIRE` de `skynet`, `approval.expired`) y la puerta falla con fail-fast.
- Cancelar: `workflow_run.cancel_requested_at` (`cancelRequestedAt` en la API, «Cancelando»), `workflow.cancel.requested`; cancela los reintentos programados y las ejecuciones hijas activas en cascada (`parent-cancelled`). `CancelWatchdog` cierra las cancelaciones que el runner no confirma en `SKYNET_CANCEL_TIMEOUT` (70 s) con `agent.cancel.forced`.
- API: en cada fase `timeoutSeconds`, `deadline`, `timedOutAt` y `cancelling`; en la ejecución, `cancelRequestedAt`; `EXPIRED` en `/api/approvals`.
- Web: «Cancelando» en la cabecera y en la fase, «Falló: se acabó el tiempo», «Caducó sin decisión», el tiempo máximo en el detalle de la fase y en el resumen del workflow, el filtro «Caducadas» en **Aprobaciones** y los eventos nuevos en el timeline.

**Implementado (W6-A), reintentos automáticos:**

- Por qué falló cada agente (`agent_run.failure`, migración `V21`): `lost`, `start`, `error`, `timeout`, `budget` o `verification` (`AgentFailure` en el protocolo). El runner la manda en `PROCESS_EXITED`; el control plane pone `lost` a las invocaciones perdidas y `verification` a las que no cumplen su salida o sus comprobaciones. Sale en la API (`failure`) y en `agent.status.changed`.
- YAML: `retry: {maxAttempts, on, delay}` en las fases `agent` y `command` y en los hijos de una `parallel` (`StageDefinition.Retry`), con sugerencias para los fallos mal escritos. En otras fases es un error.
- Motor: el `Planner` mira el último intento de cada fase. Una fase que falla con un fallo de `on` y con intentos por delante no aplica el fail-fast: `RunSteps.retry` crea el intento siguiente (`stage_run` con `attempt + 1`, `PENDING` y `not_before`, migración `V21`) y emite `stage.retry.scheduled`. El trabajo de la ejecución se reprograma para esa hora (`JobQueue.wakeAt`). Al arrancar, sus agentes son `RETRY` del agente del intento anterior, con sesión nueva.
- Worktree: al arrancar cada agente, el runner guarda una foto del worktree en `refs/skynet/snapshots/<agente>` (`agent.workspace.ready` lleva `snapshot`); el reintento pide restaurarla (`restoreSnapshot`) antes de lanzar el agente.
- `parallel`: se reintentan solo los hijos que fallaron; las salidas y los worktrees de los demás se buscan en todos los intentos.
- Web: el progreso enseña el último intento de cada fase, «Reintenta a las … (intento N de M)», «Falló: …» y los intentos anteriores al abrir la fase; el resumen del workflow, «Reintenta: hasta N intentos…»; el timeline, `stage.retry.scheduled`.
- fake-claude: `FAKE_CLAUDE_FAIL_ONCE=<fichero>` falla la primera vez (código 1) y funciona las siguientes.
- «Reintentar fase» y el E2E de aceptación llegaron en W6-C; el `timeout` por fase y la cancelación forzada, en W6-B.

**Implementado (W5-C), centro de aprobaciones y E2E:**

- Menú **Aprobaciones** (`/approvals`) con el contador de pendientes, también en el cajón del móvil. La lista va primero con las pendientes (las de más riesgo y las más antiguas) y después el historial, con filtros de estado, riesgo y proyecto en la URL.
- Página de una aprobación (`/approvals/{id}`): la acción, el riesgo y el estado; de dónde viene (proyecto, trabajo, ejecución, repositorio) y el coste; lo que dejaron sus dependencias (salida estructurada, comprobaciones, cambios del worktree y artefactos); lo que se permitirá al aprobar; qué pasa con cada decisión; y el historial. Desde la segunda ronda, también lo que se pidió en la anterior. Se decide con Aprobar, Rechazar, Pedir cambios y Comentar. Rechazar, pedir cambios y comentar exigen comentario, y aprobar con riesgo alto pide confirmarlo. Mientras se corrige, enlaza al agente que corrige y, cuando la puerta vuelve a preguntar, a la ronda nueva.
- Progreso de la ejecución: la puerta en ámbar con «Esperando aprobación» o «Corrigiendo». Al abrirla se ven su acción, su riesgo y su ronda, con «Revisar» (o el enlace a la aprobación y su historial).
- Dashboard: las aprobaciones pendientes, las primeras en «Necesita tu atención».
- Resumen del workflow: «Pide aprobar …» con su riesgo; el aviso de publicar lista las puertas. El timeline describe los eventos `approval.*`.
- La plantilla «Especificar, planificar e implementar» gana la puerta `aprobar-plan` (riesgo medio, `revise: plan`) entre `plan` e `implement`.
- E2E de aceptación (`web/e2e/approvals.e2e.ts`):
  - pedir cambios hace que `plan` reciba el comentario en su sesión y la puerta pregunte otra vez (ronda 2);
  - aprobar arranca `implement` y la ejecución termina «Completada»;
  - en otra ejecución, rechazar la hace fallar;
  - axe en el centro, en la página de una aprobación (rondas 1 y 2) y en la puerta del progreso.
- **Hito W5 terminado.**

**Implementado (W5-B), pedir cambios:**

- `REQUEST_CHANGES` (con comentario obligatorio) pone en cola, en la fase `revise` de la evidencia y en la misma ejecución, una invocación `RESUME` de la sesión de su último agente con el comentario (`RunService.queueCorrection`, con las mismas comprobaciones que enviar un mensaje). La petición se cierra `CHANGES_REQUESTED` con la invocación en `correction_agent_run_id` (migración `V20`) y la puerta pasa a `RUNNING` («changes-requested»). Sin `revise`, o si no se puede reanudar, 409 y la petición sigue pendiente.
- El motor cierra las correcciones antes de planificar (`RunSteps.settleCorrections`): con la corrección terminada bien, y su verificación de fase pasada si `revise` tiene comprobaciones, la puerta vuelve a preguntar con otra ronda y evidencia nueva (`changesRequested` lleva lo que se pidió); si falla o no pasa, la puerta falla («correction-failed», `approval.correction.failed`); si se cancela, se cancela. Es determinista: un reinicio o una evaluación repetida no duplican nada.
- Como la corrección es un agente más de la fase `revise`, la salida estructurada, el worktree y la evidencia que leen después son los suyos. `StageVerifications` comprueba también los agentes nuevos de una fase ya terminada y registra su `stage.verification.completed`.
- Cancelar la puerta mientras corrige cancela la corrección. La API lleva `correctionAgentRunId` en cada petición.

**Implementado (W5-A), puertas de aprobación en el motor:**

- YAML: `type: human-approval` deja de ser `UNSUPPORTED`, con `action` (obligatoria, con las variables de un prompt), `risk` (`low`, `medium` o `high`) y `revise` (la fase `agent` que corregirá al pedir cambios; por defecto, su única dependencia `agent`). Se guardan en `StageDefinition.approval`. La puerta pasa el worktree de su dependencia sin tocarlo (`hasWorkspace`).
- Migración `V19`: `approval_request` (una por ronda: acción ya resuelta, riesgo, la foto de la evidencia en `jsonb` y su estado) y `approval_decision` (append-only: acción, comentario, quién y cuándo). Triggers: una decisión no se modifica ni se borra y una petición solo cambia una vez de `PENDING` a su estado final, salvo en una purga.
- Motor: al arrancar, la puerta pasa a `WAITING_FOR_APPROVAL` y `Approvals` crea la petición. Aprobar la cierra `SUCCEEDED` («approval-granted») y despierta al motor; rechazar, `FAILED` («approval-rejected») con fail-fast; comentar no cambia nada. La decisión bloquea la fila de la ejecución, así que no se cruza con el motor ni con otra decisión (la segunda da 409). Cancelar la fase cancela su petición, firmada por `skynet`. Pedir cambios responde 409 hasta W5-B.
- API `/api/approvals` (lista con filtros, detalle con evidencia y decisiones, y decisiones), y la vista de la ejecución lleva en cada puerta su última petición (`approval`). Eventos `approval.requested`, `approval.granted`, `approval.rejected`, `approval.commented` y `approval.cancelled`.
- Contratos nuevos `approvals` y `approval`; tipos en `web/src/api.ts` para la web de W5-C.

**Implementado (W4-C), verificación en la web y E2E:**

- La vista de la ejecución lleva, en cada fase, sus `checks`, los datos de otras fases que lee su prompt (`reads`, p. ej. `spec.output.summary`) y la última verificación de fase de cada agente (`verifications`). Los eventos `stage.verification.*` hacen que la web relea la ejecución.
- Progreso: la fase dice «Verificando» mientras se comprueba y, si falla, «No pasó …» con el motivo; al abrirla, la lista de comprobaciones con ✓/✗/… y su motivo (en una `parallel`, la de todos los hijos) y «Recibe …». La pestaña **Verificación** enseña también la salida estructurada que declaró el agente.
- Resumen del workflow con «Comprueba …» en cada fase; el aviso de publicar lista también los comandos de `checks` y las fases con `tests`.
- Plantilla «Especificar, planificar e implementar» (`spec` con `outputSchema` y `artifacts: [openspec/proposal.md]`, `plan` con `{{stages.spec.output.summary}}`, `implement` con `checks: [tests, diff]`). E2E de aceptación (`web/e2e/spec.e2e.ts`): sin la propuesta, `spec` falla con «`openspec/proposal.md` no existe» y `plan` no arranca; con ella, `plan` recibe el resumen y la ejecución termina «Completada»; axe en la plantilla, la fase fallida y la pestaña Verificación.
- **Hito W4 terminado.**

**Implementado (W4-B), verificación de fase y nodo `verification`:**

- YAML: `artifacts` (globs), `checks` del catálogo cerrado (`tests`, `diff`, `commit`, `scope`, `{command: <cmd>}`) y `scope` (globs) en las fases `agent`, `command`, `parallel` y `verification`. Se guardan como `StageDefinition.checks` (`VerificationCheck` en `protocol`), con los artefactos primero. `type: verification` deja de ser `UNSUPPORTED`: necesita qué comprobar y una dependencia con worktree.
- Runner: `RunVerification` lleva `checks` y `baseCommit` (y `command` pasa a ser opcional). `StageChecks` hace cada comprobación en el worktree y devuelve `{name, target, passed, reason}`; `WorktreeState` mide commits nuevos, ficheros cambiados y cambios sin confirmar con git respecto al commit base.
- Control plane: `V18` añade `verification_run.stage_run_id` y `checks` (jsonb), hace `command` opcional y guarda `agent_run.base_commit` (el del primer `WORKSPACE_READY`). `StageVerifications`: al terminar bien el agente de una fase con comprobaciones, pone en cola una verificación `STAGE` en vez de la `AUTO`, y la fase sigue `RUNNING` hasta que llega; termina `SUCCEEDED` («verification-passed») o `FAILED` («verification-failed»), con fail-fast. En una `parallel`, la de cada hijo; una que falla hace fallar la fase aunque se cancelen sus hermanos. Eventos `stage.verification.started` y `stage.verification.completed`.
- Una fase `verification` crea una invocación sintética (proveedor `verification`, sin orden `START`) en el worktree que hereda y con su runner, cuya verificación mide desde `workspace.base_commit`; termina `COMPLETED` o `FAILED` con los motivos. Se cancela en el acto y no se continúa ni se reintenta.
- Límite conocido: si el runner desaparece a mitad de una verificación, la fase se queda «Verificando» (las verificaciones no se reconcilian con los latidos, igual que en M5).

**Implementado (W4-A), salida de fase y datos entre fases:**

- `outputSchema` en las fases `agent` deja de ser `UNSUPPORTED`: un JSON Schema en línea (como `output`) o la ruta de un fichero del repositorio. Manda sobre el `output` del agente, también en sus mensajes, bifurcaciones y reintentos. Las condiciones leen sus campos; con una ruta no se conocen al publicar y no se comprueban.
- Una ruta viaja al runner como `StartAgent.outputSchema = {"$ref": "<ruta>"}`, sin cambiar el protocolo. El runner lo resuelve en el worktree antes de lanzar el CLI (`StructuredOutput.resolve`), así que vale para los tres proveedores; si el fichero no existe, se sale del worktree o no es el schema de un objeto, la invocación falla con el motivo.
- `{{stages.<fase>.output.<campo>}}`, `{{stages.<fase>.<hijo>.output.<campo>}}`, `{{stages.<fase>.status}}` y `{{stages.<fase>.exitCode}}` en los prompts. Al publicar se comprueban con las reglas de las condiciones (dependencia y campo), y el prompt de un agente desde cada fase que lo usa. Al arrancar la fase, `PromptRenderer` los lee como una condición: un texto tal cual, lo demás en JSON, lo que falta vacío.

**Implementado (PV-D), web y E2E:**

- `GET /api/providers`: los proveedores con sus capacidades y los runners en línea que tienen cada uno. La política efectiva de cada fase (`StagePolicy`) lleva su `provider` y lo que ignora (`unsupported`: `allowedTools`, `maxTurns`), que la web enseña tachado.
- Lanzamiento con `provider` y `model` (`V17`: `workflow_run.launch_provider` y `launch_model`) para las fases sin agente con nombre, como la de `adhoc`; sus mensajes, bifurcaciones y reintentos los conservan. Se rechazan en un workflow cuyas fases fijan todas su agente. En **Lanzar workflow**, selector de proveedor (los que no tiene ningún runner en línea, desactivados) y campo de modelo.
- La cabecera de la ejecución enseña el proveedor del agente; **Bifurcar** y la caja de mensajes se desactivan con el motivo si el proveedor no lo admite. El resumen del workflow enseña el proveedor de cada agente; el editor ya autocompletaba `provider` desde el schema.
- Plantilla «Revisión con Codex y Gemini»: la revisión en paralelo con Codex en calidad y Gemini en seguridad. E2E de aceptación (`web/e2e/providers.e2e.ts`) con fake-claude, fake-codex y fake-gemini: con hallazgos corrige y sin ellos omite la corrección; axe en la plantilla, el lanzamiento y el agente de Gemini. Documentación en [`providers.md`](providers.md).

**Implementado (PV-C), Gemini CLI:**

- `runner/provider/gemini`: `GeminiProvider` lanza `gemini --output-format stream-json` con el id de sesión del control plane (`--session-id`) o `--resume <id>`, traduce `permissionMode` a `--approval-mode` (`plan`, `yolo` o `auto_edit`) y confía en el worktree (`GEMINI_CLI_TRUST_WORKSPACE`). `GeminiEventParser` junta los fragmentos de texto en mensajes y normaliza herramientas y resultado; `GeminiCostMeter` cobra cada modelo de `result.stats.models` a su precio (precios de `gemini-2.5-*` y `gemini-3*` de agregadores, sin comprobar en la página de Google). Formato en [`gemini-stream-json.md`](gemini-stream-json.md), escrito a partir del código fuente de Gemini CLI y sin grabar con el CLI real.
- Salida estructurada sin soporte del CLI (decisión 6A): `runner/provider/StructuredOutput` añade al prompt la instrucción con el schema, toma el último bloque JSON del último mensaje y comprueba campos y tipos del subconjunto de JSON Schema que admite `output`, sin librería nueva. Si no cumple, `error_structured_output` y el agente falla.
- `AgentProviderKind.GEMINI` (reanudar y salida estructurada; bifurcar responde 409). `provider: gemini` deja de ser `UNSUPPORTED`; los avisos de `tools` y `maxTurns` valen para cualquier proveedor que no los admite. `SKYNET_GEMINI_BIN` (por defecto `gemini`) y fake-gemini como tercer lanzador de `tools/fake-claude`.

**Implementado (PV-B), Codex:**

- `runner/provider/codex`: `CodexProvider` lanza `codex exec --json` (arranque, `exec resume <thread>` y `exec fork <thread>`), traduce `permissionMode` a sandbox (`plan` → `read-only`, `bypassPermissions` → sin sandbox, el resto → `workspace-write`) y escribe el `output` del agente en modo estricto (`additionalProperties: false`, todo `required`) para `--output-schema`. `CodexEventParser` normaliza los eventos y `CodexCostMeter` estima el coste con la tabla de precios, que ahora incluye los `gpt-5*` de OpenAI. El detalle está en [`codex-exec-json.md`](codex-exec-json.md); los fixtures están escritos a mano a partir del código de Codex, no grabados, a falta de una cuenta de OpenAI.
- `AgentEnvironment` reúne el filtrado del entorno de todos los proveedores: variables básicas, las del proveedor y `SKYNET_AGENT_ENV`. `SKYNET_CODEX_BIN` (por defecto `codex`).
- Control plane: `provider: codex` deja de ser `UNSUPPORTED`; con Codex, `tools` y `limits.maxTurns` se ignoran con aviso. `RESULT.costUsd` (coste de la invocación) se acepta además de `costUsdCumulative`: el acumulado es el de la predecesora más el de la invocación.
- Prueba real (2026-10-12): Claude Code y Codex reales lanzan, continúan y bifurcan. Se corrigió que una continuación de una continuación (p. ej. bifurcar tras enviar un mensaje) perdía el modelo, el modo de permisos y el `output` del agente del YAML: las continuaciones se ejecutan en ejecuciones `adhoc`, y ahora la política se resuelve desde el lanzamiento original del linaje.
- fake-codex: segundo lanzador del módulo `tools/fake-claude` (`bin/fake-codex`), en vez de renombrarlo a `fake-agent`: así no cambian las rutas que ya usan las guías y los E2E.

**Implementado (PV-A), interfaz `AgentProvider`:**

- El corte de §4.1 cambia: el proveedor **traduce** y el runner **ejecuta**. `runner/provider/AgentProvider` construye la línea de comandos (arranque, reanudación o fork), filtra el entorno, crea el `EventParser` y el `CostMeter` de cada invocación, prepara la sesión en otro worktree al bifurcar (`copySession`) y dice su versión. Lanzar, supervisar, cancelar, el tiempo máximo y los artefactos siguen en `AgentExecutor`, iguales para todos. `ClaudeCodeProvider` es la primera implementación, sin cambios de comportamiento; los comandos (`provider: command`) siguen su camino aparte, con el entorno de Claude Code como antes.
- `protocol/AgentProviderKind`: proveedores conocidos y sus capacidades (`RESUME`, `FORK`, `STRUCTURED_OUTPUT`, `MAX_TURNS`), compartidos por runner y control plane. `StartAgent.provider` (`null` = `claude-code`); el runner falla la invocación con «Este runner no tiene X disponible» si le llega uno que no tiene, antes de crear el worktree.
- `ProviderRegistry`: al arrancar prueba `--version` de cada CLI y declara los que responden; `SKYNET_PROVIDERS` fija la lista a mano. `RunnerRegistration.providers` y `RunnerHeartbeat.providers` (un runner que reutiliza su token tras reiniciar no se registra otra vez; el latido actualiza la lista y emite `runner.providers.changed`). `providerVersion` se mantiene para control planes anteriores; un runner que no declara `providers` cuenta como solo `claude-code`.
- Migración `V16`: tabla `runner_provider`, rellenada con `claude-code` para los runners existentes. `CommandQueue.claim` solo entrega un `START` sin asignar a un runner con su proveedor (los comandos, a cualquiera). Continuar o bifurcar una sesión responde 409 si el proveedor no lo admite.
- `agent_run.provider` sale del `provider` del agente del YAML (`EffectivePolicy.provider`); el modelo global `skynet.agent.model` solo se aplica a Claude Code.
- Dashboard: `providerShortages` (agentes en cola de un proveedor sin ningún runner en línea que lo tenga) en «Necesita tu atención». Runners y la tarjeta de cada runner listan sus proveedores con la versión.
- fake-claude responde a `--version`. El cambio de nombre a `fake-agent` del plan se deja para PV-B (allí se decidió añadir un segundo lanzador, `fake-codex`, en su lugar).

**Implementado (W1-A), definiciones en el backend:**

- Módulo `definition`: `DefinitionParser` lee el YAML con SnakeYAML (posición de cada nodo) y lo valida: claves desconocidas con sugerencia, ids, tipos, dependencias inexistentes, a sí misma y ciclos (con la cadena), agentes, herramientas, modo de permisos, límites, variables de los prompts (línea y columna exactas dentro de un bloque `|`) y el worktree de las fases con varias dependencias (`workspaceFrom`). Lo que el motor aún no ejecuta es `UNSUPPORTED`: válido, pero no publicable. El formato está en [`workflows.md`](workflows.md).
- JSON Schema en `protocol` (`workflow-definition.schema.json`) para el editor de W1-B; `SchemaConsistencyTest` comprueba que coincide con el parser.
- Migración `V12`: tabla `workflow` (clave, archivado) y estado (`DRAFT`, `VALIDATED`, `PUBLISHED`), nombre, fechas y `revision` en `workflow_definition`; como mucho un borrador por workflow, y un trigger que impide modificar o borrar una versión publicada salvo en una purga. `adhoc` v1 pide el prompt como `inputs.prompt`.
- API `/api/workflows` y `/api/workflow-versions` (crear, validar, guardar con `revision`, publicar, versión siguiente, descartar, archivar). Eventos `workflow.definition.created`, `.published` (con el `sha256` del YAML), `.discarded`, `workflow.archived` y `workflow.restored`. `/api/workflow-definitions` lista solo las versiones publicadas.

**Implementado (W1-B), definiciones en la web:**

- Menú **Workflows** (`/workflows`): lista con la versión publicada y el borrador, búsqueda y archivados. **Nuevo workflow** (`/workflows/new`) parte de una plantilla o de un archivo importado y lo guarda como borrador de la v1.
- Página de un workflow (`/workflows/<id>`): chips de versiones, YAML en Monaco con `monaco-yaml` (autocompletado y ayuda del JSON Schema, que sirve `GET /api/workflows/schema`), validación mientras se escribe con `POST /api/workflows/validate` (con `key` y `version`, los mismos mensajes que al guardar), problemas marcados en su línea y en una lista que lleva a ellos, y un resumen de fases, datos y agentes. Un borrador se guarda (Ctrl+S), se importa, se publica (con confirmación y sus avisos) o se descarta; una versión publicada solo se lee y **Editar** abre el borrador de la siguiente.
- `new`, `schema` y `validate` quedan reservados como id de workflow. Los tipos de fase salen en la API con su nombre del YAML (`agent`, `human-approval`…).
- Contratos `workflows`, `workflow` y `workflow-version`; E2E `workflows.e2e.ts` (importar, error en vivo, publicar, editar y descartar, con axe).

**Implementado (W2-A), motor en el backend y el runner:**

- Módulo `engine`: `Planner` (función pura: qué fases pasan a `READY`, se omiten, se arrancan o se cancelan y cuándo termina la ejecución), `RunEvaluator` (bloquea la ejecución y aplica los pasos con `RunSteps` del módulo `workflow`) y `JobEngine`, que implementa `WorkflowEngine`. El módulo `workflow` no conoce el motor: publica `WorkflowRunChanged` al lanzar, al terminar un agente o una verificación, al cancelar un agente en cola y al cancelar una ejecución.
- Migración `V13`: `repository_id`, `inputs` y `launch_limits` en `workflow_run` (el motor arranca cada fase con ellos, aunque sea tras un reinicio) y la tabla `workflow_job` (un trabajo por ejecución con `generation`, `run_after`, alquiler `locked_until`, intentos y último error). El cambio se evalúa en su transacción, en un punto de guardado; lo que falla o espera lo recogen los workers con `SKIP LOCKED` y alquiler, con reintentos crecientes, y al arrancar se encolan las ejecuciones sin terminar.
- Lanzar con `definitionId` e `inputs` (validados contra el YAML), prompts con variables (`{{workItem.*}}`, `{{project.*}}`, `{{inputs.*}}`), política efectiva por agente (herramientas, modo de permisos y límites recortados a la política del repositorio) y `POST /api/workflow-runs/{id}/cancel`. `adhoc` (y reanudar, bifurcar o reintentar) pasa por el mismo modelo: la ejecución guarda sus datos y el motor la cierra.
- Fail-fast: una fase fallida o cancelada cancela las demás. Una fase continúa el worktree de su dependencia (`StartAgent.workspace`): sesión nueva en el worktree existente, en su runner, y espera si hay una verificación en curso. Las órdenes de arranque fijadas a un runner esperan capacidad como las reanudaciones.
- Eventos nuevos `stage.pending`, `stage.skipped` y `stage.start.failed`; `workflow.started` lleva `definitionId`. La web los describe en la actividad y la línea de tiempo.
- Tests: `PlannerTest`, `EffectivePolicyTest`, `LaunchInputsTest` y `WorkflowEngineIT` (fases en paralelo con dependencia opcional y worktree heredado, espera a la verificación, fail-fast, trabajo con el alquiler vencido retomado una sola vez, cancelar y validar los datos).

**Implementado (W2-B), motor en la web:**

- Lanzar desde un trabajo: **Lanzar workflow** con el workflow publicado (por defecto `adhoc`), sus datos de entrada por tipo, el repositorio, los límites y la política efectiva de cada fase (`GET /api/workflow-versions/{id}/effective-policy?repositoryId=`).
- Vista de ejecución: `RunView` lleva `workflow` (clave, versión y nombre) y cada fase su `name`, su `agent` y su `dependsOn`, en el orden del YAML. El árbol enseña todas las fases desde el principio con «Pendiente», «Lista» y «Omitida», a qué fase espera cada una, la cascada (omitida o cancelada por otra fase) y resalta todas las fases en marcha. Con varias fases, la cabecera cancela la ejecución entera.
- E2E `engine.e2e.ts`: un workflow de tres fases (dos en paralelo y una que las une) lanzado desde la web termina «Completada» con un agente por fase aunque se reinicie el control plane a mitad. Es el criterio de aceptación de W2.
- Contrato nuevo `effective-policy`; `run-view` con `workflow`, `name`, `agent` y `dependsOn`.

**Implementado (W3-A), fases `command` y modelo por agente:**

- Fase `command` (`command: ./gradlew test`): el runner ejecuta el comando con `sh -c` en el worktree de la fase, con las reglas de worktree de una fase `agent` (la siguiente puede continuarlo), el entorno que el repositorio permite a sus agentes y el tiempo máximo de las verificaciones. Termina bien solo con código 0. Sin `agent`, `prompt` ni variables. El formato está en [`workflows.md`](workflows.md).
- Un comando es una invocación de su fase con proveedor `command` (`agent_run.provider`), sin tabla nueva: reutiliza la cola de órdenes (`StartAgent.command`), el worktree, la cancelación, el tiempo máximo, el journal, los latidos y la reconciliación de M6-C. El runner lo cuenta como una herramienta `Bash` (`agent.tool.started` y `agent.tool.completed` con los últimos 16 KB de la salida) y sube el comando, `command.log` y los cambios del worktree. No se verifica después y no se continúa, bifurca ni reintenta por separado (409).
- `model` en los agentes del YAML (si falta, `skynet.agent.model`) llega al runner en `StartAgent.model`; `provider` queda reservado: `claude-code` por defecto, `codex`, `gemini` y `opencode` salen como `UNSUPPORTED` hasta el hito de proveedores, que va después de W3.
- `effective-policy` lleva `type`, `model` y `command`; la web los enseña al lanzar y en el resumen del workflow.

**Implementado (W3-B), fases `parallel`:**

- Fase `parallel` con `children` (cada hijo con `agent` y, opcionalmente, `id` y `prompt`): una `stage_run` con un `agent_run` por hijo, arrancados a la vez. `agent_run.child_key` (V14) dice qué hijo ejecuta cada uno; la API lo da como `childKey` y `agent.spawned` lo lleva.
- Cada hijo trabaja en un worktree nuevo creado desde una instantánea del worktree heredado (`WorkspaceRef.copy`): el runner reutiliza la copia del fork (`HEAD` más los cambios sin confirmar y los ficheros nuevos) sin tocar el original, y todos van al runner de ese worktree. La fase siguiente continúa el original; `workspaceFrom: <fase>.<hijo>` continúa el de un hijo.
- La fase se cierra con el resultado conjunto de sus agentes cuando han terminado todos (`RunTransitions.finishStage`). Un hijo que falla o se cancela deja la fase «fallando» (`StageState.failing`) y el `Planner` aplica el fail-fast: cancela a sus hermanos y las demás fases.
- `effective-policy` da una entrada por hijo (`review.calidad`); reintentar un hijo usa la política de su agente.

**Implementado (W3-C), condiciones:**

- Salida estructurada mínima, adelantada de W4: `output` (JSON Schema de un objeto en línea) en los agentes del YAML. Llega al runner en `StartAgent.outputSchema`, que lanza `claude` con `--json-schema`; el `structured_output` del resultado se guarda en `agent_run.structured_output` (V15, junto a `output_schema`) y la API lo da como `structuredOutput`. Un agente con `output` que termina sin devolverla falla.
- Lenguaje de condiciones propio (`Condition`), subconjunto de CEL: `&&`, `||`, `!`, comparaciones, `in` con listas, números, textos, `true`, `false` y `null` (que cuenta como falso). Lee `<fase>.status`, `<fase>.exitCode`, `<fase>.<campo>`, `<fase>.<hijo>.<campo>`, `<parallel>.<campo>` (booleano: algún hijo) e `inputs.<nombre>`. Se valida al publicar (sintaxis, que el dato exista en una fase de la que depende y los tipos) con línea y columna.
- `condition` en cualquier fase: el motor la evalúa al arrancar la fase (`stage.condition.evaluated` con los valores leídos) y, si es falsa, la omite (`stage.skipped` con motivo `condition-false`). `type: conditional` es una fase sin trabajo que termina bien si su condición es verdadera.
- La web enseña la condición en el resumen del workflow y explica en el timeline cada evaluación y cada omisión por condición.

**Implementado (W3-D), nodos en la web y E2E:**

- Nuevo workflow empieza desde una plantilla: «Dos fases» o «Revisión en paralelo» (implementar → tests `command` → revisión de calidad y de seguridad en una fase `parallel` con `output: hasBlockingFindings` → `fix` con `condition: review.hasBlockingFindings` → tests finales con `dependsOn: [review, "fix?"]`). El autocompletado del editor ya cubre `command`, `children`, `condition` y `output` porque sale del JSON Schema.
- El aviso de publicar lista los comandos de shell del workflow.
- La vista de ejecución (`RunView` lleva ahora el `type`, el `command` y la `condition` de cada fase) enseña en el progreso el comando y su código de salida, los hijos de una fase `parallel` con su salida estructurada y la condición con su resultado («Omitida: review.hasBlockingFindings es falsa», «review.hasBlockingFindings se cumplió»).
- fake-claude acepta `FAKE_CLAUDE_STRUCTURED_OUTPUT=@fichero` para leer la salida del directorio de trabajo.
- E2E `nodes.e2e.ts`, criterio de aceptación de W3: la plantilla, publicada y lanzada desde la web, termina «Completada» con hallazgos (corrige) y sin ellos (omite la corrección), con axe en la plantilla, el aviso de publicar y la ejecución.

---

## 8. Fase 3 — SDD completo

| Hito | Contenido |
|---|---|
| GH GitHub conectado (antes S1) — **hecho** | GitHub App propia: instalaciones y enlace de cada repositorio, tokens de instalación limitados a un repositorio, webhooks firmados con cola y red de seguridad, eventos `github.*`, Ajustes → GitHub ([`github.md`](github.md)). GH-A #196, GH-B #197 y GH-C |
| EN Entrega por PR (antes S2) — **en curso** | `github-pr` (push de rama + PR con descripción y evidencias; EN-A), `github-check` (espera de CI dirigida por webhooks y hasta 2 rondas de corrección; EN-B), `github-merge` (tras gate; EN-C). La verificación post-merge queda para más adelante |
| S3 Plantillas y agentes | Prompts versionados de analyst, architect, developer, test, quality-reviewer, security-reviewer; esquemas de salida |
| S4 Workflow `sdd-feature` | El YAML de §11 funcionando de extremo a extremo, incluida la fase `fix-review-findings` |
| S5 Workflows adicionales | Bug fix, revisión de PR, actualización de dependencias |
| S6 Seguridad | Revisores en solo lectura, secretos bajo demanda con aprobación, separación de contenido no confiable (issues, webs) en los prompts, bloqueo de comandos destructivos |
| S7 Métricas | Dashboards de §22 por agente, fase, workflow y proyecto |

**Estimación: 8–10 semanas.**

## 9. Fase 4 — Inteligencia de orquestación

Se planificará con datos reales de las fases anteriores: descomposición dinámica, selección de agentes y modelos, estimación de costes, replanificación, segundo `AgentProvider` (p. ej. Codex u OpenCode) para validar la abstracción, y evaluación de migración a Temporal.

---

## 10. Estrategia de pruebas

- **Unitarias:** máquina de estados (todas las transiciones), parser NDJSON con fixtures reales, validador de DAG, agregador del timeline.
- **Integración (Testcontainers):** ingestión idempotente, outbox → SSE, scheduler con concurrencia (`SKIP LOCKED`), migraciones.
- **Runner:** supervisor con procesos reales (árbol de hijos, kill, timeout), worktrees sobre repos temporales, journal con corte de red simulado.
- **Contrato:** `protocol` con JSON Schemas compartidos; tests en ambos lados.
- **E2E:** Playwright + docker compose + runner + fake-claude; uno por criterio del MVP.
- **Manual periódica:** smoke test con `claude` real sobre un repositorio de prueba, con presupuesto bajo.

---

## 11. Riesgos de implementación

| Riesgo | Mitigación |
|---|---|
| Cambios en el formato `stream-json` o en flags del CLI | Fixtures versionados, parser tolerante (`agent.raw`), test de compatibilidad contra la versión instalada al arrancar el runner |
| Coste de pruebas con Claude real | fake-claude por defecto; límites de presupuesto en smoke tests |
| Volumen de eventos de streaming parcial | Agregar deltas en el runner y persistir mensajes completos; deltas solo en vivo por SSE |
| Procesos huérfanos tras caídas | Grupos de procesos, registro de PIDs en el journal, reconciliación al arrancar |
| Un cliente SSE lento frena la difusión (el dispatcher envía en serie) | Aceptable con pocos clientes en el MVP; si crece, cola por suscriptor y envío asíncrono |
| Alcance de Fase 2 crece hacia "Temporal casero" | Interfaz `WorkflowEngine` estrecha; criterio explícito para migrar (timers largos, señales complejas, volumen) |

---

## 12. Próximos pasos

1. ~~Validar las decisiones abiertas de §2.~~ Hecho.
2. ~~Spike de M0 y ajuste de §4.2 con el NDJSON real.~~ Hecho.
3. ~~Backlog de Fase 1 como issues en GitHub.~~ Hecho (épica #2 y una issue por hito).
4. ~~Fase 1 (M0–M6).~~ Terminada el 2026-10-08.
5. ~~**Rediseño visual del control plane**, antes de la Fase 2.~~ Terminado con UI-A a UI-F (issue #37); el resultado está en la [guía de estilo](ui.md). Los colores se personalizan en Ajustes > Apariencia desde UI-G (issue #47).
6. ~~Fase 2, empezando por W1 (definiciones) y W2 (motor) como primer hito.~~ Terminada el 2026-10-19 (W1–W8).
7. ~~Fase 3, empezando por GitHub conectado (GH, antes S1).~~ GH terminado: GitHub App, webhooks firmados y Ajustes → GitHub ([`github.md`](github.md)).
8. En curso: EN, la entrega por PR. EN-A trae la fase `github-pr` (push de la rama con un token por invocación y la PR con evidencias). Faltan EN-B (esperar la CI y corregirla) y EN-C (fusionar tras el gate, web y E2E).
