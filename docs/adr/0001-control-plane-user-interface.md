# ADR-0001: Interfaz del control plane de Skynet

- **Estado:** Propuesto
- **Fecha:** 2026-10-04
- **Decisores:** equipo de Skynet
- **Ámbito:** frontend web, APIs de lectura del control plane y modelo de interacción humana
- **Hitos relacionados:** M3, M4, M5, W5 y W7 de [`../implementation-plan.md`](../implementation-plan.md)
- **Especificación relacionada:** [`../agentic-orchestration-system.md`](../agentic-orchestration-system.md), especialmente §§3–6 y §13

## 1. Contexto

Skynet necesita una interfaz para ejecutar, observar y auditar workflows agénticos de desarrollo de software. La interfaz actual permite navegar por proyectos, trabajos y ejecuciones; presenta fases como tarjetas, agentes, prompts y un timeline SSE básico. Esta base demuestra el flujo de datos, pero no ofrece todavía un control plane operativo completo.

La interfaz debe resolver simultáneamente cuatro problemas:

1. Mostrar el estado global sin obligar a abrir cada ejecución.
2. Representar el workflow y sus dependencias de forma visual.
3. Explicar con evidencia qué ha hecho cada agente.
4. Permitir intervención humana segura y auditable.

La interfaz no puede usar el texto libre del agente como fuente de verdad. Los estados, transiciones, costes, artefactos, verificaciones y aprobaciones deben proceder del backend y del event store. El frontend proyecta ese estado; no lo infiere.

Se estudiaron como referencias de producto:

- OpenHands Agent Canvas: sesiones y agentes de programación.
- Temporal UI: historial durable de eventos y operaciones.
- Langfuse: trazas jerárquicas e inspección de prompts, herramientas, latencia y costes.
- n8n y Dify: canvas de workflows y relación entre definición y ejecución.
- LangGraph Studio: depuración de grafos, checkpoints e intervención humana.
- Dagster: salud global, dependencias y checks independientes.

Ninguna referencia cubre por sí sola el objetivo de Skynet. La interfaz debe combinar sus patrones sin convertirse en un constructor genérico de chatbots ni en un IDE completo.

## 2. Decisión

Skynet adoptará una interfaz de control plane compuesta por cuatro superficies principales:

1. **Dashboard operativo** para salud, trabajo pendiente y excepciones.
2. **Definición del workflow** mediante un DAG versionado.
3. **Vista de ejecución** en tiempo real, con grafo, agentes e historial durable.
4. **Inspector contextual** para prompts, herramientas, artefactos, verificaciones y aprobaciones.

La vista principal de una ejecución usará una composición de tres paneles y un timeline inferior:

```text
┌──────────────────────────────────────────────────────────────────────────┐
│ SKY-123 · Run #42 · RUNNING · 18m · coste · tokens · runner · versión  │
├──────────────────┬────────────────────────────┬──────────────────────────┤
│ Navegación       │ Workflow en vivo           │ Inspector contextual     │
│                  │                            │                          │
│ Fases/agentes    │ [Spec] → [Implementación] │ Resumen                  │
│ y conversaciones │                 ↓          │ Prompt efectivo          │
│                  │             [Review]       │ Herramientas             │
│                  │                 ↓          │ Artefactos               │
│                  │           [Aprobación]     │ Verificación             │
│                  │                            │ Coste y tokens           │
├──────────────────┴────────────────────────────┴──────────────────────────┤
│ Timeline durable: eventos, logs, tests, commits, CI y aprobaciones      │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.1. Principios de interacción

- **Excepciones antes que decoración.** El dashboard prioriza fallos, bloqueos, aprobaciones pendientes, runners sin heartbeat y ejecuciones sin actividad.
- **Definición y ejecución son vistas distintas.** Editar un workflow no altera ejecuciones iniciadas. Cada ejecución referencia una versión inmutable.
- **Una acción siempre muestra su alcance.** Cancelar, reintentar, aprobar o reanudar requiere contexto, impacto y confirmación proporcional al riesgo.
- **El evento es la unidad de auditoría.** La UI puede agregar eventos para facilitar lectura, pero debe permitir acceder al evento original.
- **La evidencia es independiente de la narración.** Tests, commits, PR, CI y diffs muestran la comprobación de Skynet, no solo lo declarado por el agente.
- **Streaming progresivo.** SSE actualiza estados, mensajes y métricas sin recargar; una reconexión recupera eventos desde `Last-Event-ID`.
- **Accesibilidad por defecto.** Estado no comunicado exclusivamente por color, navegación por teclado y paneles adaptables a pantallas estrechas.
- **Sin secretos en el DOM.** Prompts, tool calls, logs y payloads pasan por redacción del backend antes de llegar al navegador.

## 3. Arquitectura de información

### 3.1. Navegación global

La navegación principal será:

- **Dashboard**
- **Proyectos**
- **Workflows**
- **Ejecuciones**
- **Aprobaciones**
- **Runners**
- **Actividad**

La navegación puede introducirse progresivamente. Mientras no existan definiciones explícitas de workflow, `Workflows` muestra únicamente la definición `adhoc` de solo lectura. `Aprobaciones` y `Runners` aparecen cuando sus APIs estén disponibles.

### 3.2. Dashboard operativo

El dashboard no será un canvas vacío. Mostrará:

- Ejecuciones activas, fallidas y completadas durante el periodo seleccionado.
- Aprobaciones pendientes.
- Fases bloqueadas o esperando input.
- Agentes `UNRESPONSIVE`.
- Runners conectados, capacidad y último heartbeat.
- PR abiertas y checks de CI conocidos.
- Duración, tokens y coste agregados.
- Lista priorizada de elementos que requieren atención.

Cada métrica enlaza a una lista filtrada. No se implementarán gráficas sin una pregunta operativa concreta.

### 3.3. Editor y visor de workflows

El DAG usará React Flow, como ya prevé el plan de implementación. Tendrá:

- Zoom, ajuste a pantalla y minimapa.
- Swimlanes opcionales por macrofase: especificación, planificación, implementación, tests, revisión y entrega.
- Nodos tipados: `agent`, `command`, `parallel`, `conditional`, `human-approval` e integraciones futuras.
- Panel lateral de propiedades.
- Validación visible de ciclos, dependencias inexistentes y configuración incompleta.
- Estados de definición: borrador, validada y publicada.
- Versiones publicadas inmutables.

En modo ejecución, el mismo grafo es de solo lectura y superpone estado, duración, agente, coste, reintentos y artefactos.

### 3.4. Vista de ejecución

La cabecera mostrará como mínimo:

- Proyecto, trabajo y workflow.
- Identificador de ejecución y versión de definición.
- Estado estructurado.
- Inicio, duración y finalización.
- Branch/worktree cuando proceda.
- Runner asignado.
- Tokens y coste acumulados cuando existan.
- Estado de la conexión SSE.

El panel izquierdo mostrará fases y agentes como navegación jerárquica. Seleccionar una fase, agente, evento o artefacto actualiza el inspector sin perder el contexto del DAG.

El canvas central resaltará:

- Completado: check y estado textual.
- En ejecución: énfasis visual y actividad actual.
- Espera de input o aprobación: estado ámbar y llamada a la acción.
- Fallo: estado rojo, resumen y siguiente acción disponible.
- Cancelado u omitido: estilo neutral y causa.
- Pendiente: bajo contraste.

El color nunca será la única señal; todos los nodos incluirán icono y etiqueta.

### 3.5. Inspector contextual

El inspector tendrá pestañas según la entidad seleccionada:

- **Resumen:** estado, tiempos, agente, runner, sesión y actividad actual.
- **Prompt:** prompt efectivo, hash, versión y variables redaccionadas.
- **Mensajes:** texto en streaming y bloques estructurados.
- **Herramientas:** nombre, entrada redaccionada, salida, duración y código de salida.
- **Artefactos:** archivos, commits, diff, PR, logs y test reports.
- **Verificación:** checks independientes, evidencia, origen y timestamp.
- **Coste:** tokens de entrada/salida, coste incremental y acumulado.
- **Evento original:** envelope y payload normalizado para auditoría.

Los payloads grandes se cargarán bajo demanda. El inspector no descargará automáticamente NDJSON, logs completos o diffs grandes.

### 3.6. Timeline durable

El timeline actual evolucionará a una vista semántica que:

- Mantiene el orden global por `event.sequence`.
- Agrupa eventos repetitivos sin eliminar el acceso a los originales.
- Permite filtrar por fase, agente, tipo, severidad y artefacto.
- Distingue eventos del runner, control plane, usuario e integración externa.
- Muestra reintentos, timeouts, cancelaciones, señales y reconciliaciones.
- Permite enlazar directamente a una secuencia concreta.

Ejemplos de entradas semánticas:

- «El agente leyó 14 archivos».
- «Tests: 126 ejecutados, 2 fallidos».
- «Commit verificado: `abc1234`».
- «Aprobación solicitada para publicar una PR».
- «Runner sin heartbeat durante 45 s».

### 3.7. Centro de aprobaciones

Una aprobación mostrará:

- Acción exacta solicitada.
- Agente, fase, trabajo y ejecución de origen.
- Motivo y nivel de riesgo.
- Permisos o secretos requeridos.
- Diff, comandos, artefactos y verificaciones relevantes.
- Consecuencia de aprobar o rechazar.
- Historial de comentarios y decisiones.

Acciones disponibles:

- Aprobar una vez.
- Rechazar.
- Solicitar cambios.
- Comentar.

«Aprobar siempre» queda fuera del alcance inicial. Las aprobaciones son records append-only; no se reescribe una decisión anterior.

### 3.8. Acciones operativas

La interfaz podrá exponer, cuando el backend lo soporte:

- Cancelar agente o workflow.
- Reintentar una fase mediante un nuevo `AgentRun`.
- Reanudar una sesión con un nuevo `AgentRun` hijo.
- Fork de sesión.
- Reejecutar solo una verificación.
- Clonar una ejecución como experimento.

No se mutará retrospectivamente una ejecución terminada. Reintentos, resumes y forks crean nuevas entidades enlazadas para conservar auditoría.

## 4. Contrato entre frontend y backend

### 4.1. Proyecciones de lectura

La UI no reconstruirá el dominio completo procesando el event store en el navegador. El backend proporcionará proyecciones optimizadas:

- `DashboardSummary`
- `WorkflowDefinitionView`
- `WorkflowRunView`
- `StageRunView`
- `AgentRunView`
- `ApprovalView`
- `RunnerView`
- `ArtifactSummary`
- `VerificationResult`

El SSE comunica invalidaciones y deltas estructurados. Ante duda o reconexión, TanStack Query vuelve a leer la proyección canónica.

### 4.2. Estado del layout en URL

Los elementos navegables y compartibles se reflejarán en la URL:

- Entidad seleccionada.
- Pestaña del inspector.
- Filtros del timeline.
- Rango temporal del dashboard.

El tamaño de paneles o preferencias puramente visuales podrán persistirse en almacenamiento local.

### 4.3. Rendimiento

- Virtualizar listas largas y timeline.
- Paginar eventos históricos; SSE solo añade eventos nuevos.
- Cargar artefactos pesados bajo demanda.
- Mantener estable la posición del canvas durante actualizaciones.
- Evitar invalidar todas las queries ante cada delta de streaming.

## 5. Diseño visual

Skynet usará una estética de control plane técnico, no una interfaz de chat como superficie dominante.

- Densidad de información media/alta, con jerarquía clara.
- Fondo neutro y estados con contraste accesible.
- Tipografía monoespaciada solo para IDs, hashes, comandos y payloads.
- Tarjetas y paneles consistentes, evitando dashboards de widgets decorativos.
- Dark mode y light mode desde tokens de diseño compartidos.
- Componentes de estado reutilizables para workflow, fase, agente, runner y verificación.

No se adoptará una dependencia completa de componentes de otro producto. React Flow se utilizará para el DAG; Monaco se reservará para diff, código y artefactos de texto, tal como prevé la especificación.

## 6. Estrategia incremental

### Fase A — M3: observabilidad usable sin DAG editable

- Crear shell de control plane y navegación global.
- Convertir `RunPage` en layout de cabecera, navegación de fases/agentes, inspector y timeline.
- Mostrar actividad actual, duración, sesión, tokens, coste y falta de actividad.
- Mejorar timeline semántico, filtros y deep links.
- Mantener las fases como stepper/lista si todavía no existe definición DAG.
- Añadir E2E con fake-claude y SSE.

### Fase B — M4/M5: interacción y evidencia

- Añadir mensajes/resume, cancelación, retry y fork.
- Incorporar artefactos, commits, diff Monaco, tests y logs.
- Añadir `VerificationResult` y diferenciar «declarado por agente» de «verificado por Skynet».

### Fase C — W5/W7: workflow explícito

- Introducir React Flow y definiciones versionadas.
- Superponer ejecución en tiempo real sobre el DAG.
- Añadir centro de aprobaciones.
- Añadir acciones por nodo y reintentos gobernados por el motor.

Esta secuencia evita bloquear M3 esperando al motor de workflows de Fase 2 y evita construir un DAG decorativo sin semántica de dominio.

## 7. Alternativas consideradas

### 7.1. Interfaz centrada exclusivamente en chat

**Rechazada.** Facilita una conversación individual, pero oculta dependencias, concurrencia, estado global y evidencia verificable.

### 7.2. Canvas no-code como pantalla inicial

**Rechazada.** Prioriza diseño de workflows cuando el problema inicial es observar ejecuciones y excepciones. El dashboard operativo será la entrada.

### 7.3. Reconstruir todo el estado en el navegador desde eventos

**Rechazada.** Duplica reglas de dominio, complica compatibilidad y convierte el frontend en otra fuente de verdad. El backend ofrecerá proyecciones canónicas.

### 7.4. Adoptar Temporal UI, Langfuse u OpenHands como frontend

**Rechazada.** Cada producto cubre solo una parte del dominio y obligaría a adaptar el modelo de Skynet a sus abstracciones. Se reutilizan patrones de interacción, no sus contratos internos.

### 7.5. Implementar el DAG completo durante M3

**Rechazada.** M3 todavía usa un workflow `adhoc`. La UI debe ofrecer observabilidad real ahora y evolucionar al DAG cuando existan definiciones versionadas y un motor explícito.

## 8. Consecuencias

### Positivas

- Separa claramente definición, ejecución y evidencia.
- Mantiene el backend como fuente de verdad.
- Proporciona un camino incremental desde la UI actual hasta el workflow explícito.
- Reduce el tiempo para diagnosticar bloqueos y fallos.
- Hace visibles las diferencias entre afirmaciones del agente y verificaciones reales.
- Permite enlaces profundos a entidades y eventos concretos.

### Negativas y costes

- Requiere nuevas proyecciones de lectura y endpoints especializados.
- El layout de paneles y el canvas aumentan complejidad frontend.
- Los eventos deben tener semántica suficientemente estable para agregación y filtrado.
- Artefactos grandes exigen paginación, carga diferida y límites explícitos.
- La accesibilidad del DAG requiere trabajo adicional más allá del rendering visual.

### Riesgos

- Convertir el dashboard en una colección de métricas sin acciones.
- Introducir un DAG visual antes de disponer de semántica real.
- Mostrar secretos contenidos en prompts o herramientas.
- Saturar el navegador con eventos de streaming.
- Permitir acciones ambiguas o destructivas sin evidencia y confirmación.

## 9. Criterios de aceptación de la decisión

Esta ADR se considerará aplicada cuando:

1. La ruta de una ejecución muestra cabecera operativa, navegación de fases/agentes, inspector y timeline sin perder funcionalidad actual.
2. Los estados proceden de proyecciones del backend; el frontend no infiere fases desde texto libre.
3. El timeline permite abrir el evento original y recuperar eventos después de una reconexión SSE.
4. Prompt, tool calls, mensajes, tokens, coste y estado final aparecen en vivo usando fake-claude.
5. Los payloads sensibles llegan redactados desde el backend.
6. El layout es navegable con teclado y el estado no depende solo del color.
7. Existe un test E2E que crea un trabajo, lanza una ejecución y observa su progreso hasta estado terminal.
8. En M5, commits, diff y tests distinguen claramente evidencia verificada de declaraciones del agente.
9. En W7, el DAG representa la definición versionada y refleja por SSE el estado de cada nodo.
10. En W5, aprobar o rechazar deja un record auditable y desbloquea o corrige el workflow según la decisión.

## 10. Issue de implementación propuesta

### Título

`M3: construir el control plane visual de ejecuciones y agentes`

### Objetivo

Transformar la UI actual de observabilidad en un control plane operativo que permita entender el estado de una ejecución, navegar por sus fases y agentes, inspeccionar actividad y evidencia, y diagnosticar fallos en tiempo real sin depender del stdout libre del agente.

### Alcance de esta issue

Esta primera issue implementa únicamente la **Fase A** de esta ADR:

- Shell y navegación global.
- Cabecera operativa de ejecución.
- Navegación de fases y agentes.
- Inspector con resumen, prompt, mensajes, herramientas y evento original.
- Timeline semántico con filtros y deep links.
- Métricas en vivo de duración, tokens y coste.
- Estado de conexión SSE y recuperación tras reconexión.
- Responsive básico y accesibilidad por teclado.

Quedan fuera para issues posteriores:

- Editor React Flow.
- Centro de aprobaciones.
- Monaco y artefactos Git/tests.
- Resume, retry y fork.
- Métricas históricas agregadas del dashboard.

### Entregables técnicos esperados

Frontend, adaptando los nombres tras confirmar el diseño final:

- Modificar `web/src/App.tsx` para el shell y navegación.
- Refactorizar `web/src/pages/RunPage.tsx`.
- Evolucionar `web/src/components/EventTimeline.tsx`.
- Crear componentes bajo `web/src/components/run/` para cabecera, navegación, inspector y métricas.
- Añadir hooks de selección/filtros sincronizados con URL.
- Añadir pruebas de componentes y ampliar `web/src/App.test.tsx`.

Backend/protocol, solo donde falten datos para la UI:

- Extender DTOs de detalle de ejecución/agente en `protocol`.
- Añadir proyecciones de actividad actual, sesión, tokens, coste y timestamps.
- Garantizar redacción server-side de payloads sensibles.
- Mantener compatibilidad de replay SSE con `Last-Event-ID`.

E2E:

- Añadir escenario Playwright con fake-claude: crear trabajo → lanzar agente → observar mensajes/herramientas/métricas → reconectar SSE → alcanzar estado terminal.

### Criterios de aceptación de la issue

- [ ] La ejecución puede entenderse sin abrir JSON bruto.
- [ ] Se ve qué fase y agente están activos, esperando, fallidos o terminados.
- [ ] El inspector cambia de contexto sin navegar fuera de la ejecución.
- [ ] Prompt, mensajes y tool calls son inspeccionables y están redactados.
- [ ] Duración, tokens y coste se actualizan sin recargar.
- [ ] Timeline filtrable y enlazable por secuencia.
- [ ] Una reconexión SSE no pierde eventos.
- [ ] Cancelar conserva el comportamiento actual y muestra feedback de éxito/error.
- [ ] Navegación por teclado y etiquetas accesibles para estados y controles.
- [ ] Tests frontend, backend afectados y E2E en verde.
- [ ] `./gradlew build` pasa.
- [ ] `npm run build` y `npm test` pasan en `web/`.

### Descomposición recomendada

1. Definir contratos de lectura faltantes y tests de contrato.
2. Implementar proyecciones backend y redacción server-side.
3. Crear shell de navegación y tokens de layout.
4. Crear cabecera operativa y métricas de ejecución.
5. Crear navegación de fases/agentes.
6. Crear inspector contextual y sincronización con URL.
7. Evolucionar timeline con filtros, agrupación y deep links.
8. Integrar actualizaciones SSE sin invalidaciones globales innecesarias.
9. Añadir responsive y accesibilidad.
10. Añadir E2E con fake-claude y reconexión.
11. Ejecutar builds, tests y documentar evidencia en la PR.

## 11. Notas para implementación

- Aplicar TDD a contratos, agregación del timeline, selección por URL y estados accesibles.
- No introducir React Flow en la primera issue salvo que el motor ya exponga una definición DAG real.
- No duplicar la máquina de estados en TypeScript; compartir enums/contratos generados o mapear estados exclusivamente para presentación.
- No renderizar HTML procedente de agentes.
- Truncar visualmente payloads grandes, pero conservar acceso explícito y paginado al original redaccionado.
- Mantener los componentes de presentación independientes del transporte SSE para facilitar pruebas deterministas.

## 12. Referencias visuales

- OpenHands Agent Canvas: https://github.com/OpenHands/OpenHands
- LangSmith Deployment / Studio: https://www.langchain.com/langsmith/deployment
- Langfuse: https://github.com/langfuse/langfuse
- Temporal UI: https://github.com/temporalio/ui
- n8n: https://github.com/n8n-io/n8n
- Dify: https://github.com/langgenius/dify
- Dagster: https://github.com/dagster-io/dagster
