// Los letreros de la pantalla OLED (estudio v2): al llegar a un estado, Skyny escribe en la
// pantalla lo que hace («PENSANDO...», «LISTO», «REINTENTO 1/2»…) letra a letra, con cursor, y
// al rato vuelve a poner los ojos. Las letras son trazos propios (no una fuente), con el brillo
// de los ojos.
import type { CompanionState } from '../state'
import { FACE_PX } from './face'

/** Lo que escribe en cada estado. */
export const LED_MESSAGES: Record<CompanionState, string> = {
  calm: 'HOLA',
  ready: 'HOLA',
  thinking: 'PENSANDO...',
  running: 'EN MARCHA',
  reviewing: 'REVISANDO...',
  success: 'LISTO',
  error: 'ERROR',
  waiting: 'AYUDA',
  paused: 'PAUSA',
  sleeping: 'ZZZ',
  offline: 'SIN RED',
  cancelled: 'CANCELADO',
}

/** Versión corta para cuando Skyny es pequeño (la «Vista compacta» del estudio): hasta 8 letras. */
const SHORT: Partial<Record<CompanionState, string>> = { thinking: 'IDEA', reviewing: 'REVISA' }

export function ledMessage(state: CompanionState, compact: boolean, text = LED_MESSAGES[state]) {
  return compact && text.length > 8 ? (SHORT[state] ?? text) : text
}

/** Lo que tarda en escribir cada letra, el parpadeo del cursor y lo que se queda escrito. */
export const LED_CHAR_MS = 90
const CURSOR_MS = 240
export const LED_HOLD_MS = 1000

/** Cuánto dura un letrero entero: escribirlo y dejarlo un segundo. */
export const ledDuration = (text: string) => [...text].length * LED_CHAR_MS + LED_HOLD_MS

/** Cuántas letras se ven a los `ms` de empezar, si se ve el cursor y si ya acabó. */
export function ledTypingFrame(ms: number, length: number, reduced: boolean) {
  const typing = reduced ? 0 : length * LED_CHAR_MS
  return {
    count: reduced ? length : Math.max(0, Math.min(length, Math.floor(ms / LED_CHAR_MS))),
    cursor: !reduced && (ms < typing || Math.floor((ms - typing) / CURSOR_MS) % 2 === 0),
    finished: ms >= typing + LED_HOLD_MS,
  }
}

/** Trazos de cada letra, en una caja de 48 × 60. Solo las que usan los letreros y los contadores. */
export const LED_GLYPHS: Record<string, string> = {
  '.': 'M0 60 H0.01',
  '0': 'M24 0 C56 0 56 60 24 60 C-8 60 -8 0 24 0 Z',
  '1': 'M12 12 L26 0 V60 M12 60 H40',
  '2': 'M2 12 C9 -5 44 -5 46 14 C48 27 25 40 2 60 H48',
  '3': 'M3 5 C36 -8 58 10 39 24 L25 29 C57 26 58 65 18 60 L3 54',
  '4': 'M36 0 L0 40 H49 M36 0 V60',
  '5': 'M46 0 H8 L3 29 C36 17 59 39 42 54 C30 65 11 61 2 53',
  '6': 'M42 4 C20 -6 0 12 0 36 C0 66 46 66 46 40 C46 18 10 18 2 34',
  '7': 'M0 0 H48 L16 60',
  '8': 'M24 0 C-4 0 -4 28 24 28 C54 28 54 60 24 60 C-6 60 -6 28 24 28 C52 28 52 0 24 0 Z',
  '9': 'M46 26 C38 42 2 42 2 20 C2 -6 46 -6 46 24 C46 48 26 66 4 56',
  '/': 'M8 60 L40 0',
  A: 'M0 60 L24 0 L48 60 M10 38 H38',
  C: 'M46 9 C32 -9 0 0 0 30 C0 60 32 69 46 51',
  D: 'M0 0 H17 C59 0 59 60 17 60 H0 Z',
  E: 'M44 0 H0 V60 H44 M0 30 H35',
  H: 'M0 0 V60 M46 0 V60 M0 30 H46',
  I: 'M24 0 V60',
  L: 'M0 0 V60 H43',
  M: 'M0 60 V0 L24 33 L48 0 V60',
  N: 'M0 60 V0 L48 60 V0',
  O: 'M24 0 C56 0 56 60 24 60 C-8 60 -8 0 24 0 Z',
  P: 'M0 60 V0 H24 C53 0 53 32 24 32 H0',
  R: 'M0 60 V0 H24 C53 0 53 31 24 31 H0 M22 31 L47 60',
  S: 'M45 8 C24 -14 -10 10 14 25 C22 30 51 29 47 48 C44 67 12 65 2 54',
  T: 'M0 0 H48 M24 0 V60',
  U: 'M0 0 V36 C0 68 48 68 48 36 V0',
  V: 'M0 0 L24 60 L48 0',
  Y: 'M0 0 L24 31 L48 0 M24 31 V60',
  Z: 'M0 0 H48 L0 60 H48',
}

const advance = (char: string) => (char === '.' ? 22 : char === ' ' ? 32 : char === 'I' ? 39 : 67)

/**
 * Escribe `text` en la pantalla: las `count` primeras letras y, si toca, el cursor. En Error el
 * letrero es rojo; si no, cian. `big` es para los contadores (letras más grandes).
 */
export function drawLed(
  ctx: CanvasRenderingContext2D,
  text: string,
  {
    count,
    cursor,
    error = false,
    big = false,
  }: {
    count: number
    cursor: boolean
    error?: boolean
    big?: boolean
  },
) {
  const glyphs = [...text]
  const c = FACE_PX / 2
  ctx.save()
  ctx.beginPath()
  ctx.arc(c, c, c - 4, 0, Math.PI * 2)
  ctx.clip()
  ctx.fillStyle = '#030812'
  ctx.fillRect(0, 0, FACE_PX, FACE_PX)
  const width = glyphs.reduce((total, char) => total + advance(char), 0) - 19
  const scale = Math.min(big ? 1.65 : 1.1, 335 / width)
  ctx.translate(c - (width * scale) / 2, c - 30 * scale)
  ctx.scale(scale, scale)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  const halo = error ? '#ff3b30' : '#00e5ff'
  const stroke = error ? '#ff453a' : '#00d2ff'
  const glow = (path: Path2D, width: number) => {
    ctx.shadowColor = halo
    ctx.shadowBlur = 26
    ctx.strokeStyle = stroke
    ctx.lineWidth = width
    ctx.stroke(path)
    ctx.shadowBlur = 12
    ctx.strokeStyle = '#f0fdf4'
    ctx.lineWidth = width / 2
    ctx.stroke(path)
  }
  for (const char of glyphs.slice(0, count)) {
    const d = LED_GLYPHS[char]
    if (d && typeof Path2D !== 'undefined') {
      ctx.save()
      if (char === 'I') ctx.translate(-14, 0)
      glow(new Path2D(d), 16)
      ctx.restore()
    }
    ctx.translate(advance(char), 0)
  }
  if (cursor && typeof Path2D !== 'undefined') glow(new Path2D('M0 -10 V70'), 18)
  ctx.restore()
}
