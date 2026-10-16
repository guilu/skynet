# Probar W5-A en local

W5-A (#92) trae las **puertas de aprobación** al motor: una fase `type: human-approval` para la ejecución en «Esperando aprobación» hasta que alguien aprueba, rechaza o comenta. Cada petición guarda una foto de la evidencia, y las decisiones no se pueden reescribir. Pedir cambios llega en W5-B y el centro de aprobaciones de la web en W5-C, así que esta guía decide con `curl`.

Hay una migración nueva (`V19`): se aplica sola al arrancar el control plane.

## 1. Arrancar

Arranca el control plane, la web y el runner con fake-claude como en W4-C, sin cambiar nada:

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

Usa el repositorio de prueba de W4-B (`/tmp/w4b`, con `calc.py`).

Para la API:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

## 2. Publicar un workflow con puerta

En **Workflows**, **Nuevo workflow**, pega esto, **Crear borrador** y **Publicar**. No debe salir ningún problema:

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
  - id: aprobar-plan
    type: human-approval
    dependsOn: [plan]
    action: "Aprobar el plan de {{workItem.key}}: {{stages.plan.output.summary}}"
    risk: high
  - id: implement
    type: agent
    prompt: Implementa el plan
    dependsOn: [aprobar-plan]
  - id: tests
    type: command
    command: python3 -c "import calc; assert calc.add(2, 3) == 5"
    dependsOn: [implement]
```

Comprueba también que estos cambios dan su error en la línea:

- Quitar `action`: «A esta fase le falta `action`…».
- `prompt: x` en la puerta: «Una fase `human-approval` no ejecuta nada, espera a que alguien decida: quita `prompt`».
- `risk: altisimo`: «`risk: altisimo` no se conoce. Admite: low, medium, high».
- `revise: tests` en la puerta: «`tests` no va antes de esta puerta…».
- `action: x` en `implement`: «`action` solo vale en fases `type: human-approval`…».

## 3. Aprobar: la ejecución sigue en el worktree de plan

1. Lanza `con-puerta` en un trabajo sobre el repositorio de prueba.
2. Cuando termina `plan`, la ejecución se queda «En curso». En **Progreso**, `aprobar-plan` está «Esperando aprobación» e `implement`, «Pendiente».
3. Busca la petición y guarda su id:

   ```bash
   api $B/approvals?status=PENDING | jq '.[0] | {id, action, risk, round}'
   A=$(api $B/approvals?status=PENDING | jq -r '.[0].id')
   ```

   La acción es «Aprobar el plan de <trabajo>: Que add() sume», el riesgo `HIGH` y la ronda 1.
4. Mira la evidencia:

   ```bash
   api $B/approvals/$A | jq '.evidence | {origin, revise, consequences, grants: [.grants[].stage], plan: .dependencies[0].agents[0] | {structuredOutput, changes}}'
   ```

   - `revise.stageKey` es `plan`.
   - `consequences.approve` dice «Pueden arrancar: implement.» y `consequences.reject`, «La ejecución falla y se cancelan implement, tests.».
   - `grants` es `implement`.
   - `plan.structuredOutput.summary` es «Que add() sume» y `changes` tiene la rama y los ficheros cambiados.
5. Comenta y comprueba que sigue pendiente:

   ```bash
   api -X POST $B/approvals/$A/decisions -d '{"action":"COMMENT","comment":"Mira el caso de negativos"}' | jq '{status, decisions}'
   ```

6. Reinicia el control plane (Ctrl+C y `bootRun` otra vez). La puerta sigue «Esperando aprobación» y no arranca nada.
7. Aprueba:

   ```bash
   api -X POST $B/approvals/$A/decisions -d '{"action":"APPROVE"}' | jq '{status, decidedAt}'
   ```

   `implement` arranca en el mismo worktree que `plan` (en su agente, pestaña **Resumen**, la misma rama `skynet/…`), `tests` pasa y la ejecución termina «Completada».
8. Aprobar otra vez responde 409 «La aprobación ya está decidida (approved)».
9. En el timeline están `approval.requested`, `approval.commented` y `approval.granted`.

## 4. Rechazar: la ejecución falla

1. Lanza `con-puerta` otra vez y espera a la puerta. Guarda el id en `A`, como antes.
2. Rechazar sin comentario responde 400 «Para rechazar hace falta un comentario con el motivo»:

   ```bash
   api -X POST $B/approvals/$A/decisions -d '{"action":"REJECT"}'
   ```

3. Pedir cambios responde 409 «Pedir cambios todavía no se puede: llega en W5-B».
4. Rechaza con motivo:

   ```bash
   api -X POST $B/approvals/$A/decisions -d '{"action":"REJECT","comment":"No es lo que pedí"}' | jq .status
   ```

   `aprobar-plan` termina «Fallida», `implement` y `tests` «Cancelada» y la ejecución «Fallida». El timeline tiene `approval.rejected` con el comentario.

## 5. Cancelar con una petición pendiente

1. Lanza `con-puerta` y espera a la puerta.
2. **Cancelar ejecución** en la cabecera.
3. `api $B/approvals/<id> | jq '{status, decisions}'` da `CANCELLED`, con una decisión `CANCEL` de `skynet`.

## 6. Las decisiones no se reescriben (opcional)

Con `psql` sobre la base de datos del control plane:

```sql
UPDATE approval_decision SET comment = 'otro';     -- una decisión de aprobación no se modifica ni se borra
DELETE FROM approval_decision;                      -- igual
UPDATE approval_request SET status = 'PENDING' WHERE status = 'APPROVED';  -- decidida no se modifica
```

Eliminar una ejecución archivada (AE-B) sí borra sus peticiones y decisiones.

## Qué mirar si algo falla

- `GET /api/workflow-runs/<id>`: la puerta lleva `approval` con su última petición.
- `GET /api/approvals/<id>`: la evidencia y las decisiones.
- Los eventos `approval.*` y `stage.status.changed` de la puerta (`approval-requested`, `approval-granted`, `approval-rejected`).
