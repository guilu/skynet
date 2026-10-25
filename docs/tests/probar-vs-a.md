# Probar VS-A - Generador de capturas y galería

Issue #175 (parte de #174). Comprueba que `npm run screenshots` genera la galería completa en un
entorno aislado, que no toca la instalación normal y que la Action produce el mismo paquete.

## Requisitos

- Java 21, Node 22 y las dependencias de `web/` (`npm ci`).
- Chromium de Playwright: `cd web && npx playwright install chromium`.
- Docker en marcha, **o** PostgreSQL con `initdb` en el PATH (como usuario normal, no root), **o**
  una base **vacía** solo para capturas en `SCREENSHOTS_DB_URL` (no puede llamarse `skynet`;
  hay que vaciarla antes de cada ejecución).
- Puertos libres: 55432 (base desechable), 18090 (API) y 4174 (web).

## 1. La instalación normal no se toca

1. Si tienes Skynet arrancado en local, apunta cuántas ejecuciones tiene:
   `psql -h localhost -U skynet -d skynet -c 'select count(*) from workflow_run'`.
2. Al terminar el paso 2, repite la consulta: **el número es el mismo**.

## 2. Generar la galería

```bash
cd web
npm run screenshots
```

Esperado (unos 10-15 minutos):

- La primera línea dice `Base de datos desechable (docker)` (o `initdb` / `external`).
- Pasa el proyecto `fixture` (siembra) y los cuatro de captura: `desktop-light`, `desktop-dark`,
  `mobile-light` y `mobile-dark`, 40 pantallas cada uno.
- Termina con `Galería lista: 160 capturas en ../docs/screenshots, ZIP de … MB`.
- Al acabar no queda nada: `docker ps` no muestra `skynet-screenshots-db` y `/tmp/skynet-visual`
  no existe.

## 3. Revisar la galería

1. `ls docs/screenshots/*/*` muestra `desktop/light`, `desktop/dark`, `mobile/light` y
   `mobile/dark`, cada una con `01-login.png` … `40-not-found.png`.
2. Dimensiones reales: `file docs/screenshots/desktop/light/02-dashboard.png` dice 1440 de ancho y
   `file docs/screenshots/mobile/dark/30-run-active.png` dice `390 x 844`.
3. Abre al 100 % una muestra de cada combinación: `02-dashboard`, `16-workflow-editor`,
   `20-run-completed`, `30-run-active`, `39-companion-menu`. Comprueba:
   - datos del proyecto `DEMO` / `analizador-texto`, sin nombres de tus proyectos ni rutas de tu
     equipo;
   - el tema es el de la carpeta;
   - sin cursores, tooltips ni animaciones a medias;
   - la ejecución activa sale «En curso», la fallida «Fallida» y la aprobación en «Necesita tu
     atención».
4. Abre `docs/screenshots/README.md` en GitHub o en un visor de Markdown: índice por grupos, con
   miniatura, ruta y enlaces a las otras combinaciones, las notas de vistas que no existen como
   tales y cómo regenerar.

## 4. Reproducibilidad

1. Copia la galería: `cp -r docs/screenshots /tmp/galeria-1`.
2. Vuelve a ejecutar `npm run screenshots`.
3. `diff <(cd /tmp/galeria-1 && find . -name '*.png' | sort) <(cd docs/screenshots && find . -name '*.png' | sort)`
   no muestra diferencias: mismos nombres y mismo orden.
4. Las imágenes son equivalentes (las fechas relativas y duraciones coinciden; las absolutas y los
   ids son del día).

## 5. ZIP

```bash
unzip -l docs/screenshots/skynet-screenshots.zip | tail -3
unzip -l docs/screenshots/skynet-screenshots.zip | grep -Ev '\.(png|md)$|Archive|Length|---|files'
```

- El primero da 161 ficheros (160 PNG + README).
- El segundo no lista nada: ni traces, ni vídeos, ni logs, ni `auth.json`, ni `fixture.json`.

## 6. Privacidad

1. Comprueba que el escaneo funciona: en `web/visual/fixture.setup.ts` cambia temporalmente la
   descripción del proyecto `DEMO` por `contacto: ana@gmail.com` y ejecuta
   `npm run screenshots -- --project=fixture --project=desktop-light`.
2. Esperado: falla con `privacidad: correo «ana@gmail.com» en desktop-light/…` y **no** escribe el
   ZIP. Deshaz el cambio.

## 7. CI y E2E

- `cd web && npm run lint && npm run format:check && npm run typecheck && npm test`: pasa, incluido
  `visual/visual.test.ts` (inventario sin duplicados, enlaces rotos ni huérfanos; escaneo; ZIP).
- `npm run e2e` (o el workflow E2E) sigue igual: no ejecuta nada de `web/visual`.

## 8. Action manual

1. GitHub → Actions → **Galería visual** → _Run workflow_ sobre la rama de la PR.
2. Termina en verde y deja el artifact `skynet-screenshots` con las cuatro carpetas, el README y el
   ZIP.
3. La ejecución no crea commits ni ramas (`git log origin/<rama>` igual que antes).
