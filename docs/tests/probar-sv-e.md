# Probar SV-E: iconos y favicon con el nuevo Skyny

SV-E lleva el dibujo de SV-D (Skyny sin cabeza, cuerpo bajo y redondeado, manos abajo, aro metálico en el visor) a los iconos de la app. La geometría vuelve a estar en un solo sitio, `web/src/lib/brand.ts`, y la comparten el componente `Companion` y los iconos. Solo toca la web.

## 1. Comprobaciones automáticas

```bash
cd web
npm ci
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run
```

Todo en verde. `brand.test.ts` comprueba que `public/favicon.svg` es exactamente el que genera `brand.ts` y que el icono lleva dos manos y no tiene cabeza.

## 2. Favicon

1. Abre la app: la pestaña enseña a Skyny nuevo, azul y sin cabeza.
2. En **Ajustes › Colores**, elige la paleta **Mandarina**: el favicon cambia al naranja del acento.
3. Con una ejecución que falle (sobre `demo-repo`), la pestaña pone a Skyny rojo con los ojos «> <»; con una que te necesite, amarillo.

## 3. Iconos de la pantalla de inicio

- `web/public/apple-touch-icon.png`, `icon-192.png`, `icon-512.png` e `icon-maskable-512.png` salen con el dibujo nuevo, centrado en la baldosa. En Android, el «maskable» queda dentro de la zona segura.
- `docs/ui/skyny-512.webp` también.
- Para regenerarlos: `cd web && node scripts/iconos.mjs` (si Playwright no encuentra su navegador, `CHROMIUM_PATH=/ruta/al/chromium node scripts/iconos.mjs`).

## 4. Lo que no cambia

El Skyny de la app (2D y 3D) se ve igual que tras SV-D: solo se ha movido su geometría a `brand.ts`.
