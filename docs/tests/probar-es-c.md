# Probar ES-C - Supuestos para revisar después

ES-C (#278) es la tercera y última PR del hito ES. Lo explica [`docs/workflows.md`](../workflows.md), en «Supuestos (ES-C)», dentro de «Salida estructurada».

- **Propiedad reservada `assumptions`:** es una lista de textos en la salida de una fase. Si la fase tiene `outputSchema` en línea y no la declara, Skynet la añade sola.
- **Skynet los junta:** toma los del último intento de cada fase, sin repetir. Los enseña en tres sitios:
  - la descripción de la PR, en «## Supuestos para revisar»;
  - la puerta, en una sección aparte;
  - la ejecución de la web, en la tarjeta «Supuestos para revisar».
- **API:** `GET /api/workflow-runs/{id}/assumptions`.
- **Evento `run.assumptions.collected`:** sale en Actividad.
- **No paran nada:** tampoco la autoaprobación de AU-C.
- **Seeds:** `especificar-planificar-implementar` (spec y plan) e `implementar-tarea` (explorar) piden los supuestos. Se publican con `scripts/seed-workflows.sh --actualizar`.
- **Sin migración.**

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*RunAssumptionsTest' --tests '*PullRequestBodyTest' \
  --tests '*DefinitionParserTest' --tests '*WorkflowEngineIT' --tests '*ContractIT' --tests '*SeedWorkflowsTest'
cd web && npx vitest run src/components/run/Assumptions.test.tsx src/Approvals.test.tsx src/format.test.ts src/contracts.test.ts
```

- `RunAssumptionsTest`:
  - lee los textos y junta los espacios;
  - se salta lo vacío, lo que no es texto y una salida que no es JSON;
  - un supuesto muy largo se corta en 400 caracteres.
- `PullRequestBodyTest`: «## Supuestos para revisar» lleva cada supuesto con su fase; sin supuestos, la sección no sale.
- `DefinitionParserTest`: el `outputSchema` de una fase lleva `assumptions` aunque no lo declare; los campos que leen otras fases no cambian.
- `WorkflowEngineIT.anApprovalGateWaitsForADecision…`:
  - el schema que recibe el agente pide `assumptions`;
  - la puerta y la API devuelven los dos supuestos, sin el vacío;
  - sale `run.assumptions.collected`.
- `ContractIT` y `contracts.test.ts`: la evidencia de la puerta lleva `assumptions`.
- `Assumptions.test.tsx`: la tarjeta enseña cada supuesto con su fase y, sin supuestos, no sale.
- `Approvals.test.tsx`: la puerta enseña «Supuestos para revisar (1)».
- `format.test.ts`: «spec supuso 2 cosas sin poder comprobarlo».

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_STRUCTURED_OUTPUT=@/tmp/es-c/salida.json`, pon el nombre en `SKYNET_AGENT_ENV` y reinícialo. Haz `mkdir -p /tmp/es-c` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo**:

```yaml
id: es-c-supuestos
name: Supuestos para revisar
stages:
  - id: plan
    type: agent
    prompt: Planifica el cambio
    outputSchema:
      type: object
      properties:
        summary: {type: string}
  - id: aprobar
    type: human-approval
    dependsOn: [plan]
    action: "Aprobar: {{stages.plan.output.summary}}"
    risk: low
```

### 2.1 Con supuestos

1. Escribe la salida y lanza `es-c-supuestos` en un trabajo nuevo de `demo-repo`:

   ```bash
   printf '{"summary":"Exportar a CSV","assumptions":["El límite es por usuario","Sin caché"]}' > /tmp/es-c/salida.json
   ```

2. Cuando `aprobar` espera, la ejecución enseña la tarjeta «Supuestos para revisar (2)». Debajo están «El límite es por usuario» y «Sin caché», cada uno con `plan` al lado.
3. En la pestaña de eventos sale «plan supuso 2 cosas sin poder comprobarlo».
4. Abre la aprobación desde **Aprobaciones**. Tiene la sección «Supuestos para revisar (2)» con los mismos dos.
5. Aprueba. La ejecución termina bien: los supuestos no han parado nada.

### 2.2 Sin supuestos

1. `printf '{"summary":"Exportar a CSV","assumptions":[]}' > /tmp/es-c/salida.json` y lanza en un trabajo nuevo.
2. Ni la ejecución ni la aprobación tienen sección de supuestos, y no sale el evento.

### 2.3 Los workflows iniciales

1. Con `SKYNET_ADMIN_PASSWORD` en el entorno, `scripts/seed-workflows.sh --actualizar` publica las versiones nuevas.
2. En **Workflows → Especificar, planificar e implementar**, el prompt del analista y el de la fase `plan` terminan pidiendo, en `assumptions`, lo que hayan supuesto. En **Implementar una tarea**, el del explorador también.
3. No hace falta lanzarlos: el flujo es el de 2.1.

## 3. Al terminar

- Archiva `es-c-supuestos`.
- Quita `FAKE_CLAUDE_STRUCTURED_OUTPUT` del runner, borra `/tmp/es-c` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
