# Sistema web de orquestación de flujos agénticos para desarrollo de software

## 1. Resumen ejecutivo

Actualmente, el proceso de desarrollo se ejecuta principalmente desde un terminal mediante Claude Code y flujos SDD. Claude recibe una tarea, crea o coordina agentes, genera especificaciones, planes, tests y código, abre pull requests, realiza revisiones y puede completar el merge. Aunque este enfoque permite automatizar gran parte del ciclo de desarrollo, presenta varias limitaciones:

- La actividad se observa como texto en uno o varios terminales.
- No existe una representación visual del workflow completo.
- Resulta difícil saber qué agentes están activos, bloqueados o esperando información.
- Las fases del desarrollo no están representadas de forma explícita.
- Los prompts, decisiones y artefactos quedan dispersos.
- Las sesiones terminadas se guardan, pero dejan de formar parte de la experiencia habitual de trabajo.
- La interacción con un agente en ejecución o con una fase concreta no está centralizada.
- El estado real del proceso depende en exceso del contexto de Claude y de la interpretación de sus mensajes.

El objetivo del proyecto es crear una aplicación web que funcione como **plano de control, observabilidad y auditoría para procesos de desarrollo ejecutados por agentes de IA**.

La plataforma permitirá:

- Crear y ejecutar workflows de desarrollo.
- Visualizar sus fases y dependencias.
- Lanzar y supervisar agentes.
- Consultar los prompts enviados y las respuestas recibidas.
- Observar en tiempo real llamadas a herramientas, comandos y actividad.
- Interactuar con agentes y reanudar sus sesiones.
- Gestionar aprobaciones humanas.
- Visualizar especificaciones, planes, diffs, tests, revisiones, commits, pull requests y otros artefactos.
- Conservar un historial completo y auditable de cada proyecto.
- Utilizar inicialmente Claude Code, sin acoplar el producto permanentemente a un único proveedor.

La principal decisión arquitectónica es la siguiente:

> El workflow y su estado no deben vivir dentro de los prompts ni depender de la memoria de Claude. Deben pertenecer al sistema de orquestación.

Claude puede proponer acciones, ejecutar una etapa y devolver resultados. Sin embargo, una aplicación determinista debe decidir qué fase está activa, qué dependencias se han satisfecho, cuándo reintentar, cuándo pedir aprobación y cuándo el trabajo está realmente terminado.

---

## 2. Visión del producto

El producto no debe plantearse simplemente como una interfaz gráfica para Claude Code.

Debe concebirse como:

> Una plataforma de control, ejecución y auditoría para workflows de ingeniería de software realizados por agentes intercambiables.

Claude Code será el primer proveedor integrado, pero el sistema debería poder admitir posteriormente:

- Claude Code.
- OpenAI Codex.
- OpenCode.
- Agentes propios.
- Comandos y scripts locales.
- GitHub Actions.
- Procesos de CI/CD.
- Herramientas de análisis estático.
- Sistemas externos integrados mediante API o MCP.

La abstracción central no debe ser `ClaudeSession`, sino `AgentProvider`:

```text
AgentProvider
 ├── start()
 ├── resume()
 ├── sendMessage()
 ├── cancel()
 ├── streamEvents()
 └── collectArtifacts()
```

---

## 3. Objetivos

### 3.1. Objetivos principales

1. Hacer visibles los workflows agénticos de principio a fin.
2. Representar explícitamente cada fase del ciclo de desarrollo.
3. Mostrar agentes activos, finalizados, bloqueados o esperando intervención.
4. Permitir interacción humana en puntos controlados.
5. Centralizar prompts, eventos, decisiones y artefactos.
6. Mantener un historial persistente y auditable.
7. Garantizar aislamiento entre ejecuciones concurrentes.
8. Integrar Git, GitHub, tests, CI, PR y merge.
9. Evitar que el estado crítico dependa de texto libre generado por un LLM.
10. Permitir incorporar distintos motores de agentes en el futuro.

### 3.2. Objetivos secundarios

- Medir tokens, costes y tiempos por agente, fase y proyecto.
- Reintentar ejecuciones fallidas de manera segura.
- Comparar resultados entre agentes o modelos.
- Reutilizar workflows entre proyectos.
- Detectar cuellos de botella y trabajos bloqueados.
- Facilitar revisiones humanas o automáticas.
- Conservar evidencia de especificaciones, implementación, validación y entrega.

### 3.3. Fuera del alcance inicial

- Construir un IDE completo.
- Sustituir todas las capacidades de Claude Code.
- Crear desde el primer día un motor genérico equivalente a Temporal.
- Ejecutar agentes no supervisados con permisos globales sobre la máquina.
- Automatizar despliegues destructivos sin aprobaciones.
- Implementar aprendizaje autónomo avanzado antes de disponer de observabilidad fiable.

---

## 4. Principios de diseño

### 4.1. El orquestador es la fuente de verdad

El estado del workflow pertenece al backend. Los agentes no mantienen el estado global ni deciden unilateralmente qué fases se han completado.

### 4.2. Los agentes ejecutan trabajo; el workflow gobierna

Un agente puede:

- Leer contexto.
- Modificar código.
- Ejecutar herramientas.
- Producir artefactos.
- Proponer el siguiente paso.
- Comunicar bloqueos.

El motor de workflow decide:

- Si el trabajo cumple las condiciones de salida.
- Si una dependencia está satisfecha.
- Si debe ejecutarse otro agente.
- Si corresponde un reintento.
- Si es necesaria una aprobación humana.
- Si el workflow puede avanzar.

### 4.3. Estado explícito, no inferido

No se debe deducir una fase mediante reglas como:

```text
Si stdout contiene "running tests", mostrar fase Testing.
```

Los mensajes generados por un LLM son variables y no constituyen una fuente de verdad fiable. Las fases, estados y transiciones deben registrarse de forma estructurada.

### 4.4. Eventos inmutables

Toda actividad relevante debe producir un evento persistente:

- Inicio o finalización de una fase.
- Lanzamiento de un agente.
- Prompt enviado.
- Herramienta ejecutada.
- Artefacto generado.
- Aprobación solicitada.
- Error o reintento.
- Creación de commit o pull request.
- Resultado de CI.
- Merge.

### 4.5. Verificación independiente

Si un agente afirma que los tests han pasado, el sistema debe comprobar el proceso, su código de salida y los informes generados. Si afirma haber creado un commit o una PR, la plataforma debe verificar su existencia.

### 4.6. Aislamiento por ejecución

Los agentes escritores no deben compartir el mismo directorio de trabajo. Cada ejecución utilizará un `git worktree` independiente.

### 4.7. Seguridad por defecto

Los agentes solo recibirán las herramientas, secretos y permisos necesarios para su fase.

---

## 5. Modelo conceptual

```text
Proyecto
 └── Trabajo / Feature
      └── Ejecución del workflow
           ├── Fases
           │    ├── Especificación
           │    ├── Planificación
           │    ├── Implementación
           │    ├── Tests
           │    ├── Revisión
           │    ├── Pull request
           │    ├── CI
           │    ├── Merge
           │    └── Verificación
           │
           ├── Ejecuciones de agentes
           ├── Eventos
           ├── Artefactos
           └── Aprobaciones humanas
```

### 5.1. Proyecto

Representa un producto o repositorio, por ejemplo:

- TokenMeter.
- Akademia.
- Forma.

Contiene configuración de repositorios, workflows disponibles, políticas, credenciales referenciadas y agentes permitidos.

### 5.2. Trabajo o feature

Representa una unidad de trabajo:

- Nueva funcionalidad.
- Bug.
- Refactoring.
- Actualización de dependencias.
- Incidente.
- Revisión de seguridad.

Puede estar vinculado a una issue de Jira, GitHub o cualquier otro sistema.

### 5.3. WorkflowRun

Es una ejecución concreta de un workflow para un trabajo.

### 5.4. StageRun

Es la ejecución de una fase. Una fase puede lanzar uno o varios agentes y producir uno o varios artefactos.

### 5.5. AgentRun

Es una ejecución concreta de un agente. Debe estar asociada a:

- Una fase.
- Una sesión del proveedor.
- Un runner.
- Un workspace.
- Un prompt.
- Un conjunto de permisos.
- Eventos y artefactos.

### 5.6. Diferencia entre fase y agente

Una fase representa una parte del proceso. Un agente representa una ejecución que trabaja en esa fase.

```text
IMPLEMENTACIÓN
 ├── backend-agent
 ├── frontend-agent
 └── database-agent
          │
          ▼
TESTS
 ├── unit-test-agent
 └── integration-test-agent
          │
          ▼
REVIEW
 ├── quality-reviewer
 └── security-reviewer
```

Una fase puede lanzar varios agentes en paralelo. Un agente puede fallar y ser sustituido sin perder el estado global del workflow.

---

## 6. Estados

### 6.1. Estados de una fase

```text
PENDING
READY
STARTING
RUNNING
WAITING_FOR_INPUT
WAITING_FOR_APPROVAL
SUCCEEDED
FAILED
CANCELLED
SKIPPED
```

### 6.2. Estados observables de un agente

- **Queued:** esperando un runner.
- **Starting:** preparando worktree, contexto y proceso.
- **Thinking:** recibiendo tokens sin una herramienta activa.
- **Executing:** ejecutando una herramienta o comando.
- **Waiting for input:** el agente necesita información del usuario.
- **Waiting for approval:** se requiere autorización externa.
- **Unresponsive:** el proceso sigue vivo, pero no hay eventos durante un umbral configurable.
- **Completed:** ejecución finalizada correctamente.
- **Failed:** ejecución terminada con error.
- **Cancelled:** ejecución cancelada.

El término `idle` debe reservarse preferiblemente para un runner conectado sin trabajo asignado. Si un proceso está vivo pero no produce actividad, la interfaz debe mostrar información objetiva:

```text
RUNNING · sin actividad visible durante 2m 14s
```

No debe asumir que está bloqueado: podría estar esperando una respuesta de modelo o ejecutando una prueba larga.

---

## 7. Arquitectura de alto nivel

```text
┌───────────────────────────────────────────────────────────┐
│                       Aplicación web                      │
│                                                           │
│  Proyectos · DAG · Agentes · Timeline · Artefactos        │
│  Logs · Prompts · Costes · Aprobaciones · Interacción     │
└─────────────────────────────┬─────────────────────────────┘
                              │ REST + SSE/WebSocket
┌─────────────────────────────▼─────────────────────────────┐
│                    Orchestration API                      │
│                                                           │
│  Workflow service     Agent service     Artifact service  │
│  Approval service     Project service   GitHub service    │
└───────────────┬─────────────────────────────┬─────────────┘
                │                             │
┌───────────────▼─────────────┐   ┌───────────▼─────────────┐
│ Durable Workflow Engine     │   │ PostgreSQL / Storage    │
│                             │   │                         │
│ Dependencias, señales,      │   │ Estado consultable      │
│ retries, timeouts, gates    │   │ Eventos y artefactos    │
└───────────────┬─────────────┘   └─────────────────────────┘
                │ trabajos
┌───────────────▼───────────────────────────────────────────┐
│                      Runner local                         │
│                                                           │
│  Process supervisor · Worktrees · Claude adapter          │
│  Git adapter · Test adapter · GitHub adapter              │
└───────────────┬───────────────────────────────────────────┘
                │
       ┌────────┴──────────────────────────────────┐
       │                                           │
┌──────▼──────────┐                       ┌────────▼─────────┐
│ claude -p       │                       │ claude -p        │
│ implementation │                       │ code review      │
│ worktree A      │                       │ worktree B       │
└─────────────────┘                       └──────────────────┘
```

---

## 8. Componentes

### 8.1. Aplicación web

Responsable de proporcionar:

- Gestión de proyectos.
- Creación de trabajos.
- Visualización del DAG.
- Estado de fases y agentes.
- Actividad en tiempo real.
- Exploración de prompts y respuestas.
- Visualización de artefactos.
- Aprobaciones.
- Interacción con agentes.
- Reintentos y cancelaciones.
- Histórico y auditoría.

### 8.2. Orchestration API

Servicios principales:

- `ProjectService`
- `WorkItemService`
- `WorkflowService`
- `StageService`
- `AgentService`
- `RunnerService`
- `PromptService`
- `ArtifactService`
- `ApprovalService`
- `EventService`
- `GitService`
- `GitHubService`
- `CostService`

### 8.3. Runner local

Daemon instalado en la máquina donde se encuentran los repositorios y Claude Code.

Responsabilidades:

- Registrarse en el control plane.
- Enviar heartbeats.
- Recibir asignaciones.
- Crear un worktree aislado.
- Preparar el contexto y el prompt efectivo.
- Lanzar procesos de Claude Code.
- Leer y normalizar salida NDJSON.
- Publicar eventos en tiempo real.
- Ejecutar comandos de validación independientes.
- Indexar commits, diffs, tests e informes.
- Reanudar sesiones.
- Cancelar árboles completos de procesos.
- Mantener una cola local de eventos mientras no exista conexión.

El runner permite desplegar la aplicación web en otro servidor sin darle acceso directo al sistema de archivos de desarrollo.

### 8.4. Motor de workflows

Para una solución madura, Temporal encaja especialmente bien:

- Ejecuciones duraderas.
- Retries y timeouts.
- Esperas humanas de larga duración.
- Señales para workflows activos.
- Cancelación.
- Tareas paralelas.
- Recuperación después de reinicios.
- Historial de eventos.

Opciones:

#### Primera versión

- PostgreSQL.
- Máquina de estados explícita.
- Tabla de trabajos.
- Patrón transactional outbox.
- Workers reclamando tareas mediante `FOR UPDATE SKIP LOCKED`.

#### Evolución

- Temporal Java SDK.
- Spring Boot como backend y workers.

No se recomienda construir desde cero un motor genérico de workflows con todas las capacidades de Temporal si el producto empieza a crecer.

---

## 9. Integración con Claude Code

### 9.1. Ejecución no interactiva

El runner puede lanzar:

```bash
claude -p "$PROMPT" \
  --output-format stream-json \
  --verbose \
  --include-partial-messages \
  --session-id "$SESSION_ID" \
  --max-turns 30 \
  --max-budget-usd 10
```

La salida NDJSON permite capturar:

- Texto en tiempo real.
- Llamadas a herramientas.
- Resultados de herramientas.
- Identificador de sesión.
- Número de turnos.
- Tokens.
- Coste.
- Errores.
- Resultado final.

### 9.2. Reanudación e interacción

Cuando el usuario envía un mensaje desde la web:

```bash
claude -p "Aplica este feedback: ..." \
  --resume "$CLAUDE_SESSION_ID" \
  --output-format stream-json \
  --verbose
```

Acciones de la interfaz:

- **Enviar mensaje:** reanuda la sesión con un nuevo prompt.
- **Interrumpir:** cancela el proceso activo.
- **Reintentar:** crea un nuevo `AgentRun`.
- **Fork:** reanuda la sesión mediante `--fork-session`.
- **Aprobar:** desbloquea una fase dependiente.
- **Solicitar cambios:** crea una fase o ejecución correctiva.

Para la primera versión, pausar y reanudar sesiones es más sencillo que mantener procesos PTY interactivos abiertos indefinidamente.

### 9.3. Hooks de Claude

Hooks relevantes:

- `UserPromptSubmit`
- `PreToolUse`
- `PostToolUse`
- `Notification`
- `SubagentStop`
- `Stop`

Los hooks pueden enviar eventos al runner o escribirlos en un socket local. Permiten observar llamadas a herramientas, cambios de archivos y finalización sin depender de interpretar visualmente el terminal.

### 9.4. Resultados estructurados

Cada agente debe devolver un contrato validable:

```json
{
  "outcome": "SUCCESS",
  "summary": "Implementada la validación de tokens",
  "artifacts": [
    {
      "type": "GIT_COMMIT",
      "value": "ab12cd34"
    },
    {
      "type": "TEST_REPORT",
      "path": "build/reports/tests/test/index.html"
    }
  ],
  "checks": {
    "testsPassed": true,
    "lintPassed": true
  },
  "suggestedNextStage": "CODE_REVIEW"
}
```

El orquestador verifica los artefactos y checks antes de completar la fase.

---

## 10. Gestión de agentes y subagentes

Claude puede crear subagentes internamente, pero esto reduce la observabilidad del control plane.

Recomendación inicial:

> Cada agente que deba visualizarse y controlarse debe corresponder a un proceso administrado por el runner.

En lugar de pedir a un Claude principal que cree tres subagentes opacos:

```text
Crea agentes para implementar, probar y revisar.
```

El orquestador crea tres ejecuciones explícitas:

```text
implementation-agent → proceso Claude 1
test-agent           → proceso Claude 2
review-agent         → proceso Claude 3
```

Cada ejecución dispone de:

- Estado.
- Prompt.
- Sesión.
- Logs.
- PID.
- Worktree.
- Coste.
- Artefactos.
- Duración.
- Dependencias.
- Controles de cancelación y reanudación.

En una fase posterior se podrán capturar subagentes internos mediante hooks, pero no deben constituir la base del modelo de orquestación.

---

## 11. Definición declarativa de workflows

Los workflows deben declararse mediante YAML versionado:

```yaml
id: sdd-feature
version: 1

inputs:
  issue:
    type: string
    required: true

stages:
  - id: specification
    type: agent
    agent: analyst
    promptTemplate: prompts/specification.md
    outputSchema: schemas/specification.json
    artifacts:
      - openspec/proposal.md
    retry:
      maxAttempts: 2

  - id: plan
    type: agent
    agent: architect
    dependsOn: [specification]
    promptTemplate: prompts/plan.md
    outputSchema: schemas/plan.json
    artifacts:
      - openspec/tasks.md

  - id: approval
    type: human-approval
    dependsOn: [plan]

  - id: implementation
    type: agent
    agent: developer
    dependsOn: [approval]
    workspace: isolated-worktree
    promptTemplate: prompts/implementation.md

  - id: tests
    type: command
    dependsOn: [implementation]
    command: ./mvnw verify
    artifacts:
      - target/surefire-reports/**

  - id: review
    type: parallel
    dependsOn: [tests]
    children:
      - agent: quality-reviewer
      - agent: security-reviewer

  - id: fix-review-findings
    type: agent
    agent: developer
    dependsOn: [review]
    condition: review.hasBlockingFindings

  - id: pull-request
    type: github-pr
    dependsOn:
      - review
      - fix-review-findings?

  - id: ci
    type: github-check
    dependsOn: [pull-request]

  - id: merge-approval
    type: human-approval
    dependsOn: [ci]

  - id: merge
    type: github-merge
    dependsOn: [merge-approval]
```

### 11.1. Tipos de nodos

- `agent`
- `command`
- `parallel`
- `human-approval`
- `conditional`
- `github-pr`
- `github-check`
- `merge`
- `deploy`
- `verification`

### 11.2. Workflows iniciales

- Nueva funcionalidad SDD.
- Corrección de bug.
- Revisión de pull request.
- Refactoring.
- Actualización de dependencias.
- Incidente.
- Cambio de infraestructura.

---

## 12. Modelo de datos

### 12.1. Entidades principales

```text
Project
Repository
WorkItem
WorkflowDefinition
WorkflowRun
StageDefinition
StageRun
AgentDefinition
AgentRun
Prompt
Message
ToolExecution
Event
Artifact
Approval
Workspace
GitReference
Runner
```

### 12.2. AgentRun

```text
id
stage_run_id
agent_definition_id
status
provider
provider_session_id
runner_id
process_id
workspace_id
started_at
last_activity_at
finished_at
exit_code
input_tokens
output_tokens
cost
error
```

### 12.3. Artifact

```text
id
workflow_run_id
stage_run_id
agent_run_id
type
name
uri
sha256
metadata
created_at
```

Tipos de artefactos:

- Especificación.
- Plan.
- Diff.
- Archivo.
- Commit.
- Rama.
- Informe de tests.
- Cobertura.
- Informe de revisión.
- Pull request.
- Resultado de CI.
- Deployment.
- Captura o vídeo.
- Log.

### 12.4. Event

Registro append-only:

```text
id
aggregate_type
aggregate_id
event_type
payload_json
sequence
occurred_at
```

Ejemplos:

```text
workflow.started
stage.ready
stage.started
agent.spawned
agent.status.changed
agent.message.received
agent.tool.started
agent.tool.completed
artifact.created
agent.waiting_for_input
approval.requested
approval.granted
stage.completed
workflow.completed
```

Ejemplo de evento:

```json
{
  "type": "agent.status.changed",
  "workflowRunId": "wr_123",
  "stageRunId": "sr_456",
  "agentRunId": "ar_789",
  "previousStatus": "STARTING",
  "status": "RUNNING",
  "timestamp": "2026-09-28T10:42:31Z"
}
```

El estado actual puede mantenerse en tablas normales optimizadas para consultas, mientras que el timeline se construye a partir de eventos inmutables.

---

## 13. Interfaz web

### 13.1. Vista de proyectos

```text
TokenMeter
├── TKM-142  Add model pricing importer    In review
├── TKM-143  Fix cache invalidation        Testing
└── TKM-144  Upgrade Spring Boot           Waiting approval
```

### 13.2. Vista del workflow

Representación mediante DAG:

```text
Specification ✓
      │
    Plan ✓
      │
 Approval ✓
      │
Implementation ✓
      │
   Tests ✓
      │
 ┌────┴────────┐
Quality review  Security review
      ✓               Running
       └───────┬────────┘
               ▼
              PR
```

Cada nodo mostrará:

- Estado.
- Duración.
- Agente responsable.
- Prompt.
- Resumen.
- Artefactos.
- Coste.
- Reintentos.
- Dependencias.
- Acciones disponibles.

### 13.3. Vista de agentes

```text
🟢 security-reviewer
Fase: Review
Actividad: leyendo SecurityConfig.java
Duración: 04:31
Sesión: 7fa9...
Coste: $0.82
[Ver actividad] [Enviar mensaje] [Interrumpir]
```

### 13.4. Timeline semántico

En lugar de mostrar únicamente stdout:

```text
10:42 Agent started
10:43 Read 14 files
10:45 Executed ./mvnw test
10:48 183 tests passed
10:49 Modified SecurityConfig.java
10:51 Created commit 2fa81c3
10:52 Agent completed
```

Cada entrada permitirá abrir:

- Evento original.
- JSON normalizado.
- Log completo.
- Relación con artefactos.

### 13.5. Vista de artefactos

Pestañas para:

- Specification.
- Plan.
- Archivos modificados.
- Diff.
- Tests.
- Coverage.
- Reviews.
- Commits.
- Pull request.
- CI.
- Deployments.

### 13.6. Centro de aprobaciones

Mostrará acciones pendientes, riesgo, contexto y evidencia.

---

## 14. Human-in-the-loop

El sistema debe incluir gates explícitos para:

- Aprobar especificación.
- Aprobar plan.
- Autorizar cambios destructivos.
- Autorizar acceso temporal a secretos.
- Aprobar publicación de PR.
- Aprobar merge.
- Aprobar despliegue.

Ejemplo:

```text
Acción: fusionar PR #124
Repositorio: tokenmeter
Branch: feature/TKM-142
Checks: 8/8 correctos
Reviews: 2 aprobadas
Cambios: 17 archivos, +481/-93
Riesgo: medio
```

Acciones posibles:

- Aprobar.
- Rechazar.
- Pedir cambios.
- Añadir comentario.
- Delegar aprobación.

Una frase ambigua del usuario no debe convertirse en una autorización permanente.

---

## 15. Artefactos y verificación

Cada fase debe declarar:

- Artefactos esperados.
- Checks obligatorios.
- Condiciones de éxito.
- Condiciones de reintento.
- Evidencia requerida.

Ejemplos:

### Fase de especificación

- Archivo de propuesta existente.
- Esquema válido.
- Secciones obligatorias completas.

### Fase de implementación

- Diff no vacío.
- Rama correcta.
- Commit verificable.
- Sin cambios fuera del ámbito permitido.

### Fase de tests

- Código de salida cero.
- Informes generados.
- Número de tests identificado.
- Cobertura por encima del umbral, si aplica.

### Fase de PR

- Pull request existente.
- Rama y commit correctos.
- Descripción y evidencias incluidas.

### Fase de merge

- Checks en verde.
- Aprobaciones requeridas.
- Commit fusionado en la rama objetivo.
- Rama local y remota tratadas según política.

---

## 16. Seguridad y aislamiento

Cada ejecución utilizará un workspace independiente:

```text
/workspaces/<workflow-run>/<agent-run>/
```

Medidas:

- `git worktree` por agente escritor.
- Agentes revisores preferiblemente en modo de solo lectura.
- Lista de herramientas permitidas por agente.
- Límites de turnos, tiempo y presupuesto.
- Entrega de secretos solo cuando sean necesarios.
- Filtrado de variables de entorno.
- Bloqueo de comandos destructivos.
- Aprobación para publicación, merge y despliegue.
- Prohibición de dos agentes escritores en el mismo worktree.
- Hash de artefactos.
- Auditoría de prompts y acciones.
- Cancelación del árbol completo de procesos.
- Protección contra prompt injection procedente de issues, webs o archivos externos.
- Separación entre contenido no confiable y autorización del usuario.

---

## 17. Stack tecnológico recomendado

### 17.1. Backend

- Java 21.
- Spring Boot.
- PostgreSQL.
- Flyway.
- Spring Security.
- REST.
- SSE para streaming inicial.
- Temporal Java SDK como evolución.
- JGit o CLI Git encapsulado.
- GitHub App y webhooks.

### 17.2. Frontend

- React.
- TypeScript.
- Next.js o Vite.
- React Flow para representar DAGs.
- TanStack Query.
- Monaco Editor para prompts, specs y diffs.
- SSE para eventos.
- WebSocket cuando sea necesaria interacción bidireccional persistente.

### 17.3. Runner

Un servicio ligero, inicialmente también en Java:

- Spring Boot o aplicación Java standalone.
- `ProcessBuilder`.
- Parser NDJSON.
- Supervisor de procesos.
- Gestión de `git worktree`.
- Heartbeats.
- Cola persistente local.
- Reconexión y reenvío de eventos.

### 17.4. Infraestructura inicial

```text
web
api
postgres
runner local
temporal opcional
```

Puede desplegarse inicialmente mediante Docker Compose. Kubernetes no es necesario para la primera versión.

---

## 18. Estrategia de implementación

### Fase 1 — Observabilidad

Objetivo: resolver el principal problema actual sin automatizar todavía todo el SDD.

1. Registrar proyectos y repositorios.
2. Lanzar `claude -p` desde la aplicación.
3. Capturar `stream-json`.
4. Mostrar actividad en tiempo real.
5. Guardar prompts, sesiones y resultados.
6. Mostrar commits, diffs y tests.
7. Reanudar una sesión desde la web.
8. Conservar un timeline persistente.

### Fase 2 — Workflow explícito

1. Definiciones YAML.
2. Fases y dependencias.
3. Estados duraderos.
4. Gates humanos.
5. Retries y cancelación.
6. Uno o varios agentes por fase.
7. Verificación de condiciones de salida.

### Fase 3 — SDD completo

1. Specification.
2. Plan.
3. Aprobación opcional.
4. Implementación.
5. Tests.
6. Revisión paralela.
7. Correcciones.
8. Pull request.
9. CI.
10. Merge.
11. Verificación final.

### Fase 4 — Inteligencia de orquestación

- Descomposición dinámica.
- Selección automática de agentes.
- Rutas alternativas según riesgos.
- Estimación de costes.
- Replanificación automática.
- Comparación entre modelos.
- Memoria reutilizable entre proyectos.
- Aprendizaje basado en revisiones y resultados.

---

## 19. Flujo inicial de extremo a extremo

```text
Usuario crea trabajo
→ backend instancia workflow
→ se activan las fases sin dependencias
→ runner crea worktree
→ runner ejecuta Claude con stream-json
→ los eventos aparecen en la web
→ Claude produce una salida estructurada
→ el backend verifica artefactos y checks
→ la fase se completa
→ la siguiente fase se desbloquea
→ se solicita intervención humana cuando corresponda
→ se crea PR
→ se supervisa CI
→ se aprueba y ejecuta el merge
→ se verifica el resultado
→ se archiva el workflow con todos sus artefactos
```

---

## 20. Ejemplo de workflow SDD

```text
INTAKE
  │
  ▼
SPECIFICATION
  │
  ▼
PLAN
  │
  ▼
HUMAN APPROVAL
  │
  ▼
IMPLEMENTATION
  │
  ▼
TESTS
  │
  ├───────────────┐
  ▼               ▼
QUALITY REVIEW   SECURITY REVIEW
  │               │
  └───────┬───────┘
          ▼
   FIX FINDINGS?
      │       │
     YES      NO
      │       │
      └───┬───┘
          ▼
     PULL REQUEST
          │
          ▼
          CI
          │
          ▼
   HUMAN MERGE APPROVAL
          │
          ▼
        MERGE
          │
          ▼
   POST-MERGE VERIFICATION
          │
          ▼
         DONE
```

---

## 21. Riesgos técnicos

### 21.1. Dependencia excesiva de Claude Code

Mitigación: utilizar una interfaz `AgentProvider` y eventos normalizados.

### 21.2. Subagentes opacos

Mitigación: representar inicialmente cada agente visible como un proceso gestionado por el runner.

### 21.3. Procesos huérfanos

Mitigación: supervisor de procesos, heartbeats y cancelación por grupos de procesos.

### 21.4. Conflictos de Git

Mitigación: worktrees aislados, propiedad única de escritura y fases explícitas de integración.

### 21.5. Pérdida de eventos por desconexión

Mitigación: cola local persistente, identificadores idempotentes y reenvío.

### 21.6. Estados inconsistentes

Mitigación: transiciones validadas, bloqueo optimista, eventos secuenciados y outbox transaccional.

### 21.7. Automatización peligrosa

Mitigación: permisos mínimos y gates humanos para push, merge, deploy y operaciones destructivas.

### 21.8. Costes descontrolados

Mitigación: límites de turnos, presupuesto por ejecución, alertas y cancelación automática.

---

## 22. Métricas

### Por agente

- Tiempo de inicio.
- Tiempo de ejecución.
- Tiempo sin actividad.
- Turnos.
- Tokens de entrada y salida.
- Coste.
- Herramientas ejecutadas.
- Artefactos generados.
- Resultado.

### Por fase

- Duración.
- Reintentos.
- Número de agentes.
- Coste.
- Bloqueos.
- Revisiones necesarias.

### Por workflow

- Lead time total.
- Tiempo automatizado frente a tiempo esperando intervención.
- Coste total.
- Tasa de éxito.
- Número de replanificaciones.
- Número de correcciones tras review.
- Defectos detectados antes y después de merge.

### Por proyecto

- Workflows completados.
- Tiempo medio hasta PR y merge.
- Coste medio por tipo de trabajo.
- Agentes y modelos con mejor rendimiento.
- Fases con más fallos o espera.

---

## 23. Criterios de éxito del MVP

El MVP se considerará válido si permite:

1. Registrar un repositorio.
2. Crear un trabajo.
3. Lanzar una ejecución de Claude Code.
4. Visualizar tokens, mensajes y herramientas en tiempo real.
5. Ver el estado real del agente.
6. Conservar la sesión tras finalizar.
7. Mostrar los archivos modificados y el diff.
8. Mostrar el resultado de tests.
9. Reanudar la sesión con un nuevo mensaje.
10. Cancelar una ejecución.
11. Ver un timeline persistente.
12. Consultar los artefactos generados.

El MVP no necesita todavía descomposición autónoma completa, múltiples modelos ni workflows dinámicos complejos.

---

## 24. Recomendación final

La primera versión debería implementarse como un **monolito modular** compuesto por:

- Spring Boot.
- PostgreSQL.
- React y TypeScript.
- Un runner local.
- Integración con Claude Code mediante `stream-json`.
- Worktrees aislados.
- Eventos estructurados.
- Una máquina de estados explícita.

La plataforma no debe permitir que un “agente jefe” esconda toda la orquestación dentro de sus prompts. El workflow debe ser visible, determinista, auditable y controlable.

El circuito fundamental será:

```text
Trabajo
→ workflow explícito
→ fase preparada
→ agente ejecutado
→ eventos observables
→ resultado estructurado
→ verificación independiente
→ artefactos persistentes
→ transición de fase
→ aprobación humana cuando proceda
→ entrega y verificación final
```

Esta arquitectura permitirá saber con precisión:

- Qué agentes están trabajando.
- Qué prompt ha recibido cada uno.
- En qué fase se encuentra el desarrollo.
- Qué herramientas se están ejecutando.
- Qué artefactos se han producido.
- Cuánto tiempo y dinero se ha consumido.
- Por qué una fase avanzó, falló o quedó bloqueada.
- Cómo interactuar con una ejecución activa.
- Qué evidencia existe de que el trabajo terminó correctamente.

El resultado será una evolución desde una colección de terminales y sesiones aisladas hacia una verdadera plataforma de ingeniería de software agéntica.
