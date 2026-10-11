# Probar W3-B en local

W3-B añade las **fases `parallel`** (#66): una fase que ejecuta varios agentes a la vez, por ejemplo dos revisores después de implementar. Cada hijo trabaja en una copia del worktree que hereda la fase, con los cambios sin confirmar, y la fase siguiente continúa el worktree original. La web aún no dibuja los hijos de forma especial (llega en W3-D): salen como los agentes de su fase, con el id del hijo como nombre.

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

Hay una migración nueva (V14, una columna en `agent_run`) que se aplica sola al arrancar. Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado (un repositorio git real en tu máquina) y un trabajo.

## 2. Validación en el editor

En **Workflows**, **Nuevo workflow**, pega esto y comprueba los problemas que marca el editor:

```yaml
id: paralelo-mal
agents:
  calidad:
    prompt: Revisa
stages:
  - id: a
    type: parallel
  - id: b
    type: parallel
    agent: calidad
    children:
      - agent: calidad
      - agent: calidad
      - agent: nadie
  - id: c
    type: agent
    prompt: hola
    dependsOn: [b]
    workspaceFrom: b.seguridad
```

- `a`: «A esta fase le falta `children`…».
- `b.agent`: «Una fase `parallel` no tiene agente propio…».
- El segundo `calidad`: «Ya hay otro hijo `calidad` en esta fase: ponle a uno un `id` distinto…».
- `nadie`: «No hay ningún agente `nadie` en `agents`».
- `c.workspaceFrom`: «La fase `b` no tiene ningún hijo `seguridad`…».

Descarta el borrador.

## 3. Revisar en paralelo

1. **Nuevo workflow** con esto, **Crear borrador** y **Publicar…**:

   ```yaml
   id: revision-paralela
   name: Revisión en paralelo
   agents:
     calidad:
       prompt: Revisa la calidad de {{workItem.title}}
       model: claude-haiku-4-5
     seguridad:
       prompt: Revisa la seguridad
   stages:
     - id: preparar
       type: command
       command: "echo borrador > cambios.txt"
     - id: review
       type: parallel
       dependsOn: [preparar]
       children:
         - agent: calidad
         - agent: seguridad
     - id: comprobar
       type: command
       command: "cat cambios.txt && git status --short"
       dependsOn: [review]
     - id: endurecer
       type: command
       command: "echo endurecido >> cambios.txt && cat cambios.txt"
       dependsOn: [review, comprobar]
       workspaceFrom: review.seguridad
   ```

   En el resumen del workflow, `review` dice «A la vez: calidad, seguridad» y su worktree «Una copia por hijo del de su dependencia».
2. En el trabajo, **Lanzar workflow** con `revision-paralela`. En «Política de cada fase» salen `review.calidad` (con «modelo claude-haiku-4-5») y `review.seguridad`.
3. La ejecución termina **Completada**:
   - En `review` hay dos agentes, `calidad` y `seguridad`, que arrancan a la vez. Cada uno tiene su propio worktree (rutas distintas entre sí y de la de `preparar`), y en sus cambios aparece `cambios.txt` como fichero nuevo: los dos ven lo que `preparar` dejó sin confirmar.
   - `comprobar` corre en el worktree de `preparar` (misma ruta en sus artefactos) y su salida enseña `borrador`.
   - `endurecer` corre en el worktree de `seguridad` (la misma ruta que ese agente) y su salida enseña `borrador` y `endurecido`.
   - En el repositorio, el `cambios.txt` del worktree de `preparar` sigue diciendo solo `borrador`.
4. En el agente `calidad`, **Reintentar** lanza otro agente con el mismo modelo (con Claude real, el log del runner muestra `--model claude-haiku-4-5`).

## 4. Un hijo que se cancela

Lanza otra vez `revision-paralela` con `FAKE_CLAUDE_DELAY_MS=5000` en el runner (para que dé tiempo) y, con los dos revisores en marcha, abre el agente `calidad` y **Cancelar**:

- `seguridad` recibe la orden de terminar sin que hagas nada y acaba **Cancelado**.
- `review` sale **Cancelada** cuando terminan los dos, `comprobar` y `endurecer` salen **Canceladas** sin arrancar y la ejecución termina **Cancelada**.

## 5. Comprobaciones automáticas

- `./gradlew build` (con `SKYNET_TEST_DB_URL`): `DefinitionParserTest` (fases `parallel`, `children` y `workspaceFrom: <fase>.<hijo>`), `PlannerTest` (un hijo que falla cancela a sus hermanos y las demás fases), `WorkflowEngineIT` (los hijos van en copias del worktree heredado, la fase termina con los dos, la siguiente continúa el original y otra el de un hijo; un hijo que falla cancela a su hermano y hace fallar la ejecución) y `AgentExecutorTest` (dos hijos ven los cambios sin confirmar del original y no se pisan).
- `cd web && npm test` (contratos de `run-view`, `workflow-version` y el resto con `childKey` y `children`).
