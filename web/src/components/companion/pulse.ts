import type { StoredEvent } from '../../api'

/** Las reacciones al vivo de Skyny 3D: no cambian su estado, solo lo adornan un momento. */
export type PulseType = 'tool_call' | 'file_edited' | 'token_tick' | 'gate_passed'

/** Como mucho una reacción cada tanto: con ráfagas de eventos, Skyny no parece nervioso. */
export const PULSE_GAP_MS = 300

/**
 * La reacción de Skyny a un evento de una ejecución o del chat, o null si no tiene. Usa una
 * herramienta: destello y asentimiento; toca un fichero: chispa; llega un mensaje: late el brillo;
 * pasa la verificación o se confirma una acción: chispas verdes.
 */
export function pulseFor(event: Pick<StoredEvent, 'type' | 'payload'>): PulseType | null {
  switch (event.type) {
    case 'agent.tool.started':
    case 'chat.action.proposed':
      return 'tool_call'
    case 'agent.file.changed':
      return 'file_edited'
    case 'agent.message.received':
    case 'chat.message.created':
      return 'token_tick'
    case 'chat.action.confirmed':
      return 'gate_passed'
    case 'agent.verification.completed': {
      const p = event.payload
      const tests = p.tests as { failed?: number; errors?: number } | undefined
      const failed = (tests?.failed ?? 0) + (tests?.errors ?? 0) > 0
      return p.error == null && (p.exitCode ?? 0) === 0 && !failed ? 'gate_passed' : null
    }
    default:
      return null
  }
}

/**
 * Recorta una ráfaga: deja pasar una reacción si han pasado `PULSE_GAP_MS` desde la anterior.
 * Devuelve la nueva marca de tiempo, o null si hay que descartarla.
 */
export function throttlePulse(last: number | null, now: number): number | null {
  return last === null || now - last >= PULSE_GAP_MS ? now : null
}
