# Probar SY-A - Skyny v3: dibujo, colores, animaciones y accesorios

SY-A redibuja a Skyny según la lámina v3 (issue #181, plan `plans/siguiente-hito-sy.md`):

- tres bandas oscuras que suben hasta el borde del cuerpo, nodos grandes y ojos cian que brillan;
- un color de cuerpo por estado;
- una expresión nueva, **Reposo**, con animación idle;
- la lupa de Revisando en la mano derecha;
- tres niveles de tamaño;
- **accesorios de temporada** (Halloween, Navidad y verano).

Lo que cuenta Skyny no cambia: el motor de estados, el companion global, los avisos y la API son los mismos. El icono de la app y la baldosa de la barra lateral llegan en SY-B.

Documentación: `docs/companion.md`. Ninguna prueba de esta guía modifica ficheros versionados.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/companion     # 112 pruebas
npm test                                    # toda la web
npm run lint && npm run typecheck && npm run format:check
cd ..
```

Comprueban:

- **Las 12 expresiones** dibujan cuerpo, tres bandas, visor, cabeza, dos manos y ojos.
- **Cada instancia** tiene sus propios degradados, máscara y filtro (ids únicos), así que dos mascotas de distinto estado en la misma página no se pisan el color.
- **Tamaños:** pequeño (< 32 px) sin efectos, aro ni filtro; mediano (32–63 px) con efectos; grande (≥ 64 px) completo.
- **Revisando** lleva la lupa.
- **Accesorios:**
  - `seasonOf` cubre los límites de cada temporada: 23/24 de octubre, 2/3 de noviembre, 14/15 de diciembre, 6/7 de enero, junio/julio y agosto/septiembre.
  - No salen en pequeño ni con `season={null}`.
  - Sin `season`, sale el de la fecha, y desaparece al quitar la preferencia.
- **Cara global:** Reposo con todo tranquilo, En espera solo con ejecuciones en cola e Inactivo a los 5 min. El bocadillo de Reposo dice «Todo al día» y el de En espera «En cola».
- **Lo de antes sigue igual:** el parpadeo del visor al cambiar de cara, la accesibilidad con y sin `label`, y `still`.

## 2. En la web

Arranca como siempre (`docs/arranque-local.md`) y entra en `http://localhost:5173`. Si la prueba cae entre el 24 de octubre y el 2 de noviembre, Skyny lleva el sombrero de bruja, ladeado a la izquierda de la cabeza, en tamaño mediano y grande: es lo esperado. El sombrero no debe tapar los bocadillos («!», «?», pausa), que salen arriba a la derecha.

1. **Ajustes › Companion:** la mascota grande y 12 casillas.
   - **Casillas y colores:** Reposo (azul del acento), Inactivo (gris), En espera (gris, «?»), Pensando (morado), Ejecutando (naranja), Revisando (azul, lupa), Éxito (verde), Error (rojo), Te necesita (amarillo, «!»), En pausa (gris), Sin conexión (gris, prohibido) y Cancelado (gris).
   - **Bandas:** en todas hay tres bandas oscuras. Las laterales suben un poco del visor al borde y la inferior baja hasta la base.
   - **Revisando:** la lupa es grande, la sujeta la mano derecha y apunta a la derecha; los ojos miran hacia ella.
   - **Reposo:** en la grande se ve la animación idle en bucle de unos 7 s. Se ladea a un lado y a otro, mira a los lados, parpadea, guiña y le salen dos destellos junto a la cabeza.
   - **Al pulsar una casilla,** la grande parpadea (cierra el visor y cambia los ojos) y toma el color nuevo.
2. **Accesorios de temporada,** en la misma sección:
   - Se ven los tres: sombrero de bruja, gorro de Papá Noel con borde y pompón blancos, y sombrero de paja, cada uno con sus fechas.
   - Desmarca «Accesorios de temporada»: las tres casillas se atenúan y, si hoy toca accesorio, desaparece de la mascota grande, de la barra superior y del Dashboard. Vuelve a marcarla.
3. **Acento:** en Apariencia elige Bosque o Frambuesa. Reposo y Revisando cambian a ese color (cuerpo, destellos y lupa). Los demás estados mantienen el suyo.
4. **Tema oscuro:** el visor y las bandas siguen oscuros y los nodos claros. Los colores de estado se aclaran un poco (naranja y amarillo).
5. **Dashboard:**
   - Con todo tranquilo, la mascota que asoma en la cabecera está en Reposo y dice «Todo al día».
   - En la tarjeta de saludo, Skyny también está en Reposo.
   - Lanza un workflow: la mascota pasa a Pensando (morada) y luego a Ejecutando (naranja).
6. **Barra superior (34 px):** la mascota sigue el estado global con su color. Al abrir el menú, la de 48 px también.
7. **Tamaños pequeños:**
   - En la tabla de Ejecuciones (30 px) y en el grafo de una ejecución (22 y 18 px) se ven las caras quietas, con el color del estado, sin efectos ni sombrero.
   - A 18 px se distinguen las tres bandas y los ojos.
8. **Login** (cierra sesión): Skyny en Reposo, con la animación idle.
9. **Movimiento reducido** (Chrome DevTools › Rendering › `prefers-reduced-motion: reduce`): nada se mueve, ni siquiera el idle de Reposo, pero las expresiones y los colores siguen cambiando.

## 3. Qué no cambia

- **Favicon, iconos de la app y baldosa de la barra lateral:** siguen con el isotipo hasta SY-B.
- **Comportamiento:** ni las ejecuciones, ni los avisos, ni la API, ni los datos. Es solo interfaz.

## 4. Al terminar

```bash
git status --porcelain   # vacío: la guía no deja cambios en el checkout
```
