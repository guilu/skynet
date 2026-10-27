# Probar MP-A - Mapa del repositorio por proyecto

MP-A hace que el workflow inicial `conocer-repositorio` escriba el mapa en `docs/skynet/mapa-<clave>.md`, con la clave del proyecto en minúsculas, en lugar de `docs/skynet/mapa.md` (issue #189):

- **Variable nueva `{{project.slug}}`:** la clave del proyecto en minúsculas (`DEMO` → `demo`), para nombres de fichero. El validador la admite en los prompts.
- **`conocer-repositorio`:** escribe `docs/skynet/mapa-{{project.slug}}.md` y declara `artifacts: [docs/skynet/mapa-*.md]`, porque `artifacts` no admite variables.
- **`implementar-tarea` y `corregir-bug`:** el primer agente (explorar y reproducir) lee el mapa del proyecto si existe.
- **`scripts/seed-workflows.sh --actualizar`:** publica como versión nueva los workflows iniciales que ya existen y han cambiado en el repositorio. Sin la opción, el script sigue saltándose los que existen.

La API, la base de datos y la web no cambian. Documentación: `docs/workflows.md` (variables de los prompts y `artifacts`) y `docs/arranque-local.md` (sección 2 y tabla de workflows). Ninguna prueba de esta guía modifica ficheros versionados de skynet.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*PromptRendererTest' --tests '*SeedWorkflowsTest' --tests '*DefinitionParserTest'
./gradlew :control-plane:spotlessCheck
bash -n scripts/seed-workflows.sh
```

Comprueban:

- **`PromptRendererTest`:** `{{project.slug}}` da `demo2` para la clave `DEMO2`, y `{{project.key}}` y `{{project.name}}` siguen igual.
- **`SeedWorkflowsTest`:** los seis workflows iniciales, incluidos los tres que cambian, se publican sin problemas, con los modelos por defecto y con todos rellenos. Así se confirma que el validador admite `{{project.slug}}` y el glob de `artifacts`.
- **`DefinitionParserTest`:** el mensaje de variable desconocida lista `project.slug` entre las admitidas.

## 2. Actualizar los workflows iniciales

Con Skynet en marcha y los workflows iniciales ya publicados (por `reset-local.sh` o por `seed-workflows.sh`):

```bash
SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh --actualizar
```

1. La salida dice `✓ … actualizado (vN)` para `conocer-repositorio`, `corregir-bug` e `implementar-tarea`, y `· … ya está al día` para los otros tres. Termina con `3 actualizados, 3 sin cambios, 0 con error`. Si se publicaron con otras variables de modelos, pásalas igual o saldrán más como actualizados.
2. Vuelve a lanzarlo: los seis dicen `ya está al día` y termina con `0 actualizados, 6 sin cambios, 0 con error`.
3. Sin `--actualizar`, los seis dicen `ya existe, no se toca`, como antes.
4. En la web, **Workflows → Conocer un repositorio** tiene una versión nueva publicada. Su YAML tiene `mapa-{{project.slug}}.md` en los prompts y `artifacts: [docs/skynet/mapa-*.md]`.

Si Skynet está recién reseteado con esta rama, el paso 1 ya da `0 actualizados, 6 sin cambios`: está bien.

## 3. Ejecución real

> **No uses el repositorio `skynet`** para esta prueba. Se hace solo con `demo-repo`, del proyecto DEMO. Es la única ejecución con agentes reales de esta guía.

1. Antes de lanzar, anota si ya existe un mapa antiguo: `ls /home/diegobarrioh/code/demo-repo/docs/skynet/`.
2. En la web, abre el proyecto **DEMO** y crea un trabajo, o reutiliza uno que ya exista.
3. Lanza el workflow `conocer-repositorio`, en su versión nueva, desde ese trabajo. Como repositorio, selecciona **exclusivamente `demo-repo`**, el registrado en `/home/diegobarrioh/code/demo-repo`. Comprueba en la cabecera de la ejecución que el repositorio es `demo-repo`.
4. Abre la fase `mapa` y su pestaña **Prompt**: dice `docs/skynet/mapa-demo.md (proyecto <nombre de DEMO>)`, sin ningún `{{` sin sustituir.
5. Cuando termine, en la pestaña **Artefactos**, «Archivos generados» lista `docs/skynet/mapa-demo.md` con `text/markdown`. Se abre formateado y se descarga como `mapa-demo.md`.
6. En la pestaña **Verificación**, el check `artifacts` (`docs/skynet/mapa-*.md`) pasa.
7. En el worktree de la ejecución (su ruta está en el evento `agent.workspace.ready`), `git -C <worktree> status --porcelain docs/skynet` y `git -C <worktree> log -1 --stat` muestran `docs/skynet/mapa-demo.md` creado o cambiado, y **no** un `docs/skynet/mapa.md` nuevo.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
