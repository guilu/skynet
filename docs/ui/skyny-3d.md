# Skyny 3D

Skyny en 3D (hito TD) está hecho con [three.js](https://threejs.org/). Desde TD-A sale en el login; desde SV-B, en la banda «Qué pasa ahora» de cada ejecución, y desde SV-C, en el saludo de Inicio. El resto de la web sigue con el Skyny 2D (`Companion.tsx`, ver [`../companion.md`](../companion.md)).

## Skyny en vivo (SV-B)

`SkynyLive` (`web/src/components/companion/SkynyLive.tsx`) es el Skyny 3D que cuenta lo que pasa. Recibe el estado del companion y:

- lo deja al menos **1,2 s** en cada estado (`settle.ts`): si llegan varios seguidos (Pensando ↔ Ejecutando en cada herramienta), se queda con el último. Lo urgente (Te necesita, Error, Sin conexión, Cancelado) entra enseguida;
- escribe el **letrero** del estado al llegar (cortos por debajo de 160 px: «IDEA», «REVISA»), o uno propio (`message`);
- enseña el **contador** (`progress`) en Ejecutando;
- solo sigue al cursor con los ojos cuando pasa cerca de la cara (en el login, lo sigue por toda la página);
- con movimiento reducido, sin WebGL 2, mientras carga three o si el 3D falla, enseña el `fallback` (el Skyny 2D). Por eso las capturas de la galería visual, que van con movimiento reducido, siguen en 2D.

En la ejecución (`RunBand`), Skyny mide 120 px. `runLive.ts` decide qué cuenta: con varias fases, por cuál va (2/4); en un reintento de la fase, «REINTENTO 1/2» (con su política de reintentos) o «INTENTO 3» (si se reintentó a mano). Cada página tiene como mucho un Skyny 3D: cada uno es un contexto WebGL.

En Inicio (SV-C), el saludo «Hola, soy Skyny» (`CompanionHello`) es el **único Skyny** de la página: el que asomaba junto al título con su bocadillo se ha quitado. Usa el estado global del companion (`useCompanion().expression`), mide 140 px (96 px en el móvil, centrado encima del texto) y, como está por debajo de 160 px, escribe los letreros cortos. Al pulsarlo dice una frase, como antes.

Desde TD-B, **el estudio donde se diseña está dentro del repo** y usa el mismo código que la app. No hay que portar nada: lo que se cambia en el estudio es lo que sale en la web. En SV-A se trajo todo lo del estudio v2 (`skyny-v2.0.html`, hecho con Codex fuera del repo) y ese HTML suelto se retira: a partir de aquí, Codex y Claude trabajan sobre estos ficheros (ver [`AGENTS.md`](../../AGENTS.md)).

## Abrir el estudio

```bash
cd web
npm run dev
# http://localhost:5173/skyny-studio.html  (o ?state=running para abrir en un estado)
```

Es solo de desarrollo: `npm run build` no lo incluye. Trae lo del estudio original (las 6 vistas del turnaround, órbita con el ratón, Idle, Malla, Parpadear y Captura PNG) y además:

- la lista de los **12 estados** del companion (`CompanionState`), con su color, ojos y accesorio;
- **Repetir gesto**, que vuelve a lanzar el gesto de entrada del estado;
- las **poses** del login (Tapar y De reojo) y **Mirada**, que hace que siga al cursor por el lienzo;
- **Tema**, que cambia entre claro y oscuro: los colores salen de los tokens de la web;
- **Vista compacta**: Skyny a 180 px con el encuadre de la web y letreros cortos («IDEA», «REVISA»);
- las **demos de transiciones** (estudio v2): **Simular tarea** (Pensando → Ejecutando con contador 1/5…5/5 → Revisando → Éxito), **Simular recuperación** (Ejecutando → Error → Te necesita, que espera a **Reintentar** → Ejecutando con «REINTENTO 1/2» → Éxito) y **Cancelar tarea** (en Ejecutando: recoge las líneas, escribe «CANCELADO» y vuelve a Reposo). Mientras corre, el botón de la demo pasa a **Detener demo**.

Con `prefers-reduced-motion` no hay gestos de entrada ni fundidos, y los letreros salen enteros, igual que en la web.

### Exportarlo a un solo HTML

```bash
cd web
npm run studio:export   # → dist-studio/skyny-studio.html
```

Mete three, el código, los estilos y las fuentes en un único fichero que se abre con doble clic (sin servidor), para enseñarlo o adjuntarlo. Es una salida generada: no se edita ni se sube al repo.

## Qué hace Skyny 3D (estudio v4.3)

El motor es el del estudio v4.3 (`plans/skyny-3d/skyny-v4.3.html` en la carpeta del proyecto), pasado a módulos:

- **34 estados**: los 12 del companion de la web (Reposo, Pensando, Ejecutando…) y 22 más de flujos agénticos (Bloqueado por seguridad, Cooldown, Evaluando, Delegando, Enrutando, Sandbox, Recordando…). Cada uno con sus ojos, su accesorio, su gesto de entrada y su letrero (`STATES_META`, `STATE_CONFIG`, `LED_MESSAGES`).
- **Al cambiar de estado**, los colores y la postura se funden, el accesorio anterior se desvanece y llega el gesto de entrada del nuevo.
- **Letreros en la pantalla**: al acabar el gesto escribe letra a letra lo que hace, con cursor, y vuelve a poner los ojos. En compacto usa los cortos (`LED_COMPACT`). `setState(estado, { message })` pone otro letrero (p. ej. `retryMessage(1, 2)` → «REINTENTO 1/2»). Solo hay trazo para las letras que usa algún letrero: un letrero nuevo con otras letras necesita sus glifos en `LED_GLYPHS` (`screen.ts`), y `states.test.ts` lo comprueba.
- **Gorro, cara y espalda** (`extras.ts`): ingeniero, headset, mago, corona, detective, antena, Navidad y verano; gafas de sol, cybervisor y pipa; jetpack. `setHat`, `setFace` y `setBack`. Se eligen en Ajustes › Companion, se guardan en el navegador y Skyny los lleva en toda la web; sin gorro elegido, lleva el de temporada.
- **¡Boop!**: en el estudio y en el login, un clic corto sobre Skyny (`poke()`). En el login también se gira arrastrando (`spin.ts`, `setOrbit`) y, si lo giras deprisa, se marea.
- **Letreros que hablan** (login): `say(texto | [trozos])` escribe ya un letrero (a trozos, para que se lean grandes); `setTicker(líneas)` hace pasar letreros deslizándose de derecha a izquierda en reposo. Los del login están en `loginLines.ts` (saludo por la hora, de madrugada, café, viernes y los de la casa) y `loginLines.test.ts` comprueba que todas sus letras tienen trazo.
- **Pausa bajo la cortina** (`pause.ts`): con la cortina del chat abierta, `SkynyPaused` para los Skyny 3D de la página (`stop()`), y al cerrarla vuelven a animarse (`play()`).
- **Contador** de Ejecutando: `setProgress(hechos, total)`; `setProgress(null)` lo quita.
- **En reposo** parpadea, mira a los lados y sigue con los ojos al puntero si pasa cerca de la cara (`pointer({ x, y })`).
- `busy` dice si Skyny está con algo a medias (gesto, letrero o contador): las demos esperan a que acabe.

## Dónde está cada cosa

Todo en `web/src/components/companion/three/`. Los ficheros del motor son JavaScript del estudio con `// @ts-nocheck`; la API que usa la web está tipada (`SkynyScene`, `SkynyExtras`, `SkynyState`).

| Fichero | Qué tiene |
|---|---|
| `states.ts` | Los 34 estados (`STATES_META`), su aspecto (`STATE_CONFIG`) y sus letreros (`LED_MESSAGES`, `LED_COMPACT`) |
| `model.ts` | Cuerpo, bandas, cara, manos, materiales y suelo (`buildSkynyModel`) |
| `accessories.ts` | Los accesorios de cada estado (`createAccessory`) |
| `extras.ts` | Gorros, complementos de cara y de espalda (`createExtras`, `HATS`, `FACES`, `BACKS`) |
| `screen.ts` | Pantalla: ojos, letreros y trazos de las letras (`LED_GLYPHS`) |
| `math.ts` | Curvas de ayuda (`clamp01`, `smoothstep`, `easeOutCubic`) |
| `scene.ts` | `SkynyScene`: junta todo y lo anima |
| `spin.ts` | Tocar y girar a Skyny arrastrando (login) |
| `transitions.ts` | Las demos (`DEMOS`, `DemoRunner`) y `retryMessage` |
| `tokens.ts` | Colores leídos del tema |
| `Skyny3D.tsx` | El componente de React que usa la web (lee el gorro y los complementos de las preferencias) |

La página del estudio es `web/skyny-studio.html` + `web/src/studio/` (solo la interfaz: vistas, complementos, botones y lista). En el móvil, los controles se pliegan bajo «Controles» y la lista de estados va en un cajón abajo (Agrandar / Recoger / Mostrar).

## Colores

El cuerpo de cada estado es su token `--skyny-3d-<estado>` de `index.css` (claro y oscuro), con los colores claros del estudio v4.3; las bandas son ese color oscurecido. No sigue el acento de la paleta ni el `--c-lo` del Skyny 2D: Diego quiere el 3D como en el estudio. Los ojos usan `--companion-glow` y `--companion-eye`; los nodos, `--companion-node`.

Para cambiar el color de un estado en 3D, cambia su `--skyny-3d-<estado>` en los tres bloques de `index.css` (claro, oscuro del sistema y `[data-theme='dark']`). Solo los ojos de algunos estados tienen color fijo, en `states.ts`.

## Editar con Codex (u otro agente)

- Trabaja sobre `web/src/components/companion/three/` y comprueba en `/skyny-studio.html`. **No** generes otra vez un HTML suelto: si hace falta uno para enseñarlo, `npm run studio:export`.
- Lo que sea solo de la página del estudio (botones, paneles) va en `web/src/studio/`; lo que hace Skyny, en `three/`, para que llegue también a la web.
- No metas three por CDN ni variables globales: se importa de `three` (npm). Los añadidos, de `three/addons/…`.
- No pongas hex en el modelo para los colores de estado: usa los tokens (arriba).
- Nada de cálculos pesados al arrancar (la versión original tardaba ~1 s por banda con rayos contra la malla): si hace falta, una fórmula como `surfaceAt`.
- Libera lo que crees al cambiar de estado o de complemento (`disposeGroup`, `clearExtra`; `dispose()` lo suelta todo), y nada de `Math.random` en lo que salga en capturas (solo el parpadeo lo usa).
- Antes de subir: `npx tsc -b && npx oxlint && npx vitest run src/components/companion`.
