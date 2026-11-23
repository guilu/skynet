// @ts-nocheck -- motor portado del estudio v4.3 sin tipar; la API que usa la web la tipa
// `SkynyScene` (scene.ts) y la prueban los tests de Skyny3D.
// Portado del estudio v4.4 (plans/skyny-3d/skyny-v4.4.html).
import { clamp01 } from './math'

// =========================================================================
// 4. PANTALLA 2D DEL VISOR (Ojos y Texto LED con la 'F' y 'W' completas)
// =========================================================================

export const LED_GLYPHS: Record<string, string> = {
  W: 'M0 0 L11 60 L24 24 L37 60 L48 0',
  K: 'M0 0 V60 M46 0 L0 31 L47 60',
  B: 'M0 0 V60 H23 C55 60 55 30 23 30 H0 M0 0 H23 C52 0 52 30 23 30',
  '.': 'M0 60 H0.01',
  '0': 'M24 0 C56 0 56 60 24 60 C-8 60 -8 0 24 0 Z',
  '1': 'M12 12 L26 0 V60 M12 60 H40',
  '2': 'M2 12 C9 -5 44 -5 46 14 C48 27 25 40 2 60 H48',
  '3': 'M3 5 C36 -8 58 10 39 24 L25 29 C57 26 58 65 18 60 L3 54',
  '4': 'M36 0 L0 40 H49 M36 0 V60',
  '5': 'M46 0 H8 L3 29 C36 17 59 39 42 54 C30 65 11 61 2 53',
  '/': 'M8 60 L40 0',
  A: 'M0 60 L24 0 L48 60 M10 38 H38',
  C: 'M46 9 C32 -9 0 0 0 30 C0 60 32 69 46 51',
  D: 'M0 0 H17 C59 0 59 60 17 60 H0 Z',
  E: 'M44 0 H0 V60 H44 M0 30 H35',
  F: 'M44 0 H0 V60 M0 30 H35',
  G: 'M46 10 C32 -8 0 0 0 30 C0 60 36 66 46 46 V30 H24',
  H: 'M0 0 V60 M46 0 V60 M0 30 H46',
  I: 'M24 0 V60',
  L: 'M0 0 V60 H43',
  M: 'M0 60 V0 L24 33 L48 0 V60',
  N: 'M0 60 V0 L48 60 V0',
  O: 'M24 0 C56 0 56 60 24 60 C-8 60 -8 0 24 0 Z',
  P: 'M0 60 V0 H24 C53 0 53 32 24 32 H0',
  Q: 'M24 0 C56 0 56 60 24 60 C-8 60 -8 0 24 0 Z M30 42 L48 60',
  R: 'M0 60 V0 H24 C53 0 53 31 24 31 H0 M22 31 L47 60',
  S: 'M45 8 C24 -14 -10 10 14 25 C22 30 51 29 47 48 C44 67 12 65 2 54',
  T: 'M0 0 H48 M24 0 V60',
  U: 'M0 0 V36 C0 68 48 68 48 36 V0',
  V: 'M0 0 L24 60 L48 0',
  X: 'M2 0 L46 60 M46 0 L2 60',
  Y: 'M0 0 L24 31 L48 0 M24 31 V60',
  Z: 'M0 0 H48 L0 60 H48',
  // Para los letreros del login: Ñ, signos, coma y paréntesis.
  Ñ: 'M0 60 V0 L48 60 V0 M6 -16 C14 -26 22 -12 30 -18 C36 -22 40 -22 44 -18',
  ',': 'M2 58 L-4 72',
  '!': 'M6 0 V40 M6 60 H6.01',
  '¡': 'M6 0 H6.01 M6 20 V60',
  '?': 'M0 12 C4 -6 42 -6 42 14 C42 28 20 30 20 42 M20 60 H20.01',
  '¿': 'M22 0 H22.01 M22 18 C22 30 0 32 0 46 C0 66 38 66 42 48',
  '(': 'M22 0 C4 18 4 42 22 60',
  ')': 'M2 0 C20 18 20 42 2 60',
}

/** Vocales con tilde: la letra y un trazo encima. */
const ACCENTED: Record<string, string> = { Á: 'A', É: 'E', Í: 'I', Ó: 'O', Ú: 'U' }
for (const [accented, base] of Object.entries(ACCENTED))
  LED_GLYPHS[accented] = `${LED_GLYPHS[base]} M18 -10 L30 -22`

const NARROW: Record<string, number> = {
  '.': 22,
  ',': 22,
  ' ': 32,
  '!': 30,
  '¡': 30,
  '(': 40,
  ')': 40,
  '?': 60,
  '¿': 60,
  I: 39,
  Í: 39,
}
export const glyphW = (ch) => NARROW[ch] ?? 67

/** Ancho en px de un letrero a una escala dada. */
export const ledWidth = (text: string, scale = 1) =>
  ([...text].reduce((acc, c) => acc + glyphW(c), 0) - 19) * scale

export function drawLedScreen(
  ctx,
  text,
  { count, cursor, error = false, big = false, scrollX = null },
) {
  const chars = [...text]
  ctx.save()
  ctx.beginPath()
  ctx.arc(256, 256, 252, 0, Math.PI * 2)
  ctx.clip()
  ctx.fillStyle = '#030812'
  ctx.fillRect(0, 0, 512, 512)

  const totalW = chars.reduce((acc, c) => acc + glyphW(c), 0) - 19
  // Deslizándose, a tamaño fijo y desde `scrollX`; si no, centrado y encogido para que quepa.
  const scale = scrollX === null ? Math.min(big ? 1.65 : 1.1, 335 / totalW) : 1.1
  ctx.translate(scrollX === null ? 256 - (totalW * scale) / 2 : scrollX, 256 - 30 * scale)
  ctx.scale(scale, scale)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'

  const glow = error ? '#ff3b30' : '#00e5ff'
  const stroke = error ? '#ff453a' : '#00d2ff'
  const strokePath = (path, width) => {
    ctx.shadowColor = glow
    ctx.shadowBlur = 26
    ctx.strokeStyle = stroke
    ctx.lineWidth = width
    ctx.stroke(path)
    ctx.shadowBlur = 12
    ctx.strokeStyle = '#f0fdf4'
    ctx.lineWidth = width / 2
    ctx.stroke(path)
  }

  for (const ch of chars.slice(0, count)) {
    const d = LED_GLYPHS[ch]
    if (d) {
      ctx.save()
      if (ch === 'I' || ch === 'Í') ctx.translate(-14, 0)
      strokePath(new Path2D(d), 16)
      ctx.restore()
    }
    ctx.translate(glyphW(ch), 0)
  }
  if (cursor) strokePath(new Path2D('M0 -10 V70'), 18)
  ctx.restore()
}

export let winkBuffer = null
export function drawEyesScreen(
  ctx,
  eyeType,
  colors,
  offset = [0, 0],
  overrideColor,
  winkMix = 1,
  saccade = [0, 0],
) {
  if (eyeType === 'wink' && winkMix < 1) {
    if (!winkBuffer) {
      winkBuffer = document.createElement('canvas')
      winkBuffer.width = 512
      winkBuffer.height = 512
    }
    renderEyesBase(ctx, 'pills', colors, offset, overrideColor, saccade)
    const bCtx = winkBuffer.getContext('2d')
    bCtx.clearRect(0, 0, 512, 512)
    bCtx.drawImage(ctx.canvas, 0, 0)
    renderEyesBase(ctx, 'wink', colors, offset, overrideColor, saccade)
    const splitX = 256 + offset[0]
    ctx.save()
    ctx.drawImage(winkBuffer, splitX, 0, 512 - splitX, 512, splitX, 0, 512 - splitX, 512)
    ctx.globalAlpha = 1 - Math.max(0, winkMix)
    ctx.drawImage(winkBuffer, 0, 0, splitX, 512, 0, 0, splitX, 512)
    ctx.restore()
    return
  }
  renderEyesBase(ctx, eyeType, colors, offset, overrideColor, saccade)
}

export function renderEyesBase(ctx, eyeType, colors, offset, overrideColor, saccade = [0, 0]) {
  ctx.clearRect(0, 0, 512, 512)
  ctx.save()
  ctx.beginPath()
  ctx.arc(256, 256, 252, 0, Math.PI * 2)
  ctx.clip()

  const bg = ctx.createRadialGradient(256, 240, 20, 256, 256, 256)
  bg.addColorStop(0, '#0c1626')
  bg.addColorStop(0.65, '#050913')
  bg.addColorStop(1, '#020306')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, 512, 512)

  const shine = ctx.createLinearGradient(0, 50, 0, 280)
  shine.addColorStop(0, 'rgba(255,255,255,0.16)')
  shine.addColorStop(0.4, 'rgba(255,255,255,0.05)')
  shine.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = shine
  ctx.beginPath()
  ctx.ellipse(256, 140, 195, 80, 0, 0, Math.PI * 2)
  ctx.fill()

  const cx = 256 + offset[0],
    cy = 268 + offset[1]
  let coreInset = 0
  const roundPill = (x, y, w, h, r) => {
    const ins = coreInset > 0 ? Math.min(coreInset, w * 0.18, h * 0.18) : 0
    ctx.beginPath()
    ctx.roundRect(x + ins, y + ins, w - ins * 2, h - ins * 2, Math.max(3, r - ins * 0.6))
    ctx.fill()
  }

  const [sacMag, sacDir] = saccade
  const stretchY = 1 + sacMag * 0.14
  const asymL = 1 + sacMag * sacDir * 0.12
  const asymR = 1 - sacMag * sacDir * 0.12

  const drawNeon = (fn) => {
    const gCol = overrideColor ?? colors.glow
    const cCol = overrideColor ? '#ffffff' : colors.core
    ;[
      [gCol, 40, gCol, 0.4],
      [gCol, 18, gCol, 1],
      ['#ffffff', 8, cCol, overrideColor ? 0.72 : 1],
    ].forEach(([sc, sb, fs, alpha], layer) => {
      ctx.save()
      ctx.globalAlpha = alpha
      ctx.shadowColor = sc
      ctx.shadowBlur = sb
      ctx.fillStyle = fs
      ctx.strokeStyle = fs
      ctx.lineCap = 'round'
      fn(layer)
      ctx.restore()
    })
  }

  if (eyeType === 'listening') {
    ctx.lineCap = 'round'
    for (const [width, color, blur] of [
      [16, '#00d2ff', 22],
      [8, '#f4ffff', 6],
    ]) {
      ctx.lineWidth = width
      ctx.strokeStyle = color
      ctx.shadowColor = '#00d8ff'
      ctx.shadowBlur = blur
      ctx.beginPath()
      for (let i = 0; i < 9; i++) {
        const env = Math.sin((i / 8) * Math.PI)
        const height = 14 + (16 + 44 * env) * (0.5 + 0.5 * Math.sin(saccade[0] + i * 0.85))
        const x = 144 + i * 28
        ctx.moveTo(x, 256 - height)
        ctx.lineTo(x, 256 + height)
      }
      ctx.stroke()
    }
  } else if (eyeType === 'pills') {
    drawNeon((layer) => {
      const inset = layer === 2 ? 8 : 0
      const w = 44 - inset * 2
      const hL = 128 * stretchY * asymL - inset * 2
      const hR = 128 * stretchY * asymR - inset * 2
      roundPill(cx - 92 + inset, cy - hL / 2, w, hL, Math.max(4, 22 - inset / 2))
      roundPill(cx + 48 + inset, cy - hR / 2, w, hR, Math.max(4, 22 - inset / 2))
    })
  } else if (eyeType === 'happy') {
    drawNeon((layer) => {
      ctx.lineWidth = [42, 36, 14][layer]
      for (const s of [-1, 1]) {
        const r = 54 * (s < 0 ? asymL : asymR)
        ctx.beginPath()
        ctx.arc(cx + s * 82, cy + 20, r, Math.PI * 1.15, Math.PI * 1.85)
        ctx.stroke()
      }
    })
  } else {
    const col = overrideColor ?? colors.glow
    const coreCol = overrideColor ? '#ffffff' : colors.core
    const passes = eyeType === 'pause-bars' ? [0] : [0, 1]
    for (const pass of passes) {
      const isCore = pass === 1
      coreInset = isCore ? 7 : 0
      const lw = (w) => {
        ctx.lineWidth = isCore ? Math.max(3, w * 0.42) : w
      }
      ctx.save()
      ctx.globalAlpha = isCore ? (overrideColor ? 0.72 : 1) : 1
      ctx.shadowColor = isCore ? '#ffffff' : col
      ctx.shadowBlur = isCore ? 6 : 28
      ctx.strokeStyle = isCore ? coreCol : col
      ctx.fillStyle = isCore ? coreCol : col
      ctx.lineCap = 'round'
      ctx.lineJoin = 'round'
      switch (eyeType) {
        case 'empty-search':
          ctx.shadowBlur = isCore ? 6 : 26
          roundPill(cx - 92, cy - 34, 44, 68, 22)
          roundPill(cx + 48, cy - 66, 44, 132, 22)
          break
        case 'error-pills':
          roundPill(cx - 92, cy - 64, 44, 128, 22)
          roundPill(cx + 48, cy - 64, 44, 128, 22)
          break
        case 'wink':
          lw(34)
          ctx.beginPath()
          ctx.arc(cx - 78, cy + 20, 50, Math.PI * 1.15, Math.PI * 1.85)
          ctx.stroke()
          roundPill(cx + 48, cy - 64, 44, 128, 22)
          break
        case 'sleep':
          lw(30)
          for (const s of [-1, 1]) {
            ctx.beginPath()
            ctx.arc(cx + s * 75, cy - 10, 48, Math.PI * 0.15, Math.PI * 0.85)
            ctx.stroke()
          }
          break
        case 'stress':
          lw(34)
          for (const s of [-1, 1]) {
            ctx.beginPath()
            ctx.moveTo(cx + s * 105, cy - 35)
            ctx.lineTo(cx + s * 50, cy)
            ctx.lineTo(cx + s * 105, cy + 35)
            ctx.stroke()
          }
          break
        case 'pause-bars':
          ctx.shadowBlur = 0
          roundPill(cx - 117, cy - 19, 86, 38, 19)
          roundPill(cx + 31, cy - 19, 86, 38, 19)
          break
        case 'bars':
          lw(30)
          ctx.beginPath()
          ctx.moveTo(cx - 95, cy)
          ctx.lineTo(cx - 40, cy)
          ctx.moveTo(cx + 40, cy)
          ctx.lineTo(cx + 95, cy)
          ctx.stroke()
          break
        case 'circles':
          lw(24)
          for (const s of [-1, 1]) {
            ctx.beginPath()
            ctx.arc(cx + s * 70, cy, 38, 0, Math.PI * 2)
            ctx.stroke()
          }
          break
        case 'nodes':
          lw(10)
          for (const s of [-1, 1]) {
            const bx = cx + s * 74
            const pts = [
              [bx - 24, cy - 26],
              [bx + 24, cy - 12],
              [bx - 16, cy + 28],
              [bx + 22, cy + 26],
            ]
            ctx.beginPath()
            ctx.moveTo(...pts[0])
            ctx.lineTo(...pts[1])
            ctx.moveTo(...pts[0])
            ctx.lineTo(...pts[2])
            ctx.moveTo(...pts[1])
            ctx.lineTo(...pts[3])
            ctx.moveTo(...pts[2])
            ctx.lineTo(...pts[3])
            ctx.stroke()
            for (const [px, py] of pts) {
              ctx.beginPath()
              ctx.arc(px, py, isCore ? 7 : 12, 0, Math.PI * 2)
              ctx.fill()
            }
          }
          break
        case 'check-eyes':
          lw(28)
          for (const s of [-1, 1]) {
            const bx = cx + s * 74
            ctx.beginPath()
            ctx.moveTo(bx - 36, cy + 2)
            ctx.lineTo(bx - 10, cy + 28)
            ctx.lineTo(bx + 38, cy - 28)
            ctx.stroke()
          }
          break
        case 'code-eyes':
          lw(30)
          ctx.beginPath()
          ctx.moveTo(cx - 105, cy - 38)
          ctx.lineTo(cx - 52, cy)
          ctx.lineTo(cx - 105, cy + 38)
          ctx.stroke()
          roundPill(cx + 42, cy - 52, 46, 104, 14)
          break
        case 'stars':
          for (const s of [-1, 1]) {
            const sx = cx + s * 74,
              sy = cy
            const rOut = isCore ? 38 : 52,
              rIn = isCore ? 12 : 18
            ctx.beginPath()
            for (let i = 0; i < 8; i++) {
              const r = i % 2 === 0 ? rOut : rIn
              const a = (i * Math.PI) / 4 - Math.PI / 2
              if (i === 0) ctx.moveTo(sx + Math.cos(a) * r, sy + Math.sin(a) * r)
              else ctx.lineTo(sx + Math.cos(a) * r, sy + Math.sin(a) * r)
            }
            ctx.closePath()
            ctx.fill()
          }
          break
        case 'reticle':
          lw(20)
          ctx.beginPath()
          ctx.arc(cx - 72, cy, 46, 0, Math.PI * 2)
          ctx.stroke()
          ctx.beginPath()
          ctx.arc(cx - 72, cy, isCore ? 9 : 14, 0, Math.PI * 2)
          ctx.fill()
          roundPill(cx + 44, cy - 26, 56, 52, 20)
          break
        case 'merge-eyes':
          lw(18)
          ctx.beginPath()
          ctx.moveTo(cx - 95, cy - 32)
          ctx.quadraticCurveTo(cx - 32, cy - 32, cx, cy)
          ctx.moveTo(cx + 95, cy + 32)
          ctx.quadraticCurveTo(cx + 32, cy + 32, cx, cy)
          ctx.stroke()
          for (const [nx, ny, nr] of [
            [cx - 95, cy - 32, 20],
            [cx + 95, cy + 32, 20],
            [cx, cy, 28],
          ]) {
            ctx.beginPath()
            ctx.arc(nx, ny, isCore ? nr * 0.62 : nr, 0, Math.PI * 2)
            ctx.fill()
          }
          break
        case 'eco-eyes':
          roundPill(cx - 92, cy - 34, 46, 68, 22)
          roundPill(cx + 46, cy - 34, 46, 68, 22)
          break
        case 'effort-eyes':
          roundPill(cx - 92, cy - 60, 44, 120, 22)
          lw(26)
          ctx.beginPath()
          ctx.moveTo(cx + 44, cy - 22)
          ctx.lineTo(cx + 98, cy + 2)
          ctx.lineTo(cx + 44, cy + 26)
          ctx.stroke()
          break
        case 'shield-eyes':
          lw(16)
          for (const s of [-1, 1]) {
            const sx = cx + s * 74
            ctx.beginPath()
            ctx.moveTo(sx - 34, cy - 34)
            ctx.lineTo(sx + 34, cy - 34)
            ctx.lineTo(sx + 34, cy + 6)
            ctx.lineTo(sx, cy + 38)
            ctx.lineTo(sx - 34, cy + 6)
            ctx.closePath()
            ctx.stroke()
            ctx.beginPath()
            ctx.arc(sx, cy - 4, isCore ? 7 : 12, 0, Math.PI * 2)
            ctx.fill()
          }
          break
        case 'conflict-eyes':
          lw(24)
          for (const s of [-1, 1]) {
            const bx = cx + s * 74
            ctx.beginPath()
            ctx.moveTo(bx - 38, cy - s * 18)
            ctx.lineTo(bx + 38, cy + s * 18)
            ctx.stroke()
            roundPill(bx - 18, cy - 4, 36, 44, 16)
          }
          break
        case 'compact-eyes': {
          const squeeze = clamp01(saccade[0])
          const hPill = Math.round(124 - squeeze * 72)
          const wPill = Math.round(44 + squeeze * 12)
          roundPill(cx - 70 - wPill / 2, cy - hPill / 2, wPill, hPill, Math.min(22, hPill / 2))
          roundPill(cx + 70 - wPill / 2, cy - hPill / 2, wPill, hPill, Math.min(22, hPill / 2))
          break
        }
        case 'stream-eyes': {
          const beat = saccade[0]
          const pulseL = 1 + Math.sin(beat) * 0.18
          const pulseR = 1 + Math.cos(beat) * 0.18
          const hL = Math.round(122 * pulseL)
          const hR = Math.round(122 * pulseR)
          roundPill(cx - 92, cy - hL / 2, 44, hL, 22)
          roundPill(cx + 48, cy - hR / 2, 44, hR, 22)
          break
        }
        case 'dizzy': {
          lw(10)
          const spinAngle = performance.now() * 0.008
          for (const s of [-1, 1]) {
            const dx = cx + s * 74
            ctx.beginPath()
            const steps = 64
            for (let i = 0; i <= steps; i++) {
              const t = i / steps
              const r = t * 44
              const a = t * Math.PI * 5 + spinAngle * s
              const px = dx + Math.cos(a) * r
              const py = cy + Math.sin(a) * r
              if (i === 0) ctx.moveTo(px, py)
              else ctx.lineTo(px, py)
            }
            ctx.stroke()
          }
          break
        }
      }
      ctx.restore()
    }
    coreInset = 0
  }
  ctx.restore()
}

// =========================================================================
// 5. MOTOR PRINCIPAL DE SKYNY 3D (SkynyController)
// =========================================================================
