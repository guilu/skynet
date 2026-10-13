# Probar W4-A en local

W4-A (#86) deja que una fase declare su propia salida estructurada con `outputSchema`, en línea o en un fichero del repositorio. También deja que el prompt de una fase use lo que devolvió otra con `{{stages.<fase>.output.<campo>}}`. No hay migraciones nuevas.

La prueba usa fake-claude. Al final hay un paso opcional con Claude Code de verdad.

## 1. Arrancar

En la rama de la PR, arranca el control plane, la web y el runner con fake-claude:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_STRUCTURED_OUTPUT \
FAKE_CLAUDE_FIXTURE=01-simple-text \
FAKE_CLAUDE_STRUCTURED_OUTPUT='{"summary":"Añadir un endpoint de salud","risks":["sin tests"]}' \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

fake-claude solo devuelve `FAKE_CLAUDE_STRUCTURED_OUTPUT` a los agentes a los que se les pide una salida estructurada.

En tu repositorio de prueba, en la rama por defecto, crea y confirma un schema:

```bash
mkdir -p schemas
echo '{"type":"object","properties":{"summary":{"type":"string"},"risks":{"type":"array"}}}' > schemas/spec.json
git add schemas && git commit -m "schema de la especificación"
```

## 2. La salida de una fase llega al prompt de la siguiente

1. En **Workflows**, **Nuevo workflow**, pega esto, **Crear borrador** y **Publicar**:

   ```yaml
   id: datos-entre-fases
   agents:
     analista:
       prompt: Especifica {{workItem.title}}
   stages:
     - id: spec
       type: agent
       agent: analista
       outputSchema:
         type: object
         properties:
           summary: {type: string}
           risks: {type: array}
     - id: plan
       type: agent
       dependsOn: [spec]
       prompt: |
         Planifica a partir de: {{stages.spec.output.summary}}
         Riesgos: {{stages.spec.output.risks}}
         Estado de spec: {{stages.spec.status}}
   ```

   En **Qué define**, `spec` dice «Salida: summary, risks».
2. En un trabajo, **Lanzar workflow** con `datos-entre-fases`. Termina «Completada».
3. Abre el agente de `plan`, pestaña **Conversación** (o el artefacto del prompt). El prompt dice:

   ```
   Planifica a partir de: Añadir un endpoint de salud
   Riesgos: ["sin tests"]
   Estado de spec: SUCCEEDED
   ```

4. El agente de `spec` tiene su salida estructurada con `summary` y `risks`.

## 3. Errores al publicar

Edita el borrador y comprueba que cada cambio da el error en su línea:

- `{{stages.spec.output.titulo}}` en el prompt de `plan`: «El `outputSchema` de la fase `spec` no tiene el campo `titulo`».
- Quita `dependsOn: [spec]`: «`spec` no es una dependencia de `plan`: un prompt solo lee fases que han terminado antes (ponla en `dependsOn`)». Vuelve a ponerlo.
- `{{stages.spec.summary}}` (sin `output`): «… no es un dato de otra fase: usa `{{stages.<fase>.output.<campo>}}`…».
- `outputSchema: ../fuera.json` en `spec`: «… tiene que ser una ruta dentro del repositorio…».
- `outputSchema` en una fase `command`: «`outputSchema` solo vale en fases `agent`».

## 4. Schema en un fichero del repositorio

1. Publica otra versión cambiando el `outputSchema` de `spec` por `outputSchema: schemas/spec.json`. En **Qué define**, `spec` dice «Salida: schemas/spec.json». Se publica sin errores aunque el prompt de `plan` lea `summary`: con una ruta, los campos se comprueban al ejecutar.
2. Lánzala: termina «Completada» y `plan` recibe el mismo prompt que en el paso 2.
3. Publica otra versión con `outputSchema: schemas/no-existe.json` y lánzala. El agente de `spec` termina «Fallido» con «Salida estructurada de la fase: No existe `schemas/no-existe.json` en el worktree», y `plan` no arranca.

## 5. Una fase omitida deja vacío

Publica este workflow y lánzalo sin marcar `conSpec`:

```yaml
id: datos-omitidos
inputs:
  conSpec:
    type: boolean
    default: false
stages:
  - id: spec
    type: agent
    prompt: Especifica
    condition: inputs.conSpec
    outputSchema:
      type: object
      properties:
        summary: {type: string}
  - id: plan
    type: agent
    dependsOn: ["spec?"]
    prompt: "Resumen: [{{stages.spec.output.summary}}] estado {{stages.spec.status}}"
```

`spec` queda «Omitida» y el prompt de `plan` es `Resumen: [] estado SKIPPED`.

## 6. Con Claude Code de verdad (opcional)

Arranca el runner sin `SKYNET_CLAUDE_BIN` ni `FAKE_*` y lanza `datos-entre-fases` sobre un repositorio de prueba. Comprueba que `spec` devuelve `summary` y `risks` y que `plan` los recibe en su prompt. Con `provider: codex` o `provider: gemini` en `analista` (y el schema en un fichero) debería pasar lo mismo.

## Qué mirar si algo falla

- El prompt efectivo de cada agente, en sus artefactos.
- El evento `agent.spawned` del agente: `outputSchema` lleva el schema de la fase, o `{"$ref": "schemas/spec.json"}` si está en un fichero.
- Los logs del runner, si el schema del fichero no se lee.
