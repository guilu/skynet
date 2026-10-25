/** Colores del paquete «ChatGPT» (chatgpt.tsx). */
/** Degradados del paquete: arriba y abajo. */
export const CHATGPT_GRADIENTS = {
  red: ['#ff7f7f', '#ec3a50'],
  orange: ['#ffc65a', '#ff8a1f'],
  yellow: ['#ffe27a', '#ffb31f'],
  green: ['#62e6a0', '#16b462'],
  blue: ['#71b6ff', '#2d68f5'],
  indigo: ['#929aff', '#4f43e6'],
  violet: ['#b991ff', '#7638ee'],
  pink: ['#ff86b3', '#ec3778'],
  slate: ['#a2abbd', '#566077'],
  sky: ['#e2eeff', '#a8c8ff'],
  paper: ['#ffffff', '#dfe9f9'],
  teal: ['#66e3d4', '#13a79b'],
  dark: ['#4a5b85', '#1d2945'],
  lilac: ['#e4d8ff', '#b89fff'],
  coral: ['#ff9d7a', '#ff5a4a'],
} as const

export type CgColor = keyof typeof CHATGPT_GRADIENTS

export const g = (c: CgColor) => `url(#cg-${c})`
export const HL = { fill: '#fff', opacity: 0.5 }
export const WHITE = '#fff'
export const INK = '#1d2945'
