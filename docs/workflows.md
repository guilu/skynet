# Workflows en YAML

Un workflow describe las fases de un trabajo y qué agente hace cada una (§11 de la [especificación](agentic-orchestration-system.md)). Se escribe en YAML, se guarda como borrador y, cuando no tiene errores, se publica como una versión que ya no cambia. Las ejecuciones guardan la versión exacta con la que arrancaron.

En la web se escriben en **Workflows**: el editor autocompleta con el JSON Schema de [`protocol/.../workflow-definition.schema.json`](../protocol/src/main/resources/dev/skynet/protocol/workflow-definition.schema.json) y, mientras se escribe, el control plane valida el texto con los mismos mensajes que al guardar (también lo que un schema no expresa) y los marca en su línea.

## Ejemplo

```yaml
id: revisar-y-corregir      # clave del workflow; no cambia entre versiones
version: 1                  # la pone Skynet al guardar
name: Revisar y corregir
description: Un agente revisa el cambio y otro corrige lo que encuentre.

inputs:                     # datos que se piden al lanzar
  foco:
    type: string            # string, text, number o boolean
    required: false
    default: seguridad
    description: En qué fijarse.

agents:                     # agentes con nombre
  reviewer:
    prompt: |
      Revisa {{workItem.key}} ({{workItem.title}}) poniendo el foco en {{inputs.foco}}.
    tools: [Read, Grep, Glob]
    permissionMode: dontAsk
    limits:
      maxTurns: 30
      maxBudgetUsd: 1.5
      timeoutMinutes: 20

stages:
  - id: review
    type: agent
    agent: reviewer
  - id: fix
    type: agent
    prompt: Corrige lo que encontró la revisión.
    dependsOn: [review]     # continúa el worktree de review
```

## Referencia

**Raíz:** `id` (obligatorio; de 2 a 49 minúsculas, números y guiones; `new`, `schema` y `validate` están reservados), `version`, `name`, `description`, `inputs`, `agents` y `stages` (obligatorio, al menos una fase).

**`inputs.<nombre>`:** `type` (`string` por defecto), `required`, `default` y `description`. Los prompts los usan como `{{inputs.<nombre>}}`.

**`agents.<nombre>`:**
- `prompt`: plantilla con variables.
- `tools`: herramientas de Claude Code, como `Edit` o `Bash(git:*)`. Por defecto, las del repositorio.
- `permissionMode`: `dontAsk`, `acceptEdits`, `default` o `plan`. Por defecto, el del repositorio.
- `limits`: `maxTurns` (1-1000), `maxBudgetUsd` (0,01-1000) y `timeoutMinutes` (1-1440).
- Al lanzar (desde W2), lo que pide un agente se recorta a la política del repositorio: nunca obtiene más herramientas ni límites más altos que los suyos.

**`stages[]`:**
- `id` (obligatorio, único) y `type` (obligatorio).
- `agent`: un agente de `agents`. Sin él, la fase usa la política del repositorio.
- `prompt`: el de la fase; si falta, el del agente. Uno de los dos tiene que existir.
- `dependsOn`: fases que tienen que terminar antes. Con `?` al final (`plan?`) es opcional: también vale si esa fase se omite. Entre corchetes va entre comillas (`dependsOn: ["plan?"]`), porque YAML lee `?` como otra cosa.
- `workspace`: `inherit` (por defecto) continúa el worktree de la fase con agente de la que depende, sesión nueva pero mismos ficheros; sin ninguna, empieza uno nuevo. `isolated-worktree` empieza siempre uno nuevo desde la rama por defecto.
- `workspaceFrom`: obligatorio si la fase depende de varias fases con agente y no es `isolated-worktree`; dice cuál continúa.

**Variables de los prompts:** `{{workItem.key}}`, `{{workItem.title}}`, `{{workItem.description}}`, `{{workItem.type}}`, `{{workItem.externalRef}}`, `{{project.key}}`, `{{project.name}}` e `{{inputs.<nombre>}}`.

## Lo que aún no se ejecuta

El YAML admite ya todo §11 para poder escribir los workflows de la especificación, pero solo se publica un workflow que el motor sabe ejecutar. Lo demás sale como problema `UNSUPPORTED` con el hito en el que llega:

| Qué | Hito |
|---|---|
| Fases `command`, `parallel` y `conditional`; `condition`, `children` y `command` | W3 |
| Fase `verification`; `outputSchema` y `artifacts`; `{{stages.<id>...}}` en los prompts | W4 |
| Fase `human-approval` | W5 |
| `retry` y `timeout` | W6 |
| Fases `github-pr`, `github-check`, `github-merge` y `merge` | S2 |
| `promptTemplate` | S3 |
| Fase `deploy` | sin fecha |

## Estados y versiones

- **Borrador** (`DRAFT`): se guarda aunque tenga errores.
- **Validado** (`VALIDATED`): borrador sin errores. Se calcula al guardar.
- **Publicado** (`PUBLISHED`): congelado; no se edita ni se borra. Editar un workflow publicado abre un borrador de la versión siguiente.

Hay como mucho un borrador por workflow. Guardar o publicar un borrador exige la `revision` que se leyó, para no pisar los cambios de otra pestaña.

## API

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/workflows?archived=` | Lista los workflows con su última versión publicada y su borrador |
| `POST` | `/api/workflows` | Crea un workflow con el YAML (`sourceYaml`) como borrador de la versión 1 |
| `POST` | `/api/workflows/validate` | Valida un YAML sin guardarlo; con `key` y `version`, como el borrador de esa versión |
| `GET` | `/api/workflows/schema` | El JSON Schema, para el editor |
| `GET` | `/api/workflows/{key}` | El workflow con todas sus versiones |
| `POST` | `/api/workflows/{key}/draft` | Abre (o devuelve) el borrador de la versión siguiente |
| `POST` | `/api/workflows/{key}/archive` y `/restore` | Archiva o restaura (`adhoc` no se archiva) |
| `GET` | `/api/workflow-versions/{id}` | Una versión con su YAML, su validación y el workflow leído |
| `PUT` | `/api/workflow-versions/{id}` | Guarda un borrador (`sourceYaml`, `revision`) |
| `POST` | `/api/workflow-versions/{id}/publish` | Publica un borrador (`revision`); 409 con `problems` si no se puede |
| `DELETE` | `/api/workflow-versions/{id}` | Descarta un borrador; si era la única versión, el workflow desaparece |

Cada problema lleva `severity` (`ERROR`, `UNSUPPORTED` o `WARNING`), `path` (p. ej. `stages.fix.dependsOn[0]`), `line`, `column` y `message`.
