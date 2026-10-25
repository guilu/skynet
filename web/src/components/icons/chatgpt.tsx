import type { Drawing } from './ilustrados'
import type { IconName } from './registry'
import { g, INK, WHITE } from './chatgptColors'
import {
  Bell,
  Box,
  Bubble,
  Clock,
  Coin,
  Disc,
  Doc,
  Folder,
  Graph,
  Line,
  Shield,
  Tray,
} from './chatgptParts'

/* Paquete «ChatGPT»: los iconos de las láminas que trajo Diego, redibujados en SVG. Volumen con
 * degradados (claro arriba, intenso abajo), un brillo blanco y colores propios de cada objeto, no
 * del acento. Lienzo de 32 × 32. Los degradados viven una sola vez en la página (`CHATGPT_GRADIENTS` de chatgptColors.ts,
 * los pinta `ChatgptDefs`) y cada forma los usa con `g('azul')`. Las piezas con clase `a a-…` hacen
 * el gesto con las mismas animaciones que Ilustrados. */

/** Engranaje redondeado de 8 dientes con su agujero (regla par-impar). */
const GEAR = (() => {
  const p = (r: number, deg: number) =>
    [16 + r * Math.cos((deg * Math.PI) / 180), 16 + r * Math.sin((deg * Math.PI) / 180)]
      .map((n) => n.toFixed(2))
      .join(' ')
  let d = ''
  for (let i = 0; i < 8; i++) {
    const c = i * 45 - 22.5
    d += `${i ? 'L' : 'M'}${p(9.6, c - 14)} L${p(13, c - 8)} L${p(13, c + 8)} L${p(9.6, c + 14)} `
  }
  return `${d}Z M16 11.6a4.4 4.4 0 1 0 0 8.8a4.4 4.4 0 1 0 0-8.8z`
})()

/** Estrella de cuatro puntas con los lados curvos. */
const star = (x: number, y: number, r: number) =>
  `M${x} ${y - r} Q${x} ${y} ${x + r} ${y} Q${x} ${y} ${x} ${y + r} Q${x} ${y} ${x - r} ${y} Q${x} ${y} ${x} ${y - r}Z`

export const CHATGPT: Partial<Record<IconName, Drawing>> = {
  /* — Navegación — */
  dashboard: {
    gesture: 'la puerta se abre',
    body: (
      <>
        <rect x="20" y="5" width="4" height="7" rx="1.2" fill={g('red')} />
        <path d="M7 14.5h18V26a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2z" fill={g('yellow')} />
        <path
          d="M3.6 15.4 16 4.6l12.4 10.8a1.6 1.6 0 0 1-1.1 2.8H4.7a1.6 1.6 0 0 1-1.1-2.8z"
          fill={g('red')}
        />
        <path
          d="M8.5 13.6 15 8"
          stroke={WHITE}
          strokeOpacity=".55"
          strokeWidth="2"
          strokeLinecap="round"
        />
        <g className="a a-door">
          <rect x="12.6" y="19" width="6.8" height="9" rx="2.2" fill={g('orange')} />
        </g>
        <rect x="14.2" y="11.2" width="3.6" height="3.6" rx="1.8" fill={g('blue')} />
      </>
    ),
  },
  projects: {
    gesture: 'la carpeta se abre',
    body: (
      <g className="a a-flap">
        <Folder />
      </g>
    ),
  },
  workflows: {
    gesture: 'los nodos laten en orden',
    body: <Graph a="blue" b="blue" c="blue" />,
  },
  runs: {
    gesture: 'el play coge impulso',
    body: (
      <>
        <circle cx="16" cy="16" r="12.5" fill={g('violet')} />
        <path
          d="M8 11.5A9 9 0 0 1 14 6.5"
          stroke={WHITE}
          strokeOpacity=".5"
          strokeWidth="2.2"
          strokeLinecap="round"
          fill="none"
        />
        <g className="a a-play">
          <path
            d="M13 10.6v10.8a1 1 0 0 0 1.5.86l8.6-5.4a1 1 0 0 0 0-1.72l-8.6-5.4A1 1 0 0 0 13 10.6z"
            fill={WHITE}
          />
        </g>
      </>
    ),
  },
  approvals: {
    gesture: 'se dibuja el visto',
    body: (
      <>
        <circle cx="16" cy="16" r="12.5" fill={g('green')} />
        <path
          d="M8 11.5A9 9 0 0 1 14 6.5"
          stroke={WHITE}
          strokeOpacity=".5"
          strokeWidth="2.2"
          strokeLinecap="round"
          fill="none"
        />
        <g className="a a-draw">
          <Line d="m10.5 16.5 3.8 3.8 7.4-8" w={3.6} />
        </g>
      </>
    ),
  },
  runners: {
    gesture: 'parpadean los pilotos',
    body: (
      <>
        {[5, 12.5, 20].map((y, i) => (
          <g key={y}>
            <rect
              x="4"
              y={y}
              width="24"
              height="6.5"
              rx="2"
              fill={g(i === 1 ? 'indigo' : 'blue')}
            />
            <rect x="12" y={y + 2.4} width="12" height="1.8" rx=".9" fill={WHITE} opacity=".5" />
            <circle
              cx="8"
              cy={y + 3.25}
              r="1.4"
              fill={WHITE}
              className={i === 0 ? 'a a-led1' : i === 2 ? 'a a-led2' : undefined}
            />
          </g>
        ))}
      </>
    ),
  },
  usage: {
    gesture: 'las barras crecen una tras otra',
    body: (
      <>
        <g className="a a-n1">
          <rect x="4.5" y="17" width="6" height="11" rx="2" fill={g('blue')} />
        </g>
        <g className="a a-n2">
          <rect x="13" y="11" width="6" height="17" rx="2" fill={g('blue')} />
        </g>
        <g className="a a-n3">
          <rect x="21.5" y="4" width="6" height="24" rx="2" fill={g('blue')} />
        </g>
        <path
          d="M23 7v4"
          stroke={WHITE}
          strokeOpacity=".55"
          strokeWidth="1.6"
          strokeLinecap="round"
        />
      </>
    ),
  },
  activity: {
    gesture: 'el pulso recorre la línea',
    body: (
      <g className="a a-draw">
        <path
          d="M3 17h6l3-8 5 15 3.5-10 2 3H29"
          pathLength={40}
          stroke="url(#cg-pink)"
          strokeWidth="3.6"
          strokeLinecap="round"
          strokeLinejoin="round"
          fill="none"
        />
      </g>
    ),
  },
  settings: {
    gesture: 'el engranaje gira',
    body: (
      <g className="a a-spin">
        <path d={GEAR} fill={g('slate')} fillRule="evenodd" />
      </g>
    ),
  },

  /* — Cabecera — */
  search: {
    gesture: 'la lupa se inclina',
    body: (
      <g className="a a-tilt">
        <path d="m19.5 19.5 7 7" stroke="url(#cg-dark)" strokeWidth="5" strokeLinecap="round" />
        <circle cx="13.5" cy="13.5" r="9.5" fill={g('blue')} />
        <circle cx="13.5" cy="13.5" r="6.3" fill={g('sky')} />
        <path
          d="M10 11.5a4 4 0 0 1 2.5-2.5"
          stroke={WHITE}
          strokeWidth="2"
          strokeLinecap="round"
          fill="none"
        />
      </g>
    ),
  },
  notices: {
    gesture: 'la campana suena',
    body: (
      <>
        <g className="a a-ring">
          <Bell />
        </g>
        <circle cx="24.5" cy="6.5" r="3.6" fill={g('red')} />
      </>
    ),
  },
  'notices-off': {
    gesture: 'la campana se calla',
    body: (
      <>
        <g opacity=".55">
          <Bell />
        </g>
        <Line d="M5 5l22 22" w={3.4} c="#566077" />
      </>
    ),
  },
  inbox: {
    gesture: 'cae un aviso en la bandeja',
    body: (
      <>
        <g className="a a-drop">
          <rect x="10" y="4" width="12" height="11" rx="2" fill={g('paper')} />
          <path d="M13 8h6M13 11h4" stroke="#a8c8ff" strokeWidth="1.6" strokeLinecap="round" />
        </g>
        <Tray />
      </>
    ),
  },
  'theme-system': {
    gesture: 'la pantalla parpadea',
    body: (
      <>
        <rect x="3.5" y="5" width="25" height="17" rx="2.5" fill={g('dark')} />
        <g className="a a-blink">
          <rect x="6" y="7.5" width="20" height="12" rx="1.2" fill={g('blue')} />
          <path
            d="M8.5 10.5 13 9"
            stroke={WHITE}
            strokeOpacity=".5"
            strokeWidth="1.6"
            strokeLinecap="round"
          />
        </g>
        <path d="M12 27.5h8l-1-5.5h-6z" fill={g('slate')} />
      </>
    ),
  },
  'theme-light': {
    gesture: 'los rayos giran',
    body: (
      <>
        <g className="a a-rays">
          {Array.from({ length: 8 }, (_, i) => {
            const r = (i * Math.PI) / 4
            return (
              <path
                key={i}
                d={`M${(16 + 10.5 * Math.cos(r)).toFixed(2)} ${(16 + 10.5 * Math.sin(r)).toFixed(2)} L${(16 + 13.5 * Math.cos(r)).toFixed(2)} ${(16 + 13.5 * Math.sin(r)).toFixed(2)}`}
                stroke="#ffa52e"
                strokeWidth="2.6"
                strokeLinecap="round"
              />
            )
          })}
        </g>
        <circle cx="16" cy="16" r="7.2" fill={g('yellow')} />
        <path
          d="M12.5 13.5a4.5 4.5 0 0 1 2.5-2.4"
          stroke={WHITE}
          strokeOpacity=".7"
          strokeWidth="1.8"
          strokeLinecap="round"
          fill="none"
        />
      </>
    ),
  },
  'theme-dark': {
    gesture: 'la luna se mece y brilla la estrella',
    body: (
      <>
        <g className="a a-rock">
          <path d="M14.5 4.5A11.5 11.5 0 1 0 27.5 18 9 9 0 0 1 14.5 4.5z" fill={g('blue')} />
          <path
            d="M8.5 12a8 8 0 0 1 3-4.5"
            stroke={WHITE}
            strokeOpacity=".5"
            strokeWidth="2"
            strokeLinecap="round"
            fill="none"
          />
        </g>
        <path className="a a-tw1" d={star(23, 8, 3.8)} fill={g('lilac')} />
      </>
    ),
  },
  logout: {
    gesture: 'la flecha sale por la puerta',
    body: (
      <>
        <path
          d="M6 5h10a2 2 0 0 1 2 2v18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"
          fill={g('sky')}
        />
        <g className="a a-nudger">
          <Line d="M12 16h15M22.5 11l5 5-5 5" w={3.4} c="url(#cg-coral)" />
        </g>
      </>
    ),
  },
  menu: {
    gesture: 'las barras se desplazan',
    body: (
      <>
        <g className="a a-menu1">
          <Line d="M6 9h20" w={3.6} c={INK} />
        </g>
        <Line d="M6 16h20" w={3.6} c={INK} />
        <g className="a a-menu3">
          <Line d="M6 23h20" w={3.6} c={INK} />
        </g>
      </>
    ),
  },
  'sidebar-collapse': {
    gesture: 'la barra se pliega',
    body: (
      <>
        <rect x="4" y="5" width="24" height="22" rx="3" fill={g('sky')} />
        <rect x="4" y="5" width="9" height="22" rx="3" fill={g('blue')} />
        <g className="a a-nudgel">
          <Line d="m22 12-4 4 4 4" w={3} c="#2d68f5" />
        </g>
      </>
    ),
  },
  'sidebar-expand': {
    gesture: 'la barra se despliega',
    body: (
      <>
        <rect x="4" y="5" width="24" height="22" rx="3" fill={g('sky')} />
        <rect x="4" y="5" width="9" height="22" rx="3" fill={g('blue')} />
        <g className="a a-nudger">
          <Line d="m18 12 4 4-4 4" w={3} c="#2d68f5" />
        </g>
      </>
    ),
  },
  palette: {
    gesture: 'las gotas de color saltan',
    body: (
      <>
        <path
          d="M16 4C9 4 4 9.2 4 15.6c0 5.4 4.1 9.9 9.2 9.9 1.6 0 2.4-1 2.4-2.2 0-1.5-1.2-2-1.2-3.5 0-1.4 1.1-2.3 2.5-2.3h3.6c3.7 0 6.5-2.5 6.5-6.1C27 6.9 22 4 16 4z"
          fill={g('yellow')}
        />
        <circle className="a a-n1" cx="10" cy="14" r="2.4" fill={g('red')} />
        <circle className="a a-n2" cx="14.5" cy="9" r="2.4" fill={g('blue')} />
        <circle className="a a-n3" cx="20.5" cy="9.6" r="2.4" fill={g('green')} />
        <circle cx="10.4" cy="20" r="2.4" fill={g('violet')} />
      </>
    ),
  },
  icons: {
    gesture: 'las tres formas saltan',
    body: (
      <>
        <circle className="a a-n1" cx="10.5" cy="10.5" r="6.5" fill={g('blue')} />
        <path className="a a-n2" d="M22.5 4 28.8 15H16.2z" fill={g('orange')} />
        <rect className="a a-n3" x="15.5" y="17" width="12" height="11" rx="3" fill={g('green')} />
        <circle cx="10" cy="22.5" r="5" fill={g('pink')} />
      </>
    ),
  },

  /* — Estados — */
  'status-pending': {
    gesture: 'el reloj de arena da la vuelta',
    body: (
      <g className="a a-flip">
        <rect x="7" y="3.5" width="18" height="3.5" rx="1.6" fill={g('blue')} />
        <rect x="7" y="25" width="18" height="3.5" rx="1.6" fill={g('blue')} />
        <path
          d="M9.5 7h13c0 4.5-4 6.5-4 9s4 4.5 4 9h-13c0-4.5 4-6.5 4-9s-4-4.5-4-9z"
          fill={g('sky')}
        />
        <path
          d="M12 9h8c-.6 2-2.4 3.3-4 4.4-1.6-1.1-3.4-2.4-4-4.4zM11.6 24c.6-2.4 2.6-3.6 4.4-4.4 1.8.8 3.8 2 4.4 4.4z"
          fill={g('blue')}
        />
      </g>
    ),
  },
  'status-ready': {
    gesture: 'el anillo gira',
    badge: true,
    tone: 'idle',
    body: (
      <g className="a a-spin">
        <circle
          cx="16"
          cy="16"
          r="11"
          fill="none"
          stroke="url(#cg-slate)"
          strokeWidth="4"
          strokeDasharray="5 4"
          strokeLinecap="round"
        />
      </g>
    ),
  },
  'status-starting': {
    gesture: 'los puntos dan vueltas',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <g className="a a-spin">
        {Array.from({ length: 8 }, (_, i) => {
          const r = (i * Math.PI) / 4
          return (
            <circle
              key={i}
              cx={16 + 11 * Math.cos(r)}
              cy={16 + 11 * Math.sin(r)}
              r={1.4 + i * 0.27}
              fill={g(i % 2 ? 'blue' : 'violet')}
            />
          )
        })}
      </g>
    ),
  },
  'status-running': {
    gesture: 'el play coge impulso',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Disc />
        <g className="a a-play">
          <path
            d="M13 10.6v10.8a1 1 0 0 0 1.5.86l8.6-5.4a1 1 0 0 0 0-1.72l-8.6-5.4A1 1 0 0 0 13 10.6z"
            fill={WHITE}
          />
        </g>
      </>
    ),
  },
  'status-thinking': {
    gesture: 'las chispas titilan',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Disc />
        <path className="a a-tw1" d={star(14.5, 17, 7)} fill={WHITE} />
        <path className="a a-tw2" d={star(21.5, 10, 3.4)} fill={WHITE} />
      </>
    ),
  },
  'status-executing': {
    gesture: 'parpadea el cursor',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Disc />
        <Line d="m9.5 12 4 4-4 4" w={3} />
        <g className="a a-cursor">
          <Line d="M16 20.5h6" w={3} />
        </g>
      </>
    ),
  },
  'status-waiting': {
    gesture: 'la mano saluda',
    badge: true,
    tone: 'warn',
    body: (
      <>
        <Disc />
        <g className="a a-wave">
          <path
            d="M12 21.5v-8a1.5 1.5 0 0 1 3 0V12a1.5 1.5 0 0 1 3 0v1a1.5 1.5 0 0 1 3 0v6.5c0 3-2.2 5-5 5h-.4c-1.6 0-2.9-.8-3.8-2l-2.5-3.4a1.4 1.4 0 0 1 2.2-1.7z"
            fill={WHITE}
          />
        </g>
      </>
    ),
  },
  'status-ok': {
    gesture: 'se dibuja el visto',
    badge: true,
    tone: 'ok',
    body: (
      <>
        <Disc />
        <g className="a a-draw">
          <Line d="m10.5 16.5 3.8 3.8 7.4-8" w={3.6} />
        </g>
      </>
    ),
  },
  'status-error': {
    gesture: 'la cruz tiembla',
    badge: true,
    tone: 'bad',
    body: (
      <>
        <Disc />
        <g className="a a-shake">
          <Line d="m11.5 11.5 9 9m0-9-9 9" w={3.6} />
        </g>
      </>
    ),
  },
  'status-warn': {
    gesture: 'la señal se tambalea',
    tone: 'warn',
    body: (
      <g className="a a-wobble">
        <path
          d="M14 5.4a2.3 2.3 0 0 1 4 0l10.6 19.2a2.3 2.3 0 0 1-2 3.4H5.4a2.3 2.3 0 0 1-2-3.4z"
          fill={g('orange')}
        />
        <Line d="M16 12v6.5" w={3.4} />
        <circle cx="16" cy="23" r="1.9" fill={WHITE} />
      </g>
    ),
  },
  'status-paused': {
    gesture: 'las barras se aprietan',
    badge: true,
    tone: 'warn',
    body: (
      <>
        <Disc />
        <g className="a a-clap">
          <rect x="11" y="10" width="3.6" height="12" rx="1.6" fill={WHITE} />
          <rect x="17.4" y="10" width="3.6" height="12" rx="1.6" fill={WHITE} />
        </g>
      </>
    ),
  },
  'status-offline': {
    gesture: 'el enchufe se suelta',
    tone: 'idle',
    body: (
      <>
        <g className="a a-nudged">
          <rect x="11" y="3" width="2.8" height="7" rx="1.4" fill={g('slate')} />
          <rect x="18.2" y="3" width="2.8" height="7" rx="1.4" fill={g('slate')} />
          <path d="M8 9h16v4.5a8 8 0 0 1-16 0z" fill={g('slate')} />
        </g>
        <rect x="14" y="20" width="4" height="9" rx="2" fill={g('slate')} />
      </>
    ),
  },
  'status-cancelled': {
    gesture: 'el cuadrado se aprieta',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Disc />
        <rect className="a a-press" x="11" y="11" width="10" height="10" rx="2.4" fill={WHITE} />
      </>
    ),
  },
  'status-skipped': {
    gesture: 'avanza de un salto',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Disc />
        <g className="a a-nudger">
          <path
            d="M9 11.2v9.6a.9.9 0 0 0 1.4.74L16 17.3v3.5a.9.9 0 0 0 1.4.74l6.8-4.8a.9.9 0 0 0 0-1.48l-6.8-4.8a.9.9 0 0 0-1.4.74v3.5l-5.6-4.24A.9.9 0 0 0 9 11.2z"
            fill={WHITE}
          />
        </g>
      </>
    ),
  },

  /* — Alertas — */
  info: {
    gesture: 'la «i» da un salto',
    body: (
      <>
        <circle cx="16" cy="16" r="12.5" fill={g('blue')} />
        <g className="a a-bob">
          <circle cx="16" cy="10" r="2.1" fill={WHITE} />
          <Line d="M16 15v7.5" w={3.6} />
        </g>
      </>
    ),
  },
  help: {
    gesture: 'la interrogación se mece',
    body: (
      <>
        <circle cx="16" cy="16" r="12.5" fill={g('violet')} />
        <g className="a a-rock">
          <Line d="M12.4 12.5a3.8 3.8 0 1 1 5.6 3.3c-1.2.7-2 1.4-2 2.8v.6" w={3.2} />
          <circle cx="16" cy="23" r="1.9" fill={WHITE} />
        </g>
      </>
    ),
  },

  /* — Acciones — */
  play: {
    gesture: 'el play coge impulso',
    body: (
      <>
        <circle cx="16" cy="16" r="12.5" fill={g('violet')} />
        <g className="a a-play">
          <path
            d="M13 10.6v10.8a1 1 0 0 0 1.5.86l8.6-5.4a1 1 0 0 0 0-1.72l-8.6-5.4A1 1 0 0 0 13 10.6z"
            fill={WHITE}
          />
        </g>
      </>
    ),
  },
  new: {
    gesture: 'el más gira y crece',
    body: (
      <>
        <circle cx="16" cy="16" r="12.5" fill={g('blue')} />
        <g className="a a-plus">
          <Line d="M16 10v12M10 16h12" w={3.6} />
        </g>
      </>
    ),
  },
  save: {
    gesture: 'el disquete se aprieta',
    body: (
      <g className="a a-press">
        <path
          d="M6.5 4h15.5l6 6v15.5a2.5 2.5 0 0 1-2.5 2.5h-19A2.5 2.5 0 0 1 4 25.5v-19A2.5 2.5 0 0 1 6.5 4z"
          fill={g('blue')}
        />
        <rect x="9" y="4" width="11" height="7" rx="1" fill={g('sky')} />
        <rect x="8.5" y="17" width="15" height="8.5" rx="1.5" fill={g('paper')} />
      </g>
    ),
  },
  publish: {
    gesture: 'el cohete despega',
    body: (
      <g className="a a-launch">
        <path className="a a-flame" d="M13 23.5c0 3 3 5 3 5s3-2 3-5z" fill={g('orange')} />
        <path d="M16 3c4.5 3 6 7.5 6 12v7h-12v-7c0-4.5 1.5-9 6-12z" fill={g('paper')} />
        <path d="M10 16.5 6 21v3h4zM22 16.5l4 4.5v3h-4z" fill={g('red')} />
        <circle cx="16" cy="12.5" r="2.8" fill={g('blue')} />
      </g>
    ),
  },
  delete: {
    gesture: 'la tapa salta',
    body: (
      <>
        <g className="a a-lid">
          <rect x="5" y="6.5" width="22" height="4" rx="1.8" fill={g('pink')} />
          <rect x="12.5" y="3.5" width="7" height="3.5" rx="1.2" fill={g('pink')} />
        </g>
        <path
          d="M7.5 12h17l-1.4 13.6A2.6 2.6 0 0 1 20.5 28h-9a2.6 2.6 0 0 1-2.6-2.4z"
          fill={g('lilac')}
        />
        <path
          d="M13 16v7.5M19 16v7.5"
          stroke="#9b7de8"
          strokeOpacity=".8"
          strokeWidth="2.2"
          strokeLinecap="round"
        />
      </>
    ),
  },
  archive: {
    gesture: 'la tapa de la caja salta',
    body: (
      <>
        <Box />
        <rect
          className="a a-boxlid"
          x="3.5"
          y="6"
          width="25"
          height="6"
          rx="2.2"
          fill={g('blue')}
          style={{ filter: 'brightness(1.12)' }}
        />
      </>
    ),
  },
  restore: {
    gesture: 'la flecha sale de la caja',
    body: (
      <>
        <Box />
        <g className="a a-up">
          <path d="M16 3.5 22 10h-4v4.5h-4V10h-4z" fill={g('green')} />
        </g>
      </>
    ),
  },
  pin: {
    gesture: 'la chincheta se clava',
    body: (
      <g className="a a-pin">
        <path d="M11 4h10l-1.5 7.5 4 4.5H8.5l4-4.5z" fill={g('red')} />
        <path d="M16 16v12" stroke="#6b7488" strokeWidth="2.6" strokeLinecap="round" />
        <path
          d="M13.5 6.5h2"
          stroke={WHITE}
          strokeOpacity=".6"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </g>
    ),
  },
  unpin: {
    gesture: 'la chincheta se suelta',
    body: (
      <>
        <g className="a a-nudged" opacity=".6">
          <path d="M11 4h10l-1.5 7.5 4 4.5H8.5l4-4.5z" fill={g('red')} />
          <path d="M16 16v12" stroke="#6b7488" strokeWidth="2.6" strokeLinecap="round" />
        </g>
        <Line d="M5 5l22 22" w={3.4} c="#566077" />
      </>
    ),
  },
  download: {
    gesture: 'la flecha baja a la bandeja',
    body: (
      <>
        <Tray />
        <g className="a a-drop">
          <path d="M13.8 3.5h4.4v7.5h4.3L16 18l-6.5-7h4.3z" fill={g('blue')} />
        </g>
      </>
    ),
  },
  upload: {
    gesture: 'la flecha sube de la bandeja',
    body: (
      <>
        <Tray />
        <g className="a a-up">
          <path d="M16 3.5 22.5 10.5h-4.3V18h-4.4v-7.5H9.5z" fill={g('green')} />
        </g>
      </>
    ),
  },
  refresh: {
    gesture: 'las flechas giran',
    body: (
      <g className="a a-spin">
        <path
          d="M25 13A9.5 9.5 0 0 0 8.3 9.8M7 19a9.5 9.5 0 0 0 16.7 3.2"
          stroke="url(#cg-blue)"
          strokeWidth="3.6"
          strokeLinecap="round"
          fill="none"
        />
        <path d="M6 4.5v7h7zM26 27.5v-7h-7z" fill={g('blue')} />
      </g>
    ),
  },
  retry: {
    gesture: 'la flecha da la vuelta',
    body: (
      <g className="a a-spin">
        <path
          d="M9 9.2A10 10 0 1 1 6 16"
          stroke="url(#cg-blue)"
          strokeWidth="3.6"
          strokeLinecap="round"
          fill="none"
        />
        <path d="M4 4.5 12 5l-3.5 7.5z" fill={g('blue')} />
      </g>
    ),
  },
  undo: {
    gesture: 'la flecha vuelve',
    body: (
      <g className="a a-nudgel">
        <path
          d="M10 13h10a6.5 6.5 0 0 1 0 13h-6"
          stroke="url(#cg-blue)"
          strokeWidth="3.6"
          strokeLinecap="round"
          fill="none"
        />
        <path d="M11.5 6v14L3.5 13z" fill={g('blue')} />
      </g>
    ),
  },
  redo: {
    gesture: 'la flecha avanza',
    body: (
      <g className="a a-nudger">
        <path
          d="M22 13H12a6.5 6.5 0 0 0 0 13h6"
          stroke="url(#cg-blue)"
          strokeWidth="3.6"
          strokeLinecap="round"
          fill="none"
        />
        <path d="M20.5 6v14l8-7z" fill={g('blue')} />
      </g>
    ),
  },
  edit: {
    gesture: 'el lápiz garabatea',
    body: (
      <g className="a a-wobble">
        <path d="m21.5 4.5 6 6L12 26l-7.5 1.5L6 20z" fill={g('orange')} />
        <path d="m21.5 4.5 6 6-2.5 2.5-6-6z" fill={g('pink')} />
        <path d="M6 20 12 26l-7.5 1.5z" fill="#ffe0b8" />
        <path d="m4.5 27.5 1-4.6 3.6 3.6z" fill={INK} />
        <path
          d="m9 19 10.5-10.5"
          stroke={WHITE}
          strokeOpacity=".5"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </g>
    ),
  },
  draft: {
    gesture: 'el lápiz escribe en la hoja',
    body: (
      <>
        <Doc />
        <path d="M10 15h8M10 19h5" stroke="#7aa6ee" strokeWidth="2" strokeLinecap="round" />
        <g className="a a-wobble">
          <path d="m24 13.5 3.5 3.5-8.5 8.5-4.4.9.9-4.4z" fill={g('orange')} />
        </g>
      </>
    ),
  },

  /* — Controles — */
  close: {
    gesture: 'la cruz gira',
    body: (
      <g className="a a-x">
        <Line d="m9 9 14 14M23 9 9 23" w={3.6} c={INK} />
      </g>
    ),
  },
  check: {
    gesture: 'se dibuja el visto',
    body: (
      <g className="a a-draw">
        <Line d="m6.5 16.5 6 6 13-13" w={4} c="url(#cg-green)" />
      </g>
    ),
  },
  'chevron-right': {
    gesture: 'empuja a la derecha',
    body: (
      <g className="a a-nudger">
        <Line d="m12 7 9 9-9 9" w={3.6} c={INK} />
      </g>
    ),
  },
  'chevron-left': {
    gesture: 'empuja a la izquierda',
    body: (
      <g className="a a-nudgel">
        <Line d="m20 7-9 9 9 9" w={3.6} c={INK} />
      </g>
    ),
  },
  'chevron-down': {
    gesture: 'empuja hacia abajo',
    body: (
      <g className="a a-nudged">
        <Line d="m7 12 9 9 9-9" w={3.6} c={INK} />
      </g>
    ),
  },
  more: {
    gesture: 'los puntos saltan',
    body: (
      <>
        <circle className="a a-n1" cx="7" cy="16" r="3.2" fill={g('blue')} />
        <circle className="a a-n2" cx="16" cy="16" r="3.2" fill={g('blue')} />
        <circle className="a a-n3" cx="25" cy="16" r="3.2" fill={g('blue')} />
      </>
    ),
  },
  columns: {
    gesture: 'las columnas saltan',
    body: (
      <>
        <rect x="3.5" y="5" width="25" height="22" rx="3" fill={g('slate')} />
        <rect className="a a-n1" x="6.5" y="8" width="5.2" height="16" rx="1.4" fill={g('paper')} />
        <rect
          className="a a-n2"
          x="13.4"
          y="8"
          width="5.2"
          height="16"
          rx="1.4"
          fill={g('paper')}
        />
        <rect
          className="a a-n3"
          x="20.3"
          y="8"
          width="5.2"
          height="16"
          rx="1.4"
          fill={g('paper')}
        />
      </>
    ),
  },
  list: {
    gesture: 'las filas saltan',
    body: (
      <>
        {[8, 16, 24].map((y, i) => (
          <g key={y} className={`a a-n${i + 1}`}>
            <circle cx="6.5" cy={y} r="2.4" fill={g('blue')} />
            <rect x="11" y={y - 2} width="17" height="4" rx="2" fill={g('sky')} />
          </g>
        ))}
      </>
    ),
  },
  timeline: {
    gesture: 'las barras avanzan en cascada',
    body: (
      <>
        <rect className="a a-n1" x="4" y="5" width="13" height="5.5" rx="2.2" fill={g('blue')} />
        <rect
          className="a a-n2"
          x="10"
          y="13.25"
          width="14"
          height="5.5"
          rx="2.2"
          fill={g('violet')}
        />
        <rect
          className="a a-n3"
          x="17"
          y="21.5"
          width="11"
          height="5.5"
          rx="2.2"
          fill={g('green')}
        />
      </>
    ),
  },
  'panel-right': {
    gesture: 'el panel asoma',
    body: (
      <>
        <rect x="4" y="5" width="24" height="22" rx="3" fill={g('sky')} />
        <g className="a a-nudgel">
          <rect x="18" y="5" width="10" height="22" rx="3" fill={g('blue')} />
        </g>
      </>
    ),
  },
  sliders: {
    gesture: 'los mandos se deslizan',
    body: (
      <>
        <Line d="M5 9h22M5 16h22M5 23h22" w={3} c="url(#cg-blue)" />
        <circle className="a a-nudger" cx="20" cy="9" r="3.6" fill={g('blue')} />
        <circle className="a a-nudgel" cx="11" cy="16" r="3.6" fill={g('indigo')} />
        <circle className="a a-nudger" cx="17" cy="23" r="3.6" fill={g('blue')} />
      </>
    ),
  },
  code: {
    gesture: 'los signos se separan',
    body: (
      <>
        <g className="a a-nudgel">
          <Line d="M10.5 9 4.5 16l6 7" w={3.6} c="url(#cg-blue)" />
        </g>
        <Line d="m18.5 6-5 20" w={3.2} c="url(#cg-indigo)" />
        <g className="a a-nudger">
          <Line d="m21.5 9 6 7-6 7" w={3.6} c="url(#cg-blue)" />
        </g>
      </>
    ),
  },
  graph: {
    gesture: 'los nodos laten en orden',
    body: <Graph a="blue" b="red" c="orange" />,
  },
  'text-input': {
    gesture: 'parpadea el cursor',
    body: (
      <>
        <rect x="3.5" y="9" width="25" height="14" rx="3" fill={g('sky')} />
        <g className="a a-cursor">
          <Line d="M12 12.5v7" w={2.6} c="#2d68f5" />
        </g>
      </>
    ),
  },

  /* — Dominio — */
  agent: {
    gesture: 'el robot parpadea y mueve la antena',
    body: (
      <>
        <g className="a a-antenna">
          <path d="M16 8V4.5" stroke="#6b7488" strokeWidth="2.2" strokeLinecap="round" />
          <circle cx="16" cy="4" r="2.2" fill={g('orange')} />
        </g>
        <rect x="3" y="14" width="4" height="7" rx="2" fill={g('blue')} />
        <rect x="25" y="14" width="4" height="7" rx="2" fill={g('blue')} />
        <rect x="5.5" y="8" width="21" height="18" rx="7" fill={g('paper')} />
        <rect x="9" y="12" width="14" height="9.5" rx="4.7" fill={g('dark')} />
        <g className="a a-eyes">
          <rect x="12" y="14.6" width="2.6" height="4" rx="1.3" fill="#7fd8ff" />
          <rect x="17.4" y="14.6" width="2.6" height="4" rx="1.3" fill="#7fd8ff" />
        </g>
      </>
    ),
  },
  user: {
    gesture: 'la persona asiente',
    body: (
      <g className="a a-bob">
        <circle cx="16" cy="10" r="6" fill={g('blue')} />
        <path
          d="M5 27c0-6 4.9-10 11-10s11 4 11 10a1.5 1.5 0 0 1-1.5 1.5h-19A1.5 1.5 0 0 1 5 27z"
          fill={g('blue')}
        />
        <path
          d="M13 7.5a3 3 0 0 1 2.2-1.4"
          stroke={WHITE}
          strokeOpacity=".6"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </g>
    ),
  },
  tool: {
    gesture: 'la llave aprieta',
    body: (
      <g className="a a-tilt">
        <path
          d="M20.5 3.5a7.5 7.5 0 0 0-7 10L4.8 22.2a2.8 2.8 0 0 0 4 4L17.5 17.5a7.5 7.5 0 0 0 10-7l-4.2 4.2-4.2-1.3-1.3-4.2L22 5a7.5 7.5 0 0 0-1.5-1.5z"
          fill={g('slate')}
        />
        <path
          d="M14.5 10.5a6 6 0 0 1 3-4.5"
          stroke={WHITE}
          strokeOpacity=".5"
          strokeWidth="1.8"
          strokeLinecap="round"
          fill="none"
        />
      </g>
    ),
  },
  memory: {
    gesture: 'el cerebro late',
    body: (
      <g className="a a-bob">
        <path
          d="M15 5.5v21a4.2 4.2 0 0 1-7.8-1.6A4.6 4.6 0 0 1 4.5 17.5 4.6 4.6 0 0 1 6 11a4.4 4.4 0 0 1 4-5.6 4.3 4.3 0 0 1 5 .1z"
          fill={g('pink')}
        />
        <path
          d="M17 5.5v21a4.2 4.2 0 0 0 7.8-1.6 4.6 4.6 0 0 0 2.7-7.4A4.6 4.6 0 0 0 26 11a4.4 4.4 0 0 0-4-5.6 4.3 4.3 0 0 0-5 .1z"
          fill={g('violet')}
        />
        <path
          d="M9 13.5c2 0 3 1 3 3M23 13.5c-2 0-3 1-3 3M10 21c1.6 0 2.5-.8 2.5-2M22 21c-1.6 0-2.5-.8-2.5-2"
          stroke={WHITE}
          strokeOpacity=".55"
          strokeWidth="1.6"
          strokeLinecap="round"
          fill="none"
        />
      </g>
    ),
  },
  file: {
    gesture: 'se dobla la esquina',
    body: (
      <g className="a a-corner">
        <Doc />
        <path d="M10 16h11M10 20.5h7" stroke="#7aa6ee" strokeWidth="2.2" strokeLinecap="round" />
      </g>
    ),
  },
  'file-ok': {
    gesture: 'se dibuja el visto',
    body: (
      <>
        <Doc />
        <circle cx="22" cy="22.5" r="6.5" fill={g('green')} />
        <g className="a a-draw">
          <Line d="m19 22.5 2.2 2.2 4-4.2" w={2.4} />
        </g>
      </>
    ),
  },
  'file-warning': {
    gesture: 'el aviso se tambalea',
    body: (
      <>
        <Doc />
        <g className="a a-wobble">
          <path
            d="M20.5 15.5a1.8 1.8 0 0 1 3 0l5.4 9.6a1.8 1.8 0 0 1-1.5 2.7H16.6a1.8 1.8 0 0 1-1.5-2.7z"
            fill={g('orange')}
          />
          <path d="M22 19.5v3.4" stroke={WHITE} strokeWidth="2.2" strokeLinecap="round" />
          <circle cx="22" cy="25.4" r="1.1" fill={WHITE} />
        </g>
      </>
    ),
  },
  branch: {
    gesture: 'los nodos laten en orden',
    body: (
      <>
        <path
          d="M9 9v14M9 18c0-4 4-5.5 8-5.5h2"
          stroke="url(#cg-slate)"
          strokeWidth="2.6"
          strokeLinecap="round"
          fill="none"
        />
        <circle className="a a-n1" cx="9" cy="7.5" r="3.8" fill={g('red')} />
        <circle className="a a-n2" cx="22.5" cy="12.5" r="3.8" fill={g('orange')} />
        <circle className="a a-n3" cx="9" cy="24.5" r="3.8" fill={g('coral')} />
      </>
    ),
  },
  commit: {
    gesture: 'el punto late',
    body: (
      <>
        <Line d="M3.5 16h7M21.5 16h7" w={3} c="#6b7488" />
        <g className="a a-bob">
          <circle cx="16" cy="16" r="6.5" fill={g('orange')} />
          <circle cx="16" cy="16" r="2.6" fill={WHITE} />
        </g>
      </>
    ),
  },
  repo: {
    gesture: 'la carpeta se abre',
    body: (
      <>
        <g className="a a-flap">
          <Folder back={g('indigo')} front={g('blue')} />
        </g>
        <path
          d="M13 16.5v6M13 19.5c0-2 2-2.8 4-2.8"
          stroke={WHITE}
          strokeWidth="1.8"
          strokeLinecap="round"
          fill="none"
        />
        <circle cx="13" cy="16.5" r="1.6" fill={WHITE} />
        <circle cx="19" cy="16.7" r="1.6" fill={WHITE} />
        <circle cx="13" cy="23" r="1.6" fill={WHITE} />
      </>
    ),
  },
  'work-items': {
    gesture: 'se marcan las tareas',
    body: (
      <>
        <rect x="5" y="3.5" width="22" height="25" rx="3" fill={g('sky')} />
        <rect x="11" y="2" width="10" height="4" rx="1.6" fill={g('blue')} />
        <g className="a a-n1">
          <rect x="8.5" y="10" width="4.5" height="4.5" rx="1.2" fill={g('green')} />
        </g>
        <g className="a a-n2">
          <rect x="8.5" y="18" width="4.5" height="4.5" rx="1.2" fill={g('blue')} />
        </g>
        <path
          d="M15.5 12.3h8M15.5 20.3h6"
          stroke="#7aa6ee"
          strokeWidth="2.2"
          strokeLinecap="round"
        />
      </>
    ),
  },
  checks: {
    gesture: 'se dibujan los vistos',
    body: (
      <>
        <g className="a a-draw">
          <Line d="m4 9 2.6 2.6L11 7M4 20l2.6 2.6L11 18" w={2.6} c="url(#cg-green)" />
        </g>
        <rect x="14" y="7.5" width="14" height="4" rx="2" fill={g('sky')} />
        <rect x="14" y="18.5" width="14" height="4" rx="2" fill={g('sky')} />
      </>
    ),
  },
  verification: {
    gesture: 'se dibuja el visto en el escudo',
    body: (
      <>
        <Shield />
        <g className="a a-draw">
          <Line d="m11 15.5 3.5 3.5 6.5-7" w={3.2} />
        </g>
      </>
    ),
  },
  sensitive: {
    gesture: 'el escudo se tambalea',
    body: (
      <g className="a a-wobble">
        <Shield fill={g('orange')} />
        <Line d="M16 10v7" w={3.2} />
        <circle cx="16" cy="21.5" r="1.8" fill={WHITE} />
      </g>
    ),
  },
  cost: {
    gesture: 'las monedas se apilan',
    body: (
      <>
        <Coin y={23} />
        <Coin y={17.5} a="a a-n2" />
        <Coin y={12} a="a a-coin" />
      </>
    ),
  },
  duration: {
    gesture: 'la aguja gira',
    body: (
      <>
        <Clock />
        <g className="a a-needle">
          <Line d="M16 17.5v-5" w={2.4} c="#2d68f5" />
        </g>
        <circle cx="16" cy="17.5" r="1.8" fill={g('blue')} />
      </>
    ),
  },
  timer: {
    gesture: 'el cronómetro suena',
    body: (
      <g className="a a-ring">
        <Clock fill={g('paper')} />
        <path d="M16 17.5V11.5A6 6 0 1 1 10.8 14.5z" fill={g('coral')} opacity=".85" />
      </g>
    ),
  },
  terminal: {
    gesture: 'parpadea el cursor',
    body: (
      <>
        <rect x="3.5" y="5" width="25" height="22" rx="4" fill={g('dark')} />
        <Line d="m9 12 4 4-4 4" w={2.8} c="#7fd8ff" />
        <g className="a a-cursor">
          <Line d="M16 20.5h6.5" w={2.8} c="#7fd8ff" />
        </g>
      </>
    ),
  },
  provider: {
    gesture: 'el enchufe se conecta',
    body: (
      <>
        <g className="a a-nudged">
          <rect x="11" y="3" width="2.8" height="7" rx="1.4" fill={g('indigo')} />
          <rect x="18.2" y="3" width="2.8" height="7" rx="1.4" fill={g('indigo')} />
        </g>
        <path d="M7.5 9h17v5a8.5 8.5 0 0 1-17 0z" fill={g('blue')} />
        <rect x="14" y="21" width="4" height="8" rx="2" fill={g('blue')} />
        <path
          d="M10.5 12v2"
          stroke={WHITE}
          strokeOpacity=".6"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </>
    ),
  },
  model: {
    gesture: 'el cubo se mece',
    body: (
      <g className="a a-rock">
        <path d="M16 3.5 27 9.5v13L16 28.5 5 22.5v-13z" fill={g('blue')} />
        <path d="M16 3.5 27 9.5 16 15.5 5 9.5z" fill="#cfe2ff" />
        <path d="M16 15.5v13l11-6v-13z" fill="#000" opacity=".12" />
      </g>
    ),
  },
  stages: {
    gesture: 'las capas se separan',
    body: (
      <>
        <path className="a a-n3" d="M16 17.5 28 23l-12 5.5L4 23z" fill={g('indigo')} />
        <path className="a a-n2" d="M16 11.5 28 17l-12 5.5L4 17z" fill={g('violet')} />
        <path className="a a-n1" d="M16 4.5 28 10l-12 5.5L4 10z" fill={g('lilac')} />
      </>
    ),
  },
  parallel: {
    gesture: 'el camino se abre en dos',
    body: (
      <>
        <path
          d="M5 16h7l6-7h8M12 16l6 7h8"
          stroke="url(#cg-teal)"
          strokeWidth="3.2"
          strokeLinecap="round"
          strokeLinejoin="round"
          fill="none"
        />
        <circle className="a a-n1" cx="5.5" cy="16" r="3.2" fill={g('teal')} />
        <circle className="a a-n2" cx="26" cy="9" r="3.2" fill={g('blue')} />
        <circle className="a a-n3" cx="26" cy="23" r="3.2" fill={g('violet')} />
      </>
    ),
  },
  prompt: {
    gesture: 'el rayo chispea',
    body: (
      <g className="a a-shake">
        <path d="M18.5 3 7 18h7.5l-2 11L25 13.5h-7.5z" fill={g('orange')} />
        <path
          d="M16 7.5 11.5 14"
          stroke={WHITE}
          strokeOpacity=".6"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </g>
    ),
  },
  message: {
    gesture: 'aparecen los puntos',
    body: (
      <>
        <Bubble />
        <circle className="a a-n1" cx="10.5" cy="14" r="1.9" fill={WHITE} />
        <circle className="a a-n2" cx="16" cy="14" r="1.9" fill={WHITE} />
        <circle className="a a-n3" cx="21.5" cy="14" r="1.9" fill={WHITE} />
      </>
    ),
  },
  conversation: {
    gesture: 'las burbujas se responden',
    body: (
      <>
        <g className="a a-n2">
          <path
            d="M14 11h12.5a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H25v3.5L21 23h-7a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"
            fill={g('violet')}
          />
        </g>
        <g className="a a-n1">
          <path
            d="M5.5 4.5h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7l-4 3.5v-3.5h-1a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"
            fill={g('blue')}
          />
        </g>
      </>
    ),
  },
  simulated: {
    gesture: 'burbujea el matraz',
    body: (
      <>
        <path
          d="M12 3.5h8M13.5 3.5v8L6 24.5a2.6 2.6 0 0 0 2.3 3.9h15.4a2.6 2.6 0 0 0 2.3-3.9L18.5 11.5v-8"
          fill={g('sky')}
          stroke="url(#cg-blue)"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M9.8 19h12.4l3.8 5.5a2.6 2.6 0 0 1-2.3 3.9H8.3A2.6 2.6 0 0 1 6 24.5z"
          fill={g('blue')}
        />
        <circle className="a a-n1" cx="14" cy="15.5" r="1.4" fill={g('blue')} />
        <circle className="a a-n2" cx="18" cy="12.5" r="1.1" fill={g('blue')} />
      </>
    ),
  },
  live: {
    gesture: 'las ondas se emiten',
    body: (
      <>
        <g className="a a-rays">
          <path
            d="M9 9a10 10 0 0 0 0 14M23 9a10 10 0 0 1 0 14M5.5 5.5a15 15 0 0 0 0 21M26.5 5.5a15 15 0 0 1 0 21"
            stroke="url(#cg-violet)"
            strokeWidth="2.6"
            strokeLinecap="round"
            fill="none"
          />
        </g>
        <circle cx="16" cy="16" r="3.6" fill={g('violet')} />
        <path d="M14.5 19.5 13 28h6l-1.5-8.5" fill={g('violet')} />
      </>
    ),
  },
}

CHATGPT['circle-ok'] = CHATGPT['status-ok']
CHATGPT['circle-error'] = CHATGPT['status-error']
CHATGPT['alert-error'] = CHATGPT['status-error']
CHATGPT['alert-warn'] = CHATGPT['status-warn']
CHATGPT.reload = CHATGPT.refresh
