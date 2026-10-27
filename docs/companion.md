# Companion

La mascota de Skynet se llama **Skyny** (diminutivo de Skynet) y cuenta cómo van los agentes. Se presenta por su nombre en el saludo del Dashboard y en Ajustes › Companion. Es el isotipo convertido en personaje: el nodo de arriba es la cabeza, los dos de abajo son las manos y el visor oscuro es la cara, con **tres bandas** que van hasta el borde del cuerpo (las laterales suben un poco) y unos ojos cian que brillan. No usa imágenes, solo SVG con CSS.

Desde el hito SY (issue #181, plan `plans/siguiente-hito-sy.md`) se dibuja según la lámina v3 de Diego: cada estado tiene su color, hay una expresión de reposo con animación idle y lleva accesorios de temporada.

Plan del hito CO: `plans/estudio-companion.md` (carpeta de proyecto), aprobado el 2026-10-24. Solo web; la app de escritorio que planteaba la issue #140 queda descartada.

| PR | Qué añade |
|---|---|
| CO-A | la mascota, el motor de estados, la galería de Ajustes y los primeros usos estáticos (login, 404, estados vacíos) |
| CO-B | el companion global: stream en vivo (`after=latest`, `types=`), mascota y menú en la barra, campana y avisos |
| CO-C | la mascota en las páginas (Dashboard, proyecto, ejecución, listas, Uso) y los avisos fuera de la pestaña |
| SY-A | Skyny v3: dibujo nuevo, color por estado, Reposo con animación idle, tres tamaños y accesorios de temporada |
| SY-B | Skyny como icono de la app: favicon, favicon de estado, iconos de iOS y Android (con maskable) y marca de la barra lateral |
| SY-C | interacciones: ojos que siguen al cursor, se tapa los ojos en el login, frases al pulsarla, se despierta, confeti, reacciones, editor, carga, cuota apurada y saludo por hora y racha |

## Componente

```tsx
import { Companion } from './components/companion/Companion'

<Companion state="running" size={56} />                       // decorativa: el texto va al lado
<Companion state="waiting" size={144} label="Mascota de Skynet" /> // se anuncia como imagen
<Companion state="success" size={30} still />                  // quieta (listas, nodos, galerías)
<Companion state="calm" size={92} season="christmas" />         // fuerza un accesorio (null: ninguno)
<Companion state="calm" size={128} look pose="cover" />          // sigue al cursor y se tapa los ojos
<Companion state="calm" size={80} gesture="hop" sweat />         // gesto de una vez y gotita de sudor
```

- **Colores.** El cuerpo es un degradado de `--c-hi` a `--c-lo`, que cambian con el estado: Reposo y Revisando usan `--primary` (siguen al acento de UI-G), Pensando `--live`, Ejecutando `--skyny-orange`, Éxito `--ok`, Error `--bad`, Te necesita `--skyny-yellow`, y los demás grises. Las bandas, el visor, los nodos y los ojos usan `--companion-band`, `--companion-visor`, `--companion-node`, `--companion-eye` y `--companion-glow`. Cada instancia define sus propios degradados, máscara y filtro con `useId`.
- **Tamaños** (`data-size`). Pequeño (`s`, menos de 32 px): sin efectos, brillo, aro ni resplandor, con ojos y bandas más gruesos. Mediano (`m`, de 32 a 63 px): con efectos, sin animación idle. Grande (`l`, 64 px o más): completo. En la web: 128–160 px en los héroes, 96 px en los estados vacíos, 56–92 px en las cabeceras, 30–48 px en avisos, menú y listas, y 18–22 px en el grafo.
- **Accesibilidad.** Sin `label` lleva `aria-hidden`, y el estado tiene que decirse en el texto de al lado. Con `label` es `role="img"` y se anuncia como «label: Expresión». El estado nunca se transmite solo con el color: también cambian los ojos, el gesto y la insignia.

## Expresiones y estados

La traducción a expresiones está en `components/companion/state.ts`: funciones puras con tests de tabla.

| Expresión | Gesto | Cuándo |
|---|---|---|
| Reposo (`calm`) | azul del acento; en grande, animación idle de 7 s (se ladea, mira a los lados, parpadea, guiña y le salen destellos) | todo tranquilo: login, saludo, campana vacía, proyecto o Uso sin nada que contar |
| Inactivo (`sleeping`) | gris, ojos cerrados, «zZ» | nada en marcha ni pendiente durante 5 min |
| En espera (`ready`) | gris, parpadea, bocadillo «?» | agente `QUEUED`, fase `PENDING`/`READY`, ejecución `PENDING` |
| Pensando (`thinking`) | morado, un ojo cerrado, burbujas, se ladea | agente `STARTING`/`THINKING`, fase `STARTING` |
| Ejecutando (`running`) | naranja, ojos «^ ^», manos alternas, líneas de velocidad | agente `EXECUTING`, fase `RUNNING` |
| Revisando (`reviewing`) | azul del acento, lupa grande en la mano derecha apuntando a la derecha y los ojos hacia ella | fase con una verificación `QUEUED`/`RUNNING` |
| Éxito (`success`) | verde, salta, confeti | `COMPLETED`, `SUCCEEDED` |
| Error (`error`) | rojo, «> <», destellos, sacudida | `FAILED` |
| Te necesita (`waiting`) | amarillo, bocadillo «!», saluda con la mano | `WAITING_FOR_APPROVAL` (W5), `WAITING_FOR_INPUT` |
| En pausa (`paused`) | gris, ojos «– –», bocadillo de pausa | fase con un reintento programado (`notBefore` en el futuro) |
| Sin conexión (`offline`) | gris, ojos de punto, señal de prohibido | agente `UNRESPONSIVE`, stream en vivo caído |
| Cancelado (`cancelled`) | gris, ojos «– –», quieto (no está en la lámina) | `CANCELLED`, fase `SKIPPED` |

- **`agentExpression`, `stageExpression` y `runExpression`.** Cuando hay varias expresiones a la vez (fases en paralelo, varios agentes), gana la de más prioridad: Te necesita › Error › Sin conexión › Revisando › Ejecutando › Pensando › En pausa › En espera.
- **`globalExpression`.** Calcula la mascota de toda la web (CO-B). El orden es: Sin conexión › error sin ver › aprobaciones pendientes › trabajo en curso › Éxito (durante 8 s) › En espera (ejecuciones en cola) › Reposo › Inactivo, a partir de 5 minutos sin nada.

## Movimiento

- **Animaciones.** Solo `transform` y `opacity`. «Ejecutando» repite un ciclo de 4 fotogramas en 1,2 s.
- **Cambio de expresión.** El visor parpadea: se cierra en 110 ms (`SWAP_MS`), cambian los ojos y se abre. Si la expresión cambia otra vez durante el parpadeo, se queda la última.
- **Sin animación.**
  - Con `still` (listas, nodos y galerías), salvo al pasar el ratón. Se quitan, no se pausan: una animación finita en pausa (el «pop» del éxito) no terminaría nunca, y las E2E de axe esperan a que acaben las finitas.
  - Con `prefers-reduced-motion`.
  - Con la pestaña oculta, el propio navegador las detiene.

## Companion global (CO-B)

`CompanionProvider` (en `AppShell`) es la única fuente: abre **un solo stream SSE** para toda la web y lo comparte por contexto (`useCompanion`).

- **Stream.** `/api/events/stream?after=latest&types=…`: empieza desde ahora (sin reenviar el histórico) y solo trae los tipos de `COMPANION_EVENT_TYPES` (`notices.ts`): `workflow.*`, `run.resumed`, `stage.status.changed`, `stage.retry.scheduled`, `stage.verification.*`, `agent.status.changed`, `agent.rate_limit` y `approval.*`. Al reconectar, el navegador manda `Last-Event-ID` y el servidor sigue desde ahí; además se releen las ejecuciones y las aprobaciones.
- **Estado de partida.** Las ejecuciones `PENDING` y `RUNNING` (`GET /api/workflow-runs?status=PENDING&status=RUNNING&size=50`, cada 30 s como red de seguridad) y las aprobaciones pendientes (`usePendingApprovals`, cada 10 s). Los cambios de fases y agentes se aplican en memoria con `applyEvent`, como en la página de ejecución.
- **Cara global.** `globalExpression`: Sin conexión si el stream lleva más de 5 s caído, Error mientras haya un fallo sin ver (se da por visto al abrir esa ejecución o al cerrar la campana), Te necesita con aprobaciones pendientes, luego el trabajo en curso, Éxito durante 8 s, En espera si solo hay ejecuciones en cola, Reposo si no hay nada y, tras 5 min sin nada, Inactivo.

![Mascota de la barra, campana y avisos flotantes](ui/companion-avisos.png)

### Barra superior

- **Mascota (34 px).** Es un botón con nombre accesible («Skynet: Ejecutando. 2 ejecuciones activas»). Lleva un punto verde si hay algo en vivo, y al pasar el ratón dice lo mismo en un tooltip.
- **Menú rápido:** Ejecuciones activas (n), Te necesitan (n), Avisos recientes (abre la campana), Silenciar 1 h / Quitar el silencio y Ajustes del companion.
- **Campana.** Guarda los últimos 50 avisos, cada uno con su cara, su hora y un enlace. El número rojo cuenta los no leídos, que quedan leídos al cerrarla. «Vaciar los avisos» la deja limpia.

### Avisos

| Evento | Aviso | Se cierra |
|---|---|---|
| `approval.requested` | «Te necesito · La fase «aprobar» de DEMO-9 espera tu decisión» · Revisar | al decidir (`approval.granted`/`rejected`/`expired`) o cerrar |
| agente `WAITING_FOR_INPUT` | «DEMO-9 te pregunta algo» · Ver | al cerrar |
| ejecución `SUCCEEDED` | «Workflow completado · DEMO-11 · 8 min · 0,42 $» | solo, a los 6 s |
| ejecución `FAILED` | «Ha ocurrido un error · DEMO-7 · fase «tests»» · Ver | al cerrar |
| `workflow.started`, `run.resumed` | «Ejecutando workflow · DEMO-9 · Nombre del workflow» | solo, a los 4 s |
| agente `UNRESPONSIVE` | «El agente de DEMO-12 no responde» · Ver | al cerrar |
| `agent.rate_limit` (no `allowed`) | «Claude Code está al límite · DEMO-9 · reintento a las 18:40» | solo, a los 6 s |

Reglas contra el ruido (`pushToast`):

- **Nada de cambios internos.** Pensando ↔ ejecutando, herramientas o fases que avanzan no avisan.
- **Agrupar.** Dos o más del mismo tipo con menos de 10 s entre ellos van en una tarjeta («3 ejecuciones completadas · DEMO-9, DEMO-11 y DEMO-12», con «Ver todas»).
- **Como mucho 3 a la vez.** La más antigua sale, pero sigue en la campana.
- **La ejecución que estás mirando** no avisa (el aviso entra leído en la campana), salvo las aprobaciones.
- **Silenciar 1 h** y la opción «Avisos flotantes» de Ajustes quitan las tarjetas, no la campana.

La zona de avisos es `aria-live="polite"` (sin `role="status"`, que ya usan los mensajes de las páginas). Abajo a la derecha; en el móvil, arriba.

### Ajustes

En Ajustes › Companion hay opciones que valen **solo para este navegador** (`localStorage`, `prefs.ts`): «Mostrar la mascota en la barra superior», «Avisos flotantes» y «Accesorios de temporada». El silencio de 1 h se guarda en el mismo sitio.

### Accesorios de temporada (SY-A)

Skyny lleva un sombrero según la fecha (`season.ts`, hora local, se mira al montar). Va ladeado sobre el lado izquierdo de la cabeza, para no chocar con los bocadillos, que salen arriba a la derecha:

| Accesorio | Cuándo |
|---|---|
| Sombrero de bruja (Halloween) | del 24 de octubre al 2 de noviembre |
| Gorro de Papá Noel (Navidad) | del 15 de diciembre al 6 de enero |
| Sombrero de paja (verano) | julio y agosto |

Solo sale desde 32 px (nunca en listas, grafo ni favicon). Se quita con «Accesorios de temporada» en Ajustes › Companion, donde también se ven los tres. `season` en el componente fuerza uno o ninguno (`null`).

## Interacciones (SY-C)

Lote 1 del censo de `plans/siguiente-hito-sy.md`. Todo en el navegador, sin backend nuevo.

- **Props del componente.**
  - `gesture`: un gesto de una vez encima de la expresión: `hop` (saltito con ojos de risa), `nod` (asiente serio, ojos «– –») y `yawn` (bosteza estirando las manos, ojos cerrados). `useGesture` (en `gesture.ts`) lo lanza y lo quita al terminar (`GESTURE_MS`).
  - `pose`: las manos sobre el visor. `cover` tapa los dos ojos; `peek` deja ver el derecho, de reojo.
  - `look`: los ojos siguen al cursor y al foco del teclado, hasta 4 unidades (`lookOffset` en `look.ts`). Solo en grande y sin «reducir movimiento»; mueve el grupo `.companion-gaze` sin volver a pintar.
  - `sweat`: gotita de sudor (no sale en pequeño).
- **Dónde se ven.**

| # | Interacción | Dónde |
|---|---|---|
| 1 | Los ojos siguen al cursor o al foco | login, mascota que asoma, saludo, Ajustes |
| 2 | Se tapa los ojos al escribir la contraseña y mira de reojo si la muestras (botón del ojo) | login |
| 3 | Al pulsarla da un saltito y dice una frase (`SKYNY_QUIPS`, por turnos, 5 s) | mascota que asoma (Dashboard, Proyecto, Uso) y saludo |
| 4 | Dormida (Inactivo), se despierta con un bostezo al mover el ratón, teclear o volver a la pestaña | barra superior y Dashboard |
| 5 | Confeti a pantalla completa (1,6 s) si la ejecución que miras termina bien; la banda salta | ejecución (`useCelebration`, `Celebration`) |
| 6 | Reacciona a tu decisión: salta al aprobar («¡Gracias! Sigo con ello.») y asiente al rechazar, pedir cambios o comentar | aprobación (`SkynySays`) |
| 7 | Cara de lo que ve: Pensando al validar, Error si hay errores, Reposo si no; salta al publicar | editor de workflows (Problemas) y versión publicada |
| 8 | Skyny Ejecutando en lugar del «Cargando…» suelto (`SkynyLoading`) | arranque de la web, agente, evento y diff |
| 9 | Gotita de sudor con alguna alerta de cuota (las de CU: 90 % o por encima del ritmo semanal) | barra superior, Dashboard y Uso |
| 10 | El saludo cambia con la hora si no hay nada en marcha y cuenta la racha («Llevas 5 ejecuciones seguidas sin fallos») desde 3 (`successStreak`, últimas 20) | saludo del Dashboard |

- **Movimiento.** Con «reducir movimiento» no hay gestos, ni transiciones de las manos, ni seguimiento de los ojos, ni confeti; las caras de los gestos y las poses sí cambian.
- **Ajustes › Companion.** Bajo la mascota grande hay botones para ver los gestos (Saltito, Asentir, Bostezo), las poses (Taparse los ojos, De reojo) y la gotita (Sudando).

## En las páginas y fuera de la pestaña (CO-C)

### Mascota que asoma

En la cabecera, a la derecha del título, asoma una mascota de 92 px (80 px en Proyecto) con un bocadillo corto (`CompanionPeek`, textos en `peek.ts`):

- **Dashboard:** la cara global y «1 aprobación te espera», «¡Todo en marcha! 🚀», «Algo ha fallado» o «Todo al día».
- **Proyecto:** la cara más fuerte de sus ejecuciones en marcha y «2 ejecuciones en marcha» o «Nada en marcha».
- **Uso:** En pausa y «Vamos justos de cuota» si hay alertas de cuota; si no, Reposo y «Hay cuota de sobra». Solo cuando hay datos.

Bajo la cabecera del Dashboard va la tarjeta de saludo (`CompanionHello`): «Hola, soy Skyny. Hay 1 ejecución en marcha y 1 aprobación esperándote.» con atajos a revisar la aprobación, lanzar un workflow y ver la última ejecución.

### Ejecución, tabla y grafo

- **Banda «Qué pasa ahora»** (`RunBand`), bajo el progreso de la ejecución: la mascota de 56 px y quién trabaja y en qué («Claude Code está usando Read», «La fase «Plan» espera tu decisión», «Ha fallado la fase «tests»»), con fase, duración y coste debajo. Se queda quieta cuando la ejecución termina.
- **Tabla de ejecuciones:** la columna Estado lleva la cara de 30 px de cada ejecución junto a la pastilla.
- **Grafo:** las fases y los agentes llevan la cara (22 y 18 px) en lugar del icono de estado.

Las caras pequeñas van quietas (`still`) para que una lista no se mueva entera.

### Fuera de la pestaña

- **Título:** con avisos sin leer, «(2) Skynet»; al leerlos vuelve a «Skynet».
- **Favicon:** Skyny en reposo, del color del acento (SY-B). Con Te necesita pasa a Skyny amarillo y con Error a Skyny rojo con «> <». Son SVG con colores fijos (`skynySvg` en `lib/brand.ts`), porque el favicon no ve los tokens.
- **Avisos del navegador** (opcional, Ajustes › Companion › «Avisos del navegador con la pestaña oculta»): al activarlo se pide permiso. Solo salen con la pestaña oculta y para aprobaciones, preguntas, fallos y éxitos. Llevan clave, nombre y fase, nunca prompts ni salidas, y uno por ejecución (`tag`), así que el siguiente sustituye al anterior. Al pulsarlo se enfoca la pestaña y se abre su enlace. El silencio de 1 h también los calla.

## Dónde aparece (CO-A)

- **Login:** héroe Reposo de 128 px.
- **Estados vacíos (`EmptyState` con `companion`):**
  - Proyectos, Ejecuciones y Aprobaciones sin nada todavía: Inactivo.
  - Runners sin registrar: Sin conexión.
  - Uso sin datos: En pausa.
  - 404: Pensando.
  - Con filtros activos se mantiene el icono.
- **Ajustes › Companion:** galería con todas las expresiones; al elegir una, la grande cambia con su parpadeo.
