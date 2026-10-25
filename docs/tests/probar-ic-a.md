# Probar IC-A - Paquetes de iconos y Ajustes › Iconos

IC-A ([#166](https://github.com/guilu/skynet/issues/166)) convierte los iconos en **paquetes** que se eligen en Ajustes, igual que los colores. Hay tres:

- **Lucide**, por defecto: los de siempre. Nada cambia hasta que alguien elige otro.
- **Ilustrados**: formas lisas y de colores, como la mascota, con un gesto propio al pasar el ratón. Plan y boceto: `plans/estudio-iconos.md` y `plans/ic-boceto/`.
- **ChatGPT**: los de las láminas que diseñó ChatGPT, redibujados en SVG, con volumen y los colores propios de cada objeto. Tienen dibujo todos los iconos.

Qué trae:
- **Servidor:** `GET/PUT /api/settings/appearance` añade `icons` (`lucide`, `ilustrados` o `chatgpt`). Se guarda en `app_setting` con su propia clave, sin migración.
- **Ajustes** pasa a tener pestañas: **Colores**, **Iconos** y **Companion**. En Iconos hay una tarjeta por paquete con su vista previa y una galería con todos los iconos.
- Con `<Icon name=…>` se pintan la barra lateral, la barra inferior del móvil, la barra superior, los menús de tema, usuario y mascota, la campana, las migas, ⌘K, las píldoras de estado, las pestañas, la lista «Necesita tu atención» y la insignia grande de la ejecución.
- El resto de la web sigue con Lucide hasta IC-B (acciones y controles) e IC-C (dominio).
- **Rediseño ligero** (boceto de ChatGPT): textos más pequeños y en grises, sin negritas pesadas ni mayúsculas, paneles planos con borde fino y sin sombra, y más aire. El dashboard empieza por el **Resumen** del periodo, sigue con lo que necesita atención y el gráfico, y termina con las **Ejecuciones recientes** y los runners.
- **Documentación:** [`docs/ui.md`](../ui.md), secciones 1 a 3 y 12.

> **Para despliegues automáticos:** los pasos 1 y 2 no tocan nada fuera del repositorio. Los pasos 3 a 8 usan la web del control plane local, y al final hay que volver a Lucide (paso 7). No hace falta lanzar ejecuciones reales; si se lanza alguna, sobre **demo-repo (proyecto DEMO), nunca sobre skynet**.

## 1. Tests

```bash
cd web && npm run lint && npm run format:check && npm run typecheck && npx vitest run
./gradlew :control-plane:test --tests '*AppearanceIT' --tests '*SecurityIT'
./scripts/e2e.sh e2e/review.e2e.ts
```

- **`Icon.test.tsx`:** todos los nombres se pintan en los tres paquetes y son decorativos. ChatGPT tiene dibujo para todos, sus degradados se ponen una sola vez y cada `url(#cg-…)` existe. Ilustrados usa su dibujo y, si falta, el de Lucide. La variante sale del tamaño (ilustrado, compacto o trazo) y las insignias toman el tono que se les da. `ilustrados.css` solo anima `transform`, `opacity` y el trazo que se dibuja, y con movimiento reducido no se mueve nada.
- **`SettingsPage.test.tsx`:** elegir Ilustrados y guardarlo cambia los iconos de la barra lateral y lo recuerda en el navegador. La galería avisa de los iconos sin dibujo, y `#companion` abre la pestaña del companion.
- **`AppearanceIT`:** el paquete por defecto es Lucide, se guardan Ilustrados y ChatGPT sin tocar la paleta (y al revés), y un paquete desconocido da 400.
- **`App.test.tsx`:** el dashboard enseña el Resumen del periodo con «Ver más detalles», el gráfico con su tabla y las Ejecuciones recientes con «Ver todas».
- **E2E `review.e2e.ts`:** guarda Ilustrados, la barra lateral cambia, y Ajustes y el Dashboard pasan axe en claro y en oscuro.

## 2. Por defecto no cambia nada

1. Arranca la web con este código y entra: todo se ve como antes, con Lucide.
2. **Ajustes** tiene tres pestañas. **Colores** es lo de siempre, y la pestaña **Companion** tiene la galería de la mascota y sus opciones.
3. En el menú de la mascota (barra superior), **Ajustes del companion** abre directamente la pestaña Companion.

## 3. Elegir Ilustrados

1. Ve a **Ajustes › Iconos**. Arriba hay tres tarjetas, Lucide (con «En uso»), Ilustrados y ChatGPT, cada una con la barra lateral, dos botones y cuatro píldoras pintadas con su paquete.
2. Pasa el ratón por la vista previa de Ilustrados: la puerta del Dashboard se abre, la carpeta asoma el papel, el play coge impulso, el disquete se hunde…
3. Elige **Ilustrados**: sale «Cambios sin guardar.». Pulsa **Guardar**: sale «Guardado.» y, sin recargar, cambian los iconos de la barra lateral, la barra superior, las píldoras y las pestañas.
4. **Recarga** la página: sigue en Ilustrados, sin parpadear con los de Lucide. En otro navegador también sale con Ilustrados.

## 4. Galería

1. Debajo, en **Galería**, están todos los iconos por grupos (Navegación, Barra y menús, Estados, Avisos, Acciones, Controles, Skynet), cada uno a 48, 24 y 16 px, con su nombre y su gesto.
2. Arriba dice cuántos tienen ya dibujo. Los que no, dicen «Sin dibujo: usa Lucide» en ámbar y se ven con Lucide; es lo esperado hasta IC-B e IC-C.
3. **Animar todo** mueve todos a la vez, en bucle. **Movimiento reducido** los para.
4. El selector **Lucide / Ilustrados / ChatGPT** de la galería cambia solo la galería, no el paquete guardado.
5. Con **ChatGPT** en el selector, la galería dice «Todos tienen su dibujo.» y se ven como en las láminas: casa roja y amarilla, carpeta amarilla, nodos azules, play morado, visto verde, servidores azules, barras azules, pulso rosa, engranaje gris.

## 5. Por la web, con Ilustrados

1. **Barra lateral:** al pasar el ratón por cada sección, su icono hace el gesto. Al entrar en una sección, su icono lo hace una vez. Plegada y desplegada se ve bien, y el botón de plegar es de trazo.
2. **Barra superior:** la lupa del buscador, el tema (sistema, sol o luna), la campana y el menú de usuario. En el menú de la mascota, cada opción con su icono.
3. **Píldoras de estado:** en la lista de ejecuciones, insignias quietas (Completada con su check verde, Fallida con su cruz roja…). En una ejecución **en marcha**, la píldora de la cabecera y la insignia grande repiten su gesto; las demás, quietas.
4. **Dashboard › Necesita tu atención:** cada aviso con su icono (la mano en las aprobaciones, la insignia de Fallida, la de Sin conexión…).
5. **Tema oscuro y colores:** cambia a oscuro y elige otra paleta en Ajustes › Colores (por ejemplo, Mandarina): los ilustrados cambian de color con el acento, y los estados siguen con sus colores.
6. **Móvil** (390 px): la barra inferior y el cajón «Más» con los iconos ilustrados.
7. **Reducir movimiento** en el sistema: los iconos no se mueven, ni al pasar el ratón.

## 6. Elegir ChatGPT

1. En **Ajustes › Iconos**, elige **ChatGPT** y guarda: toda la web pasa a esos iconos, sin recargar.
2. Pasa el ratón por la barra lateral: cada icono hace su gesto (la puerta se abre, el engranaje gira, el pulso se dibuja…).
3. **Píldoras de estado:** el disco toma el color del estado (verde Completada, rojo Fallida, morado Pensando, ámbar Esperando aprobación).
4. **Tema oscuro y colores:** en oscuro se ven bien sobre las tarjetas, y al cambiar la paleta en Ajustes › Colores **no** cambian de color (es lo esperado: llevan los suyos).
5. **Recarga**: sigue con ChatGPT, sin parpadear.

## 7. Volver a Lucide

1. **Ajustes › Iconos**, elige **Lucide** y guarda: todo vuelve a como estaba, sin recargar.
2. Con la API: `PUT /api/settings/appearance` con `{"icons": "emoji"}` da 400; con `{"icons": "lucide"}`, vuelve a Lucide sin tocar la paleta.

## 8. Rediseño ligero

1. En toda la web: fondo **blanco**, paneles con borde fino y **sin sombra**, los botones (y enlaces con forma de botón) con su sombra inferior de Duolingo que se hunde al pulsarlos, textos en gris (nunca negro), la barra lateral y los botones en minúsculas y sin negrita gruesa, y más espacio entre bloques.
2. **Dashboard**, de arriba abajo: saludo con la mascota y «Lanzar agente»; el aviso de Skynet con «Lanzar un workflow» como botón principal; **Resumen (últimos 7 días)** con «Ver más detalles →» y las ocho tarjetas; **Necesita tu atención**; el gráfico; **Ejecuciones recientes** (las 5 últimas, con «Ver todas →»); y los runners.
3. Con los paquetes **Ilustrados** o **ChatGPT**, las tarjetas del Resumen llevan el icono grande y suelto, sin baldosa; con **Lucide**, el icono va sobre su baldosa de color.
4. Cambia el periodo a **24 horas**: el título pasa a «Resumen (últimas 24 horas)» y el gráfico, a horas.
5. **Tema oscuro** y **móvil** (390 px): se lee bien, sin cortes ni scroll horizontal.
