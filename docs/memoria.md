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
| Procedencia | Quién escribió la versión actual (un agente, una persona o Skynet), y la ejecución y la invocación de las que sale. |
| Versiones | Cada cambio crea una versión nueva; todas se guardan con quién la hizo y cuándo. |

**Guardar con un `topic_key` que ya existe** en el mismo ámbito crea la versión siguiente de esa observación, no otra. Así un tema se actualiza en vez de llenarse de entradas que compiten entre sí. Si el contenido no cambia, no se crea versión.

**Secretos:** todo lo que se guarda pasa por la misma redacción que los eventos (claves de API, tokens de GitHub, de AWS, de Slack y de Google, JWT, cabeceras `Bearer` y contraseñas en URLs) y sale como `[REDACTED]`. La entrada queda marcada como enmascarada y la web lo avisa.

## Cómo la usan los agentes

1. **Al empezar una sesión nueva**, el prompt de la fase va precedido de un bloque `<memoria-del-proyecto>`. Lleva lo fijado, los últimos resúmenes de sesión, la bitácora y lo último guardado en el repositorio de la ejecución y en todo el proyecto, con un tope de 4 000 caracteres (`SKYNET_MEMORY_CONTEXT_MAX_CHARS`). Si no cabe todo, deja fuera entradas enteras y avisa de que hay más. Llega como contexto de referencia, no como instrucciones: si contradice el repositorio, manda el repositorio. Una continuación (mensaje, reanudación o bifurcación) no lo repite.
2. **Mientras trabajan**, Claude Code y Codex tienen las herramientas MCP del servidor `skynet_memoria`:

   | Herramienta | Qué hace |
   |---|---|
   | `mem_context` | El mismo contexto del bloque inicial, otra vez. |
   | `mem_search` | Busca en el repositorio y en su proyecto; devuelve vistas previas. |
   | `mem_get` | Una observación entera. |
   | `mem_save` | Guarda una observación, con `topic_key` opcional. Solo con `memory: write`. |
   | `mem_session_summary` | El resumen de la sesión: objetivo, qué se hizo, descubrimientos, siguientes pasos y ficheros. Llamarla otra vez lo actualiza. Solo con `memory: write`. |

   Antigravity recibe el bloque, pero todavía no las herramientas: no está comprobado que `agy` admita servidores MCP en modo headless.
3. **Al terminar la ejecución** (bien, mal o cancelada), Skynet deja en cada repositorio en el que trabajó una observación `RUN_LOG` con el tema `bitacora/<ejecución>`: el workflow y su versión, el trabajo, el estado de cada fase, la rama con sus commits y archivos, la salida estructurada de los agentes, el coste y el enlace a la ejecución. Se escribe sin modelo, con lo que ya está en la base de datos. Si falla, la ejecución termina igual.

### La clave `memory` de una fase

En las fases `agent` y `parallel` ([workflows.md](workflows.md#memoria-del-proyecto)):

| Valor | Bloque inicial | Buscar y leer | Guardar |
|---|---|---|---|
| `write` (por defecto) | sí | sí | sí |
| `read` | sí | sí | no (403) |
| `off` | no | no | no |

Un revisor o un investigador que no debe dejar nada va con `read`; un paso que no tiene que ver nada del pasado, con `off`.

## El puente MCP

El servidor MCP es el propio runner: `skynet-runner memory-mcp --url <control plane> --token-file <fichero> --mode read|write`. Habla JSON-RPC por stdio y reenvía cada llamada a `/api/runner/memory/*` del control plane.

- **Token por invocación.** Justo antes de lanzar el agente, el runner pide un token de memoria para esa invocación (`POST /api/runner/agent-runs/{id}/memory-token`, con el token del runner) y lo deja en `logs/<id>.memory-token` con permisos `0600`. El token no va en la línea de comandos, ni en el prompt, ni en la configuración MCP, y el control plane solo guarda su hash.
- **Ámbito.** Lo decide el control plane a partir del token: el proyecto y el repositorio de la invocación. El agente no puede pedir otro, y lo de otro proyecto o repositorio da 404.
- **Caducidad.** El token deja de valer cuando termina la invocación (401) y el runner borra su fichero. Pedir otro token para la misma invocación invalida el anterior.
- **Límites.** Por invocación, 500 llamadas y 100 guardados (`SKYNET_MEMORY_MAX_CALLS` y `SKYNET_MEMORY_MAX_SAVES`); pasarse da 429.
- **Sin token, sin memoria.** Si el control plane no da el token, el agente arranca igual, sin herramientas, y queda un aviso en el log del runner.
- **Configuración en cada CLI:** Claude Code recibe `--mcp-config logs/<id>.mcp.json` y `mcp__skynet_memoria` en `--allowedTools`. Codex recibe `-c mcp_servers.skynet_memoria.command=…`, `…args=[…]` y `…default_tools_approval_mode="approve"`, porque `codex exec` no puede preguntar y rechazaría las llamadas.

## La pestaña Memoria

En cada proyecto, la pestaña **Memoria** enseña la memoria como una línea de tiempo: primero lo fijado y después por días, de lo último que cambió a lo más antiguo. Con texto en la búsqueda, los resultados van por pertinencia.

- **Filtros:** repositorio (todo, solo lo de todo el proyecto o un repositorio), tipo, fijadas y archivadas.
- **Detalle**, en un panel lateral: el contenido, el tema, si se enmascaró algún secreto, la procedencia (con enlaces a la ejecución y a la invocación) y todas las versiones.
- **Revisión:** editar (crea una versión nueva y conserva la procedencia), fijar o desfijar, y archivar o restaurar. Lo archivado no sale en las búsquedas ni llega a los agentes. Solo las personas fijan y archivan; los agentes no borran nada. Ninguna entrada puede pasar a ser bitácora, y en la web la bitácora conserva su tipo.

En el detalle de cada agente, la pestaña **Memoria** dice qué hizo esa invocación con ella: lo que recibió (en el bloque o con `mem_context`), lo que buscó o leyó y lo que guardó, cada entrada en la versión que vio y enlazada a la pestaña del proyecto.

## API

| Método y ruta | Qué hace |
|---|---|
| `GET /api/projects/{id}/memory` | Busca. Parámetros: `q`, `repository` (un id o `project`), `withProject`, `kind` (se puede repetir), `origin`, `pinned`, `archived` (`false`, `true` o `all`), `page` y `size` (hasta 100). |
| `POST /api/projects/{id}/memory` | Guarda una observación como persona (201, o 200 si es una versión nueva de un tema). |
| `GET /api/memory/{id}` | Una observación. |
| `PUT /api/memory/{id}` | Una versión nueva con lo que cambie una persona. |
| `GET /api/memory/{id}/history` | Todas las versiones, de la más reciente a la más antigua. |
| `POST /api/memory/{id}/archive`, `…/restore`, `…/pin`, `…/unpin` | Revisión. |
| `GET /api/agent-runs/{id}/memory` | Lo que una invocación recibió, buscó, leyó y guardó, con su modo (`READ`, `WRITE` o `null`). |

Cada cambio deja un evento en el historial: `memory.created`, `memory.updated`, `memory.archived`, `memory.restored`, `memory.pinned` y `memory.unpinned`. Los que escribe una ejecución (lo que guardan sus agentes y su bitácora) salen en su línea de tiempo.

## Fuera de esta versión

- Detectar duplicados y contradicciones entre entradas (`mem_judge`, `mem_compare` y `mem_consolidate` de engram).
- Memoria global entre proyectos, búsqueda semántica y caducidad de entradas.
- Lanzar `investigar-mejoras` de forma programada.
