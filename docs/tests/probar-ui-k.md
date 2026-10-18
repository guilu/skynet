# Probar UI-K en local

UI-K separa la página de ejecución en dos tarjetas, Actividad y Detalle, como en la plantilla, y quita el scroll horizontal de las pestañas del detalle: las que no caben van a un menú «Más ▾». Solo cambia la web; no hay migraciones ni dependencias nuevas.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

En desarrollo, `cd web && npm ci && npm run dev` con el control plane levantado como siempre. Abre una ejecución con algún agente terminado (`/runs/<id>`).

## 2. Qué mirar

1. **Dos tarjetas.** Bajo «Progreso», la actividad (Conversación, Cascada, Eventos) y el detalle del agente son dos tarjetas con su borde y su sombra, separadas por un hueco. Ya no hay una línea gris vertical entre ellas.
2. **Cambiar el ancho.** Al pasar el ratón por el hueco aparece una barra del color de acento; arrastrándola cambia el ancho de las dos tarjetas, y se recuerda al recargar como antes. Con el teclado: Tab hasta el hueco y flechas izquierda/derecha.
3. **Menú «Más».**
   - Con el ancho normal se ven Resumen, Prompt y Herramientas, y al final «Más ▾». No hay barra de scroll en la cabecera.
   - Abre «Más»: salen el resto (Artefactos, Verificación, Coste, Evento original). Elige Coste: el panel muestra el coste y el botón pasa a llamarse «Coste ▾», marcado como pestaña elegida. En el menú, Coste lleva un ✓.
   - Ensancha el detalle arrastrando el hueco: van apareciendo más pestañas en la fila y, si caben todas, el menú desaparece. Estréchalo: vuelven al menú.
   - Teclado: con el foco en una pestaña, las flechas izquierda/derecha recorren todas, también las del menú (el foco va al botón, que toma su nombre). Enter o flecha abajo sobre el botón abre el menú.
   - «Ver evento» en la conversación abre «Evento original» aunque esté en el menú: el botón lo muestra.
4. **Otras pestañas.** Las de Conversación/Cascada/Eventos, Proyecto y el editor de workflows siguen como estaban.
5. **Móvil y oscuro.** En el móvil, la pestaña «Detalle» muestra la fila con «Más ▾» sin scroll. En oscuro las tarjetas y el menú se leen bien.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

Las E2E (`scripts/e2e.sh`) abren las pestañas del detalle con `openTab`, que pasa por el menú «Más» cuando la pestaña no está en la fila.
