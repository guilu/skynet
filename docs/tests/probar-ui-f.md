# Probar UI-F en local

UI-F es la última PR del rediseño de la interfaz: pule la carga, los estados vacíos y el movimiento, amplía la revisión de accesibilidad al tema oscuro y deja la guía de estilo con capturas. Con ella se cierra el rediseño (#37).

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

O en desarrollo: control plane y runner como siempre, y `cd web && npm ci && npm run dev`. No hay dependencias nuevas y el backend no cambia.

## 2. Qué mirar

1. **Esqueletos de carga.** Con la red lenta (en las herramientas del navegador, «Slow 3G»), abre Ejecuciones, Proyectos, Runners, el dashboard, un proyecto, un trabajo y una ejecución. Mientras llegan los datos, cada página enseña bloques grises con brillo y la forma de lo que viene (filas de tabla, tarjetas, cifras o los tres paneles), en vez de quedarse en blanco.
2. **Estados vacíos.** Workflows enseña cada definición en una tarjeta y, si no hubiera ninguna, un estado vacío. Una dirección que no existe (por ejemplo `/no-existe`) dice «Página no encontrada» y enlaza al dashboard.
3. **Movimiento.** Al cambiar de página, el contenido entra con un fundido corto; al cambiar de pestaña en un proyecto, también. Las tarjetas de repositorio suben un poco al pasar el ratón.
4. **Reducir movimiento.** Activa «Reducir movimiento» en el sistema (o en las herramientas del navegador, *Rendering → prefers-reduced-motion*): no hay fundidos, rebotes ni brillos; todo aparece en su sitio.
5. **Móvil.** En una ejecución, las cifras de la cabecera van de dos en dos y los paneles se ven antes.
6. **Guía de estilo.** Abre [`docs/ui.md`](../ui.md): tokens, tipografía, componentes, patrones, movimiento, accesibilidad y capturas en claro, oscuro y móvil.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

La E2E (`scripts/e2e.sh`) tiene una prueba nueva que pasa axe en tema oscuro por todas las páginas, la ejecución y el panel de lanzar.

Para regenerar las capturas de la guía:

```bash
cd web && npm run build && npx vite preview --port 4174 &
node scripts/capturas.mjs http://localhost:4174
```
