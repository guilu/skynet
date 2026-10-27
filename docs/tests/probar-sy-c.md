# Probar SY-C - Interacciones de Skyny

SY-C trae el lote 1 del censo de interacciones con Skyny (issue #184, que cierra #181; plan `plans/siguiente-hito-sy.md`). Todo es interfaz: ni la API, ni los datos, ni las ejecuciones cambian.

Documentación: `docs/companion.md` (sección «Interacciones (SY-C)»). Ninguna prueba de esta guía modifica ficheros versionados.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/companion src/Auth.test.tsx src/Approvals.test.tsx
npm test                                    # toda la web
npm run lint && npm run typecheck && npm run format:check
cd ..
```

Comprueban:

- **Gestos:** se quitan solos al terminar y ponen sus ojos (risa, serio, dormido) sin cambiar la expresión.
- **Poses y gotita:** `cover`/`peek` en el SVG, y la gotita solo desde 32 px.
- **Ojos que siguen:** solo en grande; `lookOffset` mueve hasta 4 unidades, más cuanto más lejos.
- **Pulsar a Skyny:** salta y dice las frases por turnos; a los 5 s vuelve el bocadillo.
- **Confeti:** solo al pasar de en marcha a terminada bien con la página abierta, no al abrir una ya terminada ni al fallar.
- **Login:** se tapa los ojos con la contraseña enfocada y mira de reojo al mostrarla.
- **Aprobación:** tras aprobar, «¡Gracias! Sigo con ello.».
- **Companion global:** dormida, se despierta con un bostezo al mover el ratón; suda con una alerta de cuota.
- **Saludo:** la invitación cambia con la hora y la racha sale desde 3 (las canceladas no la cortan, un fallo sí).

## 2. En la web

Arranca como siempre (`docs/arranque-local.md`) y entra en `http://localhost:5173`. Las ejecuciones reales van en el proyecto DEMO.

1. **Login** (cierra sesión):
   - Mueve el ratón: los ojos de Skyny lo siguen. Pulsa Tab: miran al campo con el foco.
   - Entra en «Contraseña»: Skyny se tapa los ojos con las dos manos.
   - Pulsa el ojo del campo: la contraseña se ve y Skyny baja una mano y mira de reojo. Vuelve a pulsarlo y sal del campo: baja las manos.
2. **Ajustes › Companion:** bajo la mascota grande, prueba Saltito, Asentir y Bostezo, Taparse los ojos, De reojo y Sudando. Los ojos de la grande siguen al cursor.
3. **Dashboard:**
   - Pulsa la mascota de la cabecera: da un saltito, se ríe y dice una frase; cada pulsación, otra. A los 5 s vuelve su bocadillo. Lo mismo en la tarjeta de saludo.
   - Saludo sin nada en marcha: por la mañana «¿empezamos el día lanzando algo?», por la tarde «¿lanzamos algo?» y por la noche «es tarde, ¿lo dejamos para mañana?».
   - Con 3 o más ejecuciones seguidas bien (lanza workflows cortos en DEMO), el saludo añade «Llevas N ejecuciones seguidas sin fallos. 🎉».
4. **Despertar:** deja la web 5 min sin nada en marcha: la mascota de la barra se duerme (gris, «zZ»). Mueve el ratón o vuelve a la pestaña: bosteza estirando las manos y pasa a Reposo.
5. **Confeti:** abre en DEMO una ejecución en marcha y espera a que termine bien: cae confeti por toda la pantalla durante un segundo y medio y la mascota de la banda salta. Abrir una ejecución ya terminada no lo lanza.
6. **Aprobaciones:** en una puerta de DEMO, aprueba: Skyny salta y dice «¡Gracias! Sigo con ello.». En otra, rechaza o pide cambios con un comentario: asiente serio con «Entendido: lo paro aquí.» o «Vale: le paso tus cambios a la fase que corrige.».
7. **Editor de workflows** (un borrador en DEMO):
   - Junto a «Problemas», Skyny pone cara de Pensando mientras valida, de Error si escribes algo inválido y vuelve a Reposo al corregirlo.
   - Publica una versión válida: Skyny salta con «¡Publicado! clave vN ya se puede lanzar.».
8. **Cargas:** recarga la web: mientras comprueba la sesión, Skyny Ejecutando con «Cargando…». También al abrir un evento en el inspector o el diff de un archivo.
9. **Cuota apurada** (solo si algún CLI pasa del 90 % o va por encima del ritmo semanal): la mascota de la barra, la del Dashboard y la de Uso llevan una gotita de sudor.
10. **Movimiento reducido** (Chrome DevTools › Rendering › `prefers-reduced-motion: reduce`): no hay saltos, bostezos, confeti ni ojos que siguen, pero las manos se ponen en el login y las caras cambian.

## 3. Qué no cambia

- **Comportamiento:** ni las ejecuciones, ni las aprobaciones, ni la API, ni los datos.
- **Accesibilidad:** las frases y reacciones se anuncian (`aria-live`/`role="status"`), el confeti es decorativo y el botón del ojo del login dice «Mostrar la clave».

## 4. Al terminar

```bash
git status --porcelain   # vacío: la guía no deja cambios en el checkout
```
