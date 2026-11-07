// La pantalla OLED de Skyny 3D (TD-A): los ojos se dibujan en un canvas 2D que hace de textura.

/** Lado del canvas de la pantalla, en píxeles. */
export const FACE_PX = 512

/** Ojos de la pantalla: `pills` abiertos (Reposo) y `bars` cerrados (parpadeo). */
export type FaceEyes = 'pills' | 'bars'

export interface FaceColors {
  /** Halo de los ojos (`--companion-glow`). */
  glow: string
  /** Núcleo de los ojos (`--companion-eye`). */
  core: string
}

/**
 * Pinta la cara: fondo oscuro con reflejo curvo y los ojos, movidos `gaze` píxeles (x, y) hacia
 * donde mira Skyny.
 */
export function drawFace(
  ctx: CanvasRenderingContext2D,
  eyes: FaceEyes,
  colors: FaceColors,
  gaze: [number, number] = [0, 0],
) {
  const c = FACE_PX / 2
  ctx.clearRect(0, 0, FACE_PX, FACE_PX)
  ctx.save()
  ctx.beginPath()
  ctx.arc(c, c, c - 4, 0, Math.PI * 2)
  ctx.clip()

  const bg = ctx.createRadialGradient(c, c - 16, 20, c, c, c)
  bg.addColorStop(0, '#0c1626')
  bg.addColorStop(0.65, '#050913')
  bg.addColorStop(1, '#020306')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, FACE_PX, FACE_PX)

  const shine = ctx.createLinearGradient(0, 50, 0, 280)
  shine.addColorStop(0, 'rgba(255, 255, 255, 0.16)')
  shine.addColorStop(0.4, 'rgba(255, 255, 255, 0.05)')
  shine.addColorStop(1, 'rgba(255, 255, 255, 0)')
  ctx.fillStyle = shine
  ctx.beginPath()
  ctx.ellipse(c, 140, 195, 80, 0, 0, Math.PI * 2)
  ctx.fill()

  const cx = c + gaze[0]
  const cy = c + 12 + gaze[1]
  // Cada ojo en tres pasadas: halo ancho, cuerpo del color del halo y núcleo claro.
  const eye = (x: number, y: number, w: number, h: number, r: number) => {
    const layers: [string, number, string, number][] = [
      [colors.glow, 40, colors.glow, 0.4],
      [colors.glow, 18, colors.glow, 1],
      ['#ffffff', 8, colors.core, 1],
    ]
    layers.forEach(([shadow, blur, fill, alpha], i) => {
      const inset = i === 2 ? Math.min(8, w / 4, h / 4) : 0
      ctx.save()
      ctx.globalAlpha = alpha
      ctx.shadowColor = shadow
      ctx.shadowBlur = blur
      ctx.fillStyle = fill
      capsule(ctx, x + inset, y + inset, w - inset * 2, h - inset * 2, Math.max(2, r - inset / 2))
      ctx.restore()
    })
  }
  if (eyes === 'pills') {
    eye(cx - 92, cy - 64, 44, 128, 22)
    eye(cx + 48, cy - 64, 44, 128, 22)
  } else {
    eye(cx - 100, cy - 14, 62, 28, 14)
    eye(cx + 38, cy - 14, 62, 28, 14)
  }
  ctx.restore()
}

function capsule(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
) {
  ctx.beginPath()
  ctx.roundRect(x, y, w, h, r)
  ctx.fill()
}
