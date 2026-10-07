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
- `workspace/WorkspaceManager`: `git worktree add -b skynet/<trabajo>/<agente> $SKYNET_RUNNER_HOME/workspaces/<ejecución>/<agente> <rama base>`. Emite `agent.workspace.ready` (ruta, rama, commit base). El worktree se conserva al terminar; la limpieza es de M6.
- `provider/claude/ClaudeCodeProvider`: `claude -p … --output-format stream-json --verbose --include-partial-messages --session-id … --permission-mode … --allowedTools … [--model] [--max-turns] [--max-budget-usd]`.
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
GET        /api/events?workflowRunId=&aggregateId=&after=&limit=
GET        /api/events/stream?workflowRunId=&aggregateId=   # SSE; reanuda con Last-Event-ID
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

- Spring Security con un usuario único (`SKYNET_ADMIN_USER`, `SKYNET_ADMIN_PASSWORD`). Sin contraseña genera una y la escribe en el log; con `SKYNET_REQUIRE_ADMIN_PASSWORD=true` (Docker Compose) no arranca.
- La web entra con `POST /api/auth/login` (sesión por cookie, `HttpOnly` y `SameSite=Lax`; el identificador cambia al entrar), consulta `GET /api/auth/session` y sale con `POST /api/auth/logout`. Caduca tras `SKYNET_SESSION_TIMEOUT` (12 h) sin uso.
- CSRF por cookie (`XSRF-TOKEN`, cabecera `X-XSRF-TOKEN`) en todo lo que cambia algo. No se pide a peticiones con `Authorization` (HTTP Basic de scripts, o el runner) ni a `/api/runner/**`.
- Un 401 nunca lleva `WWW-Authenticate`, para que el navegador no abra su diálogo. Quedan abiertos `/actuator/health`, el login y la API del runner, que tiene su token.
- CORS cerrado salvo los orígenes de `SKYNET_CORS_ORIGINS`.
- `POST /api/runners/{id}/revoke` invalida el token de un runner (`runner.token.revoked`). El runner legítimo recibe 401 y se vuelve a registrar con el secreto de registro.
- Web: página de login, «Salir» en la barra superior y vuelta al login sin cambiar de página cuando la sesión caduca (también si el stream en vivo falla por eso). «Revocar token…» en Runners, con confirmación.

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

---

## 8. Fase 3 — SDD completo

| Hito | Contenido |
|---|---|
| S1 GitHub App | Instalación, tokens de instalación, webhooks firmados (`pull_request`, `check_suite`, `check_run`, `pull_request_review`) |
| S2 Nodos GitHub | `github-pr` (push de rama + PR con descripción y evidencias), `github-check` (espera de CI dirigida por webhooks), `github-merge` (tras gate), verificación post-merge |
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
2. Ejecutar el spike de M0 y ajustar §4.2 con el NDJSON real.
3. Crear el backlog de Fase 1 (épicas M0–M6) como issues en GitHub.
4. Arrancar M0.
