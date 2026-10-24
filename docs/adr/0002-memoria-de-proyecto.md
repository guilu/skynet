# ADR-0002: Memoria de proyecto al estilo engram

- **Estado:** Aceptado (2026-10-23)
- **Fecha:** 2026-10-24
- **Decisores:** Diego (guilu) y equipo de Skynet
- **Ámbito:** control plane (módulo `memory`), runner (puente MCP), proveedores, motor de workflows y web
- **Hito:** ME, de ME-A a ME-D ([#141](https://github.com/guilu/skynet/issues/141), [#150](https://github.com/guilu/skynet/issues/150)–[#153](https://github.com/guilu/skynet/issues/153))
- **Documentación:** [`../memoria.md`](../memoria.md)

## 1. Contexto

Cada ejecución de Skynet empezaba de cero. Lo que un agente averiguaba (cómo se pasan los tests, por qué se tomó una decisión, qué trampa tiene un módulo) se quedaba en su conversación, y la siguiente ejecución lo volvía a descubrir o, peor, lo contradecía. Además, sin un registro de lo hecho no se podía lanzar un workflow de investigación o de mejoras sobre el trabajo anterior.

La issue #141 proponía una primera fase en la que las personas aprobaban cada entrada antes de que se usara. Al estudiar [engram](https://github.com/Gentleman-Programming/engram), el que usa gentle-ai, se vio otro enfoque que funciona en la práctica: los propios agentes escriben y consultan la memoria mientras trabajan, con herramientas MCP, y un `topic_key` estable evita que se llene de entradas repetidas.

## 2. Decisión

1. **Los agentes escriben y consultan la memoria solos**, con las herramientas MCP de engram: `mem_context`, `mem_search`, `mem_get`, `mem_save` y `mem_session_summary`. Las personas la **revisan a posteriori**: editan, fijan y archivan, pero no aprueban cada entrada antes de que se use.
2. **La memoria vive en el control plane**, en PostgreSQL (`memory_observation`, `memory_observation_revision` y `memory_access`), con búsqueda de texto (`tsvector` con la configuración `simple`). No hay un engram local por máquina, porque Skynet tiene varios runners y la memoria tiene que ser común a todos.
3. **Observaciones estructuradas y versionadas:** tipo, título, `topic_key` opcional, `what`, `why`, `where`, `learned` y etiquetas. Guardar con un tema que ya existe crea una versión nueva; todas las versiones se conservan.
4. **El ámbito lo decide la ejecución, nunca el agente.** El runner es el servidor MCP (`skynet-runner memory-mcp`) y reenvía cada llamada al control plane con un **token por invocación**, que solo ve el proyecto y el repositorio de esa invocación y caduca cuando termina.
5. **Contexto inicial en el prompt:** cada sesión nueva recibe un bloque `<memoria-del-proyecto>` con lo fijado, los resúmenes, la bitácora y lo último guardado, con un tope de caracteres que deja fuera entradas enteras. El prompt que se guarda sigue siendo el de la fase.
6. **Bitácora automática y sin modelo:** al terminar cada ejecución, Skynet escribe una observación `RUN_LOG` por repositorio a partir de lo que ya está en la base de datos.
7. **Control por fase** con la clave `memory: off | read | write` (por defecto `write`).
8. **Trazabilidad:** `memory_access` guarda qué observación (y en qué versión) recibió, buscó, leyó o guardó cada invocación, y la web lo enseña en el detalle del agente.

## 3. Alternativas descartadas

- **Aprobación previa de cada entrada** (la fase 1 de #141). Lo guardado no se usaría hasta que alguien lo revisara, y en la práctica la cola crecería sin revisar. Se cambia por revisión a posteriori, con archivado para lo que sobre.
- **Un engram por máquina** (el binario con SQLite). Cada runner tendría una memoria distinta y el ámbito dependería de dónde cayera la ejecución.
- **Que el agente elija el ámbito.** Un agente que se equivoca, o al que un fichero del repositorio le da instrucciones, podría leer o escribir en otro proyecto. El token por invocación lo impide.
- **Que el agente resuma con un modelo la bitácora.** Costaría tokens en cada ejecución y podría inventar. La bitácora sale de los hechos registrados.
- **Embeddings y búsqueda semántica.** Necesitarían un modelo de embeddings y otra extensión de PostgreSQL. La búsqueda de texto basta para empezar.

## 4. Consecuencias

- Una ejecución nueva parte de lo que se sabe del proyecto y del repositorio, y workflows como `investigar-mejoras` trabajan sobre lo hecho.
- El prompt efectivo de una sesión nueva es más largo (hasta el tope del bloque).
- Lo que guarda un agente se usa enseguida, aunque esté mal. Lo mitigan:
  - el bloque llega como contexto, no como instrucciones;
  - la procedencia de cada entrada;
  - las versiones;
  - el archivado.
- Los secretos se enmascaran al guardar, igual que en los eventos.
- Hay límites de llamadas y de guardados por invocación (`SKYNET_MEMORY_MAX_CALLS` y `SKYNET_MEMORY_MAX_SAVES`).
- Antigravity recibe el bloque inicial pero no las herramientas, hasta comprobar que `agy` admite MCP en modo headless.
- Codex necesita `default_tools_approval_mode="approve"` en el servidor MCP: `codex exec` no puede preguntar y rechazaría las llamadas.
- Quedan para más adelante:
  - la detección de duplicados y contradicciones (`mem_judge`, `mem_compare` y `mem_consolidate`);
  - la memoria global entre proyectos;
  - la búsqueda semántica;
  - la caducidad de entradas.
