# Workflows en YAML

Un workflow describe las fases de un trabajo y qué agente hace cada una (§11 de la [especificación](agentic-orchestration-system.md)). Se escribe en YAML, se guarda como borrador y, cuando no tiene errores, se publica como una versión que ya no cambia. Las ejecuciones guardan la versión exacta con la que arrancaron.

En la web se escriben en **Workflows**: el editor autocompleta con el JSON Schema de [`protocol/.../workflow-definition.schema.json`](../protocol/src/main/resources/dev/skynet/protocol/workflow-definition.schema.json) y, mientras se escribe, el control plane valida el texto con los mismos mensajes que al guardar (también lo que un schema no expresa) y los marca en su línea.

La pestaña **Grafo**, junto a **YAML**, dibuja las fases de izquierda a derecha según sus dependencias, también en un borrador mientras se escribe (el último texto que se pudo leer). Cada caja lleva un icono por tipo, su número y nombre, su tipo y una línea con lo que hace: el agente, el comando, la condición de una `conditional`, «Aprobar: …» en una puerta o las comprobaciones de una `verification`, más sus reintentos, su tiempo máximo y su condición. Una `parallel` es una caja con un hijo por agente dentro. Una dependencia opcional (`fix?`) se dibuja discontinua. Si alguna fase dice su `phase`, el grafo se separa en carriles, uno por macrofase usada y en su orden (Especificación, Planificación, Implementación, Tests, Revisión, Entrega); una fase sin `phase` va en el carril más avanzado de las fases de las que depende o, sin dependencias, en el primero. Tiene zoom y ajustar a pantalla y, con más de 8 fases, minimapa. En una versión publicada es de solo lectura; en un borrador se edita (ver «Editar desde el grafo»). Cada fase con problemas lleva una insignia con lo más grave que tiene («2 errores», «1 aviso») y, si son errores, el borde en rojo; al pasar por encima se leen los mensajes. Una dependencia que no existe sale como una fase fantasma, discontinua, con «No existe», y las flechas que forman un ciclo van en rojo. Con el teclado, la caja que recibe el foco se trae a la vista si estaba fuera; con `prefers-reduced-motion`, sin animación. La pestaña elegida queda en la URL (`?view=graph`).

**Editar desde el grafo.** En un borrador (también en el workflow nuevo), la pestaña **Grafo** cambia el YAML sin escribirlo. Con la plantilla **Desde cero** (una fase de agente sin prompt) se abre directamente en esa pestaña, para montar el workflow entero sin escribir YAML. Cada cambio es una edición del YAML que se ve en la pestaña de al lado: conserva los comentarios, el orden de las claves y el estilo de las listas, y **Deshacer** / **Rehacer** (o Ctrl+Z / Ctrl+Mayús+Z) lo deshacen igual que escribir en él. Guardar y publicar siguen siendo los botones de siempre.

- **Añadir fase** ofrece los seis tipos que se ejecutan. La fase nueva lleva un `id` libre (`command`, `command-2`…) y lo mínimo de su tipo; lo que falte (el `command` vacío) se marca como problema hasta completarlo. Si había una fase elegida, la nueva va detrás y depende de ella.
- **Unir:** arrastrar desde el punto derecho de una fase hasta otra hace que la segunda dependa de la primera. Si cerraría un ciclo, no se toca el YAML y se dice por qué («Así se formaría un ciclo: plan → build → plan»).
- Al elegir una **flecha**, se marca como opcional (`plan?`) o se quita.
- Al elegir una **fase**, su panel cambia su `id` (y lo que la nombra), `name`, el agente y el prompt, el `command`, `action`, `risk` y `revise` de una puerta, la condición, la macrofase, `workspace` y `workspaceFrom` (solo con valores válidos), los intentos de `retry`, el `timeout`, las comprobaciones con nombre (`tests`, `diff`, `commit`, `scope`), los hijos de una `parallel` y sus dependencias (añadir, opcional, quitar). Cada campo con problemas lo dice debajo; los problemas de la fase llevan a su línea del YAML, igual que **Editar en YAML**, que es donde se cambian `outputSchema`, `artifacts`, `scope` y lo poco común.
- Los botones **Workflow**, **Agentes** y **Entradas**, encima del grafo, abren su panel. **Workflow** cambia el `name` y la `description` (y el `id`, solo en el alta: no cambia entre versiones). **Agentes** añade y quita agentes con nombre y cambia su prompt, `provider`, `model`, `permissionMode`, `tools` (separadas por comas) y `limits`; renombrar uno cambia las fases y los hijos que lo usan, y no se quita uno que se use (dice quién). **Entradas** añade y quita entradas y cambia su tipo, si es obligatoria, su valor por defecto (con el tipo de la entrada) y su descripción; renombrar una cambia sus `{{inputs.…}}` en prompts y acciones.
- **Borrar fase…** pide confirmación y dice qué fases dependían de ella; se le quita de sus `dependsOn`, `workspaceFrom` y `revise`.
- Todo lo que se hace con el ratón se puede hacer con el teclado: la paleta es un desplegable, cada fase es un botón y las dependencias se añaden y quitan desde el panel.
- En una pantalla de menos de 720 px el grafo es de solo lectura: se edita en la pestaña YAML.

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

**Raíz:** `id` (obligatorio; de 2 a 49 minúsculas, números y guiones; `new`, `schema` y `validate` están reservados), `version`, `name`, `description`, `inputs`, `agents`, `protected` (rutas que ningún agente puede cambiar, ver «Verificación de una fase»), `risk` (rutas y patrones que suben un cambio a riesgo 2, ver «Nivel de riesgo del cambio») y `stages` (obligatorio, al menos una fase).

**`inputs.<nombre>`:** `type` (`string` por defecto), `required`, `default` y `description`. Los prompts los usan como `{{inputs.<nombre>}}`.

**`agents.<nombre>`:**
- `prompt`: plantilla con variables.
- `tools`: herramientas de Claude Code, como `Edit` o `Bash(git:*)`. Por defecto, las del repositorio.
- `permissionMode`: `dontAsk`, `acceptEdits`, `default` o `plan`. Por defecto, el del repositorio.
- `model`: el modelo del agente, por su nombre (`claude-haiku-4-5`) o su alias (`opus`). Por defecto, el global (`SKYNET_AGENT_MODEL`).
- `provider`: quién ejecuta el agente: `claude-code` (por defecto), `codex` (Codex de OpenAI, ver [`codex-exec-json.md`](codex-exec-json.md)) o `antigravity` (Antigravity CLI de Google, ver [`antigravity-stream-json.md`](antigravity-stream-json.md)); `opencode` sale como `UNSUPPORTED` y `gemini` es un error (Gemini CLI se retiró en AR-B). Con `codex` y `antigravity`, `tools` y `limits.maxTurns` se ignoran (sale un aviso): lo que el agente puede hacer lo fija `permissionMode` (`plan` es solo lectura) y el presupuesto lo vigila el runner. `model` es entonces un modelo de su proveedor (`gpt-5.5`, `gemini-3-flash`); sin él, el de la configuración de Codex o el de la cuenta de Antigravity (en los dos, sin estimación de coste). Un agente de Antigravity se puede continuar con mensajes, pero no bifurcar; su `output` lo valida el propio CLI (`--json-schema`).
- `output`: JSON Schema de un objeto con la salida estructurada que el agente devuelve al terminar, p. ej. `{type: object, properties: {hasBlockingFindings: {type: boolean}}}`. Cada campo lleva `type` (`boolean`, `string`, `number`, `integer`, `array` u `object`). Un `array` (en cualquier nivel) lleva `items` con el tipo de sus elementos, p. ej. `{type: array, items: {type: string}}`: sin él Antigravity rechaza el schema y el borrador no se publica (las versiones publicadas antes se siguen leyendo, con un aviso). Ver «Condiciones».
- `limits`: `maxTurns` (1-1000), `maxBudgetUsd` (0,01-1000) y `timeoutMinutes` (1-1440).
- Al lanzar, lo que pide un agente se recorta a la política del repositorio: solo las herramientas que el repositorio permite (`Bash(git:*)` cabe en `Bash`, al revés no), su modo de permisos solo si no es más permisivo (`plan` < `default`/`dontAsk` < `acceptEdits`) y sus límites rebajados a los del repositorio. Lo que el agente no fija sale de la política y, los límites, de los del lanzamiento.

**`stages[]`:**
- `id` (obligatorio, único) y `type` (obligatorio): `agent`, `command`, `parallel`, `conditional`, `verification`, `human-approval`, `github-pr`, `github-check` o `github-merge` (los demás tipos aún no se ejecutan, ver abajo).
- `agent`: un agente de `agents`. Sin él, la fase usa la política del repositorio.
- `prompt`: el de la fase; si falta, el del agente. Uno de los dos tiene que existir.
- `dependsOn`: fases que tienen que terminar antes. Con `?` al final (`plan?`) es opcional: también vale si esa fase se omite. Entre corchetes va entre comillas (`dependsOn: ["plan?"]`), porque YAML lee `?` como otra cosa.
- `workspace`: `inherit` (por defecto) continúa el worktree de la fase con worktree (agente, comando o `parallel`) de la que depende, sesión nueva pero mismos ficheros; sin ninguna, empieza uno nuevo. `isolated-worktree` empieza siempre uno nuevo desde la rama por defecto.
- `workspaceFrom`: obligatorio si la fase depende de varias fases con worktree y no es `isolated-worktree`; dice cuál continúa. Para continuar el worktree de un hijo de una fase `parallel`, `<fase>.<hijo>` (p. ej. `review.seguridad`).
- `command`: solo en las fases `type: command`, y obligatorio en ellas. Ver abajo.
- `children`: solo en las fases `type: parallel`, y obligatorio en ellas. Ver abajo.
- `phase`: la macrofase de la fase, para el carril del grafo: `spec`, `plan`, `implement`, `test`, `review` o `deliver`. Opcional y solo visual: no cambia cómo se ejecuta.
- `memory`: solo en las fases `agent` y `parallel` (vale para todos sus hijos). Cómo usa su agente la memoria del proyecto: `write` (por defecto) recibe el bloque de memoria al principio del prompt y tiene las herramientas para buscar y guardar; `read` recibe el bloque y puede buscar (`mem_search`, `mem_get`), pero no guardar, lo que viene bien en un revisor o un investigador; `off` no recibe ni el bloque ni las herramientas. Ver «Memoria del proyecto».
- `condition`: la fase solo se ejecuta si es verdadera; si es falsa, se omite. Ver «Condiciones».
- `artifacts`, `checks`, `scope`, `protected` y `allowTestRemoval`: lo que Skynet comprueba en el worktree de la fase antes de darla por buena. Ver «Verificación de una fase».
- `action`, `risk`, `revise` y `autoApprove`: solo en las fases `type: human-approval`. Ver «Puertas de aprobación» y «Revisión y fusión según el riesgo (AU-C)».
- `pullRequest`: solo en las fases `type: github-pr`. Ver «Entrega por PR».
- `required`, `ifNoChecks`, `fix` y `maxRounds`: solo en las fases `type: github-check` (`fix` y `maxRounds`, también en una `verification` con `judge`). Ver «Esperar la CI de la PR».
- `judge`: solo en las fases `type: verification`. Ver «Juez con rúbrica (VA-B)» y «Verificación adversarial (VA-C)».
- `guide`: solo en las fases `type: verification`, y no con `judge`. Ver «La guía de pruebas como fase (VA-D)».
- `method` y `deleteBranch`: solo en las fases `type: github-merge`. Ver «Fusionar la PR».
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
  - `repository.github`: verdadero si el repositorio de la ejecución está en GitHub y la App tiene acceso. Así una plantilla con fases `github-pr` sigue funcionando en un repositorio local: esas fases se omiten.
- **El lenguaje** es un subconjunto de [CEL](https://cel.dev): `&&`, `||`, `!`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `in` con una lista (`review.score in [4, 5]`), paréntesis, números, textos entre comillas, `true`, `false` y `null`. Un dato que no existe (un hijo omitido, un campo que el agente no devolvió) vale `null`, y `null` cuenta como falso.
- Al publicar se comprueba cada condición: la sintaxis, que cada dato exista y sea de una fase de la que depende, y los tipos (no se compara un número con un texto). Un error dice qué falla y en qué línea y columna.
- Cada evaluación deja `stage.condition.evaluated` con la condición, los valores leídos y el resultado.

**Variables de los prompts:** `{{workItem.key}}`, `{{workItem.title}}`, `{{workItem.description}}`, `{{workItem.type}}`, `{{workItem.externalRef}}`, `{{project.key}}`, `{{project.name}}`, `{{project.slug}}` (la clave en minúsculas, para nombres de fichero como `docs/skynet/mapa-{{project.slug}}.md`), `{{inputs.<nombre>}}` y los datos de otras fases.

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
        risks: {type: array, items: {type: string}}
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

- `artifacts`: rutas o globs del repositorio (`docs/*.md`, `openspec/**/*.md`). Cada uno tiene que encontrar al menos un fichero; si no, «`docs/spec.md` no existe». No admiten variables (`{{...}}`), porque se comprueban al publicar: para un fichero con la clave del proyecto en el nombre se declara un glob (`docs/skynet/mapa-*.md`).
  - Cuenta un fichero regular o un enlace simbólico a un fichero regular **dentro** del worktree (fuera de `.git`). Un enlace que sale del worktree, una FIFO, un socket o un dispositivo no cuentan.
  - Cada fichero que casa se guarda además como artefacto `DECLARED_FILE` (#145), con su ruta relativa como nombre y en metadatos `path`, `glob`, `mediaType` y `size`: se abre y se descarga desde la web (`GET /api/artifacts/{id}/download`) aunque se limpie el worktree. Solo lo declarado: el resto de cambios sigue en `GIT_CHANGES` y `DIFF`.
  - **En la web:** la pestaña **Artefactos** del agente los lista en «Archivos generados», con su tamaño y tipo, entre los cambios y los logs. **Descargar** baja el fichero entero con su nombre. **Abrir** lo enseña dentro de Skynet si hay una vista segura: Markdown formateado (sin su HTML, con enlaces solo `http`, `https` y `mailto` que se abren en otra pestaña y las imágenes como texto, y «Ver como texto» para el original), JSON y texto en el visor de código, y PNG, JPEG, GIF y WebP como imagen. Los PDF, SVG y demás binarios solo se descargan. En la pestaña **Verificación**, el check `artifacts` dice qué ficheros no se guardaron y por qué.
  - Límites del runner: `SKYNET_ARTIFACT_FILE_MAX_MB` (10) por fichero, `SKYNET_ARTIFACT_STAGE_MAX_MB` (50) y `SKYNET_ARTIFACT_STAGE_MAX_FILES` (50) por fase. Lo que no cabe no se sube (nunca a medias) y el resultado del check lo lista en `omitted` con su motivo; el check pasa igual, porque el fichero existe.
  - El tipo MIME sale de la extensión, de una lista cerrada (Markdown, texto, CSV, JSON, YAML, XML, PNG, JPEG, GIF, WebP, SVG y PDF; el HTML se trata como texto). Lo de texto se redacta como el resto de artefactos; los binarios no se tocan.
- `checks`, del catálogo cerrado:
  - `tests`: el comando de validación del repositorio termina con código 0 y sin tests fallidos. Sin comando configurado, no pasa.
  - `diff`: hay cambios respecto al inicio de la fase (confirmados o no).
  - `commit`: hay commits nuevos desde el inicio de la fase y nada sin confirmar.
  - `scope`: todo lo que cambió está dentro de la lista `scope` (obligatoria con este check; una `scope` sin el check es un aviso).
  - `{command: <cmd>}`: el comando termina con código 0, con `sh -c` en el worktree y el tiempo máximo de las verificaciones. Sin variables `{{...}}`, como las fases `command`. Su salida queda como artefacto (`check-1.log`).
  - `tests-intact` (VA-A): el cambio no debilita los tests para ponerse en verde. **`tests` lo incluye solo**, justo detrás; se escribe a mano para pedirlo sin `tests`. Mira el diff desde el inicio de la fase (no ejecuta nada) y falla si:
    - se borra un fichero de tests («Se ha borrado el test `CalculadoraTest.divideEntreCero`») o se mueve fuera de donde están los tests;
    - un fichero de tests que sigue existiendo tiene menos casos (`@Test`, `@ParameterizedTest`…, `it(`/`test(`, `def test_`, `func Test…`);
    - se añade una forma de saltarlos: `@Disabled`, `@Ignore`, `assumeTrue(false)`, `.skip`, `.only`, `xit`, `fit`, `@pytest.mark.skip`, `pytest.skip()`, `t.Skip()`.

    Si solo baja el número de aserciones de un fichero de tests, **avisa sin fallar** («Hay menos aserciones en `…` (de 3 a 2)»). Reconoce los tests de Java y Kotlin (`src/*test*/`, `*Test.java`, `*IT.kt`…), JavaScript y TypeScript (`*.test.ts`, `*.spec.js`, `__tests__/`, `tests/`, `e2e/`), Python (`test_*.py`, `*_test.py`, `tests/`) y Go (`*_test.go`). Una fase que de verdad tiene que quitar tests (p. ej. al borrar un módulo con los suyos) lleva `allowTestRemoval: true`, que quita `tests-intact` de su `tests`.
  - `spec` (ES-A): la propuesta dice lo que hace falta para juzgarla. Mira `openspec/proposal.md`, u otro fichero con `{spec: docs/propuesta.md}`, y exige cinco secciones de Markdown (`##` o más), cada una con texto:
    - **Qué se podrá hacer**: lo que el usuario podrá hacer y antes no;
    - **Qué resultado se obtiene**: el resultado esperado, contra un ejemplo o una referencia;
    - **Qué debe respetar**: lo que no puede romper;
    - **Cómo se demuestra**: los tests o los pasos que lo prueban;
    - **Fuera de alcance**.

    Los títulos se reconocen por sus palabras, sin tildes ni mayúsculas y también en inglés: «Qué podrá hacer el usuario», «4. Cómo se comprueba» u «Out of scope» valen. Un guion, «TODO» o «pendiente» no cuentan como texto, y lo que va en bloques de código o comentarios HTML no cuenta. Si falta algo, falla con lo que falta y, con `retry: {on: [verification]}`, el intento siguiente lo recibe en su prompt: «A la propuesta `openspec/proposal.md` le falta «Cómo se demuestra»; está vacía «Fuera de alcance»».
- **Rutas protegidas** (VA-A): `protected` es una lista de globs que **ningún agente puede cambiar**, borrar ni mover:
  - en la raíz del workflow vale para todas sus fases `agent` y `parallel`; en una fase, se suma a lo de la raíz;
  - en la ficha del repositorio (**Configurar verificación → Rutas protegidas**, `PUT /api/projects/{id}/repositories/{rid}/verification` con `protectedPaths`) vale para todas las fases `agent` y `parallel` de cualquier workflow en ese repositorio, sin tocar el YAML;
  - las tres se suman y no se pueden quitar desde un nivel más bajo. Una ruta que está en `scope` y en `protected` es un aviso: gana `protected`;
  - se comprueban con el check `protected`, que se añade solo al final de esas fases: «Ha cambiado una ruta protegida: `docs/tests/probar.md`». Cuenta tanto la ruta vieja como la nueva de un fichero renombrado. No se deshace nada: la fase falla y, si tiene `retry`, el reintento vuelve a la instantánea;
  - el agente recibe antes de su prompt «Rutas protegidas: no cambies, borres ni muevas nada en …». Claude Code, además, las recibe como reglas `--disallowedTools Edit(<glob>)`, así que ni siquiera puede editarlas; Codex y Antigravity no tienen nada equivalente por ruta, y lo que hagan lo detecta el check;
  - los globs son los de Java, como `scope`: `docs/tests/**` casa con todo lo que hay debajo de `docs/tests/`, y `**/acceptance/**` no casa con un `acceptance/` en la raíz (para eso, `acceptance/**`).
- Las rutas no pueden salir del repositorio (ni `/` al principio ni `..`).
- **Cuándo:** cuando el agente termina bien, la fase **sigue en curso** («Verificando») mientras el runner comprueba su worktree; solo si pasan todas termina bien y arranca la siguiente. Si alguna no pasa, la fase falla con el motivo de cada una y se aplica el fail-fast. Corregirla llega en W5/W6.
- «El inicio de la fase» es el commit con el que su agente encontró el worktree.
- En una fase `parallel`, cada hijo se comprueba en su propio worktree; si uno no pasa, la fase falla.
- Una fase con comprobaciones no lleva además la verificación automática del repositorio (M5): con `tests` ya la incluye; sin él, en una fase `agent` o `parallel` el comando del repositorio se ejecuta igual pero solo informa.
- Cada comprobación deja `stage.verification.started` y `stage.verification.completed` (con el resultado de cada check), y el resultado es una verificación de la invocación con origen `STAGE`.
- **En la web:** en el progreso de la ejecución, la fase dice «Verificando» mientras se comprueba y, si falla, qué no pasó («No pasó artifacts: `openspec/proposal.md` no existe»). Al abrirla se ve cada comprobación con ✓, ✗ y su motivo, y los datos de otras fases que lee su prompt («Recibe spec.output.summary»). La pestaña **Verificación** del agente pone lo comprobado junto a lo que declaró (su respuesta y su salida estructurada). El resumen del workflow enseña qué comprueba cada fase y, al publicar, se avisa de los comandos de `checks` y de las fases que pasan el comando de verificación del repositorio.
- La plantilla **Especificar, planificar e implementar** es un ejemplo completo: `spec` deja `openspec/proposal.md` y devuelve un resumen, `plan` lo recibe en su prompt, la puerta `aprobar-plan` espera a que alguien apruebe el plan (o pida cambios, que corrige `plan`) e `implement` solo termina bien con `checks: [tests, diff]`.

**Nivel de riesgo del cambio** (AU-A). Skynet mide cuánto riesgo tiene lo que cambia una ejecución **a partir del diff, nunca de lo que diga el agente**. Con el nivel, una fase `judge` puede pasar sin juez o sin escépticos (`minRisk`, `refuteMinRisk`) y una puerta de fusión puede aprobarse sola (`autoApprove`), ver «Revisión y fusión según el riesgo (AU-C)».

- **Los niveles:**
  - **0**: todo lo que cambia es documentación o texto: `*.md`, `*.txt`, `*.rst`, `*.adoc`, `docs/**`, `plans/**`, imágenes, `LICENSE` y `NOTICE`;
  - **1**: código normal, incluidos los tests;
  - **2**: una ruta delicada o una línea añadida delicada. Pedirá siempre a una persona.
- **Rutas de nivel 2** (por defecto): migraciones (`**/db/migration/**`), CI (`.github/workflows/**`, `.github/actions/**`), dependencias y build (`build.gradle*`, `settings.gradle*`, `gradle.properties`, `gradle/**`, `build-logic/**`, `pom.xml`, `package.json`, los lockfiles, `requirements*.txt`, `pyproject.toml`, `go.mod`, `Cargo.toml`), los workflows de Skynet (`workflows/*.yaml`), despliegue (`deploy/**`, `Dockerfile`, `docker-compose*.yml`, `compose*.yaml`), seguridad (`**/security/**`, `**/auth/**`) y entornos (`.env*`). Cuentan la ruta vieja y la nueva de un renombrado.
- **Patrones de nivel 2** (por defecto, sin distinguir mayúsculas): en las líneas que el diff **añade** en código (no en tests ni en documentación):
  - esquema de BD: `CREATE`/`ALTER`/`DROP TABLE|INDEX|SCHEMA|TYPE|VIEW|SEQUENCE|EXTENSION`, `TRUNCATE TABLE`;
  - secretos: `password`, `secret`, `api_key`, `private_key`, `access_token`, `refresh_token`, `client_secret` («token» a secas no, porque sale en demasiado código que no es un secreto);
  - autenticación: `BCrypt`, `PasswordEncoder`, `@PreAuthorize`, `@Secured`, `@RolesAllowed`, `SecurityFilterChain`, `authorizeHttpRequests`;
  - cookies de sesión: `Set-Cookie`, `HttpOnly`, `SameSite`;
  - permisos: una línea que empieza por `permissions:`.
- **Más rutas y patrones**, que solo pueden **subir** el nivel, nunca bajarlo:
  - en la raíz del workflow:

    ```yaml
    risk:
      paths: [infra/**, "**/billing/**"]
      patterns: ['\bfeature_flag\b']
    ```

    Las rutas son globs de Java, como `protected` (un glob que empieza por `**/` también casa en la raíz). Los patrones son expresiones regulares de Java; una que no compila es un error al publicar.
  - en la ficha del repositorio (**Configurar verificación → Rutas de riesgo 2 / Patrones de riesgo 2**, `PUT /api/projects/{id}/repositories/{rid}/verification` con `riskPaths` y `riskPatterns`): valen para todos los workflows en ese repositorio. Una expresión que no compila es un 400.
- **Las líneas cambiadas:** las añadidas y quitadas, sin contar tests ni documentación. AU-C dejará fusionar solo lo de nivel 0-1 con menos de 400.
- **Cuándo se mide:** cuando termina cada agente de una fase `agent` o `parallel` que trabaja en el worktree de la ejecución, contra el commit con el que empezó la fase. El runner lo mide con los cambios del worktree (lo confirmado, lo que no y los ficheros nuevos) y lo manda con el artefacto `changes.json`, bajo `risk`. Los jueces, los escépticos y quien ejecuta la guía trabajan en una copia y no se miden, como tampoco las fases `command`.
- **Dónde queda:** la fase guarda el máximo de sus agentes (`stage_run.risk`) y la ejecución el máximo de sus fases (`workflow_run.risk`), cada una con el resultado que lo fijó (`risk_detail`). **Solo sube**: un cambio posterior más pequeño no baja el nivel de lo que ya se hizo.
- **Eventos:** `stage.risk.assessed` en cada medida (nivel, ficheros, líneas y resumen) y `run.risk.raised` cuando sube el nivel de la ejecución (de cuál a cuál y por qué fase).
- **En la web:** una insignia **Riesgo 0/1/2** en la cabecera de la ejecución y en cada fase del progreso. Al pasar por encima dice el resumen («Nivel 2: `db/migration/V2__x.sql` (ruta)», «Nivel 1: 12 líneas de código» o «Nivel 0: solo documentación») y, debajo, hasta cinco ficheros del nivel con su motivo, p. ej. «src/Config.java (secretos: «password»)».

**Fases `verification`.** No ejecutan nada: comprueban el worktree que heredan de la fase de la que dependen, p. ej. pasar los tests después de un agente sin darle a este las comprobaciones:

```yaml
  - id: verify
    type: verification
    dependsOn: [implement]
    checks: [tests, commit]
```

- Necesitan `artifacts`, `checks`, un `judge` o una `guide` (ver abajo), y una dependencia con worktree (`agent`, `command` o `parallel`); no admiten `agent`, `prompt`, `command`, `children`, `outputSchema` ni `workspace: isolated-worktree`.
- `diff`, `commit` y `scope` miden desde el commit con el que se creó el worktree.
- En la ejecución, su única invocación tiene proveedor `verification`: no arranca ningún proceso, termina bien si pasan las comprobaciones y falla con los motivos si no. No se continúa ni se reintenta por separado.
- La fase siguiente continúa el mismo worktree.

**Juez con rúbrica** (VA-B). Una fase `verification` puede llevar un `judge`: uno o varios agentes que, si pasan las comprobaciones, **puntúan el cambio de 1 a 5 con una rúbrica**. Si suspende y la fase tiene `fix`, el agente de esa fase corrige en su sesión con lo que encontraron, y la fase vuelve a comprobar y a juzgar:

```yaml
agents:
  juez-calidad:
    provider: codex
  juez-seguridad:
    provider: antigravity
stages:
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    checks: [tests]                 # lo barato primero: si falla, no se llama a ningún juez
    judge:
      agents:
        - {agent: juez-calidad, lens: correctness}
        - {agent: juez-seguridad, lens: security}
      rubric:
        - {id: resuelve, text: "Resuelve la tarea tal y como se pidió", weight: 3, required: true}
        - {id: tests, text: "Los tests cubren el cambio y sus casos límite", weight: 2}
        - {id: alcance, text: "No cambia nada que la tarea no pida", weight: 1}
      threshold: 3.5
    fix: implementar
    maxRounds: 2
```

- `judge.agents` (obligatorio, de 1 a 5): agentes de `agents`, que juzgan **a la vez**, cada uno en una copia del worktree y **siempre en solo lectura** (`permissionMode: plan`, diga lo que diga el agente). Un nombre o `{agent, lens}`; el mismo agente no puede juzgar dos veces en una fase. No necesitan `prompt`: Skynet escribe el del juez y, si el agente tiene uno, lo añade como «Indicaciones».
- `lens`: qué mira además de la rúbrica: `correctness` (lógica, casos límite, regresiones), `security` (entradas, secretos, permisos), `performance` (trabajo de más), `reproduce` (que quede demostrado que se resuelve) o `completeness` («¿qué falta para que esto esté terminado?»: tests, documentación, migraciones; lo que falte es un hallazgo). Sin lente, mira todo.
- `rubric`: los criterios, con `id` (minúsculas, números, `-` y `_`), `text`, `weight` (de 1 a 10, 1 por defecto) y `required` (con menos de 3, el juez suspende sea cual sea su media). Sin `rubric` ni `rubricFile` se usa la de siempre: `resuelve` (peso 3, obligatorio), `tests` (2) y `alcance` (1).
- `rubricFile`: la rúbrica en un fichero del repositorio, que el juez lee del worktree y puntúa con el peso y si es obligatorio que diga el fichero. **Queda protegido** en todas las fases de agente del workflow (como `protected` en la raíz), para que nadie cambie la rúbrica con la que se le juzga. No se puede poner con `rubric`.
- **Criterios del repositorio** (ES-B): si el worktree tiene `.skynet/review.md` o `REVIEW.md` en la raíz (gana el primero), cada juez lo recibe en «## Criterios de este repositorio», debajo de la rúbrica, que sigue valiendo. Ahí el repositorio dice qué es un fallo, qué rutas no mirar, qué no señalar o cuántos *nits* como mucho. Es texto libre: lo lee el juez y Skynet no lo interpreta.
  - Lo reciben también los escépticos (VA-C) y los jueces del delta (AU-B). Vale con `rubric`, con `rubricFile` y con la rúbrica de siempre.
  - Lo lee el runner del worktree al arrancar el juez: hasta 8 KB (si es más largo, se corta y el juez lo sabe). Un enlace simbólico o un fichero vacío no cuentan.
  - En la web, la línea del worktree del juez dice «Worktree listo en la rama …, con los criterios de `REVIEW.md`».
  - No queda protegido: un agente del workflow puede cambiarlo, y el juez lee el que haya en el worktree. Para que no se toque, ponlo en `protected`.
- `threshold`: la media ponderada mínima, de 1 a 5 (3.5 por defecto).
- `fix` y `maxRounds` (de la fase, como en `github-check`): la fase `agent` que corrige, que tiene que ser una de las que van antes (la que dejó el worktree que se juzga), y cuántas rondas de corrección como mucho (2 por defecto). Sin `fix`, suspender hace fallar la fase.
- **Qué recibe el juez:** la tarea (clave, título y descripción), el commit desde el que mirar el cambio (`git diff <commit>` en su copia), el resultado de las comprobaciones de la ronda con sus avisos (p. ej. «Hay menos aserciones en …»), la rúbrica, el umbral y su lente. **No recibe nada de lo que dijo el agente que hizo el cambio**, ni su resumen ni su salida estructurada: si el juez lee «he añadido tests para todos los casos», tiende a creérselo.
- **Qué devuelve:** Skynet le pide una salida estructurada con la nota y la justificación de cada criterio, sus hallazgos (fichero, línea si la sabe, gravedad `blocking` o `minor` y por qué) y un resumen.
- **Cómo se decide:** cada juez aprueba si su media ponderada llega al umbral, ningún criterio obligatorio baja de 3 y no encuentra nada `blocking`. La fase pasa si **aprueba la mayoría** de los jueces y **ninguno encuentra nada que bloquee**; con un juez, manda él. La nota de la fase es la media de las medias. Un criterio que el juez no puntúa cuenta como un 1. Un juez que no termina bien o no devuelve su veredicto hace fallar la fase (corregir no lo arreglaría).
- **Las rondas:** cada una empieza por las comprobaciones de la fase (si las tiene). Si no pasan, la ronda suspende sin llamar a los jueces. Si la ronda suspende y quedan rondas, el agente de `fix` sigue en su sesión con lo que se encontró (los hallazgos que bloquean, los criterios con nota baja y lo menor); cuando termina, y si pasan las comprobaciones de su propia fase, empieza la ronda siguiente desde cero. Sin rondas, la fase falla con «Suspende el juez: 2,8 / 5 · suspenso: aprueba 0 de 1 tras 2 rondas de corrección».
- Las fases siguientes leen `juzgar.score`, `juzgar.passed`, `juzgar.findings`, `juzgar.judgedSha` (el commit que vieron los jueces al aprobar) y `juzgar.criteria.<id>.score` en sus condiciones, y `{{stages.juzgar.output.score}}` (o `findings`, como JSON) en sus prompts.
- **Al publicar se avisa** si un juez usa el mismo proveedor y modelo que el agente que juzga («es menos independiente»), si la reserva de un juez (CQ-B) es el proveedor del autor (y entonces, sin cuota, el juez espera en vez de cambiar a ella) y si hay varios jueces sin lente («dan más con lentes distintas»).
- Cada ronda deja `stage.judge.started`, `stage.judge.verdict` (nota, aprobados, hallazgos que bloquean), `stage.judge.fix.started` y `stage.judge.round.started`; si falla, `stage.judge.failed` con el motivo. El veredicto de cada juez en cada ronda queda en `stage_verdict`, y el estado del juez de la fase en `stage_judge`.
- **En la web:** la fase dice «Juzgando: 2 jueces», «implementar corrige lo que suspendió el juez (ronda 1 de 2)» o su nota, «3,8 / 5 · aprobado por 2 de 2». Al abrirla se ve la nota de cada criterio por juez (con su justificación), los hallazgos con su fichero y el resumen de cada juez. En el grafo, la caja de la fase dice cuántos jueces, el umbral y quién corrige. Con una fase `github-pr` detrás, la descripción de la PR lleva la nota y los hallazgos.

**Verificación adversarial** (VA-C). Con `refute: N` en el `judge`, cada hallazgo que **bloquea** pasa por N escépticos que intentan refutarlo antes de decidir la ronda. Lo que mayoría refuta se descarta: no suspende la fase ni se manda corregir. Es más barato refutar un falso positivo que hacer una ronda de corrección para nada:

```yaml
    judge:
      agents:
        - {agent: calidad, lens: correctness}
        - {agent: seguridad, lens: security}
      refute: 2                     # dos escépticos por hallazgo que bloquea
      refuteAgent: esceptico        # opcional: por defecto, el agente del primer juez
    fix: implementar
```

- `refute`: de 0 (por defecto, sin refutar) a 5. `refuteAgent`: un agente de `agents`; sin él, el del primer juez. Con `refuteAgent` y sin `refute`, se avisa al publicar.
- `refuteMinRisk` (AU-C): el nivel de riesgo desde el que corren los escépticos, de 0 (por defecto, siempre) a 2. Por debajo, la fase lo dice: «Nivel 1: sin escépticos (refutan desde el nivel 2)». Sin medir el nivel, corren.
- Cada escéptico trabaja en una copia del worktree, **en solo lectura** y sin memoria, como un juez. Solo ve la tarea, el hallazgo (fichero, línea y texto) y de dónde mirar el cambio; ni la nota del juez ni lo que dijo el autor. Devuelve si lo refuta y por qué, y se le pide que no lo refute si duda.
- Un hallazgo se descarta si lo refuta **más de la mitad** de sus escépticos: con 2, los dos; un empate no lo descarta. Un escéptico que no termina bien no lo refuta. Los `minor` no se refutan: no bloquean.
- **Topes:** como mucho **8 hallazgos por ronda** (los primeros, en el orden de los jueces) y **20 llamadas a agentes por fase**, jueces y escépticos de todas las rondas. Si no caben todos, los demás cuentan sin refutar y la fase lo dice: «Se han intentado refutar 8 de 11 hallazgos que bloquean (como mucho 8 por ronda); los demás cuentan sin refutar». Nada de recortes en silencio.
- Con los descartados, cada juez vuelve a decidir (sin ellos puede aprobar) y la fase también. A la fase de `fix` solo le llega lo que sobrevive.
- La fase dice «Refutando 3 hallazgos que bloquean» mientras tanto. Al abrirla, los hallazgos refutados salen **tachados** con el motivo («Refutado: ya se comprueba en la línea 10»), y los que no, con lo que dijo el escéptico. En la PR, igual: tachados y con el motivo. En el grafo, la caja dice «refuta ×2».
- Eventos `stage.judge.refute.started` (cuántos hallazgos y escépticos, y el aviso si hubo recorte) y `stage.judge.refuted` (cuántos se descartaron); el de `stage.judge.verdict` dice cuántos se refutaron. Lo que dijo cada escéptico queda en `stage_refutation`, y en `<fase>.findings` cada hallazgo lleva `refuted` y `refutation`.
- **Los workflows iniciales** lo usan: en «Implementar una tarea», «Corregir un bug» y «Especificar, planificar e implementar», la revisión (y su fase de corregir) es ahora una fase `juzgar` con jueces de otros proveedores que Claude Code, `refute: 2` y `fix` en la fase que implementa. `scripts/seed-workflows.sh --actualizar` publica las versiones nuevas; las tuyas no se tocan.

**La guía de pruebas como fase** (VA-D). Con `guide`, un agente **ejecuta la guía de pruebas que trae el cambio**, paso a paso, y apunta qué pasa en cada uno con su evidencia. Si falla un paso y la fase tiene `fix`, el agente de esa fase corrige con los pasos que fallan, y la fase vuelve a ejecutar la guía entera:

```yaml
agents:
  probador:
    provider: codex
stages:
  - id: guia
    type: verification
    dependsOn: [implementar]
    guide:
      path: "docs/tests/probar-*.md"   # o una lista de globs
      agent: probador
      skip: ["## 4."]                  # opcional: secciones que no se ejecutan aquí
      ifMissing: fail                  # o skip
    fix: implementar
    maxRounds: 2
```

- `guide.path` (obligatorio): dónde están las guías, un glob o una lista. Se ejecutan **las que cambia el diff** desde el commit con el que se creó el worktree (las nuevas también); las que no cambian, no. Si son varias, todas.
- `guide.agent` (obligatorio): el agente que la ejecuta, en una **copia del worktree** y sin memoria. A diferencia de un juez, **no va en solo lectura**: tiene que ejecutar los pasos (tests, el servicio, `curl`…). Lo que haga en la copia no llega al cambio. Su `permissionMode` es el del agente o el del repositorio: con `plan` no podría ejecutar nada, y se avisa al publicar. No necesita `prompt`: Skynet escribe el suyo y, si el agente tiene uno, lo añade como «Indicaciones» (p. ej. cómo arrancar algo en esa máquina).
- `guide.skip`: las secciones que no se ejecutan, por cómo empieza su título (p. ej. `## 4.` para la de GitHub). Sus pasos quedan como saltados.
- `guide.ifMissing`: si el cambio no trae guía, la fase falla con «No hay guía de pruebas en el cambio» (`fail`, por defecto) o pasa (`skip`).
- **Qué hace:** cada paso es `passed` (pasa lo que la guía dice que tiene que pasar), `failed`, `blocked` (no se puede hacer ahí: otra máquina, una cuenta, un servicio que no hay) o `skipped`. Se le pide que siga la guía tal cual, sin saltarse pasos ni cambiarla, y que **no arregle nada para que pase**. No recibe lo que dijo el autor del cambio.
- **Cómo se decide:** la fase pasa si **no falla ningún paso**. Un paso `blocked` es un aviso, no un fallo. Un estado que no se entiende cuenta como fallido.
- **Las rondas** son las del juez: `fix` y `maxRounds` igual. A la fase de `fix` le llegan los pasos con su evidencia y una indicación: «No cambies la guía para que pase: corrige el código». **Es solo una indicación**: la guía no queda protegida (las guías del cambio las escribe el propio autor). Si quieres impedirlo, pon la guía en `protected` cuando ya exista.
- La fase dice «Ejecutando la guía de pruebas», «implementar corrige lo que falla de la guía (ronda 1 de 2)» o su nota, «3 de 3 pasos pasan · 1 saltado». Al abrirla se ven las guías ejecutadas y los pasos por sección, con su estado y su evidencia plegada. En el grafo, la caja dice «Guía de pruebas · probador». Con una fase `github-pr` detrás, la descripción de la PR lleva la nota en la tabla de evidencias y **los pasos con su evidencia** en un bloque plegado debajo.
- Eventos `stage.guide.started` y `stage.guide.verdict` (pasos que pasan, fallan y no se pudieron, y la nota); las correcciones y las rondas, como el juez (`stage.judge.fix.started`, `stage.judge.round.started`, `stage.judge.failed`). El recorrido de cada ronda queda en `stage_verdict.guide`.
- Una fase lleva `judge` o `guide`, no los dos: si quieres las dos cosas, ponlas en dos fases (`juzgar` y `guia` detrás).

**Entrega por PR.** Una fase `type: github-pr` sube el worktree que hereda a GitHub y abre una PR con lo que ha hecho el workflow (EN-A). Necesita la GitHub App ([`github.md`](github.md)) instalada en el repositorio:

```yaml
  - id: pr
    type: github-pr
    dependsOn: [revisar]
    condition: repository.github       # en un repositorio sin GitHub, se omite
    pullRequest:
      title: "{{workItem.key}}: {{stages.plan.output.summary}}"
      body: Generado por Skynet.       # va antes de las evidencias
      base: main                       # por defecto, la rama por defecto en GitHub
      draft: false
      labels: [skynet]
```

- Continúa el worktree de la fase de la que depende, como un `command`, y la siguiente puede seguir en él. No admite `agent` ni `prompt`, ni `workspace: isolated-worktree`.
- El runner confirma lo que el agente dejara sin confirmar y sube `HEAD` a la rama `skynet/<clave del trabajo>`, siempre la misma para un trabajo: los reintentos actualizan la misma PR. No sube nada que toque `.github/workflows/`: la fase falla con el motivo.
- El token lo pide el runner justo antes del push. Está limitado al repositorio y a `contents: write`, va a git como cabecera y no se escribe en ningún sitio.
- Después, el control plane abre la PR (o actualiza la que ya hay abierta de esa rama). La descripción lleva el trabajo, cada fase anterior con el resumen de su salida (`summary`) y sus comprobaciones, y el coste. Con `SKYNET_PUBLIC_URL`, también un enlace a la ejecución.
- La fase termina bien cuando la PR está abierta. Si GitHub no responde, se reintenta con espera creciente; si dice que no (p. ej. 422) o falla 5 veces, la fase falla con el motivo.
- En la ejecución es una invocación más de su fase, con proveedor `git-push`: una herramienta `git push` con su salida. Eventos `agent.git.pushed` (rama, commit y cuántos commits), `stage.github.pr.opened`, `stage.github.pr.updated` y `stage.github.pr.failed`. `GET /api/workflow-runs/{id}/pull-requests` da la PR de la ejecución.

**Esperar la CI de la PR.** Una fase `type: github-check` espera a que pase la CI del último commit de la PR que abrió una fase `github-pr` anterior y, si falla, puede corregirla (EN-B):

```yaml
  - id: ci
    type: github-check
    dependsOn: [pr]
    condition: repository.github
    required: [test]       # por defecto, espera a todos los checks
    ifNoChecks: pass       # sin ningún check en 5 min: pass (con un aviso) o fail
    fix: implementar       # la fase agent que corrige la CI roja
    maxRounds: 2           # rondas de corrección como mucho (por defecto 2)
    timeout: 60m           # por defecto, 60 m
```

- Va detrás de una `github-pr`. No admite `agent` ni `prompt`: el agente que corrige es el de `fix`.
- Cuenta los check-runs (el último de cada nombre) y los statuses del commit. Pasa cuando todos (o todos los de `required`) terminan en `success`, `neutral` o `skipped`, y falla si alguno termina en `failure`, `timed_out`, `cancelled` o `action_required`.
- Los webhooks `check_run`, `check_suite` y `status` de ese commit hacen que pregunte en el acto, pero siempre decide la API de GitHub. Si un webhook se pierde, pregunta igual cada 2 minutos.
- **Corrección:** con la CI roja, `fix` y rondas por delante, el agente de `fix` sigue en su sesión y en su worktree con lo que falló: cada check fallido con su resumen, sus anotaciones (fichero y línea) y las últimas 200 líneas del log de su job, redactadas. Al terminar, el runner sube la rama otra vez (sin forzar) y la fase vuelve a esperar. En la PR queda un comentario por ronda.
  - `fix` tiene que ser una fase `agent` anterior a la `github-pr`: la que dejó el worktree que se sube.
  - Los logs piden el permiso **Actions: lectura** de la App. Sin él, el agente recibe solo las anotaciones, y se le dice.
  - Si la corrección falla, no cambia nada o se agotan las rondas, la fase falla con lo que falló. La PR se queda abierta.
- Eventos `stage.github.ci.waiting`, `stage.github.ci.passed` (con `warning` si no hay CI), `stage.github.ci.failed` y `stage.github.ci.fix.started`. `GET /api/workflow-runs/{id}/ci-checks` da el estado de la espera, la ronda y cada check.

**Lo que se fusiona es lo juzgado (AU-B).** Si una fase `judge` aprobó el cambio y después lo tocó la corrección de la CI, lo que se va a fusionar ya no es lo que vieron los jueces. Por eso:

- Al aprobar, la fase `judge` guarda el commit que juzgó (`judgedSha`): la instantánea de la copia del juez si tenía cambios sin commitear, o el commit del que partió.
- El agente que corrige la CI mide su riesgo (AU-A) **desde ese commit**, no desde la rama base: el nivel es el del delta.
- Cuando la CI pasa, según el nivel del delta:
  - sin cambios desde lo juzgado: no se juzga otra vez (`stage.judge.delta.unchanged`);
  - nivel 0: tampoco (`stage.judge.delta.skipped`);
  - nivel 1: lo juzga el primer juez de la fase, en una copia del worktree, solo lo que cambió desde `judgedSha`;
  - nivel 2, o si no hay medida del delta: lo juzga todo el panel.
- Mientras juzgan, la espera de la CI está en `JUDGING` (eventos `stage.judge.delta.started` y `stage.judge.delta.verdict`). Si encuentran algo que bloquea y el nivel del delta llega a `refuteMinRisk` (AU-C), los escépticos de `refute` intentan refutarlo antes de decidir (`stage.judge.delta.refute.started`; el delta queda en `REFUTING`). Si suspenden, la fase de CI falla con «El juez suspende lo que se cambió después de aprobar: …». No se repiten las fases `guide`.
- La aprobación de la fusión lo dice: «Juzgado en `abc1234` · se fusiona `def5678` · delta revisado (nivel 1, 4 / 5)». El delta de cada fase de CI queda en la tabla `judged_delta`.

**Revisión y fusión según el riesgo (AU-C).** El nivel de riesgo (AU-A) decide cuánto se revisa y si la fusión necesita a una persona:

```yaml
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [calidad, seguridad]
      refute: 2
      minRisk: 1          # un cambio de nivel 0 (solo documentación) pasa sin juez
      refuteMinRisk: 2    # los escépticos, solo en el nivel 2
  # …pr y ci…
  - id: aprobar-fusion
    type: human-approval
    dependsOn: [ci]
    action: Fusionar la PR
    autoApprove: {maxRisk: 1, maxLines: 400}   # o `true`, con estos mismos límites
  - id: fusionar
    type: github-merge
    dependsOn: [aprobar-fusion]
```

- **`judge.minRisk`** (de 0 a 2; por defecto 0, siempre se juzga): si el nivel de lo que cambiaron los agentes en el worktree que se juzga no llega, la fase termina bien sin llamar a los jueces, con `stage.judge.skipped` y «Sin juez: nivel 0 (juzga desde el nivel 1)». Las comprobaciones de la fase sí se hacen. Si algún agente de ese worktree no tiene su nivel medido, se juzga. No vale en una guía (`guide`).
- **`autoApprove`** en una puerta `human-approval` que desbloquea una fase `github-merge`. La primera vez que pregunta (no tras pedir cambios), se aprueba sola si se cumple **todo**:
  - el repositorio tiene encendido «Fusionar sola lo de riesgo 0-1» en **Configurar verificación** (`autoMerge`; viene apagado);
  - el nivel de la ejecución es como mucho `maxRisk` (0 o 1; por defecto 1);
  - las líneas de código cambiadas (sin tests ni documentación, como las mide AU-A) son como mucho `maxLines` (por defecto 400);
  - la CI de la PR está en verde;
  - lo que se fusiona está juzgado (AU-B): el juez aprobó ese commit, el delta pasó o no hacía falta juzgarlo, o la fase pasó sin juez por `minRisk` y el nivel no ha subido desde entonces;
  - Skynet no ha aprobado solo ya 10 puertas hoy (UTC) en el repositorio (`SKYNET_AUTONOMY_MAX_MERGES_PER_DAY`).
- Si se cumple, decide el actor `skynet` con un comentario que lista lo que se cumplió, y la fusión sigue sin esperar (`approval.granted` con `auto: true`; no avisa de que hay algo pendiente). Si falla una sola, pregunta como siempre y la puerta dice por qué: «No se aprueba sola: nivel 2: `db/migration/V2__x.sql` (ruta)». En la página de la aprobación sale cada condición con ✓ o ✗ (`evidence.autoApprove`).
- Las tres fases `aprobar-fusion` de los workflows iniciales llevan `autoApprove: {maxRisk: 1, maxLines: 400}` y sus `juzgar`, `minRisk: 1` y `refuteMinRisk: 2`. `scripts/seed-workflows.sh --actualizar` publica las versiones nuevas. Mientras el interruptor del repositorio esté apagado, la fusión pregunta como antes; lo que sí cambia con esas versiones es que un cambio de solo documentación pasa sin juez y los escépticos solo corren en el nivel 2.

**Registro de hallazgos** (RH-A). Cada hallazgo de un juez (el de la fase o el del delta de AU-B) queda en el registro con su **huella** y su **desenlace**. No cambia lo que ve el juez ni lo que bloquea: solo registra.

- **La huella:** el repositorio, el fichero, la lente y las palabras del texto (en minúsculas, sin tildes, sin números, sin lo que va entre comillas invertidas y sin palabras vacías). La línea no cuenta, porque se mueve. Dos hallazgos son el mismo si coinciden el fichero y la lente y sus palabras se parecen al menos `SKYNET_FINDINGS_SIMILARITY` (Jaccard, 0,5 por defecto).
- **Dentro de una fase**, el mismo hallazgo en rondas seguidas es una sola fila: `repeats` cuenta las veces que volvió. **Entre ejecuciones**, `firstSeenId` apunta a la primera vez que salió en el repositorio.
- **Los desenlaces:**
  - `abierto`: al salir del juez;
  - `refutado`: lo tumbó un escéptico (VA-C);
  - `corregido`: hubo una ronda de corrección y la siguiente ya no lo señala;
  - `aparcado`: la fase terminó (bien, o sin rondas que gastar) y seguía abierto;
  - `aceptado` (es real, pero intencionado) e `irrelevante` (el juez se equivocó): los pone una persona con «Es intencionado» o «No aplica» en la fase, y se deshacen con «Deshacer».
- Un `aceptado` vale para todo el repositorio. Las fases de juez y de CI enseñan su **Registro de hallazgos**, y la decisión queda en **Actividad** (`finding.outcome.set`). El registro se guarda siempre; lo de antes de RH-A no se rellena.

**No repetir lo ya visto** (RH-B). Con el registro de RH-A, el juez deja de repetir lo que ya se decidió:

- **El juez recibe lo ya visto.** Su prompt lleva «Lo que ya se ha señalado», hasta 30 líneas: lo de las rondas anteriores de la fase, con su desenlace, y lo que una persona marcó como intencionado o que no aplica en el repositorio. Va con la orden de no repetir lo refutado, lo intencionado ni lo que no aplica, y de volver a mirar lo que sigue sin corregir. Los jueces del delta (AU-B) también lo reciben.
- **Desde la ronda 2, primero el delta:** «Mira primero `git diff <commit de la ronda anterior>`: es lo que se corrigió». Después puntúa el cambio entero, como siempre.
- **Se descarta antes de los escépticos.** Lo que coincide (mismo fichero y palabras parecidas, sin mirar la lente) con algo refutado en una ronda anterior de la fase, o marcado como intencionado o que no aplica en el repositorio, sale como «Ya visto» con lo que se decidió entonces. Deja de bloquear y no gasta escépticos: `stage.judge.findings.filtered`.
- **Duplicados entre jueces:** los hallazgos que bloquean y dicen lo mismo en una ronda se refutan una sola vez, y lo que digan los escépticos vale para todos.
- **Lo que vuelve:** si un hallazgo sale otra vez después de una ronda de corrección, el informe para el que corrige lo marca en «Lo que vuelve»: «⚠ ya se señaló en la ronda 1 y sigue».

**Ruido por juez** (RH-C). **Fábrica → Jueces** cuenta, con el registro de hallazgos, cómo se porta cada juez con su lente, por proveedor y modelo, en los últimos 7, 30 o 90 días:

- **Ruido:** (refutados + que no aplican) / hallazgos. Lo que tumbó un escéptico o una persona dijo que no aplicaba.
- **Acierto:** corregidos / hallazgos. Lo que el agente corrigió porque el juez lo vio.
- La tabla va de más a menos ruido. Con menos de 10 hallazgos dice «pocos datos». Al elegir una fila salen sus últimos 5 hallazgos refutados o que no aplicaban, con el motivo y el enlace a su ejecución.
- Una fila del registro es un hallazgo aunque saliera en varias rondas (`repeats`). Los del delta de AU-B cuentan con su juez.

**Fusionar la PR.** Una fase `type: github-merge` fusiona la PR de la ejecución, normalmente detrás de su `github-check` y de una puerta que lo aprueba (EN-C):

```yaml
  - id: aprobar-fusion
    type: human-approval
    dependsOn: [ci]
    condition: repository.github
    action: "Fusionar la PR de {{workItem.key}}"
    risk: high
  - id: fusionar
    type: github-merge
    dependsOn: [aprobar-fusion]
    condition: repository.github
    method: squash         # squash (por defecto), merge o rebase
    deleteBranch: true     # borra la rama de la PR al fusionarla (por defecto)
    timeout: 30m           # por defecto, 30 m
```

- Va detrás de una `github-pr` (si no, el borrador no se publica). No admite `agent` ni `prompt`.
- Fusiona **solo el commit aprobado**: el último que subió Skynet, el que pasó la CI y enseñó la puerta. Antes de fusionar comprueba en GitHub que la PR sigue abierta en ese commit y que su CI sigue en verde, y pasa ese `sha` a GitHub, que no fusiona si alguien ha subido algo después.
  - Si la PR cambió, la fase falla con «La PR cambió después de la aprobación: se aprobó el commit X y ahora tiene el Y. No se fusiona: revísala en GitHub».
  - Si la CI ya no está en verde, falla con los checks que fallan; si aún está en marcha, espera.
  - Si GitHub dice que no (p. ej. 405, la PR no se puede fusionar), falla con su mensaje; si no responde, se reintenta con espera creciente hasta 5 veces.
- Fusionada, borra la rama (si falla, solo se avisa), el trabajo se cierra («Cerrado», `workitem.closed`), se pide eliminar los worktrees que se subieron y la fase termina bien.
- Una puerta que desbloquea una `github-merge` enseña la PR: su número, la rama, el commit que se aprueba y la CI de ese commit, check a check.
- Lo que pasa en GitHub cuenta (webhook `pull_request.closed`):
  - una PR **fusionada a mano** da la fase por hecha (o la que arranque después, en el acto);
  - una PR **cerrada sin fusionar** cancela la ejecución (`workflow.github.pr.closed` y `workflow.cancel.requested` con `reason: pull-request-closed`).
- Eventos `stage.github.pr.merged` (con `external: true` si se fusionó a mano) y `stage.github.pr.merge.failed`. `GET /api/workflow-runs/{id}/merges` da la fusión de cada fase: método, commit esperado, commit de la fusión, si se borró la rama y el error.
- En la ejecución, una tarjeta **Pull request** enseña la PR con su enlace, la rama, el commit, la CI check a check con sus rondas de corrección y la fusión.

Las plantillas `especificar-planificar-implementar`, `implementar-tarea` y `corregir-bug` terminan con estas cuatro fases (`pr`, `ci`, `aprobar-fusion` y `fusionar`), todas con `condition: repository.github`: en un repositorio sin GitHub se omiten y la ejecución termina como antes. `scripts/seed-workflows.sh --actualizar` publica la versión nueva de cada una. Más en [`entrega-por-pr.md`](entrega-por-pr.md).

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
  | `quota` | la cuenta del proveedor se quedó sin su ventana de 5 h o semanal | no: espera a que se renueve (ver «Sin cuota») |

  Cancelar no es un fallo y nunca se reintenta.
- Si el intento falló porque no pasaron sus comprobaciones (`verification`), el agente del siguiente recibe detrás de su prompt cuáles y por qué (ES-A): «Nota: un intento anterior de esta tarea no pasó estas comprobaciones de la fase…», con una línea por check, p. ej. «`spec`: A la propuesta `openspec/proposal.md` le falta «Cómo se demuestra»».
- Cada intento es una fase nueva de la misma ejecución (`attempt` 2, 3…) con agentes nuevos y sesión nueva. El intento que falló se queda como estaba, con sus agentes, sus logs y su coste.
- Mientras espera su hora, el intento nuevo está `PENDING` con `notBefore`, y el evento `stage.retry.scheduled` dice qué intento es, por qué falló el anterior y cuándo arranca. Las demás fases siguen a lo suyo. Si otra fase falla sin reintentos, el fail-fast cancela también el intento que espera; cancelar la ejecución, también.
- El intento nuevo parte del worktree tal como lo encontró el que falló: al arrancar cada agente, el runner guarda una foto del worktree (`refs/skynet/snapshots/<agente>`, con lo confirmado y lo que no) y, antes del reintento, lo deja como en esa foto. Lo que dejó el intento fallido se sigue viendo en sus artefactos y su diff.
- En una fase `parallel`, `retry` va en cada hijo de `children` (en la fase es un error). Si un hijo falla, sus hermanos terminan; después, el intento nuevo de la fase repite solo los hijos que fallaron y pueden reintentarse. Los que terminaron bien no se repiten y sus salidas y worktrees siguen valiendo.
- `retry` en una fase `conditional`, `verification` o `human-approval` es un error.
- **En la web:** el progreso enseña el último intento de cada fase («Reintenta a las 10:30:15 (intento 2 de 3)»), por qué falló cada agente («Falló: terminó con error») y, al abrir la fase, sus intentos anteriores con sus agentes. El resumen del workflow dice qué fases se reintentan.

**Sin cuota.** Si un agente se queda sin la cuota de su cuenta (la ventana de 5 h o la semanal de Claude Code, Codex o Antigravity), acaba con `failure: quota` y su fase no falla: espera a que se renueve y sigue. No hace falta `retry` y no gasta sus `maxAttempts`.

```yaml
agents:
  impaciente:
    provider: claude-code
    onQuota: fail             # wait, fail o fallback; por defecto, fallback si tiene reserva y wait si no
  desarrollador:
    model: sonnet
    fallback:                 # proveedor de reserva: sigue su trabajo si la cuota tarda en renovarse
      provider: codex         # otro proveedor (claude-code, codex o antigravity)
      model: gpt-6.1          # opcional: sin él, el de su CLI
```

- La fase espera a la ventana bloqueada que más tarde se renueva, más 30 s y hasta 60 s de dispersión (`SKYNET_QUOTA_MARGIN`, `SKYNET_QUOTA_JITTER`), con un intento nuevo `PENDING` y `stage.retry.scheduled` con `reason: quota`, su `provider`, su `window` y su `resetsAt`. Sin hora de renovación, espera 30 minutos, como mucho 2 veces. Como mucho 4 esperas por fase.
- Al seguir, el agente **reanuda su sesión en el mismo worktree**, sin restaurar la foto. Si el proveedor no puede reanudar, empieza una sesión nueva en el mismo worktree con una nota de que hay trabajo a medias.
- Una fase lista cuyo proveedor está sin cuota en todos sus runners no arranca: espera igual (`stage.quota.waiting`), sin gastar nada. Mientras espera, la ejecución sigue «En curso» y conserva su hueco en la cola del repositorio.
- Si la renovación queda más allá de la espera máxima (6 h por defecto), la fase falla con `quota` como cualquier fallo.
- **Proveedor de reserva** (`fallback`): si la cuota del agente se renueva en más de 15 minutos (ajustable) o más allá de la espera máxima, no espera: un agente de la reserva sigue **en el mismo worktree**, con el prompt original, los mismos permisos y la misma salida estructurada, y una nota de que otro agente empezó la tarea (no se puede reanudar la sesión de otro proveedor). Si es menos, espera al principal.
  - Las fases siguientes del mismo agente arrancan ya con la reserva mientras el principal siga sin cuota, y vuelven a él cuando la recupera.
  - Si la reserva también se queda sin cuota, se vuelve al principal en cuanto tenga; si los dos están sin ella, espera al primero que se renueve.
  - En el progreso, «Sustituido por Codex (Claude Code sin cuota hasta las 18:05)»; un aviso al cambiar y, en Actividad, `agent.fallback.started`. El coste y la cuota se apuntan a la reserva. La API lo enseña en `fallback` del agente.
  - Cambiar a otra cuenta del mismo proveedor no es una reserva (es un error en el YAML): llegará con FL.
- En **Ajustes → Ejecución** se apaga (falla como antes), se cambia la espera máxima (6 h, 24 h o sin límite), las esperas por fase y a partir de cuántos minutos se cambia a la reserva, y se ven las fases en pausa (`GET/PUT /api/settings/quota`, `GET /api/quota/waits`).
- En el progreso, la fase dice «Esperando la cuota de Claude Code · se renueva a las 18:05 (ventana de 5 h)» y ofrece **Reintentar ahora** (`POST /api/workflow-runs/{id}/stages/{key}/skip-quota-wait`), que la arranca ya sin mirar la cuota. En una ejecución lanzada desde una issue, Skynet lo comenta en ella.

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
| Fase `merge` (sin GitHub) | sin fecha |
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
| `GET` | `/api/workflow-runs/{id}/findings` | El registro de hallazgos de la ejecución, con su desenlace (RH-A) |
| `POST` | `/api/findings/{id}/outcome` | Decide qué es un hallazgo (`{outcome: aceptado \| irrelevante \| abierto, reason}`); `abierto` lo deshace y, si la fase ya terminó, lo deja `aparcado` |
| `GET` | `/api/factory/judges?days=30` | Ruido y acierto de cada juez por proveedor, modelo y lente, con sus últimos hallazgos refutados o que no aplican (RH-C); `days` de 1 a 365 |

Cada problema lleva `severity` (`ERROR`, `UNSUPPORTED` o `WARNING`), `path` (p. ej. `stages.fix.dependsOn[0]`), `line`, `column` y `message`.

## Memoria del proyecto

Skynet recuerda lo que se hace en cada proyecto y repositorio, al estilo de [engram](https://github.com/Gentleman-Programming/engram) (hito ME). Lo guardan los propios agentes mientras trabajan y Skynet al terminar cada ejecución; las personas lo revisan después. Todo el detalle está en [memoria.md](memoria.md).

- **Al empezar una sesión nueva**, el prompt de la fase va precedido de un bloque `<memoria-del-proyecto>`: las entradas fijadas, los últimos resúmenes de sesión, la bitácora y lo último guardado en el repositorio de la ejecución y en todo su proyecto, hasta 4 000 caracteres (`SKYNET_MEMORY_CONTEXT_MAX_CHARS`). Si no cabe todo, deja fuera entradas enteras, nunca medias, y avisa de que hay más con `mem_search`. Llega como contexto de referencia, no como instrucciones: si contradice el repositorio, manda el repositorio. Nunca incluye lo archivado ni lo de otro repositorio u otro proyecto.
- **Al continuar una sesión** (un mensaje, una reanudación o una bifurcación) no se repite el bloque: ya está en la conversación. Las herramientas siguen disponibles.
- **Herramientas:** Claude Code y Codex tienen `mem_context`, `mem_search`, `mem_get`, `mem_save` y `mem_session_summary` (las dos últimas solo con `memory: write`). Antigravity recibe el bloque, pero todavía no las herramientas.
- **Trazabilidad:** el prompt que se guarda es el de la fase. `agent.spawned` lleva `memory` con el modo y las observaciones (id y versión) que recibió en el bloque, y `memory_access` registra cada observación que una invocación recibió (`PROMPT` o `CONTEXT`), buscó (`SEARCH`), leyó (`GET`) o guardó (`SAVE`).
- **Bitácora:** al terminar una ejecución (bien, mal o cancelada), Skynet deja en cada repositorio en el que trabajó una observación `RUN_LOG` con el tema `bitacora/<ejecución>`: el workflow y su versión, el trabajo, el estado de cada fase, la rama con sus commits y archivos (del artefacto `GIT_CHANGES`, si ya ha llegado), la salida estructurada de sus agentes, el coste y el enlace a la ejecución. Se escribe sin modelo; si falla, la ejecución termina igual.

