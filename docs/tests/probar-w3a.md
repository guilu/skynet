# Probar W3-A en local

W3-A añade las **fases `command`** (#65): una fase que ejecuta un comando de shell en el worktree, por ejemplo los tests después de implementar, y que solo termina bien con código 0. También deja elegir el **modelo de cada agente** en el YAML (`model:`) y reserva `provider:` para el hito de proveedores (Codex, Gemini), que va después de W3. La web aún no dibuja los comandos de forma especial (llega en W3-D): un comando sale como el agente de su fase, con una herramienta `Bash`.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=1000 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

No hay migraciones ni dependencias nuevas. Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado (un repositorio git real en tu máquina) y un trabajo.

## 2. Validación en el editor

En **Workflows**, **Nuevo workflow**, pega esto y comprueba los problemas que marca el editor:

```yaml
id: comandos-mal
stages:
  - id: a
    type: command
  - id: b
    type: command
    command: "git checkout {{inputs.rama}}"
  - id: c
    type: agent
    prompt: hola
    command: make
agents:
  x:
    prompt: hola
    provider: codex
    model: "con espacios"
```

- `a`: «A esta fase le falta `command`…».
- `b`: «Los comandos no admiten variables `{{...}}`…».
- `c`: «`command` solo vale en fases `type: command`…».
- `agents.x.provider`: aviso «El proveedor `codex` todavía no se ejecuta: llega con el hito de proveedores…» (no impide guardar, sí publicar).
- `agents.x.model`: «`con espacios` no vale como modelo…».

Descarta el borrador.

## 3. Implementar, pasar un comando y revisar

1. **Nuevo workflow** con esto, **Crear borrador** y **Publicar…**:

   ```yaml
   id: con-comando
   name: Implementar, comprobar y revisar
   agents:
     dev:
       prompt: Implementa {{workItem.title}}
       model: claude-haiku-4-5
   stages:
     - id: implement
       type: agent
       agent: dev
     - id: check
       type: command
       command: "ls -la && git status --short && echo comprobado > comprobado.txt"
       dependsOn: [implement]
     - id: review
       type: agent
       prompt: Revisa lo hecho
       dependsOn: [check]
   ```

   En el resumen del workflow, `check` dice «Ejecuta `ls -la && …`» y su worktree «continúa el de su dependencia», y el agente `dev` lleva «Modelo claude-haiku-4-5».
2. En el trabajo, **Lanzar workflow** con `con-comando`. En «Política de cada fase», `implement` dice «modelo claude-haiku-4-5» y `check` dice «ejecuta `…` en el worktree».
3. La ejecución pasa por las tres fases y termina **Completada**:
   - El agente de `check` tiene una herramienta `Bash` con el comando y su salida (el listado y el estado de git).
   - Sus artefactos: `command.txt` (el comando), `command.log` (la salida completa) y los cambios del worktree, con `comprobado.txt` como archivo nuevo.
   - `review` arranca en el mismo worktree que `implement` y `check` (misma ruta en sus artefactos).
4. En el agente de `check`, **Continuar**, **Bifurcar** y **Reintentar** responden con «Es un comando, no un agente…» (409). Los botones se ajustarán en W3-D.
5. Si el repositorio tiene comando de verificación, `implement` se verifica como siempre, pero `check` no.

Con Claude real, el log del runner muestra `--model claude-haiku-4-5` en la invocación de `implement`.

## 4. Un comando que falla

Edita el workflow (versión 2) cambiando el comando por `echo "2 tests fallan" && exit 2`, publica y lanza otra vez:

- `check` termina **Fallida** con «El comando terminó con código 2» y su salida en la herramienta `Bash`.
- `review` queda **Cancelada** sin arrancar (fail-fast) y la ejecución termina **Fallida**.

## 5. Cancelar un comando en marcha

Cambia el comando por `sleep 120` (versión 3), lanza y, con `check` en marcha, **Cancelar ejecución**. El comando termina enseguida (la fase sale **Cancelada**) y no queda ningún `sleep` vivo (`pgrep -f "sleep 120"` no devuelve nada). Aunque pasen más de 5 minutos, un comando largo no se marca «Sin respuesta».

## 6. El editor en el móvil

Abre **Workflows** en el iPhone (Safari) y entra en un borrador:

- El YAML sale en un campo de texto normal (sin números de línea ni colores): se puede mantener pulsado para seleccionar, copiar, cortar y pegar, y el teclado no pone mayúsculas ni corrige palabras.
- Escribir valida igual que en el ordenador, y tocar un problema de la lista lleva el cursor a su línea.
- En los artefactos de un agente (diff, log, resultado), el texto también se puede seleccionar y copiar.
- En el ordenador (o en un iPad con trackpad) sigue el editor completo, con colores y autocompletado.

## 7. Comprobaciones automáticas

- `./gradlew build` (con `SKYNET_TEST_DB_URL`): `DefinitionParserTest` (fases `command`, modelo y proveedor), `WorkflowEngineIT` (comando en el worktree de la fase anterior, espera a la verificación, no se verifica ni se continúa; un comando que falla hace fallar la ejecución) y `AgentExecutorTest` (salida, código, artefactos, worktree heredado, tiempo máximo y cancelación).
- `cd web && npm test` (contratos `effective-policy` y `workflow-version`, y `PlainYamlEditor.test.tsx` para el editor del móvil).
