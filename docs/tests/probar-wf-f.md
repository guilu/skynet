# Probar WF-F - Editar un workflow por pasos

WF-F (#318) es la sexta PR del hito WF («Workflows que se entienden»). Solo toca la web: un borrador se edita sobre el paso a paso de WF-E, sin tener que escribir YAML.

Qué cambia:

- **Modos Pasos, Grafo y YAML.** Un borrador (y el alta de un workflow) abre en «Pasos» en escritorio. En una pantalla estrecha sigue abriendo en el YAML y «Pasos» es de solo lectura: el editor en el móvil llega en WF-G. Se recuerdan en la URL (`?view=graph`, `?view=yaml`). La plantilla «Desde cero» sigue abriendo el grafo.
- **Barra fija** arriba, que no se va al bajar: el modo, Deshacer y Rehacer, cómo está (Sin problemas, «1 error», «1 aún no se ejecuta»…) y Guardar, Publicar, Importar y Descartar. Deshacer y Rehacer salen de la barra en los tres modos (antes estaban dentro del grafo). Ctrl/Cmd+Z y Ctrl/Cmd+Mayús+Z también funcionan en «Pasos», fuera de los campos.
- **Pasos**:
  - cada paso de WF-E, pinchable: abre a la derecha el mismo panel que el grafo (id, nombre, agente, prompt, condición, etapa, worktree, intentos, comprobaciones, dependencias…);
  - **«Añadir paso»** debajo de cada uno, con una paleta que explica cada tipo: Agente, Juez, Tu aprobación, Varios a la vez, Comando y **Entrega por PR** (abre la PR, espera la CI, apruebas y fusiona: 4 pasos, con la CI corregida por el último agente). El paso nuevo va detrás, en su etapa, y si solo un paso seguía al de arriba, ahora sigue al nuevo;
  - **mover** con Alt+↑/↓ o con «Subir» y «Bajar» de su menú «…». Solo cuando equivale a cambiar el orden: si los dos pasos no dependen uno del otro, o si van en una sola línea (se intercambian sus dependencias). Si no, dice por qué (otras fases dependen de él, continúa su worktree, usa lo que devuelve el otro…);
  - el menú «…» también tiene «Ver en YAML» y «Borrar paso…»;
  - los botones «Datos del workflow», «Agentes N» y «Datos al lanzar N» abren sus paneles.
- **Problemas en su paso**: cada paso lleva su «1 error» y, arriba, un aviso con el primer error, su paso y su línea, «Ir al paso» y «Ver en YAML». Cuando el control plane sugiere otro nombre («No hay ninguna fase `reviw`; ¿quisiste decir `review`?», también agentes y claves), **«Cambiar a «review»»** lo arregla en un clic.

Fuera de esta PR, para no hacerla enorme: arrastrar los pasos con el ratón (se mueven con el teclado y el menú), el panel por secciones del boceto con interruptores y el editor de criterios del juez (el panel es el del grafo), «Insertar dato» en el prompt, y en el YAML las sugerencias de fases y agentes y la vista previa del paso del cursor. El modo sigue llamándose «Grafo» y no «Flujo», como en la ficha publicada.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/components/workflow/stepEdits.test.ts src/components/workflow/StepsEditor.test.tsx src/Workflows.test.tsx
```

- `stepEdits.test.ts`: añadir un paso entre dos (dependencias y etapa), el juez, la entrega por PR con sus 4 pasos, no romper un `workspaceFrom`; mover en una línea, solo el orden, y los motivos cuando no se puede; el arreglo «¿quisiste decir…?».
- `StepsEditor.test.tsx`: elegir un paso abre su panel; el error en su paso y arreglarlo en un clic; Alt+↑ y «Ya es el primer paso».
- `Workflows.test.tsx`: un borrador abre en «Pasos».

E2E nueva en `workflows.e2e.ts` («editar por pasos…»): arregla un error con «Cambiar a…», añade «Tu aprobación» entre dos pasos y la completa, sube un paso con Alt+↑ y lo devuelve con «Deshacer», con axe. Las E2E de workflows, spec, nodos y proveedores leen los pasos en el alta.

## 2. Escritorio

Con el entorno local y los workflows sembrados:

1. Abre <http://localhost:5173/workflows/especificar-planificar-implementar>, pulsa «Editar». Sale «Pasos», con la barra arriba; baja la página: la barra se queda.
2. Pincha «Planifica las tareas»: se marca y a la derecha sale su panel. Cambia el nombre: el YAML cambia (míralo en la pestaña YAML) y «Deshacer» lo devuelve.
3. «Añadir paso» debajo de «Implementa el plan» → «Comando». Sale «comando» detrás, en la etapa de implementar, y «Juzga contra la propuesta» pasa a depender de él. La barra dice «1 error» (le falta el comando) y el paso nuevo lo lleva; escríbelo en el panel y desaparece. Deshaz.
4. Con el foco en el título de «Apruebas el plan» (Tab hasta él), pulsa Alt+↑: no se mueve y dice «“Apruebas el plan” usa lo que devuelve “Planifica las tareas”: no puede ir antes». En «Implementa el plan», Alt+↑ sí: pasa delante de la aprobación y las dependencias se intercambian (míralo en el Grafo). «Deshacer» lo devuelve.
5. En el YAML, cambia `dependsOn: [implementar]` de `juzgar` por `[implementa]` y vuelve a «Pasos»: el aviso rojo dice «No se puede publicar: 1 error», el paso y la línea. «Cambiar a «implementar»» lo arregla.
6. En un workflow sin entrega (`implementar-tarea` sin las fases de GitHub, o «Desde cero»), «Añadir paso» → «Entrega por PR» añade Abre la PR, Espera la CI, Apruebas fusionar y Fusiona la PR en la etapa Entrega.
7. Descarta el borrador al terminar.

## 3. Tema oscuro y móvil

1. Con el tema oscuro, repite 1 y 5: el aviso rojo, la barra y el paso elegido se leen bien.
2. En el móvil (o la ventana a menos de 720 px), «Editar» abre el YAML; «Pasos» enseña el resumen y dice que en una pantalla estrecha se edita en el YAML.
