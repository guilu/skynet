import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, MessageSquare, RotateCcw, X } from 'lucide-react'
import { useState } from 'react'
import { api, type ApprovalAction, type ApprovalView } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'

const REQUIRES_COMMENT: Record<ApprovalAction, string | null> = {
  APPROVE: null,
  REJECT: 'Para rechazar, escribe el motivo.',
  REQUEST_CHANGES: 'Para pedir cambios, escribe qué cambiar.',
  COMMENT: 'Escribe el comentario.',
}

/**
 * Decidir: un comentario (obligatorio para rechazar, pedir cambios y comentar) y las cuatro
 * acciones. Aprobar algo de riesgo alto pide confirmarlo.
 */
export function DecisionPanel({
  approval,
  onDecided,
}: {
  approval: ApprovalView
  onDecided: (action: ApprovalAction) => void
}) {
  const queryClient = useQueryClient()
  const [comment, setComment] = useState('')
  const [missing, setMissing] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const decide = useMutation({
    mutationFn: (action: ApprovalAction) =>
      api.decideApproval(approval.id, action, comment.trim() || undefined),
    onSuccess: (view, action) => {
      onDecided(action)
      queryClient.setQueryData(['approval', approval.id], view)
      setComment('')
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: ['approvals'] })
      void queryClient.invalidateQueries({ queryKey: ['run', approval.workflowRunId] })
    },
  })
  const canRevise = approval.evidence.consequences.requestChanges != null

  function act(action: ApprovalAction) {
    const needs = REQUIRES_COMMENT[action]
    if (needs && !comment.trim()) {
      setMissing(needs)
      return
    }
    setMissing(null)
    if (action === 'APPROVE' && approval.risk === 'HIGH' && !confirming) {
      setConfirming(true)
      return
    }
    decide.mutate(action)
  }

  return (
    <section className="card approval-decide" aria-labelledby="decide-title">
      <h2 id="decide-title">Decidir</h2>
      <div className="form">
        <label>
          Comentario
          <textarea
            value={comment}
            onChange={(e) => {
              setComment(e.target.value)
              setMissing(null)
            }}
            rows={4}
            maxLength={10_000}
            aria-describedby="decide-hint"
            aria-invalid={missing ? true : undefined}
          />
          <span id="decide-hint" className="hint">
            Obligatorio para rechazar, pedir cambios o comentar.
          </span>
        </label>
        {missing && (
          <p className="error" role="alert">
            {missing}
          </p>
        )}
        <div className="form-actions approval-actions">
          <Button variant="success" onClick={() => act('APPROVE')} disabled={decide.isPending}>
            <Check size={18} strokeWidth={2.5} aria-hidden="true" />
            Aprobar
          </Button>
          <Button variant="danger" onClick={() => act('REJECT')} disabled={decide.isPending}>
            <X size={18} strokeWidth={2.5} aria-hidden="true" />
            Rechazar
          </Button>
          {canRevise && (
            <Button
              variant="secondary"
              onClick={() => act('REQUEST_CHANGES')}
              disabled={decide.isPending}
            >
              <RotateCcw size={18} strokeWidth={2.5} aria-hidden="true" />
              Pedir cambios
            </Button>
          )}
          <Button variant="secondary" onClick={() => act('COMMENT')} disabled={decide.isPending}>
            <MessageSquare size={18} strokeWidth={2.5} aria-hidden="true" />
            Comentar
          </Button>
        </div>
        <ErrorMessage error={decide.error} />
      </div>
      <Sheet
        open={confirming}
        onOpenChange={setConfirming}
        title="Aprobar con riesgo alto"
        description={approval.action}
      >
        <div className="form">
          <p>{approval.evidence.consequences.approve}</p>
          <div className="form-actions">
            <Button
              variant="success"
              onClick={() => decide.mutate('APPROVE')}
              disabled={decide.isPending}
            >
              <Check size={18} strokeWidth={2.5} aria-hidden="true" />
              Sí, aprobar
            </Button>
            <Button variant="secondary" onClick={() => setConfirming(false)}>
              Cancelar
            </Button>
          </div>
          <ErrorMessage error={decide.error} />
        </div>
      </Sheet>
    </section>
  )
}
