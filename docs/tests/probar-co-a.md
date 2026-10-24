# Probar CO-A - Mascota y motor de estados

CO-A añade la mascota de Skynet: el isotipo convertido en personaje, en SVG con CSS, plano y en pastel. También trae su motor de estados (`components/companion/state.ts`), la galería de Ajustes › Companion y los primeros usos estáticos: login, 404 y estados vacíos. No hay todavía nada en vivo; eso llega con CO-B (barra, campana y avisos) y CO-C (páginas).

Documentación: `docs/companion.md`.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/companion     # 52 pruebas: mapeos de agente, fase, ejecución y global, y el componente
npm test                                    # toda la web
npm run lint && npm run typecheck && npm run format:check
cd ..
```

Los tests de tabla cubren:

- cada estado de agente;
- las fases, incluidos el reintento programado (En pausa), la verificación en curso (Revisando) y la aprobación de W5 (Te necesita);
- las ejecuciones con ramas paralelas, donde Te necesita gana a Error y Error gana a Ejecutando;
- el companion global (Sin conexión › error sin ver › aprobaciones › trabajo › éxito › Inactivo a los 5 min).

Las pruebas del componente comprueban:

- que el cambio de expresión ocurre con el visor cerrado, y que se queda la última si cambia dos veces seguidas;
- que en pequeño se quitan los efectos;
- la accesibilidad, con y sin `label`.

## 2. En la web

Arranca como siempre (`docs/arranque-local.md`) y entra en `http://localhost:5173`.

1. **Login** (cierra sesión): a la izquierda está la mascota de 128 px, en lugar de la baldosa del logo. Respira y parpadea cada pocos segundos.
2. **Ajustes › Companion**, debajo de Apariencia: la mascota grande y 11 casillas (Inactivo, En espera, Pensando, Ejecutando, Revisando, Éxito, Error, Te necesita, En pausa, Sin conexión, Cancelado).
   - Las casillas están quietas y se animan al pasar el ratón.
   - Al pulsar una, la grande **parpadea** (cierra el visor, cambia los ojos y lo abre) y entra el gesto nuevo:
     - Éxito: pop y confeti.
     - Error: se vuelve roja y se sacude.
     - Te necesita: levanta la mano derecha y saluda.
     - Ejecutando: se inclina, mueve las manos y salen líneas de velocidad.
   - El nombre y la descripción de la derecha cambian (y se anuncian con lector de pantalla).
3. **Acento:** en Apariencia elige Bosque, Mandarina o Frambuesa. El cuerpo de la mascota cambia a ese color en pastel; Error sigue en rojo y Sin conexión y Cancelado en gris.
4. **Tema oscuro:** el visor sigue oscuro, los nodos blancos y el cuerpo en pastel del acento.
5. **Movimiento reducido** (en macOS: Accesibilidad › Pantalla › Reducir movimiento; en Chrome DevTools: Rendering › `prefers-reduced-motion: reduce`): ninguna mascota se mueve, pero la expresión sigue cambiando.
6. **Estados vacíos** (con la BD limpia de `scripts/reset-local.sh`, o filtrando):
   - Proyectos, Ejecuciones y Aprobaciones sin nada: mascota dormida con «zZ».
   - Runners sin registrar: mascota gris sin señal.
   - Uso sin datos: En pausa.
   - Con filtros que no dan resultados sigue el icono de antes, no la mascota.
7. **404:** ve a `/no-existe`. Aparece la mascota pensando, con la burbuja «…».
8. **Pestaña oculta:** con la galería abierta, cambia de pestaña y vuelve. Las animaciones siguen donde estaban, porque mientras la pestaña está oculta se pausan.

## 3. Qué no cambia

- **Barra lateral:** sigue con la baldosa del logo.
- **Favicon:** sin cambios hasta CO-C.
- **Comportamiento:** ni las ejecuciones, ni la API, ni los datos cambian. Es solo interfaz.
