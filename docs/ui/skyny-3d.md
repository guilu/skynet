# Skyny 3D

Skyny en 3D (hito TD) está hecho con [three.js](https://threejs.org/). Desde TD-A sale en el login y, desde SV-B, en la banda «Qué pasa ahora» de cada ejecución; el resto de la web sigue con el Skyny 2D (`Companion.tsx`, ver [`../companion.md`](../companion.md)).

## Skyny en vivo (SV-B)

`SkynyLive` (`web/src/components/companion/SkynyLive.tsx`) es el Skyny 3D que cuenta lo que pasa. Recibe el estado del companion y:

- lo deja al menos **1,2 s** en cada estado (`settle.ts`): si llegan varios seguidos (Pensando ↔ Ejecutando en cada herramienta), se queda con el último. Lo urgente (Te necesita, Error, Sin conexión, Cancelado) entra enseguida;
- escribe el **letrero** del estado al llegar (cortos por debajo de 160 px: «IDEA», «REVISA»), o uno propio (`message`);
- enseña el **contador** (`progress`) en Ejecutando;
- solo sigue al cursor con los ojos cuando pasa cerca de la cara (en el login, lo sigue por toda la página);
- con movimiento reducido, sin WebGL 2, mientras carga three o si el 3D falla, enseña el `fallback` (el Skyny 2D). Por eso las capturas de la galería visual, que van con movimiento reducido, siguen en 2D.

En la ejecución (`RunBand`), Skyny mide 120 px. `runLive.ts` decide qué cuenta: con varias fases, por cuál va (2/4); en un reintento de la fase, «REINTENTO 1/2» (con su política de reintentos) o «INTENTO 3» (si se reintentó a mano). Cada página tiene como mucho un Skyny 3D: cada uno es un contexto WebGL.

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

## Qué hace Skyny 3D (estudio v2)

- **Al cambiar de estado**, los colores (cuerpo, bandas, carcasa y luz de abajo) y la postura se funden en 300 ms, el accesorio anterior se desvanece en 220 ms y llega el gesto de entrada del nuevo.
- **Letreros en la pantalla** (`led.ts`): al acabar el gesto escribe letra a letra lo que hace («PENSANDO...», «EN MARCHA», «LISTO», «AYUDA», «SIN RED»…) con cursor, lo deja un segundo y vuelve a poner los ojos. En Éxito escribe primero «LISTO» y luego salta. En Error el letrero es rojo. `setState(estado, { message })` pone otro letrero (p. ej. `retryMessage(1, 2)` → «REINTENTO 1/2»).
- **Contador** de Ejecutando: `setProgress(hechos, total)` lo pinta en grande (2/5) después del letrero; en compacto, en su lugar. `setProgress(null)` lo quita.
- **Te necesita, En pausa y Sin conexión**: la mano derecha sube y sostiene el símbolo («?», pausa, señal tachada), que la sigue.
- **En reposo** (Reposo y En espera) mira a un lado cada 7,6 s, parpadea, y sigue con los ojos al puntero si pasa a menos de 200 px de la cara (`pointer({ x, y })`). **Pensando**, guiña poco a poco y de vez en cuando mira arriba a la izquierda.
- Las manos flotan con un pequeño retraso respecto al cuerpo. La cabeza ya no se ve (de su sitio sale el confeti); las manos están a ±0,88.
- **Modelo**: acabado mate con poco barniz, bisel de metal plano, y las tres bandas acaban en una **hebilla triangular** en la espalda (`buckleGeometry`), con el mismo acabado que la carcasa de la cara.
- `busy` dice si Skyny está con algo a medias (gesto, letrero o contador): las demos esperan a que acabe antes de pasar al siguiente estado.

## Dónde está cada cosa

Todo en `web/src/components/companion/three/`. Las secciones son las de los estudios sueltos (en la carpeta del proyecto, `plans/skyny-3d/skyny-studio-v1.html` y `skyny-v2.0.html`):

| Fichero | Qué tiene | Sección del estudio |
|---|---|---|
| `body.ts` | Forma del cuerpo (`deform`), recorrido de las bandas (`BANDS`) y `surfaceAt`, que apoya las bandas sobre el cuerpo | 5 y 6 |
| `model.ts` | Entorno, luces, materiales, cara (carcasa, bisel, pantalla), nodos y sombra del suelo | 4, 7 y 8 |
| `face.ts` | Los ojos que se pintan en la pantalla OLED (`drawFace`) | 9 |
| `accessories.ts` | Burbujas, líneas de velocidad, lupa, confeti, rayos, «?», pausa, zetas y señal tachada | 10 |
| `states.ts` | Qué ojos y qué accesorio lleva cada estado (`EXPRESSIONS_3D`) | 1 (`STATES`) |
| `led.ts` | Letreros de la pantalla: textos de cada estado, trazos de las letras y el tecleo | 12 (`LED_MESSAGES`, `LED_GLYPHS`) |
| `motion.ts` | Curvas de los gestos: entrada, carrerilla, sacudida, salto, fundidos, mano que sube, miradas en reposo… | 13 |
| `scene.ts` | `SkynyScene`: junta todo y lo anima (fundidos, gestos, letreros, flotar, parpadeo, poses, mirada) | 12 y 13 |
| `transitions.ts` | Las demos (`DEMOS`, `DemoRunner`) y `retryMessage` | 12 (`TASK_DEMOS`) |
| `tokens.ts` | Colores leídos del tema | — |
| `Skyny3D.tsx` | El componente de React que usa la web | — |

La página del estudio es `web/skyny-studio.html` + `web/src/studio/` (solo la interfaz: vistas, botones y lista).

## Colores

El 3D **no lleva colores propios**: el cuerpo de cada estado es el mismo `--c-lo` que el Skyny 2D (`.companion[data-state]` en `index.css`), y las bandas son ese color oscurecido. Los ojos usan `--companion-glow` y `--companion-eye`; los nodos, `--companion-node`. Así casa con las píldoras de estado, el modo oscuro y las paletas personalizadas.

Para cambiar el color de un estado, cambia su token en `index.css`: cambia a la vez en 2D y en 3D. Solo los ojos de algunos estados (Error, En pausa, Inactivo, Sin conexión, Cancelado) tienen color fijo, en `states.ts`.

## Editar con Codex (u otro agente)

- Trabaja sobre `web/src/components/companion/three/` y comprueba en `/skyny-studio.html`. **No** generes otra vez un HTML suelto: si hace falta uno para enseñarlo, `npm run studio:export`.
- Lo que sea solo de la página del estudio (botones, paneles) va en `web/src/studio/`; lo que hace Skyny, en `three/`, para que llegue también a la web.
- No metas three por CDN ni variables globales: se importa de `three` (npm). Los añadidos, de `three/addons/…`.
- No pongas hex en el modelo para los colores de estado: usa los tokens (arriba).
- Nada de cálculos pesados al arrancar (la versión original tardaba ~1 s por banda con rayos contra la malla): si hace falta, una fórmula como `surfaceAt`.
- Libera lo que crees al cambiar de estado (`disposeTree`), y nada de `Math.random` en lo que salga en capturas (el confeti usa una semilla fija).
- Antes de subir: `npx tsc -b && npx oxlint && npx vitest run src/components/companion`.
