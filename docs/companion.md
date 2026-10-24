# Companion

La mascota de Skynet cuenta cómo van los agentes. Es el isotipo convertido en personaje: el nodo de arriba es la cabeza, los dos de abajo son las manos y el anillo es la cara, con un visor oscuro donde cambian los ojos. Es plana y en pastel, como el resto de la web, y no usa imágenes, solo SVG con CSS.

Plan del hito CO: `plans/estudio-companion.md` (carpeta de proyecto), aprobado el 2026-10-24. Solo web; la app de escritorio que planteaba la issue #140 queda descartada.

| PR | Qué añade |
|---|---|
| CO-A | la mascota, el motor de estados, la galería de Ajustes y los primeros usos estáticos (login, 404, estados vacíos) |
| CO-B | el companion global: stream en vivo, mascota y menú en la barra, campana y avisos |
| CO-C | la mascota en las páginas (Dashboard, proyecto, ejecución, listas, Uso) y los avisos fuera de la pestaña |

## Componente

```tsx
import { Companion } from './components/companion/Companion'

<Companion state="running" size={56} />                       // decorativa: el texto va al lado
<Companion state="waiting" size={144} label="Mascota de Skynet" /> // se anuncia como imagen
<Companion state="success" size={30} still />                  // quieta (listas, nodos, galerías)
```

- **Colores.** El cuerpo es `--primary` mezclado con blanco, así que sigue al acento (UI-G) y al tema. Los nodos y el visor usan `--companion-node` y `--companion-visor`, y los efectos los colores de estado (`--ok`, `--warn`, `--live`, `--bad`, `--idle`). El error tiñe el cuerpo de rojo; «Sin conexión» y «Cancelado» lo pasan a gris.
- **Tamaños.** 128–160 px en los héroes, 96 px en los estados vacíos, 56–80 px en las cabeceras, 30–40 px en avisos y listas, y 16 px en la pestaña. Por debajo de 32 px desaparecen los efectos, el brillo y la sombra, y el contorno se engrosa.
- **Accesibilidad.** Sin `label` lleva `aria-hidden`, y el estado tiene que decirse en el texto de al lado. Con `label` es `role="img"` y se anuncia como «label: Expresión». El estado nunca se transmite solo con el color: también cambian los ojos, el gesto y la insignia.

## Expresiones y estados

La traducción a expresiones está en `components/companion/state.ts`: funciones puras con tests de tabla.

| Expresión | Gesto | Cuándo |
|---|---|---|
| Inactivo (`sleeping`) | ojos cerrados, «zZ» | nada en marcha ni pendiente durante 5 min |
| En espera (`ready`) | parpadea | agente `QUEUED`, fase `PENDING`/`READY`, ejecución `PENDING` |
| Pensando (`thinking`) | mira arriba, cabeza ladeada, «…» | agente `STARTING`/`THINKING`, fase `STARTING` |
| Ejecutando (`running`) | ojos «^ ^», manos alternas, líneas de velocidad | agente `EXECUTING`, fase `RUNNING` |
| Revisando (`reviewing`) | mira el portátil | fase con una verificación `QUEUED`/`RUNNING` |
| Éxito (`success`) | salta, confeti | `COMPLETED`, `SUCCEEDED` |
| Error (`error`) | «> <», cuerpo rojo, sacudida | `FAILED` |
| Te necesita (`waiting`) | levanta la mano y saluda, «!» | `WAITING_FOR_APPROVAL` (W5), `WAITING_FOR_INPUT` |
| En pausa (`paused`) | ojos «– –», insignia de pausa | fase con un reintento programado (`notBefore` en el futuro) |
| Sin conexión (`offline`) | gris, insignia sin señal | agente `UNRESPONSIVE`, stream en vivo caído |
| Cancelado (`cancelled`) | gris, quieto | `CANCELLED`, fase `SKIPPED` |

- **`agentExpression`, `stageExpression` y `runExpression`.** Cuando hay varias expresiones a la vez (fases en paralelo, varios agentes), gana la de más prioridad: Te necesita › Error › Sin conexión › Revisando › Ejecutando › Pensando › En pausa › En espera.
- **`globalExpression`.** Calcula la mascota de toda la web (CO-B). El orden es: Sin conexión › error sin ver › aprobaciones pendientes › trabajo en curso › Éxito (durante 8 s) › En espera › Inactivo, a partir de 5 minutos sin nada.

## Movimiento

- **Animaciones.** Solo `transform` y `opacity`. «Ejecutando» repite un ciclo de 4 fotogramas en 1,2 s.
- **Cambio de expresión.** El visor parpadea: se cierra en 110 ms (`SWAP_MS`), cambian los ojos y se abre. Si la expresión cambia otra vez durante el parpadeo, se queda la última.
- **Animaciones en pausa.**
  - Con `still` (listas, nodos y galerías), salvo al pasar el ratón.
  - Con la pestaña oculta.
  - Con `prefers-reduced-motion`, no hay ninguna animación.

## Dónde aparece (CO-A)

- **Login:** héroe En espera de 128 px.
- **Estados vacíos (`EmptyState` con `companion`):**
  - Proyectos, Ejecuciones y Aprobaciones sin nada todavía: Inactivo.
  - Runners sin registrar: Sin conexión.
  - Uso sin datos: En pausa.
  - 404: Pensando.
  - Con filtros activos se mantiene el icono.
- **Ajustes › Companion:** galería con todas las expresiones; al elegir una, la grande cambia con su parpadeo.
