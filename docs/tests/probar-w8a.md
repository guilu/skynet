# Probar W8-A en local

W8-A (#124) es la base del editor visual de workflows:

- **Problemas en el grafo.** En la pestaña **Grafo** de un workflow, cada fase con problemas lleva una insignia («1 error», «2 avisos»). Una dependencia que no existe sale como fase fantasma y las flechas de un ciclo van en rojo.
- **Motor de edición.** Cambia el YAML desde el grafo sin perder comentarios ni formato. En esta PR todavía no tiene botones: lo usará el grafo editable de W8-B, y aquí se prueba con sus tests.

Solo cambia la web: no hay migraciones ni cambios en el control plane ni en el runner.

## 1. Arrancar

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm install && npm run dev
```

`npm install` hace falta una vez: añade `yaml` como dependencia directa. No hace falta runner.

## 2. Un grafo roto

1. **Workflows → Nuevo workflow**. Pega este YAML en el editor:

   ```yaml
   id: grafo-roto
   name: Grafo roto

   stages:
     - id: plan
       type: agent
       prompt: Planifica
       dependsOn: [spec]
     - id: build
       type: agent
       prompt: Implementa
       dependsOn: [plan, review]
     - id: review
       type: agent
       prompt: Revisa
       dependsOn: [build]
   ```

2. **Problemas** lista los errores de siempre: `spec` no existe, el ciclo y `build` depende de varias fases con worktree.
3. Abre la pestaña **Grafo**:
   - Sale una caja **`spec`** discontinua, en rojo claro, con «No existe». La flecha de `spec` a `plan` es discontinua y roja.
   - `plan` lleva **«1 error»** con un círculo y el borde rojo. Al pasar el ratón por la insignia se lee «No hay ninguna fase `spec`».
   - Las dos flechas entre `build` y `review` (el ciclo) van en **rojo**.
   - `build` y `review` llevan su insignia con sus errores.
4. Vuelve a **YAML** y corrige `dependsOn: [spec]` por `dependsOn: []`. Pasado un momento, en **Grafo** desaparecen la fase fantasma y la insignia de `plan`.
5. Quita `review` de `dependsOn` de `build`. Las flechas dejan de ser rojas y las insignias desaparecen.

## 3. Avisos y lo que aún no se ejecuta

1. Plantilla **Revisión con Codex y Antigravity** y pestaña **Grafo**: sin insignias («Sin problemas»).
2. En el YAML, añade a una fase `promptTemplate: x`. En **Grafo**, esa fase lleva **«1 aún no se ejecuta»** en ámbar, sin borde rojo.
3. Una versión publicada (`?version=…`) dibuja sus problemas igual, de solo lectura.

## 4. Lo de siempre sigue igual

- Un workflow sin problemas se ve igual que antes de esta PR: sin insignias ni fantasmas.
- Con el YAML ilegible (borra los dos puntos de `stages:`), el grafo sigue diciendo «El YAML no se puede leer: corrige los problemas para ver el grafo.».

## 5. Tests automáticos

```bash
cd web && npx vitest run src/components/workflow/definitionEdits.test.ts src/components/graph
```

- `definitionEdits.test.ts` comprueba que cada operación (añadir, borrar, renombrar, unir, opcional, campos e hijos) conserva los comentarios y el estilo de las listas. También comprueba que renombrar una fase de cada plantilla y deshacerlo devuelve el texto exacto.
- La E2E `workflows.e2e.ts` incluye el grafo roto, con axe, y deja la captura `build/e2e/w8a-problemas.png`.
