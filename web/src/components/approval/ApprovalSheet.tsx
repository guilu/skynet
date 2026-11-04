import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Pill } from '../ui/Pill'
import { Sheet } from '../ui/Sheet'
import { LinesSkeleton } from '../ui/Skeleton'
import { formatUsd } from '../work/phase'
import { RISK_LABEL, RISK_TONE } from './approvalFormat'
import { DecisionPanel } from './DecisionPanel'

/**
 * Aprobar sin salir del trabajo (FS-E): la acción, el riesgo, qué pasa al aprobar y lo gastado
 * (este intento y el trabajo), con las mismas decisiones que la página de la aprobación.
 */
export function ApprovalSheet({
  approvalId,
  workCostUsd,
  onClose,
}: {
  approvalId: string | null
  /** Lo que lleva gastado el trabajo, con todos sus intentos. */
  workCostUsd: number | null
  onClose: () => void
}) {
  const approval = useQuery({
    queryKey: ['approval', approvalId],
    queryFn: () => api.approval(approvalId!),
    enabled: approvalId != null,
  })
  const a = approval.data
  return (
    <Sheet
      open={approvalId != null}
      onOpenChange={(open) => !open && onClose()}
      title="Revisar la aprobación"
      description={a?.action}
    >
      <ErrorMessage error={approval.error} />
      {approval.isPending && <LinesSkeleton label="la aprobación" lines={4} />}
      {a && (
        <div className="approval-sheet">
          <p className="approval-pills">
            <Pill tone={RISK_TONE[a.risk]}>{RISK_LABEL[a.risk]}</Pill>
            <span className="muted small">
              {a.evidence.origin.repository.name} · {a.evidence.origin.stage.name ?? a.stageKey}
            </span>
          </p>
          <p>{a.evidence.consequences.approve}</p>
          <dl className="approval-sheet-cost">
            <div>
              <dt>Este intento</dt>
              <dd>{formatUsd(a.evidence.costUsd)}</dd>
            </div>
            <div>
              <dt>El trabajo, con todos sus intentos</dt>
              <dd>{formatUsd(workCostUsd)}</dd>
            </div>
          </dl>
          <p>
            <Link to={`/approvals/${a.id}`}>Ver toda la evidencia</Link>
          </p>
          {a.status === 'PENDING' ? (
            <DecisionPanel approval={a} onDecided={onClose} />
          ) : (
            <p className="muted">Ya está decidida.</p>
          )}
        </div>
      )}
    </Sheet>
  )
}
