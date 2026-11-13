# Probar SV-C: Skyny 3D en Inicio

SV-C deja un solo Skyny en Inicio, en 3D y en vivo, dentro de la tarjeta del saludo «Hola, soy Skyny». El Skyny 2D que asomaba junto al título, con su bocadillo («Todo al día», «¡Todo en marcha! 🚀»…), desaparece. Solo toca la web.

## 1. Comprobaciones automáticas

```bash
cd web
npm ci
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run
```

Todo en verde.

## 2. Inicio

Con la app levantada (`docs/arranque-local.md`), abre Inicio (`/`).

1. Junto a «Buenos días, …» ya no hay Skyny ni bocadillo: solo el título, el resumen y «Nuevo trabajo».
2. En la tarjeta del saludo, Skyny sale en 3D (unos 140 px) a la izquierda del texto. Al abrir la página hace el gesto de su estado y escribe un letrero corto en la pantalla (p. ej. «HOLA» sin nada en marcha).
3. Lanza un workflow sobre `demo-repo` y vuelve a Inicio: Skyny pasa a Pensando («IDEA») o Ejecutando («EN MARCHA», con las líneas de velocidad). Es el mismo estado que el icono de Skyny de la barra superior.
4. Si una fase pide aprobación, Skyny pasa enseguida a Te necesita (la mano con el «?») y aparece «Revisar la aprobación».
5. Pulsa a Skyny: dice una frase en lugar del saludo, como antes.
6. Pasa el ratón cerca de su cara: le sigue con los ojos.

## 3. Móvil

Con la ventana a menos de 720 px (o el móvil), Skyny sale centrado **encima** del texto del saludo, a unos 96 px. Antes no se veía en el móvil.

## 4. Sin 3D

- Con «reducir movimiento» activado, la tarjeta enseña el Skyny 2D de siempre (80 px), que suda si alguna cuota aprieta.
- `npm run screenshots` (galería visual) va con movimiento reducido: el saludo sale en 2D y ya sin el Skyny de la cabecera.

## 5. El resto

El login y la página de la ejecución siguen igual que tras SV-B. En el resto de páginas, Skyny sigue en 2D.
