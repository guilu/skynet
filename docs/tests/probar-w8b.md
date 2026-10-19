# Probar W8-B en local

W8-B (#125) hace que la pestaña **Grafo** de un borrador se pueda editar:

- **Paleta** para añadir fases de los seis tipos que se ejecutan.
- **Unir** fases arrastrando una flecha; un ciclo se rechaza con su camino.
- **Elegir una flecha** para hacerla opcional o quitarla.
- **Panel de propiedades** de la fase elegida, con sus problemas debajo de cada campo.
- **Borrar** una fase con confirmación.
- **Deshacer y rehacer** compartidos con el YAML.

Cada cambio reescribe el YAML de la pestaña de al lado sin perder comentarios ni formato. Solo cambia la web: no hay migraciones ni cambios en el control plane ni en el runner.

## 1. Arrancar

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev
```

No hace falta runner. Prueba con la ventana a más de 1100 px de ancho para ver el panel al lado del grafo.

## 2. Un borrador para editar

1. **Workflows → Nuevo workflow**. Pega este YAML y pulsa **Crear borrador**:

   ```yaml
   id: probar-w8b
   name: Probar W8-B

   agents:
     reviewer:
       prompt: Revisa {{workItem.key}}.

   stages:
     - id: review   # la revisión
       type: agent
       agent: reviewer
     - id: fix
       type: agent
       prompt: Corrige lo que encontró la revisión.
       dependsOn: [review]
   ```

2. En el borrador, abre la pestaña **Grafo**. Encima del grafo están **Añadir fase**, **Deshacer** y **Rehacer**, y a la derecha el panel **Propiedades**: «Elige una fase o una flecha…».

## 3. Añadir y completar una fase

1. Pulsa la caja **fix**: queda marcada en azul y el panel pasa a **Fase fix**, con su id, nombre, agente, prompt, etc.
2. En **Añadir fase** (dice «Detrás de fix…») elige **Comando (command)**:
   - Aparece la caja **command** detrás de `fix`, con **«1 error»** (falta el comando). El grafo se reajusta para que se vea.
   - El panel pasa a **Fase command**.
3. Escribe `./gradlew test` en **Comando** y pulsa Intro. Pasado un momento, la caja dice `./gradlew test` y la insignia desaparece.
4. Vuelve a **YAML**: la fase nueva está al final, con `dependsOn: [fix]`, y el comentario `# la revisión` sigue en su sitio.

## 4. Unir arrastrando y elegir el worktree

1. En **Grafo**, arrastra desde el punto azul del borde derecho de **review** hasta el punto izquierdo de **command**. Aparece la flecha y `command` lleva **«1 error»**: depende de dos fases con worktree.
2. En el panel de **command**, en **Continúa el worktree de**, elige `fix`. El error desaparece.
3. Arrastra desde **command** hasta **review**: no cambia nada y arriba sale en rojo «Así se formaría un ciclo: review → command → …» con el camino entero.

## 5. Flechas, deshacer y borrar

1. Pulsa la flecha de `review` a `command`, justo al salir de `review`. El panel dice «command depende de review». Marca **Opcional**: la flecha pasa a discontinua y en el YAML queda `"review?"`.
2. Pulsa **Deshacer**: la flecha vuelve a ser continua. **Rehacer**: otra vez opcional. Ctrl+Z y Ctrl+Mayús+Z hacen lo mismo con el foco en el grafo, y en la pestaña YAML deshacen también los cambios hechos desde el grafo.
3. Pulsa **review** y cambia su **Id** a `revision` (Intro). En el YAML, `fix` y `command` ahora dependen de `revision`.
4. Pulsa **Borrar fase…** en el panel de `revision`. El diálogo dice «Dependen de ella fix, command». Al confirmar, `revision` desaparece del grafo y de los `dependsOn`.
5. **Guardar**: «Guardado el …». Recarga la página: **Qué define** enseña las fases tal como quedaron.

## 6. Problemas en el panel

1. En el YAML, pon `agent: nadie` en `fix` y vuelve a **Grafo**.
2. Pulsa **fix**: el campo **Agente** muestra «nadie (no existe)» y, debajo, el error en rojo. En **Sus problemas** está el mismo error: al pulsarlo se abre la pestaña YAML en su línea.
3. **Editar en YAML** lleva a la línea de la fase.

## 7. Teclado, móvil y solo lectura

- Con Tab se llega a **Añadir fase**, a cada caja (botón) y a los campos del panel. Desde el panel se añaden, marcan como opcionales y quitan dependencias sin ratón.
- Con la ventana por debajo de 720 px, el grafo dice «En una pantalla estrecha el grafo es de solo lectura: edita en la pestaña YAML.».
- En una versión publicada (`?version=1` tras publicar) el grafo sigue siendo de solo lectura: sin paleta ni panel.

## 8. Tests automáticos

```bash
cd web && npx vitest run src/components/workflow src/components/graph
```

- `GraphEditor.test.tsx`: añadir desde la paleta, renombrar, ciclo rechazado, dependencias desde el panel, borrar con confirmación y problemas por campo.
- `definitionEdits.test.ts`: las lecturas nuevas del motor (fase, agentes, hijos y línea), los intentos de `retry` y las comprobaciones.
- La E2E `workflows.e2e.ts` añade una fase, la une arrastrando, completa su panel, deshace y rehace, hace opcional una flecha, pasa axe y guarda. Deja las capturas `build/e2e/w8b-editor.png` y `build/e2e/w8b-pagina.png`.
