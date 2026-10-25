# Probar TI-A - Tipografía Barlow

TI-A ([#170](https://github.com/guilu/skynet/issues/170)) cambia la tipografía de la web por **Barlow** (SIL OFL), la fuente libre más parecida a Duolingo Sans.

Qué trae (solo web):
- **Una sola familia** para títulos y texto: `--font-display` y `--font-sans` apuntan a Barlow. Sin cursiva por ahora.
- **Pesos:** el texto pasa de 600 (Nunito) a 500 (Barlow), que a igual tamaño ya pesa lo mismo; lo destacado sigue en 600–900.
- **Dependencias:** sale `@fontsource/nunito` y `@fontsource/baloo-2`, entra `@fontsource/barlow` (pesos 500–900, subconjunto latino, empaquetada con la web, sin peticiones a terceros). JetBrains Mono no cambia.
- **Documentación:** [`docs/ui.md`](../ui.md), sección 3 «Tipografía».

No hay cambios en el servidor ni migraciones.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 a 4 usan la web del control plane local. No hace falta lanzar ejecuciones.

## 1. Tests

```bash
cd web && npm ci && npm run lint && npm run format:check && npm run typecheck && npx vitest run && npm run build
```

No hay tests nuevos: el cambio es de CSS y dependencias. Basta con que todo siga en verde y que `npm run build` meta los `barlow-latin-*.woff2` en `dist/assets`.

## 2. La fuente se carga

1. Abre la web y entra.
2. En las herramientas del navegador, pestaña **Red**, filtra por `woff2`: deben aparecer `barlow-latin-500…`, `-700…` y `-800…` (y `jetbrains-mono…` si la página tiene IDs). No debe aparecer ningún `nunito` ni `baloo`.
3. Inspecciona el saludo «Buenos días…» del Dashboard: en **Fuentes renderizadas** sale «Barlow».

## 3. Aspecto

Recorre el Dashboard, Workflows, un workflow, una ejecución y Ajustes, en claro y en oscuro:
- Títulos, menú lateral (en mayúsculas), KPIs, botones y chips en Barlow.
- Nada se corta ni salta de línea donde antes no lo hacía (Barlow es algo más estrecha que Nunito, así que sobra espacio).
- Los IDs, hashes, comandos y el editor YAML siguen en JetBrains Mono.

## 4. Móvil

Con la ventana a 390 px de ancho, el Dashboard y una ejecución se leen bien y no hay desbordes horizontales.
