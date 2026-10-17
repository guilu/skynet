# Probar W6-B en local

W6-B (#99) trae los **límites de tiempo y la cancelación**:

- `timeout` por fase: cada intento tiene que terminar a tiempo; si no, se paran sus agentes y la fase falla con `failure: timeout`, y se reintenta solo si `timeout` está en su `retry.on`.
- Una puerta `human-approval` con `timeout` **caduca**: la petición queda «Caducada» y la ejecución falla.
- Cancelar una ejecución la deja «Cancelando» hasta que sus agentes paran. Si el runner no lo confirma en `SKYNET_CANCEL_TIMEOUT` (70 s), el control plane la da por cancelada.
- Cancelar también cancela las ejecuciones lanzadas desde sus agentes.

Hay una migración nueva (`V22`): se aplica sola al arrancar el control plane. «Reintentar fase» desde la web llega en W6-C.

## 1. Arrancar

El control plane con un `SKYNET_CANCEL_TIMEOUT` corto para no esperar 70 s en el apartado 6, la web, y el runner con fake-claude reproduciendo `02-tools` (cambia `return a - b` por `return a + b` en `calc.py` y hace un commit):

```bash
SKYNET_CANCEL_TIMEOUT=20s SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Usa el repositorio de prueba de W4-B (`/tmp/w4b`, con `calc.py`).

Para la API:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

## 2. El YAML

En **Workflows**, **Nuevo workflow**, pega esto, **Crear borrador** y **Publicar**. No debe salir ningún problema:

```yaml
id: limites
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
  - id: lento
    type: command
    dependsOn: [implement]
    command: "if [ ! -f /tmp/w6b-lento ]; then touch /tmp/w6b-lento; sleep 120; fi; grep -q 'a + b' calc.py"
    timeout: 15s
    retry: {maxAttempts: 2, on: [timeout], delay: 5s}
```

En **Resumen**, `lento` dice «Reintenta: hasta 2 intentos si falla por timeout; espera 5 s · Tiempo máximo: 15 s por intento».

Comprueba también que estos cambios dan su error en la línea:

- `timeout: 15 segundos`: «`15 segundos` no es una duración…».
- `timeout: 200h`: «… no puede pasar de 168 h».
- `timeout: 1m` en una fase `conditional`: «`timeout` no vale en una fase `conditional`: solo evalúa su condición».
- `timeout` ya no sale como «todavía no se usa».

## 3. Una fase que se queda sin tiempo se para y se reintenta

1. `rm -f /tmp/w6b-lento` y lanza `limites` en un trabajo sobre `/tmp/w4b`.
2. Cuando `implement` termina, arranca `lento`. Al abrirla en **Progreso**, el detalle dice «Tiempo máximo: 15 s · hasta las HH:MM:SS».
3. A los 15 s el timeline dice «Fase lento: se acabó su tiempo (intento 1)» y «Parada pedida al runner: se acabó el tiempo de su fase». El proceso `sleep 120` desaparece (`pgrep -f 'sleep 120'` no devuelve nada).
4. Sale «Reintenta a las … (intento 2 de 2)»; a los 5 s arranca el intento 2, que ya no duerme, termina bien y la ejecución queda «Completada».
5. En «Intentos anteriores» de `lento`: «Intento 1 Fallida · se acabó el tiempo».
6. En la API:

   ```bash
   R=<id de la ejecución>
   api $B/workflow-runs/$R | jq '.stages[] | select(.stageKey=="lento") | {attempt, status, timeoutSeconds, deadline, timedOutAt, agents: [.agents[] | {status, failure}]}'
   ```

   El intento 1 sale `FAILED`, con `timeoutSeconds: 15`, `timedOutAt` y un agente `FAILED`/`timeout`. El intento 2 sale `SUCCEEDED`, sin `timedOutAt`.

## 4. Sin `timeout` en `on`, la ejecución falla

1. Quita la línea `retry` de `lento`, guarda y publica la versión 2.
2. `rm -f /tmp/w6b-lento` y lanza `limites` otra vez.
3. A los 15 s de arrancar `lento`, la fase dice «Falló: se acabó el tiempo» y la ejecución termina «Fallida», sin intento 2.

## 5. Una puerta que nadie decide caduca

Publica:

```yaml
id: caduca
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
  - id: aprobar
    type: human-approval
    dependsOn: [implement]
    action: Aprobar el arreglo de {{workItem.key}}
    timeout: 30s
  - id: tests
    type: command
    dependsOn: [aprobar]
    command: grep -q 'a + b' calc.py
```

1. Lanza `caduca` y no decidas nada. En **Aprobaciones** aparece la petición pendiente.
2. A los 30 s la puerta dice «Caducó sin decisión», `tests` «No arrancó porque falló aprobar» y la ejecución termina «Fallida». El timeline dice «La aprobación de aprobar caducó sin decisión».
3. En **Aprobaciones**, el filtro **Caducadas** la enseña con el estado «Caducada». En su página, el historial dice «skynet caducó».
4. `api "$B/approvals?status=EXPIRED" | jq '.[0] | {status, stageKey}'` la devuelve con `status: EXPIRED`.

## 6. «Cancelando» y la cancelación forzada

Publica una versión de `limites` en la que `lento` duerma siempre y sin `timeout`:

```yaml
id: limites
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
  - id: lento
    type: command
    dependsOn: [implement]
    command: sleep 300
```

**Con el runner respondiendo:**

1. Lanza `limites`; con `lento` en curso, **Cancelar ejecución** y **Sí, cancelar**.
2. La cabecera dice «Cancelando» (puede durar solo un instante) y deja de ofrecer cancelar; enseguida queda «Cancelada». `sleep 300` desaparece.

**Con el runner congelado:**

1. Lanza `limites` otra vez y, con `lento` en curso, congela el runner: `kill -STOP <pid del runner>` (`pgrep -f skynet-runner`).
2. **Cancelar ejecución**. La cabecera dice «Cancelando» y la fase `lento`, «Cancelando». En la API, `api $B/workflow-runs/$R | jq '{status, cancelRequestedAt, cancelling: [.stages[].cancelling]}'` enseña `cancelRequestedAt` y `cancelling: true` en `lento`.
3. A los 20 s (`SKYNET_CANCEL_TIMEOUT`) más el intervalo de comprobación (hasta 15 s), la ejecución queda «Cancelada» y el timeline dice «El runner no confirmó la cancelación: se da por cancelado».
4. Descongela el runner: `kill -CONT <pid>`. Recibe la orden de cancelar, que seguía en su cola, y para `sleep 300`. La ejecución sigue «Cancelada».

## 7. Cancelar arrastra a las ejecuciones lanzadas desde sus agentes

1. Lanza `limites` (la del apartado 6). Cuando `implement` termina y `lento` duerme, abre el agente de `implement` y lanza desde él una ejecución nueva con **Reintentar**, que te lleva a ella.
2. Vuelve a la ejecución original y **Cancelar ejecución**.
3. La ejecución lanzada desde el agente también termina «Cancelada»; su timeline dice «Cancelación pedida: se canceló la ejecución que la lanzó».

## 8. Comprobaciones automáticas

- `./gradlew check` (con `SKYNET_TEST_DB_URL`): los nuevos tests del `Planner`, del parser y de `WorkflowEngineIT`:
  - una fase sin tiempo se para y se reintenta;
  - sin reintento, falla la ejecución;
  - una puerta caduca;
  - una cancelación sin confirmar se cierra;
  - cancelar arrastra a las hijas.
- `cd web && npx vitest run`.
