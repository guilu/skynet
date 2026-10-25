import { g, HL, WHITE, type CgColor } from './chatgptColors'

/* Piezas que repiten varios dibujos del paquete «ChatGPT» (chatgpt.tsx). */

/** Disco de estado: su color sale del tono (`tone-…`) y lleva brillo. */
export const Disc = () => (
  <>
    <circle cx="16" cy="16" r="13" className="cg-disc" />
    <path
      d="M7.5 12.5A9.5 9.5 0 0 1 14 6.7"
      stroke={WHITE}
      strokeOpacity=".45"
      strokeWidth="2.4"
      strokeLinecap="round"
      fill="none"
    />
  </>
)

export const Line = ({ d, w = 3.2, c = WHITE }: { d: string; w?: number; c?: string }) => (
  <path d={d} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round" fill="none" />
)

/** Hoja de papel con la esquina doblada. */
export const Doc = ({ fill = g('sky') }: { fill?: string }) => (
  <>
    <path d="M8 4.5h10.5L25 11v14.5a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-19a2 2 0 0 1 2-2z" fill={fill} />
    <path d="M18.5 4.5V9a2 2 0 0 0 2 2H25z" fill={WHITE} opacity=".75" />
  </>
)

export const Folder = ({
  back = g('orange'),
  front = g('yellow'),
}: {
  back?: string
  front?: string
}) => (
  <>
    <path
      d="M3.5 8.5a2.5 2.5 0 0 1 2.5-2.5h5.6l2.6 2.8H26a2.5 2.5 0 0 1 2.5 2.5v12.2A2.5 2.5 0 0 1 26 26H6a2.5 2.5 0 0 1-2.5-2.5z"
      fill={back}
    />
    <path
      d="M3.5 13a2 2 0 0 1 2-2h21a2 2 0 0 1 2 2v10.5A2.5 2.5 0 0 1 26 26H6a2.5 2.5 0 0 1-2.5-2.5z"
      fill={front}
    />
    <path
      d="M6.5 14h9"
      stroke={WHITE}
      strokeOpacity=".55"
      strokeWidth="1.8"
      strokeLinecap="round"
    />
  </>
)

/** Bandeja abierta de importar y descargar. */
export const Tray = () => (
  <>
    <path d="M4 19h6.5l1.8 3h7.4l1.8-3H28v5.5a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3z" fill={g('sky')} />
    <path d="M4 19h6.5l1.8 3h7.4l1.8-3H28" stroke="#8fb4f5" strokeWidth="1.2" fill="none" />
  </>
)

export const Bell = () => (
  <>
    <path
      d="M16 4a8 8 0 0 1 8 8v5.5l2.6 3.4A1.3 1.3 0 0 1 25.5 23h-19a1.3 1.3 0 0 1-1.1-2.1L8 17.5V12a8 8 0 0 1 8-8z"
      fill={g('yellow')}
    />
    <path d="M12.8 25a3.3 3.3 0 0 0 6.4 0z" fill={g('orange')} />
    <path
      d="M11 9.5a5.6 5.6 0 0 1 3-3"
      stroke={WHITE}
      strokeOpacity=".6"
      strokeWidth="2"
      strokeLinecap="round"
      fill="none"
    />
  </>
)

/** Burbuja de chat. */
export const Bubble = ({ fill = g('blue') }: { fill?: string }) => (
  <path
    d="M6.5 6h19A2.5 2.5 0 0 1 28 8.5v11a2.5 2.5 0 0 1-2.5 2.5H14l-5.5 4.5V22h-2A2.5 2.5 0 0 1 4 19.5v-11A2.5 2.5 0 0 1 6.5 6z"
    fill={fill}
  />
)

export const Shield = ({ fill = g('green') }: { fill?: string }) => (
  <>
    <path d="M16 3.5 26 7v8.2c0 6.3-4.2 10.8-10 13.3C10.2 26 6 21.5 6 15.2V7z" fill={fill} />
    <path d="M16 3.5 26 7v8.2c0 6.3-4.2 10.8-10 13.3z" fill="#000" opacity=".08" />
  </>
)

/** Nodos de un grafo unidos por aristas. */
export const Graph = ({ a, b, c }: { a: CgColor; b: CgColor; c: CgColor }) => (
  <>
    <Line d="M16 9 9 22M16 9l7 13" w={2.6} c="#94a3c4" />
    <g className="a a-n1">
      <circle cx="16" cy="8.5" r="4.6" fill={g(a)} />
      <circle cx="14.6" cy="7" r="1.3" {...HL} />
    </g>
    <g className="a a-n2">
      <circle cx="8.5" cy="22.5" r="4.6" fill={g(b)} />
    </g>
    <g className="a a-n3">
      <circle cx="23.5" cy="22.5" r="4.6" fill={g(c)} />
    </g>
  </>
)

export const Box = () => (
  <>
    <rect x="5" y="13" width="22" height="14" rx="2.5" fill={g('blue')} />
    <rect x="12.5" y="16.5" width="7" height="2.6" rx="1.3" fill={WHITE} opacity=".85" />
  </>
)

export const Coin = ({ y, a }: { y: number; a?: string }) => (
  <g className={a}>
    <ellipse cx="16" cy={y + 2.2} rx="9" ry="3.6" fill="#e59a0b" />
    <ellipse cx="16" cy={y} rx="9" ry="3.6" fill={g('yellow')} />
    <ellipse cx="16" cy={y} rx="5" ry="1.7" fill={WHITE} opacity=".4" />
  </g>
)

export const Clock = ({ fill = g('sky') }: { fill?: string }) => (
  <>
    <rect x="13.5" y="2.5" width="5" height="3" rx="1.2" fill={g('blue')} />
    <circle cx="16" cy="17.5" r="11" fill={g('blue')} />
    <circle cx="16" cy="17.5" r="8.2" fill={fill} />
  </>
)
