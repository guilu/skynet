# Probar WF-D - YAML legible

WF-D (#316) es la cuarta PR del hito WF («Workflows que se entienden»). Solo toca la web: el YAML de un workflow deja de parecer «sin tema».

Qué cambia:

- **Colores propios del código.** Tokens `--code-*` en `index.css`, en claro y en oscuro: fondo, número de línea, clave, valor, referencia, variable (con su fondo), literal, comentario y puntuación. Antes la clave y el valor eran casi el mismo azul oscuro; ahora la clave es azul y el valor, tierra.
- **Tokenizador propio** (`web/src/lib/yamlTokens.ts`), el mismo para Monaco y para el móvil:
  - las **referencias** a fases y agentes (`agent`, `dependsOn` en línea o en lista, `fix`, `revise`, `workspaceFrom`…) salen en morado y subrayadas con puntos;
  - las **variables** `{{…}}` salen en rosa, también dentro de los `prompt: |`;
  - los números y `true`/`false`/`null`, en verde; los comentarios, en cursiva.
- **Sin resaltado de ocurrencias** ni de la selección repetida, y sin corchetes de colores: se confundían con la selección y con las variables.
- **Barra sobre el YAML**: leyenda de colores, «Ajustar líneas», «Copiar» y «Descargar» (`<clave>-v<versión>.yaml`).
- **Móvil, solo lectura**: el YAML va en un `<pre>` con el mismo resaltado, números de línea y las líneas ajustadas de entrada, sin Monaco. El borrador editable en el móvil sigue en el campo de texto de siempre.

La ayuda al pasar por encima de cada clave ya la daba el esquema (monaco-yaml) y no cambia. El índice de agentes y fases para saltar se deja para WF-E, con el detalle «Resumen».

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/lib/yamlTokens.test.ts
```

`yamlTokens.test.ts` comprueba:

- claves, valores, literales y comentarios al final de la línea;
- referencias en `agent`, `workspaceFrom` y `dependsOn` (en línea y en lista), y que la lista de `checks` que viene después ya no es de referencias;
- variables dentro de un bloque `|` y la salida del bloque al bajar la sangría;
- que no se pierde ni cambia ningún carácter;
- que cada token `--code-*` llega a 4,5:1 sobre `--code-bg` (y la variable sobre su fondo) en el tema claro y en los dos bloques del oscuro;
- que el tono de la clave y el del valor se separan más de 90°.

## 2. Escritorio

Con el entorno local y los workflows sembrados, abre <http://localhost:5173/workflows/corregir-bug>.

1. En la pestaña YAML: `id:`, `name:`… en azul y sus valores en tierra; `{{workItem.key}}` en rosa; en las fases, `agent: desarrollador` y `dependsOn: [reproducir, "sin-reproducir?"]` en morado con subrayado de puntos.
2. Haz doble clic en una palabra: no se marcan sus otras apariciones.
3. «Ajustar líneas»: los `description` largos se parten en varias líneas. Otra vez: vuelven a una.
4. «Copiar» dice «Copiado» un momento; pega en otro sitio y comprueba que es el YAML entero.
5. «Descargar» baja `corregir-bug-v1.yaml` (o la versión que estés viendo).
6. Cambia a tema oscuro (Ajustes › Apariencia) con el editor abierto: los colores cambian sin recargar y se leen bien.
7. En un borrador, escribe: el resaltado sigue al texto (una clave nueva sale en azul en cuanto lleva `:`).

## 3. Móvil

En un teléfono (o con las herramientas de desarrollo en modo táctil, 390 px):

1. Abre la versión publicada de `corregir-bug`: el YAML sale en un bloque con números de línea, los mismos colores y las líneas ajustadas.
2. Se puede seleccionar y copiar texto; los números de línea no se copian.
3. «Ajustar líneas» lo desactiva: el bloque se desplaza en horizontal sin mover la página.
4. Un problema de la lista lleva a su línea dentro del bloque.
5. Abre un borrador: se edita en el campo de texto de siempre, con «Ajustar líneas» también.
