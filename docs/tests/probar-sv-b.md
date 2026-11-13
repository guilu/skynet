# Probar SV-B: Skyny 3D en la ejecución

SV-B pone a Skyny en 3D, y en vivo, en la banda «Qué pasa ahora» de la página de cada ejecución: cambia de estado con sus gestos y letreros, cuenta por qué fase va y avisa de los reintentos. Solo toca la web.

## 1. Comprobaciones automáticas

```bash
cd web
npm ci
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run
```

Todo en verde. Los tests nuevos son `settle.test.ts` (permanencia mínima) y `runLive.test.ts` (contador y reintentos).

## 2. Una ejecución en marcha

Con la app levantada (`docs/arranque-local.md`), lanza un workflow de varias fases sobre `demo-repo`, por ejemplo «Especificar, planificar e implementar», y abre su página.

1. La banda bajo la línea de fases enseña a Skyny en 3D (unos 120 px) en lugar del 2D. Al abrirla, hace el gesto del estado en el que está.
2. Mientras el agente trabaja, Skyny pasa de Pensando (burbujas, «IDEA») a Ejecutando (carrerilla, «EN MARCHA»). No tiembla aunque el agente use herramientas muy seguidas: cada estado dura al menos un segundo y pico.
3. En Ejecutando, al acabar el letrero, la pantalla enseña por qué fase va (p. ej. **2/3**). Con una sola fase no hay contador.
4. Durante la verificación de la fase saca la lupa (Revisando).
5. En una fase que pide tu aprobación, o si el agente pregunta, cambia **enseguida** a Te necesita: sube la mano con el «?».
6. Al terminar bien escribe «LISTO», salta y suelta confeti. Si falla, sacudida y letrero rojo.
7. Cancela una ejecución en marcha («Cancelar agente»): recoge las líneas de velocidad y escribe «CANCELADO».
8. En una fase con reintentos que falla y se reintenta, al volver a Ejecutando escribe «REINTENTO 1/2».
9. Pasa el ratón cerca de la cara de Skyny: le sigue con los ojos. Lejos, no.

## 3. Sin 3D

- Con «reducir movimiento» activado en el sistema, la banda vuelve al Skyny 2D de siempre (56 px).
- `npm run screenshots` (galería visual) sigue igual: va con movimiento reducido.

## 4. El resto

El login sigue igual que tras SV-A (Skyny 3D sin letreros, se tapa los ojos con la contraseña). En el resto de páginas, Skyny sigue en 2D.
