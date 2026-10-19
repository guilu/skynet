# Workflows en YAML

Un workflow describe las fases de un trabajo y qué agente hace cada una (§11 de la [especificación](agentic-orchestration-system.md)). Se escribe en YAML, se guarda como borrador y, cuando no tiene errores, se publica como una versión que ya no cambia. Las ejecuciones guardan la versión exacta con la que arrancaron.

En la web se escriben en **Workflows**: el editor autocompleta con el JSON Schema de [`protocol/.../workflow-definition.schema.json`](../protocol/src/main/resources/dev/skynet/protocol/workflow-definition.schema.json) y, mientras se escribe, el control plane valida el texto con los mismos mensajes que al guardar (también lo que un schema no expresa) y los marca en su línea.

La pestaña **Grafo**, junto a **YAML**, dibuja las fases de izquierda a derecha según sus dependencias, también en un borrador mientras se escribe (el último texto que se pudo leer). Cada caja lleva un icono por tipo, su número y nombre, su tipo y una línea con lo que hace: el agente, el comando, la condición de una `conditional`, «Aprobar: …» en una puerta o las comprobaciones de una `verification`, más sus reintentos, su tiempo máximo y su condición. Una `parallel` es una caja con un hijo por agente dentro. Una dependencia opcional (`fix?`) se dibuja discontinua. Si alguna fase dice su `phase`, el grafo se separa en carriles, uno por macrofase usada y en su orden (Especificación, Planificación, Implementación, Tests, Revisión, Entrega); una fase sin `phase` va en el carril más avanzado de las fases de las que depende o, sin dependencias, en el primero. Tiene zoom y ajustar a pantalla y, con más de 8 fases, minimapa; es de solo lectura. Cada fase con problemas lleva una insignia con lo más grave que tiene («2 errores», «1 aviso») y, si son errores, el borde en rojo; al pasar por encima se leen los mensajes. Una dependencia que no existe sale como una fase fantasma, discontinua, con «No existe», y las flechas que forman un ciclo van en rojo. Con el teclado, la caja que recibe el foco se trae a la vista si estaba fuera; con `prefers-reduced-motion`, sin animación. La pestaña elegida queda en la URL (`?view=graph`).

**Nuevo workflow** empieza desde una plantilla: «Dos fases» (una revisión y una corrección que continúa su worktree) o «Revisión en paralelo» (implementar, tests con una fase `command`, revisión de calidad y de seguridad a la vez con salida estructurada, corregir solo si `review.hasBlockingFindings` y tests finales con `dependsOn: [review, "fix?"]`). Antes de usar esta última, cambia `./gradlew test` por el comando de tests de tu repositorio. Al publicar, el aviso lista los comandos de shell que ejecuta el workflow.

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
- `model`: el modelo del agente, por su nombre (`claude-haiku-4-5`) o su alias (`opus`). Por defecto, el global (`SKYNET_AGENT_MODEL`).
- `provider`: quién ejecuta el agente: `claude-code` (por defecto), `codex` (Codex de OpenAI, ver [`codex-exec-json.md`](codex-exec-json.md)) o `antigravity` (Antigravity CLI de Google, ver [`antigravity-stream-json.md`](antigravity-stream-json.md)); `opencode` sale como `UNSUPPORTED` y `gemini` es un error (Gemini CLI se retiró en AR-B). Con `codex` y `antigravity`, `tools` y `limits.maxTurns` se ignoran (sale un aviso): lo que el agente puede hacer lo fija `permissionMode` (`plan` es solo lectura) y el presupuesto lo vigila el runner. `model` es entonces un modelo de su proveedor (`gpt-5.5`, `gemini-3-flash`); sin él, el de la configuración de Codex o el de la cuenta de Antigravity (en los dos, sin estimación de coste). Un agente de Antigravity se puede continuar con mensajes, pero no bifurcar; su `output` lo valida el propio CLI (`--json-schema`).
- `output`: JSON Schema de un objeto con la salida estructurada que el agente devuelve al terminar, p. ej. `{type: object, properties: {hasBlockingFindings: {type: boolean}}}`. Cada campo lleva `type` (`boolean`, `string`, `number`, `integer`, `array` u `object`). Ver «Condiciones».
- `limits`: `maxTurns` (1-1000), `maxBudgetUsd` (0,01-1000) y `timeoutMinutes` (1-1440).
- Al lanzar, lo que pide un agente se recorta a la política del repositorio: solo las herramientas que el repositorio permite (`Bash(git:*)` cabe en `Bash`, al revés no), su modo de permisos solo si no es más permisivo (`plan` < `default`/`dontAsk` < `acceptEdits`) y sus límites rebajados a los del repositorio. Lo que el agente no fija sale de la política y, los límites, de los del lanzamiento.

**`stages[]`:**
- `id` (obligatorio, único) y `type` (obligatorio): `agent`, `command`, `parallel`, `conditional`, `verification` o `human-approval` (los demás tipos aún no se ejecutan, ver abajo).
- `agent`: un agente de `agents`. Sin él, la fase usa la política del repositorio.
- `prompt`: el de la fase; si falta, el del agente. Uno de los dos tiene que existir.
- `dependsOn`: fases que tienen que terminar antes. Con `?` al final (`plan?`) es opcional: también vale si esa fase se omite. Entre corchetes va entre comillas (`dependsOn: ["plan?"]`), porque YAML lee `?` como otra cosa.
- `workspace`: `inherit` (por defecto) continúa el worktree de la fase con worktree (agente, comando o `parallel`) de la que depende, sesión nueva pero mismos ficheros; sin ninguna, empieza uno nuevo. `isolated-worktree` empieza siempre uno nuevo desde la rama por defecto.
- `workspaceFrom`: obligatorio si la fase depende de varias fases con worktree y no es `isolated-worktree`; dice cuál continúa. Para continuar el worktree de un hijo de una fase `parallel`, `<fase>.<hijo>` (p. ej. `review.seguridad`).
- `command`: solo en las fases `type: command`, y obligatorio en ellas. Ver abajo.
- `children`: solo en las fases `type: parallel`, y obligatorio en ellas. Ver abajo.
- `phase`: la macrofase de la fase, para el carril del grafo: `spec`, `plan`, `implement`, `test`, `review` o `deliver`. Opcional y solo visual: no cambia cómo se ejecuta.
- `condition`: la fase solo se ejecuta si es verdadera; si es falsa, se omite. Ver «Condiciones».
- `artifacts`, `checks` y `scope`: lo que Skynet comprueba en el worktree de la fase antes de darla por buena. Ver «Verificación de una fase».
- `action`, `risk` y `revise`: solo en las fases `type: human-approval`. Ver «Puertas de aprobación».
- `outputSchema`: solo en las fases `type: agent`. La salida estructurada que devuelve la fase, y manda sobre el `output` de su agente, así que un mismo agente sirve en fases con salidas distintas. Es un JSON Schema en línea, como `output`, o la ruta de un fichero del repositorio (`outputSchema: schemas/spec.json`). Ver «Salida de una fase en un fichero».

**Fases `command`:** ejecutan un comando de shell en lugar de un agente, p. ej. los tests después de implementar:

```yaml
  - id: tests
    type: command
    command: ./gradlew test
    dependsOn: [implement]   # en el worktree de implement
```

- El runner lo ejecuta con `sh -c` en el worktree de la fase, con las mismas reglas que una fase `agent` (`workspace`, `workspaceFrom`), y la fase siguiente puede continuar ese worktree.
- Termina bien solo con código 0; cualquier otro código, el tiempo agotado o una cancelación la hacen fallar, y entonces se aplica el fail-fast.
- Lleva el entorno que el repositorio permite a sus agentes y el tiempo máximo de las verificaciones (`SKYNET_VERIFICATION_TIMEOUT`, 30 min), o el `timeout` de la fase si lo tiene (ver «Límites de tiempo»).
- No admite `agent`, `prompt` ni variables `{{...}}`: un dato de entrada dentro de un comando de shell podría colar otro comando.
- El comando lo escribe quien publica el workflow, igual que el comando de verificación del repositorio.
- En la ejecución es una invocación más de su fase (con proveedor `command`): una herramienta `Bash` con su salida, y como artefactos el comando, la salida completa (`command.log`) y los cambios del worktree. No se verifica después (ya es un comando) y no se puede continuar ni reintentar por separado.

**Fases `parallel`:** ejecutan varios agentes a la vez, p. ej. dos revisores después de implementar:

```yaml
  - id: review
    type: parallel
    dependsOn: [implement]
    children:
      - agent: calidad
      - agent: seguridad
      - id: seguridad-estricta      # el mismo agente dos veces necesita otro id
        agent: seguridad
        prompt: Revisa la seguridad con lupa
```

- Cada hijo lleva `agent` (obligatorio, un agente de `agents`) y opcionalmente `id` (por defecto, el nombre del agente; único en la fase) y `prompt` (si falta, el del agente). La fase no tiene `agent`, `prompt` ni `command` propios.
- Arranca todos los hijos a la vez: un agente por hijo en la misma fase, cada uno con lo que se le permite a su agente.
- **Worktree:** cada hijo trabaja en un worktree nuevo, creado desde una instantánea del worktree que la fase hereda (con las mismas reglas que una fase `agent`), con sus cambios sin confirmar y sus ficheros nuevos, sin tocar el original. Todos van al runner del worktree heredado. Si la fase no hereda ninguno (sin dependencias o `isolated-worktree`), cada hijo empieza uno nuevo.
- La fase siguiente continúa el worktree **original**, no el de un hijo: lo que cambien los revisores se queda en sus worktrees, con su diff a la vista. Para continuar el de un hijo, `workspaceFrom: review.seguridad`.
- Termina bien cuando terminan bien todos sus hijos. Si uno falla o se cancela, a sus hermanos se les ordena terminar, las demás fases se cancelan (fail-fast) y la fase falla cuando acaban todos.
- En la ejecución, cada hijo es un agente de la fase con su `childKey`. Cada uno se verifica, se continúa o se reintenta como cualquier agente, y lo que se le permitirá sale en «Política de cada fase» como `review.calidad`.

**Condiciones.** Una fase con `condition` la evalúa al estar lista; si es falsa, se omite (`stage.skipped` con motivo `condition-false`) y las que dependen de ella solo siguen si la tienen como opcional (`fix?`). Una fase `type: conditional` no hace trabajo: solo evalúa su `condition` (obligatoria) y termina bien si es verdadera o se omite si es falsa; sirve para colgar de ella las fases de una rama.

```yaml
agents:
  revisor:
    prompt: Revisa el cambio y di si hay algo que bloquee
    output:
      type: object
      properties:
        hasBlockingFindings: {type: boolean}

stages:
  - id: review
    type: agent
    agent: revisor
    dependsOn: [implement]
  - id: fix
    type: agent
    dependsOn: [review]
    condition: review.hasBlockingFindings
  - id: tests
    type: command
    command: ./gradlew test
    dependsOn: [review, "fix?"]
    workspaceFrom: review
```

- **Salida estructurada:** un agente con `output`, o en una fase con `outputSchema`, se lanza pidiendo esa salida (`--json-schema` en Claude Code) y lo que devuelve se guarda en la invocación (`structuredOutput`). Si termina bien pero no la devuelve, la invocación falla.
- **Qué se puede leer** (solo de fases de las que depende, directa o indirectamente):
  - `<fase>.status`: el estado de la fase, p. ej. `review.status == 'SUCCEEDED'`.
  - `<fase>.exitCode`: el código de salida de una fase `command`.
  - `<fase>.<campo>`: un campo de la salida de la fase: la de su `outputSchema` o, si no tiene, la del `output` de su agente.
  - `<fase>.<hijo>.<campo>`: el de un hijo de una fase `parallel`.
  - `<fase>.<campo>` en una fase `parallel`: solo para campos booleanos; es verdadero si algún hijo devuelve `true`.
  - `inputs.<nombre>`: un dato de entrada.
- **El lenguaje** es un subconjunto de [CEL](https://cel.dev): `&&`, `||`, `!`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `in` con una lista (`review.score in [4, 5]`), paréntesis, números, textos entre comillas, `true`, `false` y `null`. Un dato que no existe (un hijo omitido, un campo que el agente no devolvió) vale `null`, y `null` cuenta como falso.
- Al publicar se comprueba cada condición: la sintaxis, que cada dato exista y sea de una fase de la que depende, y los tipos (no se compara un número con un texto). Un error dice qué falla y en qué línea y columna.
- Cada evaluación deja `stage.condition.evaluated` con la condición, los valores leídos y el resultado.

**Variables de los prompts:** `{{workItem.key}}`, `{{workItem.title}}`, `{{workItem.description}}`, `{{workItem.type}}`, `{{workItem.externalRef}}`, `{{project.key}}`, `{{project.name}}`, `{{inputs.<nombre>}}` y los datos de otras fases.

**Datos de otras fases en los prompts.** Un prompt puede usar lo que dejó una fase de la que depende (directa o indirectamente), con los mismos datos que las condiciones:

```yaml
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
      Planifica a partir de esta especificación: {{stages.spec.output.summary}}
      Riesgos: {{stages.spec.output.risks}}
```

- `{{stages.<fase>.output.<campo>}}`: un campo de la salida de una fase `agent`.
- `{{stages.<fase>.<hijo>.output.<campo>}}`: el de un hijo de una fase `parallel`.
- `{{stages.<fase>.status}}` y `{{stages.<fase>.exitCode}}` (de una fase `command`).
- Un texto entra tal cual; un número o un booleano, escrito; una lista o un objeto, como JSON. Lo que no existe (una fase omitida, un campo que no devolvió) queda vacío.
- Al publicar se comprueba como una condición: que la fase sea una dependencia y que el campo esté en su salida. En el prompt de un agente se comprueba desde cada fase que lo usa.
- Se sustituye al arrancar la fase, y el prompt efectivo (un artefacto de la invocación) enseña lo que recibió el agente.

**Salida de una fase en un fichero.** Con `outputSchema: schemas/spec.json`, el schema es un fichero del repositorio:
- La ruta es relativa a la raíz del repositorio y no puede salir de él (ni `/` al principio ni `..`).
- El runner lo lee del worktree de la fase al arrancar cada invocación, así que vale la versión de la rama en la que trabaja. Funciona igual con los tres proveedores.
- Tiene que ser JSON, el schema de un objeto (`"type": "object"`) con sus campos en `properties`. Si no existe o no lo es, la invocación falla con el motivo, p. ej. «No existe `schemas/spec.json` en el worktree».
- Al publicar no se conocen sus campos (el repositorio está en el runner): las condiciones y los prompts que los leen no se comprueban hasta ejecutar.

**Verificación de una fase.** Lo que dice un agente al terminar es lo que *declara*; lo que Skynet comprueba en su worktree es lo que cuenta. Una fase `agent`, `command` o `parallel` puede pedir comprobaciones:

```yaml
  - id: spec
    type: agent
    agent: analista
    artifacts: [docs/spec.md]           # tienen que existir en el worktree
    checks: [diff, commit, scope, tests, {command: npm run lint}]
    scope: ["docs/**", "openspec/**"]   # lo que se puede cambiar
```

- `artifacts`: rutas o globs del repositorio (`docs/*.md`, `openspec/**/*.md`). Cada uno tiene que encontrar al menos un fichero; si no, «`docs/spec.md` no existe».
- `checks`, del catálogo cerrado:
  - `tests`: el comando de validación del repositorio termina con código 0 y sin tests fallidos. Sin comando configurado, no pasa.
  - `diff`: hay cambios respecto al inicio de la fase (confirmados o no).
  - `commit`: hay commits nuevos desde el inicio de la fase y nada sin confirmar.
  - `scope`: todo lo que cambió está dentro de la lista `scope` (obligatoria con este check; una `scope` sin el check es un aviso).
  - `{command: <cmd>}`: el comando termina con código 0, con `sh -c` en el worktree y el tiempo máximo de las verificaciones. Sin variables `{{...}}`, como las fases `command`. Su salida queda como artefacto (`check-1.log`).
- Las rutas no pueden salir del repositorio (ni `/` al principio ni `..`).
- **Cuándo:** cuando el agente termina bien, la fase **sigue en curso** («Verificando») mientras el runner comprueba su worktree; solo si pasan todas termina bien y arranca la siguiente. Si alguna no pasa, la fase falla con el motivo de cada una y se aplica el fail-fast. Corregirla llega en W5/W6.
- «El inicio de la fase» es el commit con el que su agente encontró el worktree.
- En una fase `parallel`, cada hijo se comprueba en su propio worktree; si uno no pasa, la fase falla.
- Una fase con comprobaciones no lleva además la verificación automática del repositorio (M5): con `tests` ya la incluye; sin él, en una fase `agent` o `parallel` el comando del repositorio se ejecuta igual pero solo informa.
- Cada comprobación deja `stage.verification.started` y `stage.verification.completed` (con el resultado de cada check), y el resultado es una verificación de la invocación con origen `STAGE`.
- **En la web:** en el progreso de la ejecución, la fase dice «Verificando» mientras se comprueba y, si falla, qué no pasó («No pasó artifacts: `openspec/proposal.md` no existe»). Al abrirla se ve cada comprobación con ✓, ✗ y su motivo, y los datos de otras fases que lee su prompt («Recibe spec.output.summary»). La pestaña **Verificación** del agente pone lo comprobado junto a lo que declaró (su respuesta y su salida estructurada). El resumen del workflow enseña qué comprueba cada fase y, al publicar, se avisa de los comandos de `checks` y de las fases que pasan el comando de verificación del repositorio.
- La plantilla **Especificar, planificar e implementar** es un ejemplo completo: `spec` deja `openspec/proposal.md` y devuelve un resumen, `plan` lo recibe en su prompt, la puerta `aprobar-plan` espera a que alguien apruebe el plan (o pida cambios, que corrige `plan`) e `implement` solo termina bien con `checks: [tests, diff]`.

**Fases `verification`.** No ejecutan nada: comprueban el worktree que heredan de la fase de la que dependen, p. ej. pasar los tests después de un agente sin darle a este las comprobaciones:

```yaml
  - id: verify
    type: verification
    dependsOn: [implement]
    checks: [tests, commit]
```

- Necesitan `artifacts` o `checks`, y una dependencia con worktree (`agent`, `command` o `parallel`); no admiten `agent`, `prompt`, `command`, `children`, `outputSchema` ni `workspace: isolated-worktree`.
- `diff`, `commit` y `scope` miden desde el commit con el que se creó el worktree.
- En la ejecución, su única invocación tiene proveedor `verification`: no arranca ningún proceso, termina bien si pasan las comprobaciones y falla con los motivos si no. No se continúa ni se reintenta por separado.
- La fase siguiente continúa el mismo worktree.

**Puertas de aprobación.** Una fase `type: human-approval` para la ejecución hasta que alguien decide, p. ej. aprobar el plan antes de implementar:

```yaml
  - id: aprobar-plan
    type: human-approval
    dependsOn: [plan]
    action: "Aprobar el plan de {{workItem.key}}: {{stages.plan.output.summary}}"
    risk: high          # low, medium (por defecto) o high
    revise: plan        # opcional: por defecto, su única dependencia `agent`
  - id: implement
    type: agent
    prompt: Implementa el plan
    dependsOn: [aprobar-plan]   # continúa el worktree de plan
```

- `action` (obligatoria) es lo que se pide aprobar. Admite las variables de un prompt, incluidas `{{stages…}}`, con las mismas comprobaciones al publicar.
- `revise` es la fase `agent` (anterior a la puerta) cuyo agente recibe el comentario al pedir cambios. Sin `revise` ni una única dependencia `agent`, pedir cambios responde 409.
- No admite `agent`, `prompt`, `command`, `children`, `outputSchema` ni comprobaciones. Admite `condition`.
- El worktree pasa sin tocarlo: la fase que depende de la puerta continúa el de la dependencia de la puerta, con las reglas de siempre (`workspaceFrom` si hay varias).
- Al arrancar, la fase pasa a `WAITING_FOR_APPROVAL` y deja una **petición** con la acción ya resuelta, el riesgo y una foto de la evidencia: de dónde viene (proyecto, trabajo, ejecución, workflow y fase), lo que dejaron sus dependencias (salida estructurada, comprobaciones, cambios del worktree y artefactos de cada agente), el coste de la ejecución hasta ahora, lo que se permitirá a las fases que desbloquea y qué pasa con cada decisión. Mientras espera no ocupa ningún worker, y un reinicio del control plane no la toca.
- **Aprobar** cierra la puerta bien y arrancan las que dependen de ella. **Rechazar** (con un comentario obligatorio, el motivo) la hace fallar y se aplica el fail-fast. **Comentar** solo añade el comentario. Cancelar la ejecución cancela la petición.
- **Pedir cambios** (con un comentario obligatorio, qué cambiar) cierra la petición como `CHANGES_REQUESTED` y manda el comentario al agente de `revise`: reanuda su sesión (`--resume`) en su worktree y su runner, como una invocación más de esa fase en la misma ejecución. Mientras corrige, la puerta está «Corrigiendo» (`RUNNING`) y la fase `revise` sigue terminada bien.
  - Si la fase `revise` tiene `artifacts` o `checks`, se comprueban otra vez tras la corrección, desde el mismo commit que la primera vez.
  - Si la corrección termina bien (y pasa las comprobaciones), la puerta vuelve a preguntar: una petición nueva, con una ronda más, la acción resuelta con lo corregido y la evidencia nueva, que lleva en `changesRequested` lo que se pidió en la ronda anterior.
  - Si la corrección falla o no pasa las comprobaciones, la puerta falla con el motivo (`approval.correction.failed`) y se aplica el fail-fast; si se cancela, la puerta se cancela.
  - Lo que leen después la puerta y las fases siguientes (`{{stages.plan.output…}}`, el worktree) es lo de la última corrección. No hay límite de rondas.
  - Si no se puede continuar la sesión (el proveedor no lo permite, el worktree se eliminó, hay otra invocación en él), responde 409 y la petición sigue pendiente.
- Una petición pendiente solo se decide una vez: una segunda decisión (o una a la vez que otra) responde 409. Las decisiones y comentarios se guardan con quién y cuándo, y no se modifican ni se borran (salvo al eliminar la ejecución).
- Eventos: `approval.requested`, `approval.granted`, `approval.rejected`, `approval.changes_requested`, `approval.correction.failed`, `approval.commented`, `approval.cancelled` y `approval.expired`.
- En la web se decide en **Aprobaciones**. El menú cuenta las pendientes, que salen también en «Necesita tu atención» del dashboard. La página de cada petición enseña la evidencia, lo que se concede, las consecuencias y el historial. En el progreso de la ejecución, la puerta lleva a «Revisar». Aprobar algo de riesgo `high` pide confirmarlo.

**Reintentos.** `retry` en una fase `agent` o `command` hace que el motor la repita si falla por algo pasajero, en vez de aplicar el fail-fast:

```yaml
  - id: implement
    type: agent
    prompt: Implementa el plan
    retry:
      maxAttempts: 3            # intentos en total, incluido el primero (1-5); obligatorio
      on: [lost, start, error]  # qué fallos se reintentan; este es el valor por defecto
      delay: 30s                # espera antes del segundo intento (s, m o h); se dobla en cada uno, hasta 1 h
```

- Cada agente que falla guarda por qué (`failure`):

  | Categoría | Cuándo | En `on` por defecto |
  |---|---|---|
  | `lost` | el runner se cayó o perdió la invocación | sí |
  | `start` | no llegó a arrancar: no se pudo preparar el worktree, lanzar el proceso o no hay CLI del proveedor | sí |
  | `error` | el proceso terminó con error (código distinto de 0, resultado con error) | sí |
  | `timeout` | se acabó el tiempo del agente (`limits.timeoutMinutes`) o el de su fase (`timeout`) | no |
  | `budget` | superó el presupuesto o los turnos | no |
  | `verification` | la salida estructurada o las comprobaciones de la fase (`artifacts`, `checks`) no pasaron | no |

  Cancelar no es un fallo y nunca se reintenta.
- Cada intento es una fase nueva de la misma ejecución (`attempt` 2, 3…) con agentes nuevos y sesión nueva. El intento que falló se queda como estaba, con sus agentes, sus logs y su coste.
- Mientras espera su hora, el intento nuevo está `PENDING` con `notBefore`, y el evento `stage.retry.scheduled` dice qué intento es, por qué falló el anterior y cuándo arranca. Las demás fases siguen a lo suyo. Si otra fase falla sin reintentos, el fail-fast cancela también el intento que espera; cancelar la ejecución, también.
- El intento nuevo parte del worktree tal como lo encontró el que falló: al arrancar cada agente, el runner guarda una foto del worktree (`refs/skynet/snapshots/<agente>`, con lo confirmado y lo que no) y, antes del reintento, lo deja como en esa foto. Lo que dejó el intento fallido se sigue viendo en sus artefactos y su diff.
- En una fase `parallel`, `retry` va en cada hijo de `children` (en la fase es un error). Si un hijo falla, sus hermanos terminan; después, el intento nuevo de la fase repite solo los hijos que fallaron y pueden reintentarse. Los que terminaron bien no se repiten y sus salidas y worktrees siguen valiendo.
- `retry` en una fase `conditional`, `verification` o `human-approval` es un error.
- **En la web:** el progreso enseña el último intento de cada fase («Reintenta a las 10:30:15 (intento 2 de 3)»), por qué falló cada agente («Falló: terminó con error») y, al abrir la fase, sus intentos anteriores con sus agentes. El resumen del workflow dice qué fases se reintentan.

**Límites de tiempo.** `timeout` limita lo que puede durar cada intento de una fase, desde que queda lista (`READY`) hasta que termina, incluidas la espera a un runner y sus comprobaciones:

```yaml
  - id: implement
    type: agent
    prompt: Implementa el plan
    timeout: 30m              # s, m o h; hasta 168 h
    retry: {maxAttempts: 2, on: [timeout]}
```

- Al acabarse, el motor emite `stage.timed.out` y ordena parar sus agentes (`agent.cancel.requested` con `reason: stage-timeout`). Cuando el runner lo confirma, cada uno termina `FAILED` con `failure: timeout` y la fase falla. Solo se reintenta si `timeout` está en su `retry.on`, que no lo está por defecto; si no, se aplica el fail-fast.
- Vale en las fases `agent`, `command`, `parallel` (cuenta para la fase entera), `verification` y `human-approval`. En una `conditional` es un error: solo evalúa su condición.
- En una puerta `human-approval`, el `timeout` es su caducidad: si nadie decide a tiempo, la petición queda `EXPIRED`, firmada por `skynet` (decisión `EXPIRE`, evento `approval.expired`), y la puerta falla con fail-fast. Si estaba corrigiendo (se pidieron cambios), se para la corrección y la puerta falla igual.
- Es independiente de `limits.timeoutMinutes`, que limita cada proceso del agente y lo aplica el runner.
- La API enseña `timeoutSeconds`, `deadline` (la hora límite del intento en curso) y `timedOutAt` en cada fase.

**Reintentar fase.** En una ejecución **fallida o cancelada**, el detalle de la fase que falló (o se canceló) en el progreso tiene **Reintentar fase**. Antes de confirmar dice el alcance: «Se reintenta implement (intento 2). Después vuelven a lanzarse tests y review.». Al confirmar (`POST /api/workflow-runs/{id}/stages/{key}/retry`):

- La **misma ejecución** vuelve a «En curso» (`run.resumed`, con quién lo pidió, y `workflow.status.changed` con `reason: stage-retried`).
- Reciben un intento nuevo la fase, las que dependen de ella y las demás que fallaron o se cancelaron: las que canceló el fail-fast vuelven a planificarse. Lo que terminó bien y no depende de ella no se repite; en una `parallel`, tampoco los hijos que terminaron bien.
- No se toca nada terminado: los intentos anteriores, sus agentes y sus peticiones de aprobación siguen como estaban.
- El intento parte del worktree como lo dejaron sus dependencias, como un reintento automático. Si se repite una fase anterior, las que dependen de ella siguen desde lo que deje esa, sin volver a su foto.
- Lo pide una persona, así que **no cuenta para `maxAttempts`**: los reintentos automáticos vuelven a contar desde ese intento. La API lo marca con `retriedBy` y el detalle de la fase dice «lo pidió admin».
- Una puerta rechazada o caducada no se reintenta así: se reintenta la fase de la que depende (aunque terminara bien, p. ej. el plan que se rechazó) y la puerta vuelve a preguntar.
- En una ejecución de workflow, el «Reintentar…» del agente deja de salir: se reintenta la fase. En una ejecución ad hoc (un agente suelto) sigue como siempre, lanzando otra ejecución.

**Cancelar.** Cancelar una ejecución (`POST /api/workflow-runs/{id}/cancel`) la deja en «Cancelando» (`cancelRequestedAt` en la API, evento `workflow.cancel.requested`) mientras sus agentes paran; termina `CANCELLED` cuando han parado todos. También:

- Cancela los reintentos programados y las ejecuciones lanzadas desde sus agentes que no han terminado (con `reason: parent-cancelled`), y las de estas, en cascada.
- Si el runner no confirma que ha parado un agente en `SKYNET_CANCEL_TIMEOUT` (70 s: los 10 s de gracia del runner más un margen), el control plane lo da por cancelado (`agent.cancel.forced`) y la ejecución termina igual. La orden sigue en la cola del runner, por si vuelve.
- En la web, la cabecera dice «Cancelando» y no vuelve a ofrecer cancelar; la fase cuyos agentes aún no han parado dice «Cancelando», y la que se quedó sin tiempo, «Falló: se acabó el tiempo» o, en una puerta, «Caducó sin decisión». El resumen del workflow enseña el tiempo máximo de cada fase.

## Lo que aún no se ejecuta

El YAML admite ya todo §11 para poder escribir los workflows de la especificación, pero solo se publica un workflow que el motor sabe ejecutar. Lo demás sale como problema `UNSUPPORTED` con el hito en el que llega:

| Qué | Hito |
|---|---|
| Fases `github-pr`, `github-check`, `github-merge` y `merge` | S2 |
| `promptTemplate` | S3 |
| `provider: opencode` | sin fecha |
| Fase `deploy` | sin fecha |

## Cómo se ejecuta

Una ejecución se lanza sobre un trabajo y un repositorio con una versión publicada y sus datos de entrada (`POST /api/work-items/{id}/runs`):

```json
{ "repositoryId": "…", "definitionId": "…", "inputs": { "issue": "#42" },
  "maxTurns": 30, "maxBudgetUsd": 5, "timeoutMinutes": 60,
  "provider": "codex", "model": "gpt-5.5" }
```

- `provider` y `model` son para las fases de agente sin agente con nombre (como la de `adhoc`, y también sus mensajes, bifurcaciones y reintentos); sin ellos, Claude Code con el modelo global. Si todas las fases fijan su agente, se rechazan: ahí manda el YAML.
- Sin `definitionId` se lanza la última versión de `adhoc`; `prompt` es un atajo para el dato `prompt` de los workflows que lo piden (como `adhoc`) y se rechaza en los demás.
- Los datos se comprueban contra `inputs`: sobra uno, falta uno obligatorio o no es de su tipo → 400 con el motivo. Los que faltan toman su `default`.
- Los límites valen para los agentes que no fijan los suyos y no pueden pasar de la política del repositorio.

La ejecución nace `RUNNING` con todas sus fases en `PENDING` (`stage.pending`, con sus dependencias) y el **motor** la hace avanzar:

- Una fase pasa a `READY` (`stage.ready`) cuando todas sus dependencias han terminado bien; una opcional (`plan?`) también vale si se omitió. Si se omitió una que no es opcional, la fase también se omite (`stage.skipped`).
- Una fase lista se arranca: se renderiza su prompt, se calcula lo que se le permite a su agente y se pone en cola. Varias fases listas a la vez se ejecutan en paralelo. Una fase con comprobaciones no termina hasta que pasan (ver «Verificación de una fase»).
- Con `workspace: inherit`, la fase continúa el worktree de `workspaceFrom` o, si no, el de su única dependencia con worktree, siempre que haya terminado bien: sesión nueva, mismos ficheros, mismo runner. Si en ese worktree hay algo en curso (la verificación automática del agente anterior), la fase espera en `READY` y arranca cuando termina.
- Si una fase no puede arrancar (el repositorio o el trabajo se archivaron, el worktree se eliminó, el prompt queda vacío) falla con `stage.start.failed` y el motivo.
- **Fail-fast:** si una fase (o un hijo de una fase `parallel`) falla o se cancela, las que no han empezado se cancelan y a los agentes en marcha se les ordena terminar; la ejecución termina `FAILED` (o `CANCELLED`) cuando todas han acabado. Si todas terminan bien u omitidas, `SUCCEEDED`.
- `POST /api/workflow-runs/{id}/cancel` cancela la ejecución entera del mismo modo; 409 si ya ha terminado.

**Reinicios.** Cada cambio en una ejecución (lanzarla, que termine un agente o una verificación, cancelar) deja un trabajo en la tabla `workflow_job` en la misma transacción y se evalúa allí mismo, así que el siguiente agente queda en cola al instante. Si esa evaluación falla o tiene que esperar, el trabajo queda para los workers del motor, que lo reclaman con `FOR UPDATE SKIP LOCKED` y un alquiler (`skynet.engine.lease`). Al arrancar, el control plane encola todas las ejecuciones sin terminar. Evaluar una ejecución bloquea su fila y solo mira el estado guardado, así que repetirla tras una caída no duplica agentes.

| Variable | Por defecto | Qué |
|---|---|---|
| `SKYNET_ENGINE_WORKERS` | `2` | Evaluaciones a la vez en este control plane |
| `SKYNET_ENGINE_POLL_INTERVAL` | `1s` | Cada cuánto se buscan trabajos pendientes |
| `SKYNET_ENGINE_LEASE` | `2m` | Tiempo que un worker se queda un trabajo; si cae, otro lo retoma al vencer |
| `SKYNET_ENGINE_RETRY_DELAY` | `5s` | Espera antes de reintentar (crece con los intentos, hasta 12 veces) |

**En la web.** En un trabajo, **Lanzar workflow** pide el workflow (por defecto `adhoc`), el repositorio y los datos de entrada según su tipo (texto, número o casilla), y enseña la política del repositorio y, si el workflow tiene agentes con nombre, lo que se le permitirá a cada fase, con su proveedor y tachado lo que ese proveedor no admite. Si alguna fase no fija su agente (como en `adhoc`), pide también el **proveedor** (los que no tiene ningún runner en línea salen desactivados) y el **modelo** (vacío: el predeterminado). La cabecera de la ejecución enseña el proveedor del agente, y **Bifurcar** sale desactivado con el motivo si su proveedor no lo admite (Antigravity CLI). Ver [`providers.md`](providers.md). El **Progreso** de la ejecución dibuja sus fases con el mismo grafo que el workflow, con el estado de cada una encima y al día por el stream: su estado con icono y texto, lo que hace ahora (la herramienta en curso, «Verificando», «Cancelando») o cuánto lleva, quién la hace, su coste, «intento 2 de 3» y la nota de la fase; cada hijo de una `parallel` lleva el suyo. La flecha que sale de una fase omitida o cancelada va apagada. Pulsar una caja abre la fase debajo, como antes; pulsar un hijo abre su agente. El conmutador **Grafo / Lista** cambia al camino de siempre y se recuerda en el navegador; en una pantalla de menos de 720 px y en una ejecución de una sola fase se ve siempre la lista. La ejecución lista todas sus fases desde el principio, en el orden del YAML: las que esperan dicen a qué fases («Espera a izquierda y derecha»), las omitidas por qué («Omitida porque se omitió build»), las canceladas sin arrancar qué fase falló, y las demás detrás de cuáles van. La cabecera enlaza con el workflow y su versión; con varias fases, **Cancelar ejecución** cancela la ejecución entera.

Al abrir una fase en el progreso se ve, según su tipo, el comando que ejecuta y su código de salida, los hijos de una fase `parallel` (cada uno con su agente, su worktree y su salida estructurada, p. ej. «Salida: hasBlockingFindings: sí») o su condición con el resultado: «Omitida: review.hasBlockingFindings es falsa» o «review.hasBlockingFindings se cumplió». El timeline guarda cada evaluación con los valores que leyó.

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
| `POST` | `/api/workflow-runs/{id}/stages/{key}/retry` | «Reintentar fase»: la ejecución fallida o cancelada vuelve a «En curso» desde esa fase; 409 si no ha terminado, está archivada o la fase es una puerta |
| `GET` | `/api/approvals?status=&projectId=&risk=` | Las peticiones de aprobación: primero las pendientes (las de más riesgo y las más antiguas), luego las decididas. `status` admite varios separados por comas (`PENDING`, `APPROVED`, `REJECTED`, `CHANGES_REQUESTED`, `CANCELLED`, `EXPIRED`) |
| `GET` | `/api/approvals/{id}` | Una petición con su evidencia y sus decisiones |
| `POST` | `/api/approvals/{id}/decisions` | Decide (`{action: APPROVE \| REJECT \| REQUEST_CHANGES \| COMMENT, comment}`) |

Cada problema lleva `severity` (`ERROR`, `UNSUPPORTED` o `WARNING`), `path` (p. ej. `stages.fix.dependsOn[0]`), `line`, `column` y `message`.
