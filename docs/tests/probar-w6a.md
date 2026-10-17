# Probar W6-A en local

W6-A (#98) trae los **reintentos automáticos**: una fase con `retry` que falla por algo pasajero se repite en vez de hacer fallar la ejecución. Cada intento es una fase nueva de la misma ejecución, parte del worktree tal como lo encontró el intento que falló y usa una sesión nueva. Cada agente que falla guarda por qué (`lost`, `start`, `error`, `timeout`, `budget` o `verification`).

Hay una migración nueva (`V21`): se aplica sola al arrancar el control plane. «Reintentar fase» desde la web llega en W6-C, y el `timeout` por fase, en W6-B.

## 1. Arrancar

Arranca el control plane y la web como siempre. El runner, con fake-claude reproduciendo `02-tools` (cambia `return a - b` por `return a + b` en `calc.py` y hace un commit) y con `FAKE_CLAUDE_FAIL_ONCE`, que hace fallar la primera invocación de fake-claude que vea:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_FAIL_ONCE \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
FAKE_CLAUDE_FAIL_ONCE=/tmp/w6a-falla-una-vez \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

`FAKE_CLAUDE_FAIL_ONCE` crea ese fichero al fallar; mientras exista, fake-claude funciona normal. Para que vuelva a fallar una vez: `rm -f /tmp/w6a-falla-una-vez`.

Usa el repositorio de prueba de W4-B (`/tmp/w4b`, con `calc.py`).

Para la API:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

## 2. El YAML

En **Workflows**, **Nuevo workflow**, pega esto, **Crear borrador** y **Publicar**. No debe salir ningún problema:

```yaml
id: reintenta
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
    retry:
      maxAttempts: 3
      delay: 10s
  - id: tests
    type: command
    command: python3 -c "import calc; assert calc.add(2, 3) == 5"
    dependsOn: [implement]
```

En **Resumen**, `implement` dice «Reintenta: hasta 3 intentos si falla por lost, start o error; espera 10 s».

Comprueba también que estos cambios dan su error en la línea:

- Quitar `maxAttempts`: «A `retry` le falta `maxAttempts`, los intentos en total, p. ej. `maxAttempts: 3`».
- `maxAttempts: 9`: «`stages.implement.retry.maxAttempts` debe estar entre 1 y 5».
- `on: [errror]`: «Fallo `errror` desconocido; ¿quisiste decir `error`?…».
- `delay: 5 minutos`: «`5 minutos` no es una duración…»; `delay: 2h`: «… no puede pasar de 1 h».
- `retry: {maxAttempts: 2}` en una fase `human-approval` o `conditional`: «`retry` solo vale en fases `agent` y `command`…».
- En una fase `parallel`, `retry` en la fase da «En una fase `parallel`, pon `retry` en cada hijo de `children`…»; en un hijo, vale.
- `timeout: 30m` sigue saliendo como «`timeout` todavía no se usa: llega en W6».

## 3. Un fallo pasajero se reintenta

1. `rm -f /tmp/w6a-falla-una-vez` y lanza `reintenta` en un trabajo sobre `/tmp/w4b`.
2. El primer agente de `implement` falla enseguida. En **Progreso**, `implement` sigue en el camino una sola vez, pendiente, con la nota «Reintenta a las HH:MM:SS (intento 2 de 3)». `tests` espera.
3. A los 10 s arranca el intento 2: termina bien, `tests` pasa y la ejecución termina «Completada».
4. Abre `implement` en el progreso: dice «intento 2 de 3» y, en «Intentos anteriores», «Intento 1 Fallida · terminó con error» con «ver el agente», que abre el agente que falló.
5. En la API:

   ```bash
   R=<id de la ejecución>
   api $B/workflow-runs/$R | jq '.stages[] | {stageKey, attempt, status, notBefore, agents: [.agents[] | {kind, status, failure}]}'
   ```

   `implement` sale dos veces: intento 1 `FAILED` con un agente `START`/`FAILED`/`error`, e intento 2 `SUCCEEDED` con un agente `RETRY`/`SUCCEEDED`/`null`.
6. En el timeline está «Fase implement: se reintenta (intento 2 de 3) en 10 s».

## 4. Un fallo que no está en `on` no se reintenta

1. Cambia el `retry` de `implement` a `{maxAttempts: 3, on: [lost], delay: 10s}`, guarda y publica la versión 2.
2. `rm -f /tmp/w6a-falla-una-vez` y lanza `reintenta` otra vez.
3. `implement` falla («Falló: terminó con error»), `tests` se cancela («No arrancó porque falló implement») y la ejecución termina «Fallida», sin intento 2.

## 5. El reintento parte del worktree de su dependencia

Una fase `command` que ensucia el worktree y falla la primera vez. Publica:

```yaml
id: restaura
stages:
  - id: implement
    type: agent
    prompt: Arregla add()
  - id: ensucia
    type: command
    dependsOn: [implement]
    command: "if [ ! -f /tmp/w6a-comando ]; then touch /tmp/w6a-comando; echo basura > basura.txt && git add -A && git -c user.email=a@b -c user.name=a commit -qm basura; echo sucio >> calc.py; exit 1; fi; test ! -f basura.txt && ! git log --format=%s | grep -qx basura && ! grep -q sucio calc.py && grep -q 'a + b' calc.py"
    retry: {maxAttempts: 2, delay: 5s}
```

1. `touch /tmp/w6a-falla-una-vez` (para que fake-claude no falle) y `rm -f /tmp/w6a-comando`.
2. Lanza `restaura`. El intento 1 de `ensucia` deja un fichero nuevo, un commit y un cambio sin confirmar, y falla.
3. El intento 2 comprueba que no hay nada de eso y que sigue el arreglo de `implement`: termina bien y la ejecución queda «Completada».
4. En el repositorio, la foto de cada agente queda en `git -C /tmp/w4b for-each-ref refs/skynet/snapshots`.

## 6. Cancelar mientras espera

1. Cambia el `delay` de `implement` en `reintenta` a `5m`, publica y lanza con `rm -f /tmp/w6a-falla-una-vez`.
2. Con `implement` en «Reintenta a las …», **Cancelar** la ejecución.
3. La ejecución termina «Cancelada», el intento 2 queda cancelado y no arranca ningún agente después.

## 7. Comprobaciones automáticas

- `./gradlew check` (con `SKYNET_TEST_DB_URL`): los nuevos tests del `Planner`, del parser y de `WorkflowEngineIT` (reintento con restauración, sin intentos, cancelar en espera y un hijo de `parallel` reintentado solo), del `WorkspaceManager` y de fake-claude.
- `cd web && npx vitest run`.
- `scripts/e2e.sh retries.e2e.ts`: los apartados 3 a 5 con una fase `command` (un fallo pasajero se reintenta desde el worktree de su dependencia; con `on: [lost]` no).
