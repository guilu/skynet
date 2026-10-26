# Probar SY-B - Icono de la app con Skyny

SY-B convierte a Skyny en el icono de la app (issue #183, plan `plans/siguiente-hito-sy.md`):

- **Favicon:** Skyny en reposo, sin fondo y del color del acento.
- **Favicon de estado:** amarillo cuando Skyny te necesita y rojo con «> <» si hay un error.
- **Iconos de iOS y Android:** con un icono «maskable» nuevo.
- **WEBP** de 512 px para documentación.
- **Barra lateral:** Skyny sustituye al isotipo sobre la baldosa.

La geometría de Skyny (cuerpo, bandas, nodos y visor) está ahora en `lib/brand.ts` y la comparten el componente y los iconos. Las ejecuciones, los avisos y la API no cambian.

Documentación: `docs/ui.md` (sección 10, marca) y `docs/companion.md`. Ninguna prueba de esta guía modifica ficheros versionados.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/lib/brand.test.ts src/components/companion   # marca, favicon y companion
npm test                                                        # toda la web
npm run lint && npm run typecheck && npm run format:check
cd ..
```

Comprueban:

- **`public/favicon.svg`** es exactamente `faviconSvg` con el azul por defecto.
- **`skynySvg`** dibuja el cuerpo con el degradado del color, tres bandas, tres nodos y los ojos.
- **`tint`** mezcla bien con blanco.
- **Con otro acento,** el favicon se genera al vuelo con ese color y vuelve al estático sin él.
- **`faceIcon`** da a Skyny amarillo con ojos de Reposo para «Te necesita» y rojo con «> <» para «Error».

Los PNG y el WEBP están versionados: se regeneran con `node scripts/iconos.mjs` solo cuando cambia Skyny. Esta guía no los regenera.

## 2. En la web

Arranca como siempre (`docs/arranque-local.md`) y entra en `http://localhost:5173`. Si el navegador guarda el favicon antiguo, recarga sin caché (Ctrl/Cmd+Shift+R).

1. **Pestaña:** el favicon es Skyny azul, sin baldosa. A 16 px se distinguen el triángulo, las bandas y el visor oscuro con los ojos.
2. **Favicon de estado:**
   - Con una aprobación pendiente (por ejemplo, lanza en DEMO un workflow con fase de aprobación), el favicon pasa a Skyny amarillo.
   - Con un fallo sin ver, pasa a Skyny rojo con los ojos «> <».
   - Al abrir la ejecución, o cuando ya no hay nada pendiente, vuelve al azul.
3. **Acento:** en Ajustes › Apariencia elige Mandarina. El favicon pasa a Skyny naranja. Vuelve al acento por defecto: el favicon vuelve al azul.
4. **Barra lateral:**
   - Arriba a la izquierda, Skyny en reposo y quieto, sin baldosa, junto a «Skynet». Hasta el 2 de noviembre lleva el sombrero de bruja ladeado.
   - Al pasar el ratón se ilumina y al pulsar se hunde un poco y lleva al Dashboard.
   - Con la barra plegada solo queda Skyny, sin cortarse.
   - En tema oscuro se ve igual de bien, y con Mandarina toma el naranja.
5. **Iconos de la pantalla de inicio** (opcional):
   - Abre `http://localhost:5173/apple-touch-icon.png`, `/icon-192.png`, `/icon-512.png` y `/icon-maskable-512.png`. Todos muestran a Skyny azul sobre un fondo azul muy claro. El maskable lleva más margen.
   - En Chrome DevTools › Application › Manifest se ven los tres iconos, uno con `purpose: maskable`, sin avisos.
6. **Avisos del navegador** (si los tienes activados en Ajustes › Companion): con la pestaña oculta, el aviso de una aprobación lleva el icono de Skyny.

## 3. Qué no cambia

- **La mascota de las páginas** (Dashboard, ejecución, tabla, grafo, campana): sigue como en SY-A.
- **Comportamiento:** ni las ejecuciones, ni los avisos, ni la API, ni los datos. Es solo interfaz.

## 4. Al terminar

```bash
git status --porcelain   # vacío: la guía no deja cambios en el checkout
```
