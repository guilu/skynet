import type { ReactNode } from 'react'
import { Part } from './Part'
import type { IconName } from './registry'

/* Paquete «Ilustrados» (IC-A): formas lisas y sin contorno, con el lenguaje de la mascota.
 * Lienzo de 32 × 32. Cada forma lleva su labio (la misma forma 2 px más abajo, más oscura); los
 * colores salen de clases (`m` vivo, `w` claro, `k` profundo, `warn`…) que resuelve
 * `ilustrados.css` con los tokens del acento y los estados. Las piezas con clase `a-…` son las que
 * se mueven en la microanimación; `det` se quita por debajo de 32 px. Plan: estudio-iconos.md. */

export type DrawingTone = 'ok' | 'warn' | 'bad' | 'live' | 'idle'

export interface Drawing {
  /** El gesto, tal como se cuenta en la galería de Ajustes. */
  gesture: string
  body: ReactNode
  /** Insignia de estado: disco del tono con el glifo en blanco. */
  badge?: boolean
  /** Tono propio de la insignia, si quien la pinta no da otro. */
  tone?: DrawingTone
  /** Control pequeño (cerrar, chevrons, menú): siempre de trazo. */
  util?: boolean
  /** Estado vivo: su gesto se repite donde se pide (`loop`). */
  live?: boolean
}

/** Engranaje de 8 dientes centrado en el lienzo. */
const GEAR = (() => {
  const p = (r: number, deg: number) =>
    [16 + r * Math.cos((deg * Math.PI) / 180), 16 + r * Math.sin((deg * Math.PI) / 180)]
      .map((n) => n.toFixed(2))
      .join(' ')
  let d = ''
  for (let i = 0; i < 8; i++) {
    const c = i * 45
    d += `${i ? 'L' : 'M'}${p(8.6, c - 15)} L${p(11.6, c - 9)} L${p(11.6, c + 9)} L${p(8.6, c + 15)} `
  }
  return d + 'Z'
})()

/** Estrella de cuatro puntas con los lados curvos. */
const star = (x: number, y: number, r: number) =>
  `M${x} ${y - r} Q${x} ${y} ${x + r} ${y} Q${x} ${y} ${x} ${y + r} Q${x} ${y} ${x - r} ${y} Q${x} ${y} ${x} ${y - r}Z`

export const ILUSTRADOS: Partial<Record<IconName, Drawing>> = {
  palette: {
    gesture: 'las gotas de color saltan una tras otra',
    body: (
      <>
        <Part ink={<path className="hl" d="M8 12a8 8 0 0 1 4-4" />}>
          <path d="M16 4.5C9 4.5 4 9.5 4 15.8c0 5.2 4 9.7 9 9.7 1.6 0 2.3-1 2.3-2.2 0-1.5-1.2-2-1.2-3.5 0-1.4 1.1-2.3 2.5-2.3h3.6c3.6 0 6.3-2.5 6.3-6 0-4.2-4.9-7-10.5-7z" />
        </Part>
        <Part a="a-n1">
          <circle cx="10.2" cy="14" r="2.3" className="m" />
        </Part>
        <Part a="a-n2">
          <circle cx="14.6" cy="9.2" r="2.3" className="ok" />
        </Part>
        <Part a="a-n3">
          <circle cx="20.4" cy="9.8" r="2.3" className="warn" />
        </Part>
        <Part>
          <circle cx="10.4" cy="20" r="2.3" className="bad" />
        </Part>
      </>
    ),
  },
  icons: {
    gesture: 'las tres formas saltan una tras otra',
    body: (
      <>
        <Part a="a-n1" ink={<circle cx="9" cy="9" r="1.6" className="hb" />}>
          <circle cx="11" cy="11" r="6.5" className="m" />
        </Part>
        <Part a="a-n2">
          <path d="M22.5 4.5 28.5 15h-12z" className="warn" />
        </Part>
        <Part a="a-n3">
          <rect x="15.5" y="17" width="12" height="11" rx="3.2" className="ok" />
        </Part>
        <Part>
          <rect x="4.5" y="20" width="8" height="8" rx="4" className="w" />
        </Part>
      </>
    ),
  },
  dashboard: {
    gesture: 'la puerta se abre y sale humo',
    body: (
      <>
        <Part>
          <rect x="19" y="6.5" width="4.5" height="8" rx="1.5" className="k" />
        </Part>
        <g className="a a-smoke det">
          <circle cx="21.2" cy="3.6" r="1.9" className="wf" />
        </g>
        <Part>
          <path d="M7.5 14.5h17V25a2.5 2.5 0 0 1-2.5 2.5H10A2.5 2.5 0 0 1 7.5 25z" />
        </Part>
        <Part
          ink={
            <>
              <path className="hl" d="M9 14.3 15.5 9" />
            </>
          }
        >
          <path
            d="M4.8 15.2 16 5.8l11.2 9.4a1.5 1.5 0 0 1-1 2.6H5.8a1.5 1.5 0 0 1-1-2.6z"
            className="m"
          />
        </Part>
        <Part>
          <rect x="10.5" y="19.5" width="4" height="4" rx="1.2" className="w" />
        </Part>
        <g className="a a-door">
          <Part>
            <rect x="16.5" y="19" width="5" height="8.5" rx="1.8" className="warn" />
          </Part>
        </g>
      </>
    ),
  },
  projects: {
    gesture: 'se abre la carpeta y asoma el papel',
    body: (
      <>
        <Part>
          <path
            d="M4 9.5A2.5 2.5 0 0 1 6.5 7h5.2l2.8 3h11A2.5 2.5 0 0 1 28 12.5V24a2.5 2.5 0 0 1-2.5 2.5h-19A2.5 2.5 0 0 1 4 24z"
            className="m"
          />
        </Part>
        <Part
          a="a-paper"
          ink={
            <>
              <path className="lm soft det" d="M11 13.2h10M11 16.4h6" />
            </>
          }
        >
          <rect x="8" y="9.5" width="16" height="11" rx="1.6" className="w" />
        </Part>
        <Part
          a="a-flap"
          ink={
            <>
              <path className="hl" d="M7.5 18h6" />
            </>
          }
        >
          <path d="M4 14.5h24v9.5a2.5 2.5 0 0 1-2.5 2.5h-19A2.5 2.5 0 0 1 4 24z" />
        </Part>
      </>
    ),
  },
  workflows: {
    gesture: 'los nodos laten en orden y viaja un paquete',
    body: (
      <>
        <Part>
          <path d="M16 8 8 23.5M16 8l8 15.5M8 23.5h16" style={{ fill: 'none', strokeWidth: '4' }} />
        </Part>
        <Part
          a="a-n1"
          ink={
            <>
              <circle cx="14.6" cy="6.6" r="1.3" className="hb" />
            </>
          }
        >
          <circle cx="16" cy="8" r="4.6" className="m" />
        </Part>
        <Part a="a-n2">
          <circle cx="8" cy="23.5" r="4.6" className="w" />
        </Part>
        <Part a="a-n3">
          <circle cx="24" cy="23.5" r="4.6" className="w" />
        </Part>
        <g className="a a-packet det">
          <circle cx="16" cy="8" r="1.8" className="warnf" />
        </g>
      </>
    ),
  },
  runs: {
    gesture: 'el play coge impulso, con líneas de velocidad',
    body: (
      <>
        <g className="a a-speed det">
          <path className="lm" d="M2.5 12.5h3M1.5 16h3.5M2.5 19.5h3" />
        </g>
        <Part
          ink={
            <>
              <path className="hl" d="M10 11a7.8 7.8 0 0 1 4-3.2" />
            </>
          }
        >
          <circle cx="17" cy="16" r="11.5" className="m" />
        </Part>
        <g className="a a-play">
          <Part>
            <path d="M14.5 11 22.2 16l-7.7 5z" className="w" />
          </Part>
        </g>
      </>
    ),
  },
  approvals: {
    gesture: 'la mano saluda (como la mascota)',
    body: (
      <>
        <g className="a a-wave">
          <Part>
            <rect x="5.2" y="14" width="4.2" height="9" rx="2.1" transform="rotate(-28 7.2 18.5)" />
          </Part>
          <Part>
            <rect x="9.2" y="7.5" width="3.6" height="12" rx="1.8" />
            <rect x="13.2" y="5" width="3.6" height="14" rx="1.8" />
            <rect x="17.2" y="6" width="3.6" height="13" rx="1.8" />
            <rect x="21.2" y="8.5" width="3.6" height="11" rx="1.8" />
          </Part>
          <Part
            ink={
              <>
                <path className="ln soft" d="M13 9.5v6M17 7.5v8M21 9v6.5" />
                <path className="hl" d="M12.5 20a4 4 0 0 0 2 3" />
              </>
            }
          >
            <path d="M9.2 15.5h15.6v5a7 7 0 0 1-7 7h-1.6a7 7 0 0 1-7-7z" />
          </Part>
          <Part>
            <rect x="9.2" y="25" width="15.6" height="3.5" rx="1.75" className="m" />
          </Part>
        </g>
      </>
    ),
  },
  runners: {
    gesture: 'los pilotos parpadean',
    body: (
      <>
        <Part
          ink={
            <>
              <path className="ln soft" d="M16 10h6.5" />
            </>
          }
        >
          <rect x="5.5" y="5" width="21" height="10" rx="3.4" />
        </Part>
        <Part
          ink={
            <>
              <path className="lw soft" d="M16 22h6.5" />
            </>
          }
        >
          <rect x="5.5" y="17" width="21" height="10" rx="3.4" className="m" />
        </Part>
        <g className="a a-led1">
          <circle cx="10.5" cy="10" r="2.2" className="okf" />
        </g>
        <g className="a a-led2">
          <circle cx="10.5" cy="22" r="2.2" className="okf" />
        </g>
      </>
    ),
  },
  usage: {
    gesture: 'la aguja barre hasta el límite',
    body: (
      <>
        <Part>
          <path
            d="M4.5 21.5a11.5 11.5 0 0 1 23 0v1.5a2.5 2.5 0 0 1-2.5 2.5H7A2.5 2.5 0 0 1 4.5 23z"
            className="m"
          />
        </Part>
        <Part>
          <path d="M8 21.5a8 8 0 0 1 16 0z" className="w" />
        </Part>
        <path
          d="M9.4 19.2a7 7 0 0 1 2.6-4.4"
          className="ln"
          style={{ stroke: 'var(--ok)', strokeWidth: '2.6' }}
        />
        <path
          d="M13.9 13.1a7 7 0 0 1 4.2 0"
          className="ln"
          style={{ stroke: 'var(--warn)', strokeWidth: '2.6' }}
        />
        <path
          d="M20 14.8a7 7 0 0 1 2.6 4.4"
          className="ln"
          style={{ stroke: 'var(--bad)', strokeWidth: '2.6' }}
        />
        <g className="a a-needle">
          <path className="ln" d="M16 21 11.8 15.8" style={{ strokeWidth: '2.6' }} />
        </g>
        <circle cx="16" cy="21" r="2.6" className="kf" />
      </>
    ),
  },
  activity: {
    gesture: 'se dibuja el pulso',
    body: (
      <>
        <Part>
          <rect x="4" y="6.5" width="24" height="19.5" rx="4.5" className="w" />
        </Part>
        <rect x="4" y="6.5" width="24" height="4.5" rx="2.2" className="mf" />
        <path
          className="lm a a-draw"
          d="M7.5 18.5h4l2.5-5 4 9 2.5-4h4"
          style={{ strokeWidth: '2.6' }}
        />
      </>
    ),
  },
  settings: {
    gesture: 'el engranaje gira un diente',
    body: (
      <>
        <g className="a a-spin">
          <Part>
            <path d={GEAR} className="m" />
          </Part>
          <Part>
            <circle cx="16" cy="16" r="4" className="w" />
          </Part>
        </g>
      </>
    ),
  },
  notices: {
    gesture: 'la campana suena',
    body: (
      <>
        <g className="a a-ring">
          <Part>
            <circle cx="16" cy="5.2" r="2" className="warn" />
          </Part>
          <Part
            ink={
              <>
                <path className="hl" d="M12 12.5a4 4 0 0 1 2-3" />
              </>
            }
          >
            <path
              d="M16 6a7.2 7.2 0 0 1 7.2 7.2v4.6l2.6 4.2H6.2l2.6-4.2v-4.6A7.2 7.2 0 0 1 16 6z"
              className="warn"
            />
          </Part>
        </g>
        <g className="a a-clap">
          <Part>
            <circle cx="16" cy="25" r="2.6" className="k" />
          </Part>
        </g>
      </>
    ),
  },
  search: {
    gesture: 'la lupa ladea y busca',
    body: (
      <>
        <g className="a a-tilt">
          <Part>
            <path d="m20.5 20.5 5.5 5.5" style={{ fill: 'none', strokeWidth: '5' }} className="k" />
          </Part>
          <Part>
            <circle cx="14" cy="14" r="9" className="m" />
          </Part>
          <circle cx="14" cy="14" r="5.6" fill="var(--ico-glass)" />
          <path className="hl" d="M11 12.5a3.5 3.5 0 0 1 2.5-2.5" />
        </g>
      </>
    ),
  },
  new: {
    gesture: 'la cruz gira y rebota',
    body: (
      <>
        <Part>
          <rect x="5" y="5" width="22" height="22" rx="7" className="m" />
        </Part>
        <g className="a a-plus">
          <path className="lw" d="M16 10.5v11M10.5 16h11" style={{ strokeWidth: '3.2' }} />
        </g>
      </>
    ),
  },
  play: {
    gesture: 'arranca hacia delante',
    body: (
      <>
        <g className="a a-speed det">
          <path className="lm" d="M3 12h3M2 16h4M3 20h3" />
        </g>
        <g className="a a-play">
          <Part
            ink={
              <>
                <path className="hl" d="M13.5 10v4" />
              </>
            }
          >
            <path
              d="M10 7.5c0-1.6 1.7-2.5 3-1.7l12.6 8.5c1.2.8 1.2 2.6 0 3.4L13 26.2c-1.3.8-3-.1-3-1.7z"
              className="m"
            />
          </Part>
        </g>
      </>
    ),
  },
  save: {
    gesture: 'se hunde como un botón',
    body: (
      <>
        <g className="a a-press">
          <Part>
            <path
              d="M5.5 8.5a3 3 0 0 1 3-3h12.3l5.7 5.7v12.3a3 3 0 0 1-3 3h-15a3 3 0 0 1-3-3z"
              className="m"
            />
          </Part>
          <Part>
            <rect x="10.5" y="5.5" width="9" height="6" rx="1.2" className="g" />
          </Part>
          <Part
            ink={
              <>
                <path className="lm soft det" d="M12.5 20h7M12.5 23h4" />
              </>
            }
          >
            <rect x="9.5" y="16.5" width="13" height="9.5" rx="1.8" className="w" />
          </Part>
        </g>
      </>
    ),
  },
  delete: {
    gesture: 'se levanta la tapa',
    body: (
      <>
        <Part
          ink={
            <>
              <path className="ln soft" d="M13.5 16v7.5M18.5 16v7.5" />
            </>
          }
        >
          <path d="M8.8 12h14.4l-1.3 13.2a2.5 2.5 0 0 1-2.5 2.3h-6.8a2.5 2.5 0 0 1-2.5-2.3z" />
        </Part>
        <g className="a a-lid">
          <Part>
            <path
              d="M13 8V6a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 19 6v2"
              style={{ fill: 'none', strokeWidth: '2.4' }}
              className="k"
            />
          </Part>
          <Part>
            <rect x="6.5" y="7.5" width="19" height="4" rx="2" className="m" />
          </Part>
        </g>
      </>
    ),
  },
  archive: {
    gesture: 'la tapa sube y baja',
    body: (
      <>
        <Part>
          <path d="M7 13h18v11a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 7 24z" />
        </Part>
        <Part>
          <rect x="12.5" y="16.3" width="7" height="3" rx="1.5" className="w" />
        </Part>
        <g className="a a-boxlid">
          <Part
            ink={
              <>
                <path className="hl" d="M8.5 9.5h4" />
              </>
            }
          >
            <rect x="5" y="6.5" width="22" height="6.5" rx="2.2" className="m" />
          </Part>
        </g>
      </>
    ),
  },
  pin: {
    gesture: 'se clava con un rebote',
    body: (
      <>
        <g className="a a-pin">
          <g transform="rotate(35 16 16)">
            <path
              className="ln"
              d="M16 20v8"
              style={{ strokeWidth: '2.4', stroke: 'var(--idle-shade)' }}
            />
            <Part
              ink={
                <>
                  <path className="hl" d="M14.6 10.5v3.5" />
                </>
              }
            >
              <path d="M11 5h10v2.6l-2 2v5.2l4 3.2V20H9v-2l4-3.2V9.6l-2-2z" className="bad" />
            </Part>
          </g>
        </g>
      </>
    ),
  },
  download: {
    gesture: 'la flecha cae en la bandeja',
    body: (
      <>
        <Part>
          <rect x="4.5" y="19" width="23" height="8.5" rx="3" className="m" />
        </Part>
        <circle cx="23" cy="23.2" r="1.4" className="okf det" />
        <g className="a a-drop">
          <Part>
            <path d="M13 4.5h6v8h4.2L16 20l-7.2-7.5H13z" />
          </Part>
        </g>
      </>
    ),
  },
  upload: {
    gesture: 'la flecha sale de la bandeja',
    body: (
      <>
        <Part>
          <rect x="4.5" y="19" width="23" height="8.5" rx="3" className="m" />
        </Part>
        <g className="a a-up">
          <Part>
            <path d="M13 20.5h6v-8h4.2L16 5l-7.2 7.5H13z" />
          </Part>
        </g>
      </>
    ),
  },
  publish: {
    gesture: 'tiembla, se enciende y despega',
    body: (
      <>
        <g className="a a-launch">
          <g className="a a-flame">
            <Part>
              <path d="M13.3 23.5h5.4L16 29z" className="warn" />
            </Part>
          </g>
          <Part>
            <path d="M10.5 17.5 6 22v3.5l5-2.5z" className="m" />
            <path d="M21.5 17.5 26 22v3.5l-5-2.5z" className="m" />
          </Part>
          <Part
            ink={
              <>
                <path
                  className="hl"
                  d="M13.2 10.5a6 6 0 0 1 1.5-3"
                  style={{ stroke: 'var(--ico-glass)' }}
                />
              </>
            }
          >
            <path
              d="M16 3.5c4.2 3 6.2 7.6 6.2 12.4v7.6h-12.4v-7.6c0-4.8 2-9.4 6.2-12.4z"
              className="w"
            />
          </Part>
          <Part>
            <circle cx="16" cy="14" r="3" className="m" />
          </Part>
          <circle cx="15" cy="13" r="1" className="hb" />
        </g>
      </>
    ),
  },
  refresh: {
    gesture: 'da una vuelta',
    body: (
      <>
        <g className="a a-spin">
          <Part>
            <circle cx="16" cy="16" r="11.5" className="m" />
          </Part>
          <path className="lw" d="M21 13a5.6 5.6 0 1 0 .6 5" style={{ strokeWidth: '2.6' }} />
          <path
            className="wf"
            d="M22.8 9v5.4h-5.4z"
            stroke="var(--ico-white)"
            strokeWidth="1.4"
            strokeLinejoin="round"
          />
        </g>
      </>
    ),
  },
  agent: {
    gesture: 'parpadea y la antena se enciende (pariente de la mascota)',
    body: (
      <>
        <Part>
          <path d="M16 9V5.5" style={{ fill: 'none', strokeWidth: '2.4' }} className="k" />
        </Part>
        <g className="a a-antenna">
          <Part>
            <circle cx="16" cy="4.5" r="2.4" className="warn" />
          </Part>
        </g>
        <Part>
          <rect x="3.5" y="14" width="3.8" height="7" rx="1.6" className="m" />
          <rect x="24.7" y="14" width="3.8" height="7" rx="1.6" className="m" />
        </Part>
        <Part
          ink={
            <>
              <path className="hl" d="M9.5 12.2h3" />
            </>
          }
        >
          <rect x="6" y="9" width="20" height="17" rx="6.5" />
        </Part>
        <rect x="9" y="13.5" width="14" height="8" rx="4" className="visf" />
        <g className="a a-eyes">
          <circle cx="13" cy="17.5" r="1.7" className="wf" />
          <circle cx="19" cy="17.5" r="1.7" className="wf" />
        </g>
      </>
    ),
  },
  memory: {
    gesture: 'chispas de recuerdo',
    body: (
      <>
        <Part
          ink={
            <>
              <path className="ln" d="M16 8.5v15" style={{ stroke: 'var(--live)' }} />
              <path
                className="ln soft"
                d="M10 15.5c1.5 0 2.5 1 2.5 2.5M22 15.5c-1.5 0-2.5 1-2.5 2.5"
                style={{ stroke: 'var(--live)' }}
              />
              <path className="hl" d="M9.5 10a3 3 0 0 1 2-2" />
            </>
          }
        >
          <circle cx="12" cy="11.5" r="5.2" className="lv" />
          <circle cx="20" cy="11.5" r="5.2" className="lv" />
          <circle cx="9.8" cy="18.5" r="5.2" className="lv" />
          <circle cx="22.2" cy="18.5" r="5.2" className="lv" />
          <circle cx="16" cy="21.5" r="5.4" className="lv" />
        </Part>
        <g className="a a-tw1 det">
          <path d={star(27, 5.5, 3.2)} className="warnf" />
        </g>
        <g className="a a-tw2 det">
          <path d={star(4.5, 26, 2.6)} className="mf" />
        </g>
      </>
    ),
  },
  file: {
    gesture: 'la esquina se dobla y se escriben las líneas',
    body: (
      <>
        <Part>
          <path
            d="M8.5 4.5h10l6.5 6.5v14a2.5 2.5 0 0 1-2.5 2.5h-14A2.5 2.5 0 0 1 6 25V7a2.5 2.5 0 0 1 2.5-2.5z"
            className="w"
          />
        </Part>
        <g className="a a-corner">
          <path
            d="M18.5 4.5V9a2 2 0 0 0 2 2H25z"
            className="mf"
            stroke="var(--ico-mid)"
            strokeWidth="2"
            strokeLinejoin="round"
          />
        </g>
        <path className="lm a a-draw" d="M10.5 15.5h11M10.5 19.5h11M10.5 23.5h6" />
      </>
    ),
  },
  branch: {
    gesture: 'un commit recorre la rama',
    body: (
      <>
        <Part>
          <path d="M10 9v14M22 12.5c0 5-6 4-12 9.5" style={{ fill: 'none', strokeWidth: '3' }} />
        </Part>
        <Part a="a-n1">
          <circle cx="10" cy="7" r="3.6" className="w" />
        </Part>
        <Part a="a-n2">
          <circle cx="22" cy="10" r="3.6" className="m" />
        </Part>
        <Part a="a-n3">
          <circle cx="10" cy="25" r="3.6" className="w" />
        </Part>
      </>
    ),
  },
  cost: {
    gesture: 'la moneda de arriba da la vuelta',
    body: (
      <>
        <Part>
          <ellipse cx="16" cy="22.5" rx="9.5" ry="4" className="warn" />
        </Part>
        <Part>
          <ellipse cx="16" cy="18" rx="9.5" ry="4" className="warn" />
        </Part>
        <g className="a a-coin">
          <Part
            ink={
              <>
                <path className="ln" d="M13 12h6" style={{ stroke: 'var(--warn-shade)' }} />
                <path className="hl" d="M9.5 11a5 2 0 0 1 3-1.6" />
              </>
            }
          >
            <ellipse cx="16" cy="12" rx="9.5" ry="4.2" className="warn" />
          </Part>
        </g>
      </>
    ),
  },
  duration: {
    gesture: 'las agujas dan la vuelta',
    body: (
      <>
        <Part>
          <rect x="13.5" y="3.2" width="5" height="3.4" rx="1.2" className="k" />
        </Part>
        <Part>
          <circle cx="16" cy="17" r="10.5" className="m" />
        </Part>
        <circle cx="16" cy="17" r="7.2" className="wf" />
        <g className="a a-hands">
          <path className="ln" d="M16 17v-4.5M16 17l3 2" style={{ strokeWidth: '2.4' }} />
        </g>
        <circle cx="16" cy="17" r="1.4" className="kf" />
      </>
    ),
  },
  terminal: {
    gesture: 'parpadea el cursor',
    body: (
      <>
        <Part>
          <rect x="4" y="6" width="24" height="20" rx="4.5" className="vis" />
        </Part>
        <rect x="4" y="6" width="24" height="5" rx="2.2" className="mf" />
        <circle cx="8" cy="8.5" r="1.1" className="wf" />
        <circle cx="11.2" cy="8.5" r="1.1" className="wf" />
        <path className="lw" d="m9 15.5 3.5 3-3.5 3" style={{ strokeWidth: '2.4' }} />
        <g className="a a-cursor">
          <path className="ln" d="M15 21.5h6" style={{ strokeWidth: '2.6', stroke: 'var(--ok)' }} />
        </g>
      </>
    ),
  },
  provider: {
    gesture: 'se enchufa',
    body: (
      <>
        <Part>
          <path
            d="M12.5 23.5V27h7a5 5 0 0 0 5-5v-6"
            style={{ fill: 'none', strokeWidth: '2.8' }}
            className="k"
          />
        </Part>
        <Part>
          <circle cx="24.5" cy="14" r="3" className="warn" />
        </Part>
        <g className="a a-play">
          <Part>
            <path d="M10 11V6M15 11V6" style={{ fill: 'none', strokeWidth: '2.8' }} className="k" />
          </Part>
          <Part
            ink={
              <>
                <path className="hl" d="M10 13.5v3" />
              </>
            }
          >
            <path d="M7 11h11v7a5.5 5.5 0 0 1-11 0z" className="m" />
          </Part>
        </g>
      </>
    ),
  },
  'theme-light': {
    gesture: 'los rayos giran',
    body: (
      <>
        <g className="a a-rays">
          <path
            className="ln"
            d="M16 3.5v3M16 25.5v3M3.5 16h3M25.5 16h3M7.2 7.2l2.1 2.1M22.7 22.7l2.1 2.1M7.2 24.8l2.1-2.1M22.7 9.3l2.1-2.1"
            style={{ strokeWidth: '2.8', stroke: 'var(--warn)' }}
          />
        </g>
        <Part
          ink={
            <>
              <path className="hl" d="M13 13.5a3.5 3.5 0 0 1 2-1.5" />
            </>
          }
        >
          <circle cx="16" cy="16" r="7" className="warn" />
        </Part>
      </>
    ),
  },
  'theme-dark': {
    gesture: 'la luna se mece',
    body: (
      <>
        <g className="a a-rock">
          <Part
            ink={
              <>
                <path className="hl" d="M9.5 13a7 7 0 0 1 3-4" />
                <circle cx="13" cy="20" r="1.6" className="hb" />
              </>
            }
          >
            <path d="M20.5 5.5A11 11 0 1 0 26.5 21 8.6 8.6 0 0 1 20.5 5.5z" className="m" />
          </Part>
        </g>
        <g className="a a-tw1 det">
          <path d={star(24.5, 9, 2.8)} className="warnf" />
        </g>
      </>
    ),
  },
  'status-pending': {
    gesture: 'el reloj de arena se da la vuelta',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-flip">
          <path
            className="lw"
            d="M11 8h10M11 24h10M12 8c0 5 8 5 8 8s-8 3-8 8M20 8c0 5-8 5-8 8s8 3 8 8"
            style={{ strokeWidth: '2.4' }}
          />
        </g>
      </>
    ),
  },
  'status-thinking': {
    gesture: 'chispas que titilan',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-tw1">
          <path d={star(14, 17, 7)} fill="#fff" />
        </g>
        <g className="a a-tw2">
          <path d={star(21.5, 9.5, 3.4)} fill="#fff" />
        </g>
      </>
    ),
  },
  'status-executing': {
    gesture: 'el cursor escribe',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <path className="lw" d="m9.5 11.5 4.5 4.5-4.5 4.5" style={{ strokeWidth: '2.8' }} />
        <g className="a a-cursor">
          <path className="lw" d="M16.5 21h6" style={{ strokeWidth: '2.8' }} />
        </g>
      </>
    ),
  },
  'status-waiting': {
    gesture: 'saluda',
    badge: true,
    tone: 'warn',
    live: true,
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-wave">
          <path
            className="lw"
            d="M12 17V10.5M15 16V8.5M18 16V9M21 17v-5M11 17c0 4 2.5 7 5.5 7s5-2.8 5-6.5"
            style={{ strokeWidth: '2.6' }}
          />
        </g>
      </>
    ),
  },
  'status-ok': {
    gesture: 'se dibuja el check',
    badge: true,
    tone: 'ok',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <path className="lw a a-draw" d="m10 16.5 4 4 8-9" style={{ strokeWidth: '3' }} />
      </>
    ),
  },
  'status-error': {
    gesture: 'sacudida',
    badge: true,
    tone: 'bad',
    body: (
      <>
        <g className="a a-shake">
          <Part>
            <circle cx="16" cy="16" r="13" className="disc" />
          </Part>
          <path className="lw" d="m11.5 11.5 9 9M20.5 11.5l-9 9" style={{ strokeWidth: '3' }} />
        </g>
      </>
    ),
  },
  'status-warn': {
    gesture: 'se tambalea',
    badge: true,
    tone: 'warn',
    body: (
      <>
        <g className="a a-wobble">
          <Part>
            <path
              d="M16 4.5c.9 0 1.7.5 2.2 1.3l10 17.4c1 1.7-.2 3.8-2.2 3.8H6c-2 0-3.2-2.1-2.2-3.8l10-17.4c.5-.8 1.3-1.3 2.2-1.3z"
              className="disc"
            />
          </Part>
          <path className="lw" d="M16 11.5v6" style={{ strokeWidth: '3' }} />
          <circle cx="16" cy="22" r="1.7" fill="#fff" />
        </g>
      </>
    ),
  },
  info: {
    gesture: 'da un saltito',
    badge: true,
    body: (
      <>
        <g className="a a-bob">
          <Part>
            <circle cx="16" cy="16" r="13" className="disc" />
          </Part>
          <path className="lw" d="M16 14.5v8" style={{ strokeWidth: '3' }} />
          <circle cx="16" cy="10" r="1.8" fill="#fff" />
        </g>
      </>
    ),
  },
  'status-paused': {
    gesture: 'quieta, sin gesto',
    badge: true,
    tone: 'warn',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <path className="lw" d="M13 11v10M19 11v10" style={{ strokeWidth: '3' }} />
      </>
    ),
  },
  'status-offline': {
    gesture: 'el enchufe tiembla',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-shake">
          <path
            className="lw"
            d="M12 10v3.5M17 10v3.5M10 13.5h9v2.5a4.5 4.5 0 0 1-9 0zM14.5 20.5v3"
            style={{ strokeWidth: '2.4' }}
          />
          <path className="lw" d="m8.5 8.5 15 15" style={{ strokeWidth: '2.4' }} />
        </g>
      </>
    ),
  },
  'status-cancelled': {
    gesture: 'quieta',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <rect x="11.5" y="11.5" width="9" height="9" rx="2" fill="#fff" />
      </>
    ),
  },
  close: {
    gesture: 'gira un cuarto',
    util: true,
    body: (
      <>
        <g className="a a-x">
          <path className="ln" d="m9 9 14 14M23 9 9 23" />
        </g>
      </>
    ),
  },
  'chevron-right': {
    gesture: 'empuja hacia la derecha',
    util: true,
    body: (
      <>
        <g className="a a-nudger">
          <path className="ln" d="m12.5 8 8 8-8 8" />
        </g>
      </>
    ),
  },
  check: {
    gesture: 'se dibuja',
    util: true,
    body: (
      <>
        <path className="ln a a-draw" d="m7.5 16.5 5.5 5.5 11.5-12" />
      </>
    ),
  },
  menu: {
    gesture: 'las líneas se desplazan',
    util: true,
    body: (
      <>
        <g className="a a-menu1">
          <path className="ln" d="M6 9h20" />
        </g>
        <path className="ln" d="M6 16h20" />
        <g className="a a-menu3">
          <path className="ln" d="M6 23h20" />
        </g>
      </>
    ),
  },
  more: {
    gesture: 'ola de puntos',
    util: true,
    body: (
      <>
        <g className="a a-dots">
          <circle cx="8" cy="16" r="2.4" className="kf" />
        </g>
        <g className="a a-dots">
          <circle cx="16" cy="16" r="2.4" className="kf" />
        </g>
        <g className="a a-dots">
          <circle cx="24" cy="16" r="2.4" className="kf" />
        </g>
      </>
    ),
  },
  'theme-system': {
    gesture: 'la pantalla se enciende',
    body: (
      <>
        <Part>
          <rect x="12" y="22" width="8" height="5" rx="1.5" className="k" />
        </Part>
        <Part>
          <rect x="7" y="25.5" width="18" height="2.5" rx="1.25" className="m" />
        </Part>
        <Part>
          <rect x="3.5" y="5.5" width="25" height="17" rx="4" className="m" />
        </Part>
        <g className="a a-blink">
          <rect x="6.5" y="8.5" width="19" height="11" rx="2" fill="var(--ico-glass)" />
        </g>
        <path className="hl" d="M9 11.5h4" />
      </>
    ),
  },
  inbox: {
    gesture: 'cae un aviso en la bandeja',
    body: (
      <>
        <g className="a a-drop">
          <Part
            ink={
              <>
                <path className="lm soft" d="M13.5 7.5h5M13.5 10h3" />
              </>
            }
          >
            <rect x="11" y="4" width="10" height="9" rx="2" className="w" />
          </Part>
        </g>
        <Part>
          <path d="M4.5 16.5 8 10h16l3.5 6.5V24a3 3 0 0 1-3 3h-17a3 3 0 0 1-3-3z" className="m" />
        </Part>
        <Part
          ink={
            <>
              <path className="hl" d="M8 20.5h2" />
            </>
          }
        >
          <path d="M4.5 16.5h7l1.5 3h6l1.5-3h7V24a3 3 0 0 1-3 3h-17a3 3 0 0 1-3-3z" />
        </Part>
      </>
    ),
  },
  'notices-off': {
    gesture: 'la campana se tapa',
    body: (
      <>
        <Part>
          <path
            d="M16 6a7.2 7.2 0 0 1 7.2 7.2v4.6l2.6 4.2H6.2l2.6-4.2v-4.6A7.2 7.2 0 0 1 16 6z"
            className="w"
          />
        </Part>
        <Part>
          <circle cx="16" cy="25" r="2.6" className="k" />
        </Part>
        <g className="a a-press">
          <Part>
            <path d="M6 5 26 27" style={{ fill: 'none', strokeWidth: '3.2' }} className="bad" />
          </Part>
        </g>
      </>
    ),
  },
  logout: {
    gesture: 'la flecha sale por la puerta',
    body: (
      <>
        <Part>
          <path
            d="M6 7a2.5 2.5 0 0 1 2.5-2.5h8A2.5 2.5 0 0 1 19 7v18a2.5 2.5 0 0 1-2.5 2.5h-8A2.5 2.5 0 0 1 6 25z"
            className="m"
          />
        </Part>
        <circle cx="15" cy="16" r="1.4" className="wf" />
        <g className="a a-nudger">
          <Part>
            <path d="M14 13.5h7v-4l7 6.5-7 6.5v-4h-7z" className="warn" />
          </Part>
        </g>
      </>
    ),
  },
  'sidebar-collapse': {
    gesture: 'la flecha empuja',
    util: true,
    body: (
      <>
        <rect x="5" y="6" width="22" height="20" rx="4.5" className="ln" />
        <path className="ln" d="M12.5 6v20" />
        <g className="a a-nudgel">
          <path className="ln" d="m21 12.5-3.5 3.5 3.5 3.5" />
        </g>
      </>
    ),
  },
  'sidebar-expand': {
    gesture: 'la flecha empuja',
    util: true,
    body: (
      <>
        <rect x="5" y="6" width="22" height="20" rx="4.5" className="ln" />
        <path className="ln" d="M12.5 6v20" />
        <g className="a a-nudger">
          <path className="ln" d="m17.5 12.5 3.5 3.5-3.5 3.5" />
        </g>
      </>
    ),
  },
  'chevron-down': {
    gesture: 'empuja hacia abajo',
    util: true,
    body: (
      <>
        <g className="a a-nudged">
          <path className="ln" d="m8 12.5 8 8 8-8" />
        </g>
      </>
    ),
  },
  'chevron-left': {
    gesture: 'empuja hacia la izquierda',
    util: true,
    body: (
      <>
        <g className="a a-nudgel">
          <path className="ln" d="m19.5 8-8 8 8 8" />
        </g>
      </>
    ),
  },
  'status-ready': {
    gesture: 'el anillo da una vuelta',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-spin">
          <circle
            cx="16"
            cy="16"
            r="6.5"
            className="lw"
            style={{ strokeWidth: '2.6', strokeDasharray: '3.4 3.4' }}
          />
        </g>
      </>
    ),
  },
  'status-starting': {
    gesture: 'la flecha da vueltas',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-spin">
          <path className="lw" d="M21.5 13.5a6 6 0 1 0 .3 4.5" style={{ strokeWidth: '2.6' }} />
          <path d="M22.8 9.6v5h-5z" fill="#fff" />
        </g>
      </>
    ),
  },
  'status-running': {
    gesture: 'el play late',
    badge: true,
    tone: 'live',
    live: true,
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-play">
          <path
            d="M13 10.5 22 16l-9 5.5z"
            fill="#fff"
            stroke="#fff"
            strokeWidth="1.6"
            strokeLinejoin="round"
          />
        </g>
      </>
    ),
  },
  'status-skipped': {
    gesture: 'salta hacia delante',
    badge: true,
    tone: 'idle',
    body: (
      <>
        <Part>
          <circle cx="16" cy="16" r="13" className="disc" />
        </Part>
        <g className="a a-nudger">
          <path
            d="M10.5 10.5 18 16l-7.5 5.5z"
            fill="#fff"
            stroke="#fff"
            strokeWidth="1.4"
            strokeLinejoin="round"
          />
          <path className="lw" d="M21.5 10.5v11" style={{ strokeWidth: '2.8' }} />
        </g>
      </>
    ),
  },
}

// Los mismos dibujos con otro nombre: en Lucide son iconos distintos, aquí basta la insignia.
ILUSTRADOS['circle-ok'] = ILUSTRADOS['status-ok']
ILUSTRADOS['circle-error'] = ILUSTRADOS['status-error']
ILUSTRADOS['alert-error'] = ILUSTRADOS['status-error']
ILUSTRADOS['alert-warn'] = ILUSTRADOS['status-warn']
ILUSTRADOS.reload = ILUSTRADOS.refresh
