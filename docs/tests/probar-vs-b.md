# Probar VS-B - Mapa visual de navegación

Issue #176 (parte de #174). El mapa se genera dentro de la misma generación de la galería, así que
basta **una** generación completa, verificada entera de forma automática; la estructura del mapa
(cada pantalla una vez, conectores, submapas, orden estable) y los casos negativos los cubren tests
unitarios, y la Action es la segunda ejecución independiente. Nada de esta guía modifica ficheros
versionados.

## Requisitos

Los mismos que [probar-vs-a.md](probar-vs-a.md): Java 21, Node 22, `npm ci` en `web/`, Chromium de
Playwright, Docker (o `initdb` como usuario normal, o una base vacía en `SCREENSHOTS_DB_URL` que no
se llame `skynet`) y los puertos 55432, 18090 y 4174 libres.

Anota el commit desplegado: `SHA=$(git rev-parse HEAD)`.

## 1. Tests unitarios (sin navegador)

```bash
cd web
npm run lint && npm run format:check && npm run typecheck && npm test
```

Pasan, incluido `visual/visual.test.ts`, que para el mapa comprueba:

- cada captura canónica sale exactamente una vez, sin dos tarjetas en el mismo sitio y dentro del
  lienzo; hay un conector por cada enlace del inventario;
- el SVG tiene cada pantalla (`class="node" data-id="…"`) una sola vez y la leyenda con los seis
  tipos de conector;
- el mismo inventario da el mismo SVG y el mismo JSON, byte a byte;
- cada submapa tiene su grupo entero y, atenuadas, solo las pantallas de otros grupos con un
  conector al grupo;
- **prueba negativa** sobre un mapa sintético en un directorio temporal: si al JSON le falta una
  pantalla, un conector queda sin destino, el README del mapa lleva un correo, falta una miniatura
  o el SVG repite una pantalla, el cierre lo dice con el fichero y la pantalla;
- el ZIP de una galería limpia lleva capturas, README y todos los ficheros del mapa.

## 2. Una generación completa, fuera del repositorio

```bash
cd web
SCREENSHOTS_OUT=/tmp/galeria-vs-b npm run screenshots
```

Esperado (unos 6-10 minutos): `161 passed`, después
`Mapa listo en /tmp/galeria-vs-b/map: 40 pantallas, 72 conectores, 8 submapas (… × …)` y por último
`Galería lista: 160 capturas comprobadas en …, ZIP de … MB`. Puede avisar de páginas que desbordan
en el móvil (`Aviso: …`): no es un fallo.

Ese cierre ya ha comprobado, además de lo de VS-A: que están todos los ficheros del mapa (HTML,
SVG, PNG, PDF, JSON, README, 40 miniaturas y 8 submapas), que el JSON y el SVG tienen cada captura
canónica exactamente una vez, que todos los conectores tienen destino, el escaneo de privacidad de
los textos del mapa y que el ZIP lleva exactamente capturas, índice y mapa.

## 3. Verificación independiente de la salida

```bash
npm run screenshots:verify /tmp/galeria-vs-b
```

Esperado: `160 PNG (…), tamaños correctos, mapa completo, README y ZIP con 215 ficheros`.

## 4. Limpieza

`docker ps -a | grep skynet-screenshots-db` no muestra nada y `/tmp/skynet-visual` no existe.

## 5. Inspección visual del mapa

En `/tmp/galeria-vs-b/map`:

- `navigation-map.png`: las pantallas en columnas por zona (Acceso, Inicio y supervisión, Trabajo,
  Automatización, Ejecución, Decisiones, Configuración, Overlays y estados transversales), cada una con miniatura, título, ruta y número;
  conectores con flecha y la leyenda arriba; texto legible al 100 %.
- `navigation-map.html` (doble clic, o `npx serve /tmp/galeria-vs-b` si el navegador no carga las
  miniaturas desde `file://`): la rueda acerca, arrastrar mueve, «Ver todo» encaja; al pulsar una
  pantalla sale su ficha con la ruta, las notas, las cuatro capturas y a dónde lleva.
- `navigation-map.pdf`: una página con el mapa entero.
- `navigation-map.svg`: se abre en el navegador con las miniaturas (van incrustadas). Opcional:
  importarlo en Figma o Miro.
- `groups/`: un submapa por zona, con las vecinas atenuadas.
- Sin nombres de tus proyectos ni rutas de tu equipo en ninguna miniatura.

## 6. Action

Etiqueta `galeria-visual` en la PR (o _Run workflow_ en **Galería visual** cuando esté en main).
Termina en verde y el artifact `skynet-screenshots` lleva también la carpeta `map/` con los
submapas. Es la segunda ejecución completa; no hace commit ni push.

## 7. Checkout limpio

```bash
rm -rf /tmp/galeria-vs-b
test "$(git rev-parse HEAD)" = "$SHA" && test -z "$(git status --porcelain)" && echo limpio
```

Esperado: `limpio`.
