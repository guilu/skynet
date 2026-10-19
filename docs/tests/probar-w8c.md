# Probar W8-C en local

W8-C (#126) cierra el editor visual de workflows:

- **Paneles del workflow.** Encima del grafo hay tres botones nuevos, **Workflow**, **Agentes** y **Entradas**, que abren su panel para editar el nombre del workflow, sus agentes con nombre y los datos que se piden al lanzar.
- **Desde cero.** En el alta, la plantilla **Desde cero** abre la pestaña Grafo para montar el workflow sin escribir YAML.
- **Aceptación.** Una E2E monta un workflow en el grafo, lo publica y lo ejecuta, hace lo mismo con su v2 y comprueba que la primera ejecución no cambia.

Solo cambia la web: no hay migraciones ni cambios en el control plane ni en el runner.

## 1. Arrancar

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev
```

Para el paso 4 (ejecutar) hace falta un runner, con CLI real o simulado. Prueba con la ventana a más de 1100 px de ancho.

## 2. Montar un workflow sin escribir YAML

1. **Workflows → Nuevo workflow**. En **Plantilla** elige **Desde cero**: se abre la pestaña **Grafo** con una sola fase, `agent`, que lleva «1 error» porque no tiene prompt.
2. Pulsa **Workflow**:
   - Cambia **Id** a `probar-w8c` y pulsa Intro.
   - Cambia **Nombre** a `Probar W8-C`.
   - En la pestaña YAML, `name:` queda justo debajo de `id:`.
3. Pulsa **Entradas** (antes dice «Sin entradas: al lanzar no se pide nada.»):
   - **Añadir entrada** crea `dato`. Renómbrala a `tema` en **Nombre de dato**.
   - Marca **Obligatoria**.
   - Cambia **Tipo** a número y escribe `3` en **Valor por defecto**: en el YAML queda `default: 3`, sin comillas.
   - Vuelve a **Texto corto (string)** y borra el valor por defecto.
4. Pulsa **Agentes**:
   - **Añadir agente** crea `agente`. Renómbralo a `autor`.
   - **Prompt**: `Escribe sobre {{inputs.tema}}.`
   - **Proveedor**: Codex. **Modelo**: `gpt-5.5`.
   - **Herramientas**: `Read, Bash(git:*)`. **Turnos máximos**: `10`.
   - En el YAML: `tools: [Read, Bash(git:*)]` y `limits: {maxTurns: 10}`, o en bloque. Con Codex, la lista de problemas avisa de que `tools` y `maxTurns` se ignoran.
   - Vuelve a poner **Proveedor** en Claude Code.
5. Pulsa la caja **agent**:
   - En **Agente** elige `autor`.
   - **Prompt**: `Primera versión sobre {{inputs.tema}}.`
   - **Id**: `escribir`.
   - **Problemas** dice «Sin problemas».
6. Vuelve a **Entradas** y renombra `tema` a `asunto`. En el YAML, los dos prompts pasan a `{{inputs.asunto}}`. Deshaz con **Deshacer**.
7. En **Agentes**, pulsa **Quitar autor**: arriba sale en rojo «No se puede quitar `autor`: lo usa escribir», y no cambia nada.
8. **Crear borrador**. En el borrador, el panel **Workflow** ya no deja cambiar el id: dice «Id `probar-w8c`: no cambia entre versiones.».

## 3. Publicar, ejecutar y editar la v2

1. **Publicar…** → **Publicar v1**.
2. En un trabajo, **Lanzar workflow**. Elige `probar-w8c`, rellena **Tema** y lanza. Espera a «Completada»: una sola fase, «1 de 1 fase completada».
3. Vuelve al workflow y pulsa **Editar** (borrador v2) → pestaña **Grafo**.
4. Pulsa **escribir** y en **Añadir fase** elige **Agente**. En su panel:
   - **Prompt**: `Repasa lo escrito sobre {{inputs.tema}}.`
   - **Id**: `repasar`.
5. **Guardar**, **Publicar…** → **Publicar v2**, y lánzalo otra vez desde el trabajo: dos fases, «2 de 2 fases completadas».
6. Abre la **primera** ejecución: sigue diciendo **v1** en sus detalles y «1 de 1 fase completada».

## 4. Tema oscuro y teclado

- Con el sistema en modo oscuro, los paneles y el botón elegido (borde y texto en azul) se leen bien.
- Con Tab se llega a los tres botones nuevos y a cada campo de sus paneles. Los agentes y las entradas son desplegables: Intro o Espacio los abre.

## 5. Tests automáticos

```bash
cd web && npx vitest run src/components/workflow
```

- `definitionEdits.test.ts`: agentes (añadir, quitar solo si nadie lo usa, renombrar con sus usos, campos, herramientas y límites), entradas (añadir, renombrar con sus usos, quitar, valor por defecto con su tipo) e id, nombre y descripción del workflow.
- `GraphEditor.test.tsx`: los tres paneles desde el grafo.
- La E2E nueva `editor.e2e.ts` hace el recorrido de los pasos 2 y 3 y pasa axe en claro y oscuro. Deja la captura `build/e2e/w8c-agentes.png`.
