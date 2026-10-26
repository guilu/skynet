# Probar VS-C - Composiciones para redes sociales

Issue #177; con ella se cierra #174. Las composiciones se hacen dentro de la misma generación de la
galería, así que basta **una** generación completa, verificada entera de forma automática; la
geometría (sin deformar ni ampliar, dentro del lienzo), los nombres y los casos negativos los cubren
tests unitarios, y la Action es la segunda ejecución independiente. Nada de esta guía modifica
ficheros versionados.

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

Pasan, incluido `visual/visual.test.ts`, que para las composiciones comprueba:

- la selección de `social` en `web/visual/navigation-map.json` es válida, y se detectan una
  composición repetida, una pantalla sin captura y un titular de más de 48 caracteres;
- nombres estables y ordenados: `social/<formato>/<número>-<pantalla>-<tema>.png`, 18 en total;
- en cada formato (1600 × 900, 1080 × 1080, 1080 × 1350), con una captura corta y una larga: las
  capturas nunca se amplían por encima del 100 %, la imagen mide justo ancho × escala (proporción
  intacta), el teléfono cabe entero en el lienzo, la ventana empieza bajo el titular, no deja hueco
  bajo la imagen y el teléfono pisa la ventana;
- la plantilla usa `height: auto` en las dos imágenes y escapa el titular;
- **prueba negativa** sobre composiciones sintéticas en un directorio temporal: una que falta, otra
  que mide mal, otra que sobra y un correo en un titular hacen fallar el cierre con el fichero o el
  titular;
- el ZIP de una galería limpia lleva también las composiciones.

## 2. Una generación completa, fuera del repositorio

```bash
cd web
SCREENSHOTS_OUT=/tmp/galeria-vs-c npm run screenshots
```

Esperado (unos 6-10 minutos): `161 passed`, la línea del mapa, después
`Composiciones listas en /tmp/galeria-vs-c/social: 18 (landscape, square, portrait)` y por último
`Galería lista: 160 capturas comprobadas en …, ZIP de … MB`. Puede avisar de páginas que desbordan
en el móvil (`Aviso: …`): no es un fallo.

Ese cierre ya ha comprobado, además de lo de VS-A y VS-B: que están las 18 composiciones y ninguna
más, que cada una mide exactamente su formato (leído de la cabecera del PNG), que los titulares no
llevan nada privado (las capturas que contienen ya pasaron el escaneo de privacidad) y que el ZIP
lleva exactamente capturas, índice, mapa y composiciones.

## 3. Verificación independiente de la salida

```bash
npm run screenshots:verify /tmp/galeria-vs-c
```

Esperado: `160 PNG (…), tamaños correctos, mapa completo, composiciones para redes, README y ZIP con
233 ficheros`.

## 4. Limpieza

`docker ps -a | grep skynet-screenshots-db` no muestra nada y `/tmp/skynet-visual` no existe.

## 5. Inspección visual

Abre al 100 % las 18 imágenes de `/tmp/galeria-vs-c/social` (`landscape/`, `square/`,
`portrait/`) y la sección «Redes sociales» de `/tmp/galeria-vs-c/README.md`. Comprueba:

- la marca y el nombre de la pantalla arriba, el titular debajo, legible y sin cortar;
- la captura de escritorio en una ventana y la de móvil en un teléfono, del mismo tema que el fondo;
  nada estirado ni aplastado (los círculos de los iconos y del companion son círculos);
- la ventana solo se corta por abajo y, en cuadrado y vertical, por la derecha; el teléfono, entero;
- datos del proyecto `DEMO`, sin nombres de tus proyectos ni rutas de tu equipo.

## 6. Action

Etiqueta `galeria-visual` en la PR (o _Run workflow_ en **Galería visual** cuando esté en main).
Termina en verde y el artifact `skynet-screenshots` lleva también la carpeta `social/`. Es la
segunda ejecución completa; no hace commit ni push.

## 7. Checkout limpio

```bash
rm -rf /tmp/galeria-vs-c
test "$(git rev-parse HEAD)" = "$SHA" && test -z "$(git status --porcelain)" && echo limpio
```

Esperado: `limpio`.
