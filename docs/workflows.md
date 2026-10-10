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
- Al lanzar, lo que pide un agente se recorta a la política del repositorio: solo las herramientas que el repositorio permite (`Bash(git:*)` cabe en `Bash`, al revés no), su modo de permisos solo si no es más permisivo (`plan` < `default`/`dontAsk` < `acceptEdits`) y sus límites rebajados a los del repositorio. Lo que el agente no fija sale de la política y, los límites, de los del lanzamiento.

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

## Cómo se ejecuta

Una ejecución se lanza sobre un trabajo y un repositorio con una versión publicada y sus datos de entrada (`POST /api/work-items/{id}/runs`):

```json
{ "repositoryId": "…", "definitionId": "…", "inputs": { "issue": "#42" },
  "maxTurns": 30, "maxBudgetUsd": 5, "timeoutMinutes": 60 }
```

- Sin `definitionId` se lanza la última versión de `adhoc`; `prompt` es un atajo para el dato `prompt` de los workflows que lo piden (como `adhoc`) y se rechaza en los demás.
- Los datos se comprueban contra `inputs`: sobra uno, falta uno obligatorio o no es de su tipo → 400 con el motivo. Los que faltan toman su `default`.
- Los límites valen para los agentes que no fijan los suyos y no pueden pasar de la política del repositorio.

La ejecución nace `RUNNING` con todas sus fases en `PENDING` (`stage.pending`, con sus dependencias) y el **motor** la hace avanzar:

- Una fase pasa a `READY` (`stage.ready`) cuando todas sus dependencias han terminado bien; una opcional (`plan?`) también vale si se omitió. Si se omitió una que no es opcional, la fase también se omite (`stage.skipped`).
- Una fase lista se arranca: se renderiza su prompt, se calcula lo que se le permite a su agente y se pone en cola. Varias fases listas a la vez se ejecutan en paralelo.
- Con `workspace: inherit`, la fase continúa el worktree de `workspaceFrom` o, si no, el de su única dependencia con agente, siempre que haya terminado bien: sesión nueva, mismos ficheros, mismo runner. Si en ese worktree hay algo en curso (la verificación automática del agente anterior), la fase espera en `READY` y arranca cuando termina.
- Si una fase no puede arrancar (el repositorio o el trabajo se archivaron, el worktree se eliminó, el prompt queda vacío) falla con `stage.start.failed` y el motivo.
- **Fail-fast:** si una fase falla o se cancela, las que no han empezado se cancelan y a los agentes en marcha se les ordena terminar; la ejecución termina `FAILED` (o `CANCELLED`) cuando todas han acabado. Si todas terminan bien u omitidas, `SUCCEEDED`.
- `POST /api/workflow-runs/{id}/cancel` cancela la ejecución entera del mismo modo; 409 si ya ha terminado.

**Reinicios.** Cada cambio en una ejecución (lanzarla, que termine un agente o una verificación, cancelar) deja un trabajo en la tabla `workflow_job` en la misma transacción y se evalúa allí mismo, así que el siguiente agente queda en cola al instante. Si esa evaluación falla o tiene que esperar, el trabajo queda para los workers del motor, que lo reclaman con `FOR UPDATE SKIP LOCKED` y un alquiler (`skynet.engine.lease`). Al arrancar, el control plane encola todas las ejecuciones sin terminar. Evaluar una ejecución bloquea su fila y solo mira el estado guardado, así que repetirla tras una caída no duplica agentes.

| Variable | Por defecto | Qué |
|---|---|---|
| `SKYNET_ENGINE_WORKERS` | `2` | Evaluaciones a la vez en este control plane |
| `SKYNET_ENGINE_POLL_INTERVAL` | `1s` | Cada cuánto se buscan trabajos pendientes |
| `SKYNET_ENGINE_LEASE` | `2m` | Tiempo que un worker se queda un trabajo; si cae, otro lo retoma al vencer |
| `SKYNET_ENGINE_RETRY_DELAY` | `5s` | Espera antes de reintentar (crece con los intentos, hasta 12 veces) |

**En la web.** En un trabajo, **Lanzar workflow** pide el workflow (por defecto `adhoc`), el repositorio y los datos de entrada según su tipo (texto, número o casilla), y enseña la política del repositorio y, si el workflow tiene agentes con nombre, lo que se le permitirá a cada fase. La ejecución lista todas sus fases desde el principio, en el orden del YAML: las que esperan dicen a qué fases («Espera a izquierda y derecha»), las omitidas por qué («Omitida porque se omitió build»), las canceladas sin arrancar qué fase falló, y las demás detrás de cuáles van. La cabecera enlaza con el workflow y su versión; con varias fases, **Cancelar ejecución** cancela la ejecución entera.

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
| `POST` | `/api/work-items/{id}/runs` | Lanza una versión publicada (`definitionId`, `inputs`; ver arriba) |
| `GET` | `/api/workflow-versions/{id}/effective-policy?repositoryId=` | Lo que se permitirá al agente de cada fase en ese repositorio (los límites que no fija el agente, `null`, salen del lanzamiento) |
| `POST` | `/api/workflow-runs/{id}/cancel` | Cancela una ejecución y sus agentes |

Cada problema lleva `severity` (`ERROR`, `UNSUPPORTED` o `WARNING`), `path` (p. ej. `stages.fix.dependsOn[0]`), `line`, `column` y `message`.
