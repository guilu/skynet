import type { DeploymentHealth, DeploymentStatus, HealthProbeOutcome } from '../../api'
import type { StatusTone } from '../../format'

/** Cómo terminó el despliegue: Compose arrancó, no que la aplicación responda. */
export const DEPLOYMENT_STATUS: Record<DeploymentStatus, { tone: StatusTone; label: string }> = {
  PENDING: { tone: 'neutral', label: 'Pendiente' },
  IN_PROGRESS: { tone: 'active', label: 'Desplegando' },
  SUCCEEDED: { tone: 'ok', label: 'Desplegado' },
  FAILED: { tone: 'bad', label: 'Falló' },
  SUPERSEDED: { tone: 'neutral', label: 'Sustituido' },
}

/** Si lo desplegado responde, según la comprobación que configura el admin. */
export const DEPLOYMENT_HEALTH: Record<DeploymentHealth, { tone: StatusTone; label: string }> = {
  UNKNOWN: { tone: 'neutral', label: 'Salud sin comprobar' },
  CHECKING: { tone: 'active', label: 'Comprobando' },
  HEALTHY: { tone: 'ok', label: 'Sano' },
  UNHEALTHY: { tone: 'bad', label: 'No sano' },
  TIMEOUT: { tone: 'bad', label: 'No responde' },
}

export const PROBE_OUTCOME: Record<HealthProbeOutcome, { tone: StatusTone; label: string }> = {
  OK: { tone: 'ok', label: 'Bien' },
  BAD_STATUS: { tone: 'bad', label: 'Código malo' },
  SHA_MISMATCH: { tone: 'bad', label: 'Otro SHA' },
  TIMEOUT: { tone: 'bad', label: 'Sin respuesta' },
  ERROR: { tone: 'bad', label: 'Error' },
}

export const shortSha = (sha: string) => sha.slice(0, 7)

/** `owner/name` si la URL remota es de GitHub (https o ssh); si no, null. */
export function gitHubRepo(remoteUrl: string | null): string | null {
  const m = remoteUrl?.match(/github\.com[:/]([^/\s]+\/[^/\s]+?)(?:\.git)?\/?$/)
  return m ? m[1] : null
}
