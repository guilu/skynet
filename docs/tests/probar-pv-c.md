# Probar PV-C en local

PV-C añade **Gemini CLI** como tercer proveedor de agentes (#79). Un agente con `provider: gemini` en el YAML se ejecuta con `gemini --output-format stream-json` en un runner que tenga Gemini CLI. Se ven su conversación, sus herramientas y su coste estimado, y se le pueden enviar mensajes. Bifurcarlo no se puede, porque Gemini CLI no lo permite. Gemini tampoco tiene salida estructurada, así que su `output` lo pide el runner en el prompt y lo valida él.

Con Claude Code y Codex todo sigue igual. La prueba usa **fake-gemini**; al final hay un paso opcional con Gemini de verdad.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude **y** fake-gemini:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_GEMINI_BIN=$FAKE/fake-gemini \
SKYNET_AGENT_ENV=FAKE_GEMINI_FIXTURE,FAKE_GEMINI_DELAY_MS,FAKE_GEMINI_STRUCTURED_OUTPUT \
FAKE_GEMINI_FIXTURE=02-tools FAKE_GEMINI_DELAY_MS=500 GEMINI_CLI_HOME=/tmp/fake-gemini-home \
FAKE_GEMINI_STRUCTURED_OUTPUT='{"hasBlockingFindings": false}' \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

No hay migraciones ni dependencias nuevas. Si tienes Codex instalado, también aparecerá; no molesta.

- El runner escribe al conectarse `proveedores: claude-code 2.1.0 (fake-claude), gemini 0.65.0`.
- En **Runners**, la columna de versiones dice `… · Gemini CLI 0.65.0`.

## 2. Un workflow con Gemini

1. En **Workflows**, **Nuevo workflow** y pega:

   ```yaml
   id: con-gemini
   name: Con Gemini
   agents:
     arreglador:
       provider: gemini
       model: gemini-2.5-pro
       prompt: Arregla add() en calc.py.
       permissionMode: acceptEdits
       limits:
         maxTurns: 10
     revisor:
       provider: gemini
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

2. El editor no marca errores. Salen dos **avisos**: «Gemini CLI no admite `maxTurns`: se ignora…» en `limits.maxTurns` y «Gemini CLI no admite una lista de herramientas: se ignora…» en `tools`. Se puede publicar.
3. Prueba a cambiar un `provider` por `opencode`: sale «El proveedor `opencode` todavía no se ejecuta. Por ahora solo `claude-code`, `codex` y `gemini`». Déjalo otra vez en `gemini` y **Publicar**.

## 3. Ejecutarlo

1. Lanza `con-gemini` sobre un repositorio registrado. Las dos fases terminan «Completada».
2. En el agente de `fix`:
   - Tiene el modelo `gemini-2.5-pro`.
   - La conversación tiene dos mensajes completos, no troceados: «Voy a mirar calc.py.» y «He cambiado `a - b` por `a + b` en calc.py y la prueba pasa.».
   - Las herramientas son `read_file`, `replace`, `Bash` (`python3 -m unittest -q`) y otro `Bash` (`git push`) en rojo, porque la política lo denegó: «Tool execution denied by policy.».
   - Cambios de ficheros: `calc.py` modificado. fake-gemini no lo cambia de verdad, así que el diff del worktree puede salir vacío.
   - El coste es de unos **0,02 US$**.
3. En el agente de `review`:
   - La salida estructurada es `{"hasBlockingFindings": false}`.
   - En **Prompt**, al final del prompt aparece la instrucción que añade el runner, con el schema y el bloque ```` ```json ````.
   - No fija modelo, así que su coste, unos 0,02 US$, sale de los dos modelos que usó `auto`.

## 4. Continuar, pero no bifurcar

1. En el agente de `fix`, **Enviar mensaje** con «Añade un test». Arranca en el mismo runner y worktree y termina «Completada» con «Sigo donde lo dejamos: he añadido el test que pedías.». Conserva el modelo `gemini-2.5-pro` y la conversación tiene los dos turnos.
2. **Bifurcar**: responde con el error «Gemini CLI no permite bifurcar una sesión». La web todavía enseña el botón; se oculta en PV-D.
3. En `/tmp/fake-gemini-home/.gemini/tmp/<carpeta del worktree>/chats` hay un fichero por sesión.

## 5. Fallos

1. Para el runner y arráncalo igual, pero con `FAKE_GEMINI_STRUCTURED_OUTPUT='{"hasBlockingFindings": "no"}'`. Lanza `con-gemini`: `review` termina «Fallida» con «La salida no cumple el schema de `output`: `hasBlockingFindings` debe ser boolean».
2. Arráncalo con `FAKE_GEMINI_FIXTURE=04-turn-limit` (y la salida estructurada buena). Lanza otra vez: `fix` termina «Fallida» con el error «Reached max session turns…» y código de salida 53.
3. Arráncalo con `FAKE_GEMINI_FIXTURE=05-cancelled`. Mientras `fix` está «Ejecutando» (`sleep 600`), **Cancelar**: termina «Cancelada».

## 6. Con Gemini de verdad (opcional)

Necesita `gemini` instalado con la sesión de Google iniciada (o `GEMINI_API_KEY`).

1. Arranca el runner sin `SKYNET_GEMINI_BIN` ni las variables `FAKE_GEMINI_*`: en **Runners** aparece `Gemini CLI <versión real>`.
2. Lanza `con-gemini` sobre un repositorio de prueba con un `calc.py` roto.

Los fixtures se escribieron a partir del código de Gemini CLI y no de una sesión grabada. Comprueba:

- Las fases terminan, y la conversación y las herramientas se ven como con fake-gemini.
- `review` devuelve una salida estructurada válida a partir de la instrucción del prompt.
- **Enviar mensaje** continúa la sesión con su modelo.
- Con `permissionMode: acceptEdits`, ¿puede ejecutar los tests o Gemini deniega los comandos de shell? Si los deniega, el agente necesitará `bypassPermissions` para pasar los tests, y lo documentaremos.

Si algo no cuadra, guarda el log NDJSON del agente (`~/.skynet-runner/logs/<agente>.ndjson`) y pásamelo.

## Qué mirar si algo falla

- Logs del runner: la línea `proveedores:` al conectarse y el fin de cada proceso.
- El log NDJSON del agente tiene la salida tal cual de Gemini CLI.
- [`docs/gemini-stream-json.md`](../gemini-stream-json.md) explica cómo se traduce cada evento, cada modo de permisos y la salida estructurada.
