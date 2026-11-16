# Memoria del proyecto

Skynet recuerda lo que se hace en cada proyecto y repositorio: decisiones, bugs resueltos, descubrimientos, convenciones, trampas y lo que pasó en cada ejecución. Sigue el modelo de [engram](https://github.com/Gentleman-Programming/engram), el que usa gentle-ai: **los agentes escriben y consultan la memoria mientras trabajan**, y las personas la revisan después. Así una ejecución nueva parte de lo ya sabido, y un workflow como `investigar-mejoras` trabaja sobre lo hecho.

Se hizo en el hito ME ([#141](https://github.com/guilu/skynet/issues/141)). Las razones de cada decisión están en la [ADR-0002](adr/0002-memoria-de-proyecto.md).

## Qué se guarda

Cada entrada es una **observación**:

| Campo | Qué es |
|---|---|
| Ámbito | El proyecto entero, o un repositorio del proyecto. |
| Tipo | Lo escriben los agentes y las personas: `DECISION`, `BUGFIX`, `DISCOVERY`, `PATTERN`, `CONFIG`, `PITFALL` y `CONSTRAINT`. Lo escribe Skynet: `SESSION_SUMMARY` (con `mem_session_summary`) y `RUN_LOG` (la bitácora). |
| Título y tema | Un título breve (hasta 200 caracteres) y, opcional, un `topic_key` estable como `arquitectura/auth`. |
| Contenido | `what` (obligatorio), `why`, `where` y `learned`, hasta 4 000 caracteres cada uno, y hasta 20 etiquetas. |
| Estado | Activa o archivada, y fijada o no. |
| Confianza | Confirmada, sin confirmar o en cuarentena, con su motivo (ver «Confianza»). |
| Procedencia | Quién escribió la versión actual (un agente, una persona o Skynet), y la ejecución y la invocación de las que sale. |
| Versiones | Cada cambio crea una versión nueva; todas se guardan con quién la hizo y cuándo. |

**Guardar con un `topic_key` que ya existe** en el mismo ámbito crea la versión siguiente de esa observación, no otra. Así un tema se actualiza en vez de llenarse de entradas que compiten entre sí. Si el contenido no cambia, no se crea versión.

**Secretos:** todo lo que se guarda pasa por la misma redacción que los eventos (claves de API, tokens de GitHub, de AWS, de Slack y de Google, JWT, cabeceras `Bearer` y contraseñas en URLs) y sale como `[REDACTED]`. La entrada queda marcada como enmascarada y la web lo avisa.

## Confianza

Lo que guarda un agente puede estar mal, o venir de algo que leyó en el repositorio o en la web y que no debería repetirse en cada ejecución. Por eso cada observación tiene una **confianza** (hito MV, MV-A):

| Confianza | Cuándo | Qué ven los agentes |
|---|---|---|
| **Confirmada** | La escribió o la editó una persona, o Skynet (la bitácora); o una persona aceptó el trabajo de la ejecución de la que sale. | Va en el bloque inicial y sale en las búsquedas. |
| **Sin confirmar** | La guardó un agente (también su resumen de sesión) y nadie ha aceptado todavía su trabajo. | No va en el bloque inicial. `mem_search` y `mem_get` la devuelven con `trust: UNCONFIRMED`, y el agente sabe que tiene que comprobarla. |
| **En cuarentena** | Parece una orden para el agente, o una persona la apartó. | Nada: ni el bloque, ni las búsquedas, ni `mem_get` (404). |

**Cuándo se confirma lo de un agente:**
- Al **fusionarse la PR** de su ejecución, la fusione Skynet (`github-merge`) o alguien a mano en GitHub.
- Al **terminar bien una ejecución en la que una persona aprobó alguna puerta** (también con `/skynet aprobar` desde GitHub). Una aprobación automática (`autoApprove`) no cuenta.
- Cuando una persona la **edita**, la **fija** o la **confirma** a mano.

Lo que está en cuarentena no sale de ella con una fusión ni con una versión nueva de un agente: solo si una persona la confirma, la edita o la fija.

**Qué va a cuarentena sola.** Sin modelo, se miran el título y los campos de lo que guardan los agentes y Skynet. Va a cuarentena lo que tiene frases dirigidas al agente («ignora las instrucciones anteriores», «a partir de ahora debes…», «from now on, you must…», «new instructions:») o lo que descarga y ejecuta código (`curl … | sh`, `base64 -d | sh`, `bash <(curl …)`). Una nota normal sobre comandos, URLs o configuración no se marca. Lo que escribe una persona no se mira.

Lo guardado antes de MV-A quedó como confirmado.

## ¿Ya está hecho?

Antes de lanzar un trabajo, Skynet lo compara con los **trabajos ya entregados** de su proyecto, que son los que tienen una PR fusionada (hito MV, MV-B). No usa ningún modelo:
- toma las palabras del título y de la descripción, sin tildes ni palabras vacías;
- corta cada palabra a sus cinco primeras letras, así que «exportar» y «exportación» cuentan como la misma;
- calcula su parecido (Jaccard) entre 0 y 1. Usa el mayor de dos: el de los títulos solos y el de título más descripción.

| Parecido | Qué pasa |
|---|---|
| **Fuerte**: desde `SKYNET_MEMORY_DONE_SIMILARITY` (0,6), o el propio trabajo ya entregado | Lanzarlo desde la web o la API responde **409** con `code: DONE_MATCH` y los parecidos en `doneMatches`: «Esto parece ya hecho: AKA-1 «Exportar las notas a PDF» se entregó en la PR #40 el 3/11/2026…». Se lanza igualmente con `confirmDone: true`; en la web, el formulario de lanzar enseña los parecidos con su PR y el botón «Lanzar igualmente». Un disparador de GitHub no lo lanza y lo comenta en la issue. |
| **Leve**: desde `SKYNET_MEMORY_DONE_HINT` (0,3) | Se lanza sin preguntar. |

Si se lanza con algún parecido, la ejecución deja el evento `memory.done_match` con los parecidos y si se confirmó. Además, el bloque `<memoria-del-proyecto>` de cada agente lleva «Trabajos parecidos que ya se entregaron en este proyecto», con su PR y su parecido, para que no repita lo hecho.

Los disparadores programados y los de despliegue no se comparan: relanzan a propósito el mismo trabajo.

## La wiki

Además de las entradas sueltas, cada proyecto tiene una **wiki**: páginas de markdown que resumen lo que Skynet sabe de él (hito MV, MV-C). Cada página lleva sus **fuentes** (la PR, el hallazgo, la lección, la observación o el escape de donde sale) y sus **versiones**.

Skynet genera cuatro páginas de todo el proyecto **sin modelo**, a partir de lo que ya está en la base de datos:

| Página | Qué lleva |
|---|---|
| **Índice** (`indice`) | Qué páginas hay y una línea de cada una: «1 trabajo entregado; el último, AKA-1 el 3/11/2026». |
| **Lo hecho** (`lo-hecho`) | Los trabajos entregados (con una PR fusionada), del más reciente al más antiguo, hasta 100: la PR, la fecha, el repositorio y los ficheros que tocó, sacados del último `GIT_CHANGES` de su ejecución. |
| **Errores conocidos** (`errores-conocidos`) | Los hallazgos de los jueces que se corrigieron, agrupados por hallazgo y con en cuántas ejecuciones salió; las lecciones de `prevenir` aplicadas; las trampas y bugs (`PITFALL`, `BUGFIX`) **confirmados** de la memoria, y lo que se le escapó al juez. Hasta 30 de cada. |
| **Decisiones y restricciones** (`decisiones-y-restricciones`) | Las `CONSTRAINT` y `DECISION` **confirmadas**, primero las fijadas. Hasta 30 de cada. |

- **Cuándo se generan:** al terminar cada ejecución y al fusionarse una PR, después de confirmar la memoria de esa ejecución. También a mano, con `POST /api/projects/{id}/wiki/refresh`, por ejemplo para un proyecto con historia de antes de la wiki o tras confirmar entradas.
- **Lo que no cambia no crea versión.** Si el título y el contenido son los de ahora, la página se queda en su versión.
- **No entra lo que no está confirmado** ni lo que está en cuarentena o archivado.

Los agentes reciben el índice en su bloque inicial y leen las páginas con `wiki_index` y `wiki_read` (MV-D, abajo). El curador escribe otras páginas ([El curador](#el-curador)). La web las enseña en la vista **Wiki** de la pestaña Memoria (MV-F).

## Cómo la usan los agentes

1. **Al empezar una sesión nueva**, el prompt de la fase va precedido de un bloque `<memoria-del-proyecto>`, con un tope de 4 000 caracteres (`SKYNET_MEMORY_CONTEXT_MAX_CHARS`). Solo lleva lo confirmado, del repositorio de la ejecución y del proyecto entero. Llega como contexto de referencia, no como instrucciones: si contradice el repositorio, manda el repositorio. Una continuación (mensaje, reanudación o bifurcación) no lo repite. Ver [Qué recibe cada agente](#qué-recibe-cada-agente).
2. **Mientras trabajan**, Claude Code y Codex tienen las herramientas MCP del servidor `skynet_memoria`:

   | Herramienta | Qué hace |
   |---|---|
   | `mem_context` | El mismo contexto del bloque inicial, otra vez. |
   | `mem_search` | Busca en el repositorio y en su proyecto; devuelve vistas previas. |
   | `mem_get` | Una observación entera. |
   | `mem_done` | ¿Ya está hecho?: los trabajos entregados del proyecto que se parecen al de la ejecución o, con `query`, a ese texto. |
   | `wiki_index` | El índice de la wiki y la lista de sus páginas (las del proyecto y las de su repositorio). |
   | `wiki_read` | Una página entera de la wiki, por su `slug`: la de su repositorio si la hay y, si no, la del proyecto. |
   | `mem_save` | Guarda una observación, con `topic_key` opcional. Solo con `memory: write`. |
   | `mem_session_summary` | El resumen de la sesión: objetivo, qué se hizo, descubrimientos, siguientes pasos y ficheros. Llamarla otra vez lo actualiza. Solo con `memory: write`. |

   Las seis de lectura se anuncian con la anotación MCP `readOnlyHint: true`, así que el CLI las trata como de solo lectura también en `permissionMode: plan`. Antigravity recibe el bloque, pero todavía no las herramientas: no está comprobado que `agy` admita servidores MCP en modo headless.
3. **Al terminar la ejecución** (bien, mal o cancelada), Skynet deja en cada repositorio en el que trabajó una observación `RUN_LOG` con el tema `bitacora/<ejecución>`: el workflow y su versión, el trabajo, el estado de cada fase, la rama con sus commits y archivos, la salida estructurada de los agentes, el coste y el enlace a la ejecución. Se escribe sin modelo, con lo que ya está en la base de datos. Si falla, la ejecución termina igual.

### Qué recibe cada agente

El bloque se reparte el tope en secciones, en este orden (MV-D):

| Sección | Qué lleva | Hasta |
|---|---|---|
| Índice de la wiki | Las líneas de la página `indice` que nombran páginas, para que el agente sepa qué puede leer con `wiki_read`. Entra entero o no entra. | 20 % |
| Errores conocidos que tocan este trabajo | Las trampas (`PITFALL`) y bugs (`BUGFIX`) confirmados y los hallazgos de los jueces que se corrigieron en el repositorio, si tienen que ver con el trabajo. | 20 % |
| Lo más pertinente para este trabajo | Las demás entradas confirmadas que más se parecen al trabajo. | 25 % |
| Lo fijado y lo último | Lo de siempre: lo fijado, los últimos resúmenes de sesión, la bitácora y lo último guardado. | lo que quede |

- **Qué es tener que ver con el trabajo:** compartir palabras con el título y la descripción del trabajo (dos o más, o una si el trabajo tiene pocas), o nombrar un fichero que el trabajo nombra (`src/Pdf.java`, `index.css`), que cuenta como tres. Van primero las que más se parecen, hasta ocho por sección.
- **Cada entrada va una sola vez:** lo que ya salió en una sección no se repite en otra.
- **Lo que no cabe** se deja fuera entero, nunca a medias, y el bloque avisa de cuántas entradas más hay para buscarlas con `mem_search`.
- **Sin wiki o sin trabajo** el bloque es el de siempre: solo las secciones que tienen algo.
- Si el trabajo se parece a otros ya entregados, el bloque lo avisa también ([¿Ya está hecho?](#ya-está-hecho)).

`memory_access` registra también la wiki: la página del índice cuando va en el bloque (`PROMPT`) o en `mem_context` (`CONTEXT`), y cada página que el agente lee con `wiki_index` o `wiki_read` (`GET`), en la versión que vio.

### La clave `memory` de una fase

En las fases `agent` y `parallel` ([workflows.md](workflows.md#memoria-del-proyecto)):

| Valor | Bloque inicial | Buscar y leer | Guardar |
|---|---|---|---|
| `write` (por defecto) | sí | sí | sí |
| `read` | sí | sí | no (403) |
| `off` | no | no | no |
| `curate` | sí, con lo nuevo para curar | sí | no: escribe páginas y propuestas con su salida |

Un revisor o un investigador que no debe dejar nada va con `read`; un paso que no tiene que ver nada del pasado, con `off`. `curate` es el curador: solo en fases `agent`.

## El curador

El workflow `curar-memoria` (en `workflows/`) repasa la memoria del proyecto como una wiki viva (MV-E). Se lanza a mano o con un disparador programado semanal ([disparadores.md](disparadores.md#6-disparadores-programados-cron)). Su fase va con `memory: curate` y `permissionMode: plan`: lee el repositorio y la memoria, pero no cambia ningún fichero ni guarda entradas.

1. **Ingiere.** Su bloque inicial lleva, además del contexto de siempre, «Para curar: lo confirmado desde la última curación»: las entradas confirmadas y sin archivar de su repositorio y del proyecto que cambiaron desde la última curación del proyecto (todas, la primera vez), sin la bitácora, hasta 40. Las lee con `mem_get` y mira la wiki con `wiki_index` y `wiki_read`.
2. **Escribe páginas** de la wiki de su repositorio, con origen `CURATOR`: `arquitectura`, `modulos` y `convenciones` en el workflow de serie. Con `scope: project`, la página es del proyecto entero.
3. **Revisa** duplicados, contradicciones y entradas caducadas, y **propone** archivarlas o fusionarlas en la que mejor lo cuenta.

Todo eso va en su **salida estructurada**, y Skynet lo aplica cuando la invocación termina bien:

```json
{
  "summary": "Dos decisiones repetidas y una configuración vieja.",
  "pages": [{"slug": "arquitectura", "title": "Arquitectura", "body": "…", "sources": ["<id de entrada>"]}],
  "proposals": [
    {"action": "merge", "into": "<id>", "observations": ["<id>"], "reason": "Dicen lo mismo"},
    {"action": "archive", "observations": ["<id>"], "reason": "Ya es Java 21"}
  ]
}
```

- **Lo que no puede tocar:** las páginas que genera Skynet (`indice`, `lo-hecho`, `errores-conocidos`, `decisiones-y-restricciones`) y las que escribió una persona. Tampoco vale un slug que no sea de minúsculas y cifras con guiones. Lo que no vale no hace fallar la ejecución: queda en el informe, en `rejected`, con el motivo.
- **Límites:** 10 páginas y 30 propuestas por curación. Una propuesta solo vale con entradas del proyecto sin archivar y un motivo; si ya hay una igual pendiente, no se repite.
- **Las propuestas no se aplican solas.** Quedan `PENDING` hasta que una persona las acepta (archivar archiva sus entradas; fusionar archiva las de `observations` y deja `into` como está) o las rechaza, que no cambia nada.
- **El informe** de cada curación (resumen, páginas escritas, lo rechazado y las propuestas) queda en `memory_curation`, y la curación siguiente parte de su fecha. Después de escribir páginas, el índice de la wiki se regenera para nombrarlas.

## El puente MCP

El servidor MCP es el propio runner: `skynet-runner memory-mcp --url <control plane> --token-file <fichero> --mode read|write`. Habla JSON-RPC por stdio y reenvía cada llamada a `/api/runner/memory/*` del control plane.

- **Token por invocación.** Justo antes de lanzar el agente, el runner pide un token de memoria para esa invocación (`POST /api/runner/agent-runs/{id}/memory-token`, con el token del runner) y lo deja en `logs/<id>.memory-token` con permisos `0600`. El token no va en la línea de comandos, ni en el prompt, ni en la configuración MCP, y el control plane solo guarda su hash.
- **Ámbito.** Lo decide el control plane a partir del token: el proyecto y el repositorio de la invocación. El agente no puede pedir otro, y lo de otro proyecto o repositorio da 404.
- **Caducidad.** El token deja de valer cuando termina la invocación (401) y el runner borra su fichero. Pedir otro token para la misma invocación invalida el anterior.
- **Límites.** Por invocación, 500 llamadas y 100 guardados (`SKYNET_MEMORY_MAX_CALLS` y `SKYNET_MEMORY_MAX_SAVES`); pasarse da 429.
- **Sin token, sin memoria.** Si el control plane no da el token, el agente arranca igual, sin herramientas, y queda un aviso en el log del runner.
- **Configuración en cada CLI:** Claude Code recibe `--mcp-config logs/<id>.mcp.json` y `mcp__skynet_memoria` en `--allowedTools`. Codex recibe `-c mcp_servers.skynet_memoria.command=…`, `…args=[…]` y `…default_tools_approval_mode="approve"`, porque `codex exec` no puede preguntar y rechazaría las llamadas.

## La pestaña Memoria

En cada proyecto, la pestaña **Memoria** tiene tres vistas (MV-F), y la elegida va en la URL (`view=wiki` o `view=curations`).

**Entradas** enseña la memoria como una línea de tiempo: primero lo fijado y después por días, de lo último que cambió a lo más antiguo. Con texto en la búsqueda, los resultados van por pertinencia.

- **Filtros:** repositorio (todo, solo lo de todo el proyecto o un repositorio), tipo, confianza, fijadas y archivadas. Lo que no está confirmado lleva una etiqueta.
- **Detalle**, en un panel lateral: el contenido, el tema, si se enmascaró algún secreto, la confianza y su motivo, la procedencia (con enlaces a la ejecución y a la invocación), **¿Quién lo vio?** (las invocaciones que la recibieron, buscaron, leyeron o guardaron) y todas las versiones.
- **Revisión:** editar (crea una versión nueva y conserva la procedencia), fijar o desfijar, confirmar (también saca de cuarentena), poner en cuarentena con un motivo, y archivar o restaurar. Lo archivado no sale en las búsquedas ni llega a los agentes. Solo las personas fijan y archivan; los agentes no borran nada. Ninguna entrada puede pasar a ser bitácora, y en la web la bitácora conserva su tipo.

**Wiki** enseña las páginas, primero las de todo el proyecto (con el índice delante) y después las de cada repositorio, con quién las escribió (Skynet, el curador o una persona), su versión y cuántas fuentes citan. Cada página se abre en un panel con su contenido en markdown (sin HTML ni imágenes), sus fuentes (las entradas se abren en su panel; las PRs, en GitHub) y sus versiones. Un enlace a otra página la abre en el mismo panel. **Regenerar las páginas de Skynet** hace lo mismo que `POST …/wiki/refresh`.

**Curador** enseña los informes del curador, el más reciente primero: el resumen, las páginas que escribió (y si cambiaron), sus propuestas con las entradas por su título y lo que no se aplicó. Una propuesta pendiente se **acepta** (archiva sus entradas) o se **rechaza** (no cambia nada); las decididas dicen quién y cuándo.

En el detalle de cada agente, la pestaña **Memoria** dice qué hizo esa invocación con ella: lo que recibió (en el bloque o con `mem_context`), lo que buscó o leyó y lo que guardó, cada entrada en la versión que vio y enlazada a la pestaña del proyecto. También lista las páginas de la wiki que recibió o leyó (`wiki` en `GET /api/agent-runs/{id}/memory`), con su versión y cómo.

## API

| Método y ruta | Qué hace |
|---|---|
| `GET /api/projects/{id}/memory` | Busca. Parámetros: `q`, `repository` (un id o `project`), `withProject`, `kind` (se puede repetir), `origin`, `pinned`, `archived` (`false`, `true` o `all`), `trust` (`CONFIRMED`, `UNCONFIRMED` o `QUARANTINED`; se puede repetir), `page` y `size` (hasta 100). |
| `POST /api/projects/{id}/memory` | Guarda una observación como persona (201, o 200 si es una versión nueva de un tema). |
| `GET /api/memory/{id}` | Una observación. |
| `PUT /api/memory/{id}` | Una versión nueva con lo que cambie una persona. |
| `GET /api/memory/{id}/history` | Todas las versiones, de la más reciente a la más antigua. |
| `GET /api/memory/{id}/readers` | «¿Quién lo vio?»: las invocaciones que la recibieron, buscaron, leyeron o guardaron, de la más reciente a la más antigua (hasta 200), con la ejecución, la fase, el trabajo, el proveedor, la versión que vieron y cuántas veces. |
| `POST /api/memory/{id}/archive`, `…/restore`, `…/pin`, `…/unpin` | Revisión. Fijar también la confirma. |
| `POST /api/memory/{id}/confirm` | Confirmarla: llega a los agentes, también en el bloque inicial. |
| `POST /api/memory/{id}/quarantine` | Ponerla en cuarentena, con un `reason` opcional en el cuerpo. Deja de estar fijada. |
| `GET /api/work-items/{id}/done-matches` | Los trabajos entregados del proyecto que se parecen a este, del más parecido al menos, con su PR y si el parecido es fuerte (`strong`). |
| `GET /api/projects/{id}/wiki` | Las páginas de la wiki, sin su contenido: `slug`, título, origen (`GENERATED`, `CURATOR` o `HUMAN`), versión, cuántas fuentes y cuándo cambió. |
| `GET /api/projects/{id}/wiki/{slug}` | Una página con su markdown (`body`) y sus fuentes (`sources`: `kind`, `id`, `label` y `url`). Con `repositoryId`, la de ese repositorio. |
| `GET /api/projects/{id}/wiki/{slug}/revisions`, `…/revisions/{version}` | Sus versiones, de la más reciente a la más antigua, o una de ellas. |
| `POST /api/projects/{id}/wiki/refresh` | Regenera las páginas que genera Skynet y devuelve la lista. |
| `POST /api/runner/memory/wiki-index`, `…/wiki-read` | Lo que usan `wiki_index` y `wiki_read`, con el token de memoria de la invocación. `wiki-read` pide `slug`: sin él, 400; si no existe, 404. |
| `GET /api/projects/{id}/memory/curations` | Los informes del curador, del más reciente al más antiguo (`limit`, 10 por defecto): resumen, páginas escritas, lo rechazado y las propuestas. |
| `GET /api/projects/{id}/memory/proposals` | Las propuestas del curador; con `status` (`PENDING`, `ACCEPTED` o `REJECTED`), solo esas. |
| `POST /api/memory/proposals/{id}/accept`, `…/reject` | Decidir una propuesta pendiente: aceptar archiva sus entradas; rechazar no cambia nada. Una ya decidida da 409. |
| `GET /api/agent-runs/{id}/memory` | Lo que una invocación recibió, buscó, leyó y guardó, con su modo (`READ`, `WRITE`, `CURATE` o `null`). |

Cada cambio deja un evento en el historial: `memory.created`, `memory.updated`, `memory.archived`, `memory.restored`, `memory.pinned`, `memory.unpinned`, `memory.confirmed` y `memory.quarantined`, todos con la confianza (`trust`) que queda. Los que escribe una ejecución (lo que guardan sus agentes y su bitácora) salen en su línea de tiempo.

## Fuera de esta versión

- Detectar duplicados y contradicciones entre entradas sin modelo (`mem_judge`, `mem_compare` y `mem_consolidate` de engram): hoy lo propone el curador.
- Memoria global entre proyectos, búsqueda semántica y caducidad de entradas.
- Lanzar `investigar-mejoras` de forma programada.
- Editar las páginas de la wiki desde la web: hoy las escriben Skynet y el curador.
