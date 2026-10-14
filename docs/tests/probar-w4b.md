# Probar W4-B en local

W4-B (#87) deja que una fase declare qué tiene que comprobar Skynet en su worktree antes de darla por buena: `artifacts` (ficheros que tienen que existir), `checks` (`tests`, `diff`, `commit`, `scope` o `command: <cmd>`) y `scope` (lo que se puede cambiar). Mientras se comprueba, la fase sigue en curso; si algo no pasa, falla con el motivo y se aplica el fail-fast. También se ejecuta ya la fase `type: verification`, que solo comprueba el worktree que hereda.

Hay una migración nueva (`V18`): se aplica sola al arrancar el control plane. La web completa (la fase «Verificando» en el progreso, la plantilla) llega con W4-C; aquí las comprobaciones se ven en la pestaña **Verificación** del agente y en el timeline.

La prueba usa fake-claude aplicando de verdad sus cambios. Al final hay un paso opcional con Claude Code de verdad.

## 1. Arrancar

En la rama de la PR, arranca el control plane y la web, y el runner con fake-claude reproduciendo `02-tools` con `FAKE_CLAUDE_APPLY=1`. Ese fixture cambia `return a - b` por `return a + b` en `calc.py` y hace un commit:

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

Prepara un repositorio de prueba con `calc.py` en la rama por defecto:

```bash
mkdir -p /tmp/w4b && cd /tmp/w4b && git init -q -b main
printf 'def add(a, b):\n    return a - b\n' > calc.py
git add . && git commit -qm init
```

Regístralo en un proyecto y, en su página, pon como **comando de verificación** `python3 -c "import calc; assert calc.add(2, 3) == 5"`.

## 2. Todo pasa: la fase espera a sus comprobaciones

1. En **Workflows**, **Nuevo workflow**, pega esto, **Crear borrador** y **Publicar** (no debe salir ningún problema):

   ```yaml
   id: verificada
   stages:
     - id: implement
       type: agent
       prompt: Arregla add()
       artifacts: [calc.py]
       checks: [diff, commit, scope, {command: "grep -q 'a + b' calc.py"}]
       scope: ["*.py"]
     - id: verify
       type: verification
       dependsOn: [implement]
       checks: [tests]
     - id: cierre
       type: command
       dependsOn: [verify]
       command: git log --oneline -1
   ```

2. En un trabajo, **Lanzar workflow** con `verificada` sobre el repositorio de prueba. Termina «Completada» y `cierre` imprime `fix add`.
3. En el timeline de la ejecución, después de que termine el agente de `implement`, aparecen `stage.verification.started` y `stage.verification.completed` con `status: PASSED`, y solo después `implement` pasa a «Completada».
4. Abre el agente de `implement`, pestaña **Verificación**. La verificación de origen «Comprobaciones de la fase» enseña `✓ artifacts calc.py`, `✓ diff`, `✓ commit`, `✓ scope *.py` y `✓ command grep -q 'a + b' calc.py`.
5. Abre el agente de `verify`: no hay conversación (no ha arrancado ningún proceso) y su verificación enseña `✓ tests` con el comando del repositorio y código de salida 0.

## 3. Lo que no pasa hace fallar la fase

Publica una versión nueva de `verificada` con cada cambio y lánzala; vuelve a dejarla como estaba antes del siguiente.

- `artifacts: [docs/spec.md]` en `implement`: `implement` termina «Fallida» y `verify` y `cierre` «Cancelada». En la pestaña **Verificación** de su agente: `✗ artifacts docs/spec.md · \`docs/spec.md\` no existe`.
- `scope: ["docs/**"]`: `✗ scope docs/** · 1 fichero fuera del ámbito permitido: \`calc.py\``.
- `{command: "grep -q 'a * b' calc.py"}`: `✗ command … · \`grep -q 'a * b' calc.py\` terminó con código 1`, y su salida está en el artefacto `check-1.log`.
- Quita el comando de verificación del repositorio y lanza la versión original: `implement` pasa, pero `verify` termina «Fallida» y su agente dice «tests: El repositorio no tiene comando de verificación». Vuelve a ponerlo.

Con `FAKE_CLAUDE_FIXTURE=01-simple-text` en el runner (el agente no cambia nada), `implement` falla con `diff: No hay cambios respecto al inicio de la fase` y `commit: No hay commits nuevos`.

## 4. Errores al publicar

Edita el borrador y comprueba que cada cambio da el error en su línea:

- `checks: [test]`: «Check `test` desconocido; ¿quisiste decir `tests`?…».
- `checks: [scope]` sin la lista `scope`: «El check `scope` necesita la lista `scope`…».
- `checks: [{command: "make {{inputs.x}}"}]`: «Los comandos no admiten variables `{{...}}`…».
- `artifacts: [../fuera.md]`: «`../fuera.md` tiene que ser una ruta dentro del repositorio…».
- Una `scope` sin el check `scope` en `checks`: aviso «`scope` no se comprueba: añade `scope` a `checks`».
- `checks` en una fase `conditional`: «`checks` solo vale en fases `agent`, `command`, `parallel` o `verification`…».
- En `verify`, quitar `checks`: «A esta fase le falta qué comprobar…»; añadir `prompt: x`: «Una fase `verification` no ejecuta nada…»; quitar `dependsOn`: «Una fase `verification` comprueba el worktree de la fase de la que depende…».

## 5. Una fase parallel comprueba cada hijo (opcional)

```yaml
id: paralela-verificada
agents:
  uno:
    prompt: Arregla add()
  dos:
    prompt: Arregla add()
stages:
  - id: review
    type: parallel
    checks: [commit]
    children:
      - agent: uno
      - agent: dos
```

Con `02-tools` termina «Completada» y cada hijo tiene su verificación «Comprobaciones de la fase». Con `01-simple-text`, `review` termina «Fallida» y cada hijo dice `✗ commit · No hay commits nuevos`.

## 6. Con Claude Code de verdad (opcional)

Arranca el runner sin `SKYNET_CLAUDE_BIN` ni `FAKE_*` y lanza `verificada` sobre el repositorio de prueba. Si Claude corrige `add()` y hace commit, pasa todo; si no hace commit, `implement` falla con «No hay commits nuevos» o «Quedan cambios sin confirmar…», que es justo lo que W4-B tiene que detectar.

## Qué mirar si algo falla

- Los eventos `stage.verification.started` y `stage.verification.completed` del timeline, con cada check y su motivo.
- `GET /api/agent-runs/<agente>/verifications`: la verificación con `trigger: STAGE`, `stageRunId` y `checks`.
- Los logs del runner y el artefacto `check-<n>.log` de cada `command`.
- Si el runner se cae a mitad de una comprobación, la fase se queda «En curso»: es un límite conocido de este hito.
