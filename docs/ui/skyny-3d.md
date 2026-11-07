# Skyny 3D

Skyny en 3D (hito TD) está hecho con [three.js](https://threejs.org/). Desde TD-A sale en el login; el resto de la web sigue con el Skyny 2D (`Companion.tsx`, ver [`../companion.md`](../companion.md)).

Desde TD-B, **el estudio donde se diseña está dentro del repo** y usa el mismo código que la app. No hay que portar nada: lo que se cambia en el estudio es lo que sale en la web.

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
- **Tema**, que cambia entre claro y oscuro: los colores salen de los tokens de la web.

Con `prefers-reduced-motion` no hay gestos de entrada, igual que en la web.

## Dónde está cada cosa

Todo en `web/src/components/companion/three/`. Las secciones son las del estudio original `skyny-studio.html` (en la carpeta del proyecto, `plans/skyny-3d/skyny-studio-v1.html`):

| Fichero | Qué tiene | Sección del estudio |
|---|---|---|
| `body.ts` | Forma del cuerpo (`deform`), recorrido de las bandas (`BANDS`) y `surfaceAt`, que apoya las bandas sobre el cuerpo | 5 y 6 |
| `model.ts` | Entorno, luces, materiales, cara (carcasa, bisel, pantalla), nodos y sombra del suelo | 4, 7 y 8 |
| `face.ts` | Los ojos que se pintan en la pantalla OLED (`drawFace`) | 9 |
| `accessories.ts` | Burbujas, líneas de velocidad, lupa, confeti, rayos, «?», pausa, zetas y señal tachada | 10 |
| `states.ts` | Qué ojos y qué accesorio lleva cada estado (`EXPRESSIONS_3D`) | 1 (`STATES`) |
| `motion.ts` | Curvas de los gestos: entrada, carrerilla, sacudida, salto… | 13 |
| `scene.ts` | `SkynyScene`: junta todo y lo anima (gestos, flotar, parpadeo, poses, mirada) | 12 y 13 |
| `tokens.ts` | Colores leídos del tema | — |
| `Skyny3D.tsx` | El componente de React que usa la web | — |

La página del estudio es `web/skyny-studio.html` + `web/src/studio/` (solo la interfaz: vistas, botones y lista).

## Colores

El 3D **no lleva colores propios**: el cuerpo de cada estado es el mismo `--c-lo` que el Skyny 2D (`.companion[data-state]` en `index.css`), y las bandas son ese color oscurecido. Los ojos usan `--companion-glow` y `--companion-eye`; los nodos, `--companion-node`. Así casa con las píldoras de estado, el modo oscuro y las paletas personalizadas.

Para cambiar el color de un estado, cambia su token en `index.css`: cambia a la vez en 2D y en 3D. Solo los ojos de algunos estados (Error, En pausa, Inactivo, Sin conexión, Cancelado) tienen color fijo, en `states.ts`.

## Editar con Codex (u otro agente)

- Trabaja sobre `web/src/components/companion/three/` y comprueba en `/skyny-studio.html`.
- No metas three por CDN ni variables globales: se importa de `three` (npm). Los añadidos, de `three/addons/…`.
- No pongas hex en el modelo para los colores de estado: usa los tokens (arriba).
- Nada de cálculos pesados al arrancar (la versión original tardaba ~1 s por banda con rayos contra la malla): si hace falta, una fórmula como `surfaceAt`.
- Libera lo que crees al cambiar de estado (`disposeTree`), y nada de `Math.random` en lo que salga en capturas (el confeti usa una semilla fija).
- Antes de subir: `npx tsc -b && npx oxlint && npx vitest run src/components/companion`.
