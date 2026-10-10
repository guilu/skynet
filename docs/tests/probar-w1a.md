# Probar W1-A en local

W1-A añade las **definiciones de workflow** en el backend (#57): un YAML (§11) que se guarda como borrador, se valida con errores que dicen la línea y la columna, y se publica como una versión que ya no cambia. La web todavía no tiene editor (llega en W1-B) y nada se ejecuta aún con estas definiciones (llega en W2), así que esta guía usa `curl`.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

Hay una migración nueva, `V12__workflow_definitions.sql`: crea la tabla `workflow`, añade estado, nombre, fechas y revisión a `workflow_definition`, y un trigger que impide tocar una versión publicada. También reescribe el YAML de `adhoc` v1 para que pida el prompt como un dato (`inputs.prompt`); las ejecuciones existentes no cambian. La única dependencia nueva es SnakeYAML, que ya venía con Spring Boot.

Los ejemplos usan `admin:secreto`; cámbialo por tu usuario y tu `SKYNET_ADMIN_PASSWORD`:

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

Guarda este workflow como `revisar.yaml`. Tiene un error a propósito en la línea 15 (`reviw`):

```yaml
id: revisar
version: 1
name: Revisar y corregir
agents:
  reviewer:
    prompt: Revisa {{workItem.title}}
    tools: [Read, Grep]
stages:
  - id: review
    type: agent
    agent: reviewer
  - id: fix
    type: agent
    prompt: Corrige lo que encontró la revisión
    dependsOn: [reviw]
```

## 2. Qué mirar

1. **adhoc ya está publicado.** `api $B/workflows | jq` devuelve solo `adhoc`, «Agente suelto», con `published.version` 1 y sin `draft`.
2. **Validar sin guardar.**

   ```bash
   jq -Rs '{sourceYaml: .}' revisar.yaml | api -X POST $B/workflows/validate -d @- | jq .validation
   ```

   - `valid` y `publishable` son `false`.
   - El problema dice «No hay ninguna fase `reviw`; ¿quisiste decir `review`?», en la línea 15, columna 17.
   - `api $B/workflows` sigue con solo `adhoc`.
3. **Guardar el borrador con errores.**

   ```bash
   jq -Rs '{sourceYaml: .}' revisar.yaml | api -X POST $B/workflows -d @- | tee v1.json | jq '{id, version, status, revision}'
   ```

   - Se crea con `version` 1 y `status` `DRAFT`.
   - Publicarlo responde 409 «No se puede publicar un borrador con errores», con la lista en `problems`:

     ```bash
     api -X POST $B/workflow-versions/$(jq -r .id v1.json)/publish -d '{"revision": 0}' | jq
     ```
4. **Corregir y publicar.** Cambia `reviw` por `review` en el fichero y guarda:

   ```bash
   ID=$(jq -r .id v1.json)
   jq -Rs '{sourceYaml: ., revision: 0}' revisar.yaml | api -X PUT $B/workflow-versions/$ID -d @- | jq '{status, revision}'
   api -X POST $B/workflow-versions/$ID/publish -d '{"revision": 1}' | jq '{status, publishedAt}'
   ```

   - Al guardar pasa a `VALIDATED` y `revision` 1. Repetir el guardado con `revision: 0` responde 409 («ha cambiado desde que lo abriste»).
   - Al publicar queda `PUBLISHED`.
   - Volver a guardarlo, o borrarlo con `api -X DELETE $B/workflow-versions/$ID`, responde 409.
   - En **Actividad** aparecen `workflow.definition.created` y `workflow.definition.published` (este con el `sha256` del YAML).
   - La página **Workflows** de la web ya lo muestra junto a `adhoc` (solo enseña versiones publicadas).
5. **Una versión nueva.** `api -X POST $B/workflows/revisar/draft | jq '{version, status, sourceYaml}'`
   - Abre la versión 2 con el YAML de la 1 y `version: 2` ya cambiado.
   - Pedirlo otra vez devuelve el mismo borrador.
   - `api $B/workflows/revisar | jq '.versions[] | {version, status}'` lista la 2 (borrador) y la 1 (publicada).
   - Si cambias `id: revisar` por otro, el borrador se guarda como `DRAFT` con «El id de un workflow no se puede cambiar».
   - `api -X DELETE $B/workflow-versions/<id de la 2>` lo descarta y deja solo la 1.
6. **Lo que llega más tarde.** Valida el ejemplo de §11 de la especificación (con una sección `agents` para analyst, architect y developer):
   - `valid` es `true` y `publishable` es `false`.
   - Los problemas `UNSUPPORTED` dicen en qué hito llega cada cosa: «El tipo `human-approval` todavía no se ejecuta: llega en W5», «`retry` todavía no se usa: llega en W6», etc.
7. **Otros errores que conviene ver.** Prueba a validar:
   - un ciclo (`a` depende de `c`, `c` de `b` y `b` de `a`): «Las dependencias forman un ciclo: a → c → b → a»;
   - una clave mal escrita (`dependOn`): «¿quisiste decir `dependsOn`?»;
   - una variable que no existe en un prompt (`{{inputs.isue}}`): señala la línea y la columna exactas dentro del bloque `|`;
   - una fase que depende de dos fases con agente sin `workspaceFrom`: pide indicar cuál continúa o poner `workspace: isolated-worktree`.
8. **Archivar.** `api -X POST $B/workflows/revisar/archive`
   - Sale de `api $B/workflows` y aparece con `?archived=true`.
   - Su borrador ya no se puede guardar ni publicar (409) hasta `api -X POST $B/workflows/revisar/restore`.
   - `adhoc` no se archiva (409).

El formato completo del YAML está en [`docs/workflows.md`](../workflows.md).

## 3. Comprobaciones automáticas

```bash
./gradlew build
```

- `DefinitionParserTest` cubre cada error de validación con su mensaje y su posición, el ejemplo de §11 y el cambio de versión.
- `SchemaConsistencyTest` comprueba que el JSON Schema de `protocol` (el que usará el editor de W1-B) admite las mismas claves y tipos que el parser.
- `WorkflowDefinitionsIT` recorre la API: borrador con errores, revisión, publicación, inmutabilidad (también por SQL), versión nueva, descartar, archivar y eventos.

Los IT de Java también se pueden pasar en local, sin Docker, contra un PostgreSQL vacío:

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_it ./gradlew :control-plane:test
```
