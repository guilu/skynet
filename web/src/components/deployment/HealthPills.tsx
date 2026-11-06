import type { Deployment } from '../../api'
import { Pill } from '../ui/Pill'
import { DEPLOYMENT_HEALTH, DEPLOYMENT_STATUS } from './deployment'

/** El estado del despliegue y, aparte, su salud: «desplegado» no es «sano». */
export function DeploymentPills({ deployment: d }: { deployment: Deployment }) {
  const status = DEPLOYMENT_STATUS[d.status]
  const health = DEPLOYMENT_HEALTH[d.health]
  return (
    <span className="deploy-pills">
      <Pill tone={status.tone}>{status.label}</Pill>
      {(d.status === 'SUCCEEDED' || d.health !== 'UNKNOWN') && (
        <Pill tone={health.tone}>{health.label}</Pill>
      )}
      {d.stuckAt && <Pill tone="warn">Atascado</Pill>}
    </span>
  )
}
