# Probar WF-G - Editar workflows en móvil y aceptación

WF-G (#319) es la última PR del hito WF («Workflows que se entienden»). Solo toca la web: en el móvil se edita por pasos, y una E2E de aceptación recorre el hito en escritorio y en el móvil con axe.

Qué cambia:

- **Pasos en el móvil.** Un borrador (y el alta) abre en «Pasos» también en una pantalla estrecha; se acaba el «solo lectura por debajo de 720 px» de los pasos.
  - Cada paso es una fila (número, título, quién lo hace, su «1 error») que abre una hoja con su panel, «Subir», «Bajar», «Borrar paso…» y «Hecho».
  - «Añadir paso», al final de cada etapa, abre una hoja inferior con cada tipo explicado (también «Entrega por PR»).
  - «Datos del workflow», «Agentes» y «Datos al lanzar» se abren en una hoja.
  - El aviso de errores con «Cambiar a…», «Ir al paso» (abre su hoja) y «Ver en YAML».
- **Guardar y Publicar fijos abajo**, encima de la barra de navegación; Importar y Descartar se quedan arriba. En escritorio no cambia nada.
- Objetivos de 44 px en el móvil: filas, «Añadir paso», los botones del aviso y los de la hoja.
- **E2E de aceptación** (`e2e/wf-aceptacion.e2e.ts`), en escritorio y en el móvil (390 × 844, táctil): tarjeta, resumen, editar el nombre de un paso (en su panel o en su hoja), guardar y publicar la v2, con axe en cada pantalla. Deja capturas `wf-g-*.png` en la carpeta de salida de las E2E.
- **Galería visual**: «Workflow: resumen» se captura en «Resumen», «Workflow: YAML» con `?view=yaml` y «Workflow: editor visual» en «Pasos».

Sigue igual: el grafo en el móvil es de solo lectura, y el YAML en un móvil es el campo de texto de siempre (de solo lectura en una versión publicada).

Fuera: las capturas de `docs/screenshots` no se regeneran en esta PR. La galería necesita todo el entorno (base de datos, control plane, datos de demostración) y ya estaba desfasada respecto a su mapa; se rehace con la Action **Galería visual** (etiqueta `galeria-visual` en la PR o «Run workflow»), que deja las capturas como artifact. Tampoco entran los interruptores y «Insertar dato» del boceto, aplazados en WF-F.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/components/workflow/StepsEditor.test.tsx visual
```

- `StepsEditor.test.tsx`: en el móvil, la fila lleva su error, abre su hoja, «Subir» mueve, «Hecho» cierra, y «Añadir paso» → «Comando» añade el paso y abre su hoja.
- `visual`: el mapa de la galería sigue siendo coherente con las rutas nuevas.

La E2E `wf-aceptacion.e2e.ts` corre en CI con las rápidas.

## 2. Móvil

Con el entorno local y los workflows sembrados, en el móvil o con la ventana a 390 px de ancho:

1. Abre <http://localhost:5173/workflows/especificar-planificar-implementar> y pulsa «Editar». Sale «Pasos»: filas por etapa y, abajo, Guardar y Publicar fijos sobre la barra de navegación.
2. Toca «2. Planifica las tareas»: se abre su hoja. Cambia el nombre y pulsa Intro; «Hecho» cierra la hoja y la fila ya lleva el nombre nuevo.
3. Vuelve a abrirla y toca «Subir»: no se mueve y dice por qué (usa lo que devuelve «Especifica la propuesta»).
4. «Añadir paso» al final de «Implementar» → «Comando»: el paso nuevo aparece y se abre su hoja; la barra dice «1 error» y la fila lo lleva. Escribe el comando y el error desaparece.
5. En el YAML cambia `dependsOn: [implementar]` de `juzgar` por `[implementa]`. En «Pasos», el aviso rojo ofrece «Cambiar a «implementar»» y lo arregla.
6. Descarta el borrador al terminar (arriba, «Descartar borrador…»).

## 3. Escritorio y tema oscuro

1. En escritorio, «Editar» sigue como en WF-F: los pasos con el panel a la derecha y la barra fija con Guardar y Publicar.
2. Con el tema oscuro, repite 1, 2 y 4 del móvil: las filas, las hojas y la barra de abajo se leen bien.
