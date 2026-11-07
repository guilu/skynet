// Las expresiones de Skyny 3D: qué ojos pone y qué accesorio saca en cada estado del companion.
// Es la tabla `STATES` del estudio, con las 12 expresiones de `CompanionState`. El color del
// cuerpo no está aquí: sale de los tokens del 2D (`.companion[data-state]` en index.css).
import type { CompanionState } from '../state'
import type { FaceEyes } from './face'

export type Accessory =
  'bubbles' | 'speed' | 'magnifier' | 'confetti' | 'stress' | 'alert' | 'pause' | 'zzz' | 'offline'

export interface Expression3D {
  eyes: FaceEyes
  accessory: Accessory | null
  /** Hace un gesto de entrada al llegar al estado (si no se pidió reducir el movimiento). */
  entry: boolean
  /** Color propio de los ojos; sin él, los del tema (`--companion-glow` / `--companion-eye`). */
  eyeColor?: string
}

export const EXPRESSIONS_3D: Record<CompanionState, Expression3D> = {
  calm: { eyes: 'pills', accessory: null, entry: false },
  ready: { eyes: 'pills', accessory: null, entry: false },
  thinking: { eyes: 'wink', accessory: 'bubbles', entry: true },
  running: { eyes: 'happy', accessory: 'speed', entry: true },
  reviewing: { eyes: 'pills', accessory: 'magnifier', entry: true },
  success: { eyes: 'happy', accessory: 'confetti', entry: true },
  error: { eyes: 'stress', accessory: 'stress', entry: true, eyeColor: '#ffffff' },
  waiting: { eyes: 'pills', accessory: 'alert', entry: true },
  paused: { eyes: 'pause-bars', accessory: 'pause', entry: true, eyeColor: '#64748b' },
  sleeping: { eyes: 'sleep', accessory: 'zzz', entry: true, eyeColor: '#e2e8f0' },
  offline: { eyes: 'circles', accessory: 'offline', entry: true, eyeColor: '#cbd5e1' },
  cancelled: { eyes: 'bars', accessory: null, entry: false, eyeColor: '#94a3b8' },
}
