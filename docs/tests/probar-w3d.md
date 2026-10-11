# Probar W3-D en local

W3-D cierra el hito W3 (#68) en la web: la plantilla **«Revisión en paralelo»** en Nuevo workflow, la lista de comandos en el aviso de publicar y, en la vista de ejecución, el comando con su código de salida, los hijos de una fase `parallel` con su salida estructurada y el resultado de cada condición. La E2E de aceptación de W3 lanza esa plantilla con hallazgos y sin ellos.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude. Cada revisor devuelve como salida estructurada lo que haya en `.revision.json` dentro de su worktree (`@` delante del nombre del fichero):

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_DELAY_MS,FAKE_CLAUDE_STRUCTURED_OUTPUT \
FAKE_CLAUDE_FIXTURE=01-simple-text FAKE_CLAUDE_DELAY_MS=500 \
FAKE_CLAUDE_STRUCTURED_OUTPUT=@.revision.json \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

No hay migraciones nuevas. Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado y un trabajo.

## 2. La plantilla

1. **Workflows**, **Nuevo workflow**. Arriba sale **Plantilla** con «Dos fases». Elige **Revisión en paralelo**:
   - El editor cambia al YAML de la plantilla (`id: revision-en-paralelo`) sin pedir confirmación, porque no habías tocado nada.
   - El resumen enseña cinco fases. `tests` dice «Ejecuta `./gradlew test`», `review` «A la vez: calidad, seguridad» y `fix` «Solo si `review.hasBlockingFindings`».
   - Escribe algo en el editor y vuelve a **Dos fases**: el navegador pregunta antes de sustituir lo escrito. Cancela.
2. Escribe `outp` dentro de un agente y comprueba que el autocompletado ofrece `output`. Haz lo mismo con `condition` dentro de una fase.

## 3. Publicar

Cambia los dos `command` de la plantilla para que valgan en tu repositorio. Para probar sin tests de verdad:

- En `tests`: `command: printf '{"hasBlockingFindings":true}' > .revision.json`
- En `tests-finales`: `command: test -f .revision.json && git status --short`

Cambia también el `id` a `revision-con-hallazgos`. Después, **Crear borrador** y **Publicar…**: el aviso lista los dos comandos («Ejecuta estos comandos de shell en el worktree…»). Publica.

Repite con `id: revision-limpia` y `"hasBlockingFindings":false` en el primer comando.

## 4. Con hallazgos

En el trabajo, **Lanzar workflow** con `revision-con-hallazgos`. La ejecución termina **Completada**. En el progreso:

- **tests**: «ejecuta `printf …`» y, en su invocación, «Código de salida 0».
- **review**: «2 agentes a la vez», con `calidad` y `seguridad`, cada uno con su worktree y «Salida: hasBlockingFindings: sí».
- **fix**: se ejecuta y su nota dice «Después de review · review.hasBlockingFindings se cumplió».
- **tests-finales**: «Completada» después de `fix`.

## 5. Sin hallazgos

Lanza `revision-limpia`. La ejecución termina **Completada**:

- **review**: los dos revisores con «Salida: hasBlockingFindings: no».
- **fix**: **Omitida**, con la nota «Omitida: review.hasBlockingFindings es falsa» y ningún agente.
- **tests-finales**: se ejecuta igual, porque depende de `fix?`.

## 6. Comprobaciones automáticas

- `./gradlew build` (con `SKYNET_TEST_DB_URL`): `ContractIT` (`run-view` con `type`, `command` y `condition` por fase) y `FakeClaudeTest` (`FAKE_CLAUDE_STRUCTURED_OUTPUT=@fichero`).
- `cd web && npm test`: la plantilla en `Workflows.test.tsx`, las notas de las condiciones en `stageNotes.test.ts` y el resumen de la salida en `agentOutput.test.ts`.
- `scripts/e2e.sh nodes`: la E2E de aceptación de W3. Publica la plantilla desde la web y la lanza dos veces, con hallazgos (corrige) y sin ellos (omite `fix`), con axe en la plantilla, el aviso de publicar y la ejecución.
