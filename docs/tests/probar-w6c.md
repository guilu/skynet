# Probar W6-C en local

W6-C (#100) trae **«Reintentar fase»**: en una ejecución fallida o cancelada, la fase que falló se puede reintentar desde la web, y la **misma ejecución** vuelve a «En curso». Con esto se cierra el hito W6.

- La fase recibe un intento nuevo. También lo reciben las fases que dependen de ella y las que se cancelaron.
- Lo que terminó bien y no depende de ella no se repite.
- No cuenta para `maxAttempts`: los reintentos automáticos vuelven a contar desde ese intento.
- Una puerta rechazada se reintenta desde la fase de la que depende.
- Trae el E2E de aceptación de W6.

Hay una migración nueva (`V23`): se aplica sola al arrancar el control plane.

## 1. Arrancar

Arranca el control plane, la web y el runner como en W6-B: fake-claude reproduce `02-tools`, que cambia `return a - b` por `return a + b` en `calc.py` y hace un commit.

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
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

## 2. «Reintentar fase» en una ejecución fallida

Publica:

```yaml
id: reintentar-fase
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
  - id: comprobar
    type: command
    dependsOn: [implement]
    command: "if [ ! -f /tmp/w6c-comprobar ]; then touch /tmp/w6c-comprobar; exit 1; fi; grep -q 'a + b' calc.py"
  - id: cerrar
    type: command
    dependsOn: [comprobar]
    command: "true"
```

1. `rm -f /tmp/w6c-comprobar` y lanza `reintentar-fase` en un trabajo sobre `/tmp/w4b`.
2. `comprobar` falla la primera vez y la ejecución termina «Fallida». `cerrar` queda «No arrancó porque falló comprobar».
3. En **Progreso**, abre `comprobar`. Sale **Reintentar fase**; en `implement`, que terminó bien, no.
4. **Reintentar fase**. La confirmación dice «Se reintenta comprobar (intento 2). Después vuelven a lanzarse cerrar. Lo que ya terminó bien y no depende de comprobar no se repite.».
5. **No** cierra la confirmación sin hacer nada. Vuelve a pulsar **Reintentar fase** y luego **Sí, reintentar**.
6. La cabecera vuelve a «En curso», con la misma URL. `comprobar` pasa y la ejecución termina «Completada». `implement` no se repite.
7. El detalle de `comprobar` dice «intento 2 · lo pidió admin». En «Intentos anteriores» sale «Intento 1 Fallida».
8. En el timeline: «admin reintentó la fase comprobar (intento 2)» y «Fallida → En curso».
9. En la API:

   ```bash
   R=<id de la ejecución>
   api $B/workflow-runs/$R | jq '{status, finishedAt, stages: [.stages[] | {stageKey, attempt, status, retriedBy}]}'
   ```

   - `implement` sale una vez.
   - `comprobar` y `cerrar` salen dos veces cada una. Su intento 2 está `SUCCEEDED` y lleva `retriedBy: "admin"`.
   - `finishedAt` es la hora en que terminó el intento 2.
10. En la cabecera ya no sale **Reintentar…** del agente: en una ejecución de workflow se reintenta la fase. En una ejecución ad hoc (un agente suelto, sin workflow) sigue saliendo.

## 3. No cuenta para `maxAttempts`

1. Cambia `comprobar` para que falle siempre y se reintente sola una vez:

   ```yaml
     - id: comprobar
       type: command
       dependsOn: [implement]
       command: "exit 1"
       retry: {maxAttempts: 2, delay: 5s}
   ```

   Publica la versión 2 y lánzala.
2. `comprobar` falla dos veces (intento 1 y su reintento automático) y la ejecución termina «Fallida».
3. **Reintentar fase** en `comprobar`. Arranca el intento 3, lo pedido por ti. Al fallar, el motor programa también su reintento automático: «Reintenta a las … (intento 4)». No pone «de 2», porque ese contador volvió a empezar.
4. La ejecución termina «Fallida» después del intento 4.

## 4. Una puerta rechazada se reintenta desde lo que revisa

Publica:

```yaml
id: puerta-reintento
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
  - id: aprobar
    type: human-approval
    dependsOn: [implement]
    action: Aprobar el arreglo de {{workItem.key}}
  - id: cerrar
    type: command
    dependsOn: [aprobar]
    command: "true"
```

1. Lánzala y, en **Aprobaciones**, **Rechazar** con un comentario. La ejecución termina «Fallida».
2. En el progreso, `aprobar` no ofrece **Reintentar fase**. Por la API, reintentarla da 409 («Una puerta no se reintenta así…»):

   ```bash
   api -X POST $B/workflow-runs/$R/stages/aprobar/retry
   ```

3. `implement`, aunque terminó bien, sí lo ofrece: su confirmación dice «Después vuelven a lanzarse aprobar y cerrar». Confirma. `implement` corre otra vez y `aprobar` vuelve a pedir aprobación: en **Aprobaciones** hay una pendiente nueva. La rechazada sigue en **Rechazadas**.
4. Aprueba. La ejecución termina «Completada».

## 5. Cancelar una `parallel` no deja procesos

1. Publica una fase `parallel` con dos agentes y lánzala:

   ```yaml
   id: paralelo
   agents:
     uno:
       prompt: Revisa la primera mitad
     dos:
       prompt: Revisa la segunda mitad
   stages:
     - id: revisar
       type: parallel
       children:
         - agent: uno
         - agent: dos
   ```

2. Con los dos agentes en curso, **Cancelar ejecución**. La ejecución queda «Cancelada».
3. `pgrep -fa dev.skynet.fakeclaude` ya no devuelve nada.

## 6. Comprobaciones automáticas

- `./gradlew check` (con `SKYNET_TEST_DB_URL`). Los tests nuevos de `WorkflowEngineIT`:
  - reintentar la fase reabre la misma ejecución y los reintentos automáticos vuelven a contar;
  - repetir una fase que terminó bien repite las que dependen de ella, sin volver a su foto;
  - una puerta rechazada se reintenta desde el plan.
- `cd web && npx vitest run`.
- `scripts/e2e.sh cancel-retry.e2e.ts retries.e2e.ts`: el criterio de aceptación de W6, con axe en la confirmación.
