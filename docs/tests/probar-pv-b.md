# Probar PV-B en local

PV-B añade **Codex** como segundo proveedor de agentes (#78): un agente con `provider: codex` en el YAML se ejecuta con `codex exec --json` en un runner que tenga Codex, con su conversación, sus herramientas, su coste estimado y la posibilidad de enviarle un mensaje o bifurcarlo. Con Claude Code todo sigue igual. La prueba usa **fake-codex**, que imita el CLI sin gastar; al final hay un paso opcional con Codex de verdad.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude **y** fake-codex:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex \
SKYNET_AGENT_ENV=FAKE_CODEX_FIXTURE,FAKE_CODEX_DELAY_MS,FAKE_CODEX_STRUCTURED_OUTPUT,CODEX_HOME \
FAKE_CODEX_FIXTURE=02-tools FAKE_CODEX_DELAY_MS=500 CODEX_HOME=/tmp/fake-codex-home \
FAKE_CODEX_STRUCTURED_OUTPUT='{"hasBlockingFindings":false}' \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

No hay migraciones ni dependencias nuevas.

- El runner escribe al conectarse `proveedores: claude-code 2.1.0 (fake-claude), codex codex-cli 0.50.0 (fake-codex)`.
- En **Runners**, la columna de versiones dice `runner <versión> · Claude Code 2.1.0 (fake-claude) · Codex codex-cli 0.50.0 (fake-codex)`.

## 2. Un workflow con Codex

1. En **Workflows**, **Nuevo workflow** y pega:

   ```yaml
   id: con-codex
   name: Con Codex
   agents:
     arreglador:
       provider: codex
       model: gpt-5.5
       prompt: Arregla add() en calc.py.
       permissionMode: acceptEdits
       limits:
         maxTurns: 10
     revisor:
       provider: codex
       prompt: Revisa el cambio.
       permissionMode: plan
       tools: [Read, Grep]
       output:
         type: object
         properties:
           hasBlockingFindings: {type: boolean}
   stages:
     - id: fix
       type: agent
       agent: arreglador
     - id: review
       type: agent
       agent: revisor
       dependsOn: [fix]
   ```

2. El editor no marca ningún error ni `UNSUPPORTED` por `provider: codex`, pero sí dos **avisos**: «Codex no admite `maxTurns`: se ignora…» en `limits.maxTurns` y «Codex no admite una lista de herramientas: se ignora…» en `tools`. Se puede publicar.
3. Prueba a cambiar un `provider` por `gemini`: sale «El proveedor `gemini` todavía no se ejecuta… Por ahora solo `claude-code` y `codex`». Déjalo otra vez en `codex` y **Publicar**.

## 3. Ejecutarlo

1. Lanza `con-codex` sobre un repositorio registrado. Las dos fases terminan «Completada».
2. En el agente de `fix`:
   - La conversación muestra el mensaje final «He cambiado `a - b` por `a + b` en calc.py y la prueba pasa.».
   - Las herramientas son `Bash` (`cat calc.py`, la prueba y un `git status` en rojo con código 128), `apply_patch`, `mcp__docs__search` y `WebSearch`.
   - Cambios de ficheros: `calc.py` modificado (fake-codex no lo cambia de verdad, así que el diff del worktree puede salir vacío).
   - El coste es unos **0,10 US$** (estimado con el precio de `gpt-5.5`).
3. En el agente de `review`, la salida estructurada es `{"hasBlockingFindings": false}` y el coste sale vacío o 0: no fija `model`, así que no se sabe qué precio aplicar. El fin del proceso en los logs del runner avisa del modelo sin precio `codex (modelo predeterminado)`.

## 4. Continuar y bifurcar

1. En el agente de `fix`, **Enviar mensaje** con «Añade un test». Arranca en el mismo runner y worktree y termina «Completada» con «Sigo donde lo dejamos: he añadido el test que pedías.». La conversación tiene los dos turnos y el coste acumulado suma el de los dos.
2. Vuelve al agente **original** de `fix` (el del lanzamiento, no el de la continuación) y **Bifurcar** con «Prueba otra solución»: parte de la última continuación y termina «Completada» en un worktree nuevo, con otra sesión.
3. Las dos invocaciones nuevas conservan la configuración del agente del YAML: modelo `gpt-5.5` y un coste de unos 0,01 US$ cada una (no vacío). Antes de este arreglo, la bifurcación tras una continuación salía sin modelo ni coste.
4. Haz lo mismo con el agente de `review`: **Enviar mensaje** y después **Bifurcar** desde el original. Las dos terminan «Completada» con la salida estructurada `{"hasBlockingFindings": false}`.
5. En `/tmp/fake-codex-home/sessions` hay un fichero por sesión (las de los lanzamientos y las bifurcadas).

## 5. Fallos

1. Para el runner y arráncalo igual pero con `FAKE_CODEX_FIXTURE=04-failed`. Lanza `con-codex`: `fix` termina «Fallida» con el error «stream disconnected before completion: rate limit reached».
2. Arráncalo con `FAKE_CODEX_FIXTURE=05-cancelled` y lanza otra vez. Mientras `fix` está «Ejecutando» (`sleep 600`), **Cancelar**: termina «Cancelada».
3. Arráncalo como en el paso 1 pero con `SKYNET_PROVIDERS=claude-code`. Lanza `con-codex`: `fix` se queda «En cola» y el Dashboard avisa «1 agente espera: ningún runner en línea tiene Codex». Al volver a arrancar el runner como en el paso 1, arranca solo.

## 6. Con Codex de verdad (opcional)

Necesita una cuenta de OpenAI y `codex` instalado con `codex login` hecho (o `OPENAI_API_KEY`). Arranca el runner sin `SKYNET_CODEX_BIN` ni las variables `FAKE_CODEX_*`: en **Runners** aparece `Codex codex-cli <versión real>`. Lanza `con-codex` sobre un repositorio de prueba con un `calc.py` roto. Lo que importa comprobar, porque los fixtures se escribieron a partir del código de Codex y no de una sesión grabada:

- Las fases terminan y la conversación y las herramientas se ven como con fake-codex.
- `review` devuelve una salida estructurada válida (el schema estricto lo acepta Codex).
- **Enviar mensaje** continúa la sesión, y **Bifurcar** desde el agente original después de un mensaje termina con el modelo del YAML (aunque `~/.codex/config.toml` tenga otro), su coste y su salida estructurada.

Si algo no cuadra, guarda el log NDJSON del agente (`~/.skynet-runner/logs/<agente>.ndjson`) y pásamelo: con él se corrige el fixture y el traductor.

## Qué mirar si algo falla

- Logs del runner: la línea `proveedores:` al conectarse y el fin de cada proceso.
- El log NDJSON del agente tiene la salida tal cual de `codex exec --json`; al lado, `<agente>.schema.json` es el schema que se le pasó.
- [`docs/codex-exec-json.md`](../codex-exec-json.md) explica cómo se traduce cada evento y cada modo de permisos.
