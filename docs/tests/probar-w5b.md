# Probar W5-B en local

W5-B (#93) trae **pedir cambios** a las puertas de aprobación. El comentario llega al agente de la fase `revise`, que reanuda su sesión en su worktree. Mientras corrige, la puerta está «Corrigiendo». Cuando termina, la puerta vuelve a preguntar con una ronda más y lo corregido. Si la corrección falla, la puerta falla. El centro de aprobaciones de la web llega en W5-C, así que esta guía decide con `curl`, como la de W5-A.

Hay una migración nueva (`V20`): se aplica sola al arrancar el control plane.

## 1. Arrancar

Igual que en W5-A: control plane, web y runner con fake-claude, y el repositorio de prueba `/tmp/w4b`.

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_STRUCTURED_OUTPUT \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
FAKE_CLAUDE_STRUCTURED_OUTPUT='{"summary":"Que add() sume"}' \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

fake-claude devuelve siempre la misma salida estructurada, así que la corrección deja el mismo `summary`. Lo que cambia es la invocación de la que sale todo.

## 2. Publicar el workflow

Publica `con-puerta` como en W5-A, con dos cambios en `plan`: una comprobación que se puede hacer fallar desde fuera, y `implement`, que lee la salida de `plan`:

```yaml
id: con-puerta
stages:
  - id: plan
    type: agent
    prompt: Planifica cómo arreglar add()
    outputSchema:
      type: object
      properties:
        summary:
          type: string
    checks: [{command: "test ! -f /tmp/w5b-falla"}]
  - id: aprobar-plan
    type: human-approval
    dependsOn: [plan]
    action: "Aprobar el plan de {{workItem.key}}: {{stages.plan.output.summary}}"
    risk: high
  - id: implement
    type: agent
    prompt: "Implementa: {{stages.plan.output.summary}}"
    dependsOn: [aprobar-plan]
  - id: tests
    type: command
    command: python3 -c "import calc; assert calc.add(2, 3) == 5"
    dependsOn: [implement]
```

Si ya tienes `con-puerta` de W5-A, publícalo como versión nueva. Antes de empezar, `rm -f /tmp/w5b-falla`.

## 3. Pedir cambios y aprobar la segunda ronda

1. Lanza `con-puerta` y espera a que `aprobar-plan` esté «Esperando aprobación». Guarda el id:

   ```bash
   A=$(api $B/approvals?status=PENDING | jq -r '.[0].id')
   ```

2. Pedir cambios sin comentario responde 400 «Para pedir cambios hace falta un comentario con qué cambiar».
3. Pide cambios:

   ```bash
   api -X POST $B/approvals/$A/decisions -d '{"action":"REQUEST_CHANGES","comment":"Cubre también los negativos"}' | jq '{status, correctionAgentRunId}'
   ```

   El estado es `CHANGES_REQUESTED` y `correctionAgentRunId` es la invocación que corrige.
4. En **Progreso**, `plan` sigue «Completada» y `aprobar-plan` pasa a «En curso» (la web dirá «Corrigiendo» en W5-C). En la fase `plan` hay un segundo agente. En su pestaña **Conversación**:
   - es la misma sesión que el primero;
   - el mensaje empieza por «Al revisar «Aprobar el plan de …» han pedido estos cambios» y termina con tu comentario;
   - en **Resumen**, trabaja en la misma rama `skynet/…`.
5. La comprobación de `plan` se repite tras la corrección: en el timeline, un segundo `stage.verification.started` y `stage.verification.completed` de `plan`.
6. La puerta vuelve a «Esperando aprobación» con la segunda ronda:

   ```bash
   A2=$(api $B/approvals?status=PENDING | jq -r '.[0].id')
   api $B/approvals/$A2 | jq '{round, action, changesRequested: .evidence.changesRequested, plan: .evidence.dependencies[0].agents[0].agentRunId}'
   ```

   `round` es 2. `changesRequested.comment` es tu comentario. `plan` es el `correctionAgentRunId` del paso 3.
7. La primera petición sigue `CHANGES_REQUESTED`, y aprobarla responde 409 «La aprobación ya está decidida (changes_requested)».
8. Aprueba la segunda:

   ```bash
   api -X POST $B/approvals/$A2/decisions -d '{"action":"APPROVE"}' | jq .status
   ```

   `implement` arranca con el prompt «Implementa: Que add() sume», en la misma rama. `tests` pasa y la ejecución termina «Completada».
9. En el timeline están `approval.requested`, `approval.changes_requested`, `approval.requested` (ronda 2) y `approval.granted`.

## 4. Una corrección que no pasa las comprobaciones

1. `rm -f /tmp/w5b-falla`, lanza `con-puerta` y espera a la puerta.
2. `touch /tmp/w5b-falla` y pide cambios.
3. La corrección termina, pero su comprobación no pasa. `aprobar-plan` termina «Fallida», `implement` y `tests` «Cancelada», y la ejecución «Fallida».
4. En el timeline, `approval.correction.failed` lleva el motivo: «Las comprobaciones de plan no pasaron tras la corrección: command: …».
5. `rm -f /tmp/w5b-falla`.

## 5. Cancelar mientras corrige

1. Lanza `con-puerta`, espera a la puerta y pide cambios.
2. Mientras corrige, pulsa **Cancelar ejecución**.
3. El agente que corrige termina «Cancelado». `aprobar-plan` termina «Cancelada», y la ejecución también.

Con fake-claude la corrección dura poco. Si termina antes de cancelar, arranca el runner con `FAKE_CLAUDE_DELAY_MS=2000` y añádelo a `SKYNET_AGENT_ENV`.

## 6. Una puerta sin a quién pedir cambios

Publica esto y lánzalo:

```yaml
id: sin-revise
stages:
  - id: tests
    type: command
    command: python3 -c "import calc"
  - id: aprobar
    type: human-approval
    dependsOn: [tests]
    action: Aprobar los tests
```

- La evidencia tiene `revise: null` y `consequences.requestChanges: null`.
- Pedir cambios responde 409 «Esta puerta no tiene a quién pedir cambios…».
- La petición sigue pendiente.

## Qué mirar si algo falla

- `GET /api/approvals/<id>`: `status`, `correctionAgentRunId` y, desde la segunda ronda, `evidence.changesRequested`.
- Los eventos de la puerta: `stage.status.changed` con `changes-requested`, `correction-failed` o `correction-cancelled`.
- El agente que corrige está en la fase `plan`, no en la puerta: es una invocación `RESUME` con su prompt y su verificación.
