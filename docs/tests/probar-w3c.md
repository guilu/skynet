# Probar W3-C en local

W3-C añade las **condiciones** (#67): un agente puede declarar su salida estructurada con `output`, cualquier fase puede llevar una `condition` que la omite si es falsa, y la fase `type: conditional` solo evalúa su condición. La web aún no dibuja las ramas de forma especial (llega en W3-D): la condición sale en el resumen del workflow y en el timeline.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude. El runner pasa al agente la salida estructurada que devolverá fake-claude (`FAKE_CLAUDE_STRUCTURED_OUTPUT`), que tiene que ir en `SKYNET_AGENT_ENV`:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_DELAY_MS,FAKE_CLAUDE_STRUCTURED_OUTPUT \
FAKE_CLAUDE_FIXTURE=01-simple-text FAKE_CLAUDE_DELAY_MS=500 \
FAKE_CLAUDE_STRUCTURED_OUTPUT='{"hasBlockingFindings":true}' \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Hay una migración nueva (V15, dos columnas en `agent_run`) que se aplica sola al arrancar. Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado y un trabajo. Si el repositorio limita las variables de entorno de sus agentes, deja pasar `FAKE_CLAUDE_STRUCTURED_OUTPUT`.

## 2. Validación en el editor

En **Workflows**, **Nuevo workflow**, pega esto y comprueba los problemas que marca el editor, cada uno en su línea y columna:

```yaml
id: condiciones-mal
agents:
  revisor:
    prompt: Revisa
    output:
      type: object
      properties:
        hasBlockingFindings: {type: boolean}
  raro:
    prompt: hola
    output: {type: string}
stages:
  - id: review
    type: agent
    agent: revisor
  - id: a
    type: agent
    prompt: hola
    dependsOn: [review]
    condition: review.hasBlockingFindings &&
  - id: b
    type: agent
    prompt: hola
    dependsOn: [review]
    condition: review.nada
  - id: c
    type: agent
    prompt: hola
    condition: review.hasBlockingFindings
  - id: d
    type: agent
    prompt: hola
    dependsOn: [review]
    condition: review.status > 3
  - id: e
    type: conditional
    dependsOn: [review]
```

- `raro`: el aviso «Ninguna fase usa el agente `raro`» y, en su `output`, «La salida estructurada tiene que ser un objeto: pon `type: object` y sus campos en `properties`».
- `a`: «La condición termina antes de tiempo», al final de la condición.
- `b`: «El `output` del agente `revisor` no tiene el campo `nada`».
- `c`: «`review` no es una dependencia de `c`: una condición solo lee fases que han terminado antes (ponla en `dependsOn`)».
- `d`: «`>` compara dos números o dos textos, no un texto y un número».
- `e`: «A esta fase le falta `condition`, p. ej. `condition: review.hasBlockingFindings`».

Descarta el borrador.

## 3. Arreglar solo si hace falta

1. **Nuevo workflow** con esto, **Crear borrador** y **Publicar…**:

   ```yaml
   id: revisar-y-arreglar
   name: Revisar y arreglar
   agents:
     revisor:
       prompt: Revisa {{workItem.title}} y di si hay algo que bloquee
       output:
         type: object
         properties:
           hasBlockingFindings: {type: boolean}
   stages:
     - id: review
       type: agent
       agent: revisor
     - id: fix
       type: agent
       prompt: Arregla lo que encontró la revisión
       dependsOn: [review]
       condition: review.hasBlockingFindings
     - id: limpio
       type: conditional
       dependsOn: [review]
       condition: "!review.hasBlockingFindings"
     - id: final
       type: command
       command: "echo terminado"
       dependsOn: [review, "fix?"]
       workspaceFrom: review
   ```

   En el resumen del workflow, `fix` dice «Solo si `review.hasBlockingFindings`» y `limpio` «Evalúa `!review.hasBlockingFindings`».
2. Con el runner arrancado con `{"hasBlockingFindings":true}`, en el trabajo, **Lanzar workflow** con `revisar-y-arreglar`. La ejecución termina **Completada**:
   - El agente de `review` tiene `structuredOutput` con `hasBlockingFindings: true` (en `GET /api/agent-runs/{id}`). Con Claude real, el log del runner muestra `--json-schema`.
   - `fix` se ejecuta y en el timeline sale «Condición de fix: review.hasBlockingFindings es verdadera».
   - `limpio` sale **Omitida porque su condición es falsa**, y en el timeline «Fase limpio omitida: su condición !review.hasBlockingFindings es falsa».
   - `final` corre después de `fix`.
3. Para el runner y arráncalo de nuevo con `FAKE_CLAUDE_STRUCTURED_OUTPUT='{"hasBlockingFindings":false}'`. Lanza otra vez: `fix` sale **Omitida porque su condición es falsa**, `limpio` **Completada** sin agentes, `final` corre igual (depende de `fix?`) y la ejecución termina **Completada**.

## 4. Un agente que no devuelve su salida

Para el runner y arráncalo **sin** `FAKE_CLAUDE_STRUCTURED_OUTPUT`. Lanza `revisar-y-arreglar`: el agente de `review` termina **Fallido** con «El agente no devolvió la salida estructurada que pide su `output`», y el resto de fases se cancelan sin arrancar.

## 5. Comprobaciones automáticas

- `./gradlew build` (con `SKYNET_TEST_DB_URL`): `ConditionTest` (el lenguaje: operadores, `null`, tipos y errores con su posición), `DefinitionParserTest` (`output`, `condition` con cada error en su línea y columna, fases `conditional`), `WorkflowEngineIT` (una condición falsa omite su fase y la opcional que depende de ella sigue; una verdadera la ejecuta; un agente sin su salida falla), `ClaudeCodeProviderTest` (`--json-schema`) y `FakeClaudeTest` (`FAKE_CLAUDE_STRUCTURED_OUTPUT`).
- `cd web && npm test` (contratos con `structuredOutput`, `output` y `condition`; textos de las condiciones en el timeline y en el árbol).
