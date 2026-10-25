# Probar VS-A - Generador de capturas y galería

Issue #175 (parte de #174). Una sola generación completa, verificada entera de forma automática;
lo demás (orden estable, ZIP, privacidad) lo cubren tests unitarios, y la Action es la segunda
ejecución independiente. Nada de esta guía modifica ficheros versionados.

## Requisitos

- Java 21, Node 22 y las dependencias de `web/` (`npm ci`).
- Chromium de Playwright: `cd web && npx playwright install chromium`.
- Docker en marcha, **o** PostgreSQL con `initdb` en el PATH (como usuario normal, no root), **o**
  una base **vacía** solo para capturas en `SCREENSHOTS_DB_URL` (no puede llamarse `skynet`).
- Puertos libres: 55432 (base desechable), 18090 (API) y 4174 (web).

Anota el commit desplegado: `SHA=$(git rev-parse HEAD)`.

## 1. Tests unitarios (sin navegador)

```bash
cd web
npm run lint && npm run format:check && npm run typecheck && npm test
```

Pasan, incluido `visual/visual.test.ts`, que comprueba:

- inventario: sin ids, órdenes ni nombres repetidos, sin enlaces rotos ni huérfanos, cada
  pantalla con su preparador;
- el mismo manifiesto da siempre las mismas 160 rutas en el mismo orden;
- ZIP determinista: mismos bytes con las entradas en otro orden, ordenado, exactamente 160 PNG y el
  README;
- cabeceras PNG: falta una captura, mide mal o el tema no es el de su carpeta → falla;
- **privacidad, prueba negativa**: sobre una galería sintética en un directorio temporal, un
  correo, un token de GitHub, una clave de AWS, una ruta de home (en el texto) y un token de Slack
  (en la API) hacen fallar el cierre con el patrón y la captura (`privacidad: correo «…» en
desktop-dark/dashboard (texto)`) y **sin crear el ZIP**; el caso limpio pasa y crea el ZIP.

## 2. Una generación completa, fuera del repositorio

Si tienes Skynet arrancado en local, anota cuántas ejecuciones tiene:
`psql -h localhost -U skynet -d skynet -c 'select count(*) from workflow_run'`.

```bash
cd web
SCREENSHOTS_OUT=/tmp/galeria-vs-a npm run screenshots
```

`SCREENSHOTS_OUT` escribe la galería en `/tmp/galeria-vs-a` en vez de `docs/screenshots`, así que
no toca las capturas versionadas. Esperado (unos 6-10 minutos):

- primera línea: `Base de datos desechable (docker)` (o `initdb` / `external`);
- `161 passed`: la siembra y las 40 pantallas en cada una de `desktop-light`, `desktop-dark`,
  `mobile-light` y `mobile-dark`;
- puede avisar de páginas que desbordan en horizontal en el móvil (`Aviso: …`): es información
  para el rediseño, no un fallo;
- última línea: `Galería lista: 160 capturas comprobadas en …, ZIP de … MB`.

Ese cierre ya ha comprobado **todas** las salidas: las 160 rutas del manifiesto, sin faltantes ni
sobrantes; ancho y alto de cada PNG leídos de su cabecera; que cada captura se hizo con su tema y
su ancho de ventana; el escaneo de privacidad del texto y la API de cada pantalla; y que el ZIP
lleva exactamente los 160 PNG y el README, ordenados.

## 3. Verificación independiente de la salida

```bash
npm run screenshots:verify /tmp/galeria-vs-a
```

Esperado: `160 PNG (40 en cada una de desktop/light, desktop/dark, mobile/light, mobile/dark),
tamaños correctos, README y ZIP con 161 ficheros`.

## 4. Limpieza

- `docker ps -a | grep skynet-screenshots-db` no muestra nada y `/tmp/skynet-visual` no existe.
- Si anotaste el número de ejecuciones de tu Skynet, sigue siendo el mismo.

## 5. Inspección visual de una muestra

Abre al 100 % `02-dashboard`, `20-run-completed` y `30-run-active` de cada combinación en
`/tmp/galeria-vs-a`, y el `README.md` de esa carpeta. Comprueba:

- datos del proyecto `DEMO` / `analizador-texto`, sin nombres de tus proyectos ni rutas de tu
  equipo;
- el tema es el de la carpeta; sin cursores, tooltips ni animaciones a medias;
- la ejecución activa sale «En curso» y hay una aprobación en «Necesita tu atención»;
- el README agrupa las pantallas con miniatura, ruta y enlaces a las otras combinaciones.

## 6. Action

GitHub → Actions → **Galería visual** → _Run workflow_ (cuando esté en main) o etiqueta
`galeria-visual` en la PR. Termina en verde y deja el artifact `skynet-screenshots` con las cuatro
carpetas, el README y el ZIP. Es la segunda ejecución completa, en otro entorno; no hace commit ni
push.

## 7. Checkout limpio

```bash
rm -rf /tmp/galeria-vs-a
test "$(git rev-parse HEAD)" = "$SHA" && test -z "$(git status --porcelain)" && echo limpio
```

Esperado: `limpio`.
