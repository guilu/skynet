# Probar CH-E - Skyny vivo en la cortina

CH-E (#339) es la quinta parte del hito CH «Chat con Skyny»: **Skyny pone cara de lo que pasa en la cortina**. Solo cambia la web. Lo explica [`docs/chat.md`](../chat.md), en «Skyny en la cortina».

- La cara, el letrero y el texto bajo Skyny siguen al turno: Pensando, Ejecutando (mientras se confirma una acción), Te necesita, Éxito y Error (un momento, 4 s), Sin conexión y Reposo.
- Letreros nuevos «CONFIRMA» y «HECHO», con el glifo de la F que faltaba.
- Con la cortina abierta, los Skyny 3D de la página se quedan quietos.

Las pruebas 1 y 2 no gastan nada. La 3 hace dos preguntas a Haiku (unos céntimos) y **no confirma** nada que lance o cambie algo.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/components/chat src/components/companion
```

- `chatContext.test.ts`: el orden de las caras (error, pensando, ejecutando, el momento, sin conexión, te necesita, reposo), el turno que no pudo contestar y que los letreros del chat tienen trazo para todas sus letras.
- `ChatCurtain.test.tsx`: con una acción pendiente Skyny está en «Te necesita»; al confirmarla pasa a «Éxito» con «Hecho» debajo.
- `Skyny3D.test.tsx`: bajo la cortina abierta Skyny no se anima, y al cerrarla vuelve a hacerlo.
- `led.test.ts`: todos los letreros se pueden escribir.

## 2. Sin runner del chat

1. Abre **Inicio** (o una ejecución en curso de `demo-repo`), con Skyny 3D moviéndose. Pulsa **Ctrl+J** (⌘J en el Mac).
   **Esperado:** bajo el fondo atenuado, el Skyny de la página se queda quieto. Cierra la cortina: vuelve a moverse.
2. Abre la cortina y pregunta «¿Qué me espera hoy?».
   **Esperado:** Skyny pone cara de Pensando mientras se envía y, al quedarse sin runner, la de Sin conexión con «SIN RED» en su pantalla y «Ningún runner me atiende ahora» debajo.
3. Pulsa «Nueva».
   **Esperado:** sin conversación, Skyny vuelve a reposo.

## 3. Con un runner del chat (dos preguntas a Haiku)

Con un runner con Claude Code real que declare «Chat con Skyny» en Runners:

1. Pregunta «¿Qué hay en marcha ahora mismo?».
   **Esperado:** Pensando («PENSANDO...», «Pensando…») mientras contesta; al llegar la respuesta, reposo («En reposo»).
2. Pide «Lanza implementar-tarea en un trabajo de demo-repo».
   **Esperado:** al llegar la tarjeta, Skyny pone cara de Te necesita, escribe «CONFIRMA» y debajo dice «Esperando tu confirmación».
3. Pulsa **Cancelar** en la tarjeta (no confirmes: gastaría cuota).
   **Esperado:** Skyny vuelve a reposo. (Éxito y Ejecutando, que salen al confirmar, ya los cubren las pruebas automáticas.)
4. Repite 1 y 2 con el tema oscuro y en el móvil (Skyny a 150 px con letreros cortos).

## Qué no cambia

- El control plane, el runner y la API son los de CH-A a CH-D.
- Sin WebGL o con movimiento reducido, sale el Skyny 2D con la misma cara.
