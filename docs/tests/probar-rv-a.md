# Probar RV-A: revisión real con Antigravity

RV-A ([#134](https://github.com/guilu/skynet/issues/134), parte de [#133](https://github.com/guilu/skynet/issues/133)) hace que `revisar-codigo` termine con los tres CLIs reales:

- **Esquemas:** los tres `findings` de `workflows/revisar-codigo.yaml` llevan `items` (objetos con `file`, `severity` y `recommendation`). Validar un workflow con un `type: array` sin `items` en su `output` o `outputSchema` da un **aviso** (no un error, para que las versiones ya publicadas se sigan leyendo). También se arreglan los `risks` y `files` de `especificar-planificar-implementar`, `implementar-tarea` y la plantilla de la web.
- **Antigravity sin terminal:** `permissionMode: dontAsk` pasa a `agy --dangerously-skip-permissions --sandbox`. `plan` sigue siendo restrictivo. Nunca se genera bypass sin `--sandbox`, tampoco con `bypassPermissions`.
- **Revisor de seguridad:** el agente `seguridad` de `revisar-codigo` usa `permissionMode: dontAsk`.
- **Fail-fast:** un agente cancelado sin `result` conserva sus tokens (Antigravity los manda paso a paso) y su coste es el que estimó el runner (`estimatedCostUsd`).

No hay migraciones ni dependencias nuevas.

> **Para despliegues automáticos:** los pasos 1 y 2 no cambian nada fuera de una carpeta temporal. El paso 3 lanza agentes reales: consume cuota (unos 315k tokens solo Antigravity en la prueba de #133) y tarda varios minutos, así que hazlo a mano.

## 1. Tests

```bash
./gradlew test --no-daemon
(cd web && npm test)
```

Comprueban, entre otras cosas:
- `AntigravityProviderTest`: `dontAsk` → `--dangerously-skip-permissions --sandbox` exacto, y ningún modo da bypass sin sandbox;
- `AntigravityEventParserTest`: en `fixtures/agy/06-cancelled` (sin `result`) los tokens de los pasos 1 y 3 viajan con las herramientas siguientes;
- `DefinitionParserTest`: un array sin `items` (también anidado) da aviso y se puede publicar;
- `RunnerProtocolIT`: un agente cancelado se queda con sus tokens en vivo y con el coste estimado;
- `SeedWorkflowsTest`: los cinco workflows iniciales se publican sin problemas.

## 2. El aviso en el editor

1. **Workflows → Nuevo**, pega:

   ```yaml
   id: prueba-arrays
   name: Prueba arrays
   version: 1
   agents:
     a:
       provider: antigravity
       prompt: hola
       output:
         type: object
         properties:
           findings: {type: array}
   stages:
     - id: a
       type: agent
       agent: a
   ```

2. Sale un aviso en `agents.a.output.properties.findings.items`: «Un `type: array` de `output` necesita `items` con el tipo de sus elementos, p. ej. `items: {type: string}`: sin él Antigravity rechaza el schema». Se puede publicar igual.
3. Cambia la línea a `findings: {type: array, items: {type: string}}`: el aviso desaparece. Descarta el borrador.

## 3. Ejecución real completa de `revisar-codigo` (a mano)

Con el runner real (Claude Code, Codex y Antigravity sin «Simulado» en **Runners**) y el repositorio de Skynet registrado:

1. **Publica la versión nueva de `revisar-codigo`.** En una instalación que ya tenía la v1, ábrela, **Nueva versión**, y copia de `workflows/revisar-codigo.yaml` los tres `findings` con `items` y el `permissionMode: dontAsk` del agente `seguridad` (los `model:` siguen siendo los tuyos). Publica. En una instalación limpia, `scripts/seed-workflows.sh` ya la publica así.
2. **Lanza** `revisar-codigo` sobre un trabajo de Skynet (p. ej. `SELF-1`). Si quieres gastar menos, pon un foco acotado (p. ej. `runner/src/main/java/dev/skynet/runner/provider`).
3. **Qué esperar:**
   - los cuatro agentes (`arquitectura`, `tests`, `seguridad` y `redactor`) terminan en **Completado**, ninguno con la píldora «Simulado»;
   - en el Resumen de `seguridad`, la sesión empieza con `permissionMode` distinto de `plan` y no hay «agy no tiene permiso para…» al leer ficheros;
   - `seguridad` tiene salida estructurada con `summary` y `findings` (lista de objetos con `file`, `severity`, `recommendation`); ninguno acaba en `error_structured_output`;
   - el informe del `redactor` junta los hallazgos de los tres;
   - en **Uso**, los tres proveedores aparecen con sus tokens, su modelo y su cuota.
4. **Worktree limpio:** en el repositorio de Skynet,

   ```bash
   git status --short            # vacío
   git worktree list             # solo los de Skynet, sin cambios
   for w in $(git worktree list --porcelain | awk '/^worktree/{print $2}'); do git -C "$w" status --short; done
   ```

   No sale ningún fichero cambiado.

## 4. Fail-fast sin perder datos (opcional, a mano)

1. Lanza `revisar-codigo` otra vez y, cuando `seguridad` lleve un rato trabajando, cancela `tests` desde su panel (o deja que uno falle).
2. La fase `revisar` se cancela y `seguridad` termina en **Cancelado**.
3. En el Resumen de `seguridad` siguen sus tokens (entrada, salida, caché) y, si tiene `model`, un coste distinto de cero; en **Uso** cuenta en su proveedor.
