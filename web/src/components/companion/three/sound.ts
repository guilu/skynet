/**
 * Sonidos de Skyny (estudio v5): tonos y soplidos hechos al vuelo con Web Audio, sin ficheros.
 * Un ¡boop! al tocarlo, propulsores al entrar y salir, un pitido por cada pulso o microexpresión y
 * el del scouter al recibir texto. Se apagan en Ajustes › Companion (`setSkynySound`). El
 * navegador no deja sonar nada hasta que la persona toca la página: lo de antes se pierde en
 * silencio. Sin Web Audio (tests, navegadores viejos) no hace nada.
 */

let ctx: AudioContext | null = null
let enabled = true

function ensure(): AudioContext | null {
  if (!enabled) return null
  if (!ctx) {
    const AC =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!AC) return null
    try {
      ctx = new AC()
    } catch {
      return null
    }
  }
  if (ctx.state === 'suspended') ctx.resume().catch(() => {})
  return ctx
}

function tone(
  freq: number,
  endFreq: number | null,
  dur: number,
  type: OscillatorType = 'sine',
  vol = 0.08,
  delay = 0,
) {
  const c = ensure()
  if (!c) return
  const t0 = c.currentTime + delay
  const osc = c.createOscillator()
  const gain = c.createGain()
  osc.type = type
  osc.frequency.setValueAtTime(freq, t0)
  if (endFreq && endFreq !== freq)
    osc.frequency.exponentialRampToValueAtTime(Math.max(20, endFreq), t0 + dur)
  gain.gain.setValueAtTime(0.0001, t0)
  gain.gain.linearRampToValueAtTime(vol, t0 + 0.012)
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  osc.connect(gain)
  gain.connect(c.destination)
  osc.start(t0)
  osc.stop(t0 + dur + 0.02)
}

function noiseWhoosh(dur: number, vol = 0.06, delay = 0, cutoffStart = 420, cutoffEnd = 1200) {
  const c = ensure()
  if (!c) return
  const len = Math.floor(c.sampleRate * dur)
  const buf = c.createBuffer(1, len, c.sampleRate)
  const data = buf.getChannelData(0)
  for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1
  const src = c.createBufferSource()
  src.buffer = buf
  const filter = c.createBiquadFilter()
  filter.type = 'bandpass'
  const t0 = c.currentTime + delay
  filter.frequency.setValueAtTime(cutoffStart, t0)
  filter.frequency.exponentialRampToValueAtTime(cutoffEnd, t0 + dur)
  filter.Q.value = 1.6
  const gain = c.createGain()
  gain.gain.setValueAtTime(0.0001, t0)
  gain.gain.linearRampToValueAtTime(vol, t0 + dur * 0.25)
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
  src.connect(filter)
  filter.connect(gain)
  gain.connect(c.destination)
  src.start(t0)
  src.stop(t0 + dur + 0.02)
}

/** Enciende o apaga los sonidos de todos los Skyny de la página. */
export function setSkynySound(on: boolean) {
  enabled = on
  if (!on && ctx) {
    void ctx.close().catch(() => {})
    ctx = null
  }
}

export const skynySound = {
  get enabled() {
    return enabled
  },
  boop() {
    tone(360, 740, 0.11, 'sine', 0.11)
    tone(740, 920, 0.06, 'triangle', 0.05, 0.07)
  },
  scouterBeep() {
    tone(1320, 1480, 0.045, 'triangle', 0.065, 0)
    tone(1760, 1980, 0.045, 'triangle', 0.065, 0.065)
    tone(2217, 2349, 0.07, 'sine', 0.07, 0.13)
  },
  pulse(type: string) {
    if (type === 'gate_passed') {
      tone(523, 523, 0.07, 'sine', 0.07, 0)
      tone(659, 659, 0.07, 'sine', 0.07, 0.06)
      tone(784, 880, 0.13, 'sine', 0.08, 0.12)
    } else if (type === 'file_edited') {
      tone(660, 880, 0.08, 'triangle', 0.06)
    } else if (type === 'token_tick') {
      tone(1420, 1280, 0.028, 'sine', 0.03)
    } else {
      tone(880, 1140, 0.055, 'triangle', 0.06)
    }
  },
  micro(type: string) {
    if (type === 'nod') {
      tone(587, 784, 0.08, 'sine', 0.07)
      tone(784, 880, 0.09, 'sine', 0.06, 0.09)
    } else if (type === 'shake') {
      tone(240, 180, 0.09, 'sawtooth', 0.035)
      tone(210, 160, 0.11, 'sawtooth', 0.035, 0.1)
    } else if (type === 'curious') {
      tone(440, 660, 0.14, 'sine', 0.07)
    } else if (type === 'startle') {
      tone(480, 1040, 0.1, 'triangle', 0.08)
    } else if (type === 'sigh') {
      noiseWhoosh(0.45, 0.04, 0.15, 700, 260)
      tone(380, 210, 0.35, 'sine', 0.04, 0.12)
    } else if (type === 'glance') {
      tone(720, 780, 0.045, 'sine', 0.05)
      tone(780, 720, 0.05, 'sine', 0.05, 0.14)
    } else if (type === 'celebrate') {
      tone(523, 659, 0.08, 'triangle', 0.065)
      tone(659, 784, 0.08, 'triangle', 0.065, 0.08)
      tone(784, 1046, 0.14, 'sine', 0.07, 0.16)
    } else if (type === 'shrug') {
      tone(360, 480, 0.13, 'sine', 0.055)
    } else if (type === 'salute') {
      tone(660, 880, 0.075, 'triangle', 0.06)
      tone(880, 988, 0.09, 'sine', 0.055, 0.08)
    } else if (type === 'focus') {
      tone(320, 520, 0.14, 'sine', 0.05)
    }
  },
  thruster(mode: 'enter' | 'exit') {
    if (mode === 'enter') {
      noiseWhoosh(1.25, 0.055, 0.15, 350, 950)
      tone(115, 88, 0.9, 'triangle', 0.045, 0.2)
    } else {
      noiseWhoosh(1.05, 0.06, 0.55, 400, 1600)
      tone(98, 210, 0.7, 'triangle', 0.05, 0.75)
    }
  },
  stateChange(state: string) {
    if (['error', 'guardrail', 'conflict', 'cancelled', 'offline'].includes(state)) {
      tone(330, 240, 0.12, 'triangle', 0.055)
    } else if (['success', 'celebrating', 'pr-open', 'merging'].includes(state)) {
      tone(587, 784, 0.12, 'sine', 0.06)
    } else {
      tone(480, 560, 0.07, 'sine', 0.04)
    }
  },
}
