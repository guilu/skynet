// La pantalla OLED de Skyny 3D: los ojos se dibujan en un canvas 2D que hace de textura. Es
// `drawScreenFace` del estudio, con los colores del tema.

/** Lado del canvas de la pantalla, en píxeles. */
export const FACE_PX = 512

/** Ojos de la pantalla (los `eyeType` del estudio). */
export type FaceEyes =
  | 'pills'
  | 'happy'
  | 'wink'
  | 'sleep'
  | 'stress'
  | 'error-pills'
  | 'pause-bars'
  | 'bars'
  | 'circles'

export interface FaceColors {
  /** Halo de los ojos (`--companion-glow`). */
  glow: string
  /** Núcleo de los ojos (`--companion-eye`). */
  core: string
}

let openEyes: HTMLCanvasElement | null = null

/**
 * Pinta la cara: fondo oscuro con reflejo curvo y los ojos, movidos `gaze` píxeles (x, y) hacia
 * donde mira Skyny. `tint` es el color propio de los ojos de algunos estados (Error, Pausa…).
 * Con `wink` < 1, el guiño de Pensando está a medias: el ojo izquierdo pasa de abierto a cerrado
 * (el derecho, que no cambia, se queda tal cual).
 */
export function drawFace(
  ctx: CanvasRenderingContext2D,
  eyes: FaceEyes,
  colors: FaceColors,
  gaze: [number, number] = [0, 0],
  tint?: string,
  wink = 1,
) {
  if (eyes !== 'wink' || wink >= 1) {
    paintFace(ctx, eyes, colors, gaze, tint)
    return
  }
  if (!openEyes) {
    openEyes = document.createElement('canvas')
    openEyes.width = FACE_PX
    openEyes.height = FACE_PX
  }
  paintFace(ctx, 'pills', colors, gaze, tint)
  const buffer = openEyes.getContext('2d')!
  buffer.clearRect(0, 0, FACE_PX, FACE_PX)
  buffer.drawImage(ctx.canvas, 0, 0)
  paintFace(ctx, 'wink', colors, gaze, tint)
  const split = FACE_PX / 2 + gaze[0]
  ctx.save()
  ctx.drawImage(openEyes, split, 0, FACE_PX - split, FACE_PX, split, 0, FACE_PX - split, FACE_PX)
  ctx.globalAlpha = 1 - Math.max(0, wink)
  ctx.drawImage(openEyes, 0, 0, split, FACE_PX, 0, 0, split, FACE_PX)
  ctx.restore()
}

function paintFace(
  ctx: CanvasRenderingContext2D,
  eyes: FaceEyes,
  colors: FaceColors,
  gaze: [number, number],
  tint?: string,
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

  // Ojos que brillan en tres pasadas: halo ancho, cuerpo del color del halo y núcleo claro.
  const glowing = (paint: (layer: number) => void) => {
    const layers: [string, number, string, number][] = [
      [colors.glow, 40, colors.glow, 0.4],
      [colors.glow, 18, colors.glow, 1],
      ['#ffffff', 8, colors.core, 1],
    ]
    layers.forEach(([shadow, blur, fill, alpha], layer) => {
      ctx.save()
      ctx.globalAlpha = alpha
      ctx.shadowColor = shadow
      ctx.shadowBlur = blur
      ctx.fillStyle = fill
      ctx.strokeStyle = fill
      ctx.lineCap = 'round'
      paint(layer)
      ctx.restore()
    })
  }

  if (eyes === 'pills') {
    glowing((layer) => {
      const inset = layer === 2 ? 8 : 0
      capsule(
        ctx,
        cx - 92 + inset,
        cy - 64 + inset,
        44 - inset * 2,
        128 - inset * 2,
        22 - inset / 2,
      )
      capsule(
        ctx,
        cx + 48 + inset,
        cy - 64 + inset,
        44 - inset * 2,
        128 - inset * 2,
        22 - inset / 2,
      )
    })
  } else if (eyes === 'happy') {
    glowing((layer) => {
      ctx.lineWidth = [42, 36, 14][layer]
      for (const side of [-1, 1]) {
        ctx.beginPath()
        ctx.arc(cx + side * 82, cy + 20, 54, Math.PI * 1.15, Math.PI * 1.85)
        ctx.stroke()
      }
    })
  } else {
    // Ojos de un solo color, con un halo suave del mismo.
    const color = tint ?? colors.glow
    ctx.shadowColor = color
    ctx.shadowBlur = 28
    ctx.strokeStyle = color
    ctx.fillStyle = color
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    switch (eyes) {
      case 'error-pills':
        capsule(ctx, cx - 92, cy - 64, 44, 128, 22)
        capsule(ctx, cx + 48, cy - 64, 44, 128, 22)
        break
      case 'wink':
        ctx.lineWidth = 34
        ctx.beginPath()
        ctx.arc(cx - 78, cy + 20, 50, Math.PI * 1.15, Math.PI * 1.85)
        ctx.stroke()
        capsule(ctx, cx + 48, cy - 64, 44, 128, 22)
        break
      case 'sleep':
        ctx.lineWidth = 30
        for (const side of [-1, 1]) {
          ctx.beginPath()
          ctx.arc(cx + side * 75, cy - 10, 48, Math.PI * 0.15, Math.PI * 0.85)
          ctx.stroke()
        }
        break
      case 'stress':
        ctx.lineWidth = 34
        for (const side of [-1, 1]) {
          ctx.beginPath()
          ctx.moveTo(cx + side * 105, cy - 35)
          ctx.lineTo(cx + side * 50, cy)
          ctx.lineTo(cx + side * 105, cy + 35)
          ctx.stroke()
        }
        break
      case 'pause-bars':
        ctx.shadowBlur = 0
        capsule(ctx, cx - 117, cy - 19, 86, 38, 19)
        capsule(ctx, cx + 31, cy - 19, 86, 38, 19)
        break
      case 'bars':
        // Parpadeo y Cancelado: dos rayas.
        ctx.lineWidth = 30
        ctx.beginPath()
        ctx.moveTo(cx - 95, cy)
        ctx.lineTo(cx - 40, cy)
        ctx.moveTo(cx + 40, cy)
        ctx.lineTo(cx + 95, cy)
        ctx.stroke()
        break
      case 'circles':
        ctx.lineWidth = 24
        for (const side of [-1, 1]) {
          ctx.beginPath()
          ctx.arc(cx + side * 70, cy, 38, 0, Math.PI * 2)
          ctx.stroke()
        }
        break
    }
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
