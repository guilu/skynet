# Probar ME-C: protocolo de memoria y bitácora

ME-C ([#152](https://github.com/guilu/skynet/issues/152), parte de [#141](https://github.com/guilu/skynet/issues/141)) hace que cada ejecución use la memoria sin que nadie se lo pida.

Qué trae:
- **Bloque de memoria al principio del prompt** de cada sesión nueva:
  - Va entre `<memoria-del-proyecto>` y `</memoria-del-proyecto>`, antes del prompt de la fase.
  - Lleva lo fijado, los últimos resúmenes de sesión, la bitácora y lo último guardado en el repositorio y en todo el proyecto.
  - Tiene un tope de 4 000 caracteres (`SKYNET_MEMORY_CONTEXT_MAX_CHARS`). Si no cabe todo, deja fuera entradas enteras, nunca medias.
  - Incluye las instrucciones de uso de las herramientas. En Claude Code y Codex son las de `write` o `read`; Antigravity recibe el bloque sin instrucciones de herramientas, porque no las tiene.
  - Una continuación (mensaje, reanudación o bifurcación) no lo repite.
- **Clave `memory` en las fases** `agent` y `parallel`:
  - `write`, el valor por defecto, lee y guarda.
  - `read` solo lee: el token es de solo lectura y `mem_save` da 403.
  - `off` no lleva ni bloque ni herramientas, y el token da 409.
  - Un valor desconocido, o la clave en otro tipo de fase, es un error al publicar.
- **Bitácora `RUN_LOG`:**
  - Al terminar una ejecución, bien, mal o cancelada, Skynet deja una observación en cada repositorio en el que trabajó, con el tema `bitacora/<ejecución>`.
  - Contiene el workflow, el trabajo, el estado de cada fase, la rama con sus commits y archivos, la salida estructurada de los agentes, el coste y el enlace `/runs/<id>`.
  - Los commits salen del artefacto `GIT_CHANGES`. Si aún no ha llegado cuando termina la ejecución, la bitácora solo dice la rama.
  - No hay datos de PRs: Skynet aún no las abre (llega con S1).
- **Trazabilidad:**
  - El prompt guardado sigue siendo el de la fase.
  - `agent.spawned` lleva `memory` con el modo y las observaciones (id y versión) del bloque.
  - `memory_access` las anota con `via = PROMPT`.

Hay una migración nueva, la **V29**, que añade `agent_run.memory` y el valor `PROMPT` en `memory_access.via`. Las invocaciones anteriores a ME-C quedan sin memoria. No hay dependencias nuevas ni cambia la exposición de red. El proveedor anuncia una capacidad nueva, `MEMORY_TOOLS`, en Claude Code y Codex.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 y 3 lanzan agentes reales sobre demo-repo (proyecto DEMO), nunca sobre skynet.

## 1. Tests

```bash
./gradlew build --no-daemon
```

- `MemoryProtocolIT`:
  - una ejecución nueva recibe lo fijado (primero) y lo del proyecto, pero no lo de otro repositorio ni lo archivado;
  - el prompt guardado no cambia, y `agent.spawned` y `memory_access` dicen qué recibió;
  - al terminar queda la bitácora en su repositorio y la siguiente ejecución la recibe, pero la de otro repositorio no;
  - una fase `memory: read` recibe el bloque de solo lectura y no puede guardar (403);
  - una fase `memory: off` recibe el prompt tal cual y su token da 409;
  - el tope deja fuera entradas enteras.
- `RunnerProtocolIT`: una continuación recibe el mensaje sin bloque.
- `DefinitionParserTest`: `memory` con `read` y `off` (como texto, no como booleano de YAML), valor por defecto `write`, valor desconocido y clave en una fase `command`.
- Los demás ITs del motor comparan el prompt de la fase sin el bloque (`taskPrompt`).

## 2. Con agentes reales (demo-repo)

Con el control plane y el runner en marcha (`http://localhost:8080`, contraseña de admin en `$SKYNET_ADMIN_PASSWORD`) y el proyecto DEMO con demo-repo:

```bash
api() { curl -s -u "admin:$SKYNET_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
P=$(api localhost:8080/api/projects | jq -r '.[] | select(.key=="DEMO") | .id')
R=$(api localhost:8080/api/projects/$P/repositories | jq -r '.[] | select(.name=="demo-repo") | .id')
```

1. **Una entrada fijada llega al prompt.**

   ```bash
   ID=$(api -X POST localhost:8080/api/projects/$P/memory -d "{\"repositoryId\":\"$R\",\"kind\":\"CONSTRAINT\",
     \"title\":\"No tocar el README\",\"what\":\"El README lo mantiene una persona: no lo cambies.\"}" | jq -r .id)
   api -X POST localhost:8080/api/memory/$ID/pin | jq .pinned   # true
   ```

   Lanza en DEMO una ejecución de un agente de Claude Code con el prompt «Dime qué restricciones tiene este repositorio según la memoria, sin cambiar nada». En los artefactos del agente, el prompt efectivo (`prompt.txt`, de tipo Prompt) empieza por `<memoria-del-proyecto>` y contiene «No tocar el README». La respuesta del agente la cita.

2. **La bitácora.** Al terminar esa ejecución:

   ```bash
   api "localhost:8080/api/projects/$P/memory?kind=RUN_LOG&repository=$R" | jq '.items[0] | {title, topicKey, what}'
   ```

   Sale una entrada `RUN_LOG` con el tema `bitacora/<id de la ejecución>`, el estado de las fases, la rama y el enlace `/runs/<id>`. Si la ejecución hizo commits, aparecen en «Cambios». Lanza otra ejecución en DEMO: su bloque incluye esa bitácora.

3. **`memory: read` y `memory: off`.** Publica este workflow y lánzalo en DEMO, sobre demo-repo:

   ```yaml
   id: probar-memoria
   version: 1
   name: Probar memoria
   stages:
     - id: investigar
       type: agent
       prompt: Busca en la memoria qué se sabe de este repositorio y resúmelo. Intenta guardar con mem_save un descubrimiento con título «prueba de solo lectura».
       memory: read
     - id: aplicar
       type: agent
       prompt: Di si tienes herramientas de memoria. No cambies nada.
       dependsOn: [investigar]
       memory: off
   ```

   - `investigar` recibe el bloque y puede buscar. Su `mem_save` falla con «esta fase solo lee la memoria» y no aparece ninguna entrada «prueba de solo lectura».
   - El `prompt.txt` de `aplicar` es exactamente su prompt, sin bloque, y el agente no tiene herramientas `mcp__skynet_memoria`.

4. **Codex y Antigravity.** Repite el paso 1 con un agente `provider: codex`, que recibe el bloque con las instrucciones de las herramientas. Haz lo mismo con `provider: antigravity`, que recibe el bloque sin instrucciones de herramientas y sigue funcionando.

5. **Error al publicar.** Un borrador con `memory: raed` en una fase da «`memory: raed` no se conoce; ¿quisiste decir `read`?». Con `memory` en una fase `command`, da «`memory` solo vale en fases `agent` y `parallel`».
