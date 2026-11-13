import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, type FindingView, type JudgeLens, type StageRun } from '../../api'
import { cn } from '../../lib/cn'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { LENS_LABEL } from './stageNotes'
import { CATEGORY_LABEL, OUTCOME_LABEL, summary } from './findingFormat'

/**
 * El registro de hallazgos de una fase (RH-A): cada hallazgo de sus jueces con su desenlace. Lo
 * abierto o aparcado se puede marcar como intencionado (no se vuelve a señalar en el repositorio)
 * o como que no aplica (el juez se equivocó); y se puede deshacer.
 */
export function StageFindings({ runId, stage }: { runId: string; stage: StageRun }) {
  const findings = useQuery({
    queryKey: ['run', runId, 'findings', stage.status, stage.judge?.round ?? null],
    queryFn: () => api.findings(runId),
  })
  const mine = (findings.data ?? []).filter((f) => f.stageRunId === stage.id)
  if (mine.length === 0) return null
  return (
    <section className="stage-findings small" aria-label="Registro de hallazgos">
      <p className="stage-judge-head">
        <strong>Registro de hallazgos</strong>
        <span className="muted"> · {summary(mine)}</span>
      </p>
      <ul className="stage-findings-list">
        {mine.map((f) => (
          <FindingRow key={f.id} finding={f} runId={runId} />
        ))}
      </ul>
    </section>
  )
}

function FindingRow({ finding: f, runId }: { finding: FindingView; runId: string }) {
  const queryClient = useQueryClient()
  const [choosing, setChoosing] = useState<'aceptado' | 'irrelevante' | null>(null)
  const [reason, setReason] = useState('')
  const decide = useMutation({
    mutationFn: (outcome: 'aceptado' | 'irrelevante' | 'abierto') =>
      api.decideFinding(f.id, outcome, outcome === 'abierto' ? undefined : reason),
    onSuccess: () => {
      setChoosing(null)
      setReason('')
      void queryClient.invalidateQueries({ queryKey: ['run', runId, 'findings'] })
    },
  })
  const open = f.outcome === 'abierto' || f.outcome === 'aparcado'
  const human = f.outcome === 'aceptado' || f.outcome === 'irrelevante'
  return (
    <li className={cn('stage-finding', f.outcome)}>
      <span className={cn('finding-outcome', f.outcome)}>{OUTCOME_LABEL[f.outcome]}</span>{' '}
      {f.category !== 'otro' && (
        <span className="tag finding-category">{CATEGORY_LABEL[f.category]}</span>
      )}{' '}
      {location(f) && <code>{location(f)}</code>} {f.text}{' '}
      <span className="muted">
        ({f.judge}
        {f.lens && ` · ${LENS_LABEL[f.lens as JudgeLens] ?? f.lens}`}
        {f.source === 'delta' && ' · delta'}
        {f.round > 1 && ` · ronda ${f.round}`})
      </span>
      {f.repeats > 0 && (
        <span className="muted">
          {' · '}salió {f.repeats + 1} veces
        </span>
      )}
      {f.firstSeenRunId && f.firstSeenRunId !== f.runId && (
        <>
          {' · '}
          <Link to={`/runs/${f.firstSeenRunId}`}>ya salió en otra ejecución</Link>
        </>
      )}
      {f.outcomeReason && (
        <span className="muted stage-judge-reason">
          {' '}
          {f.outcomeBy && f.outcomeBy !== 'skynet' ? `${f.outcomeBy}: ` : ''}
          {f.outcomeReason}
        </span>
      )}
      {choosing ? (
        <span
          className="finding-actions"
          role="group"
          aria-label={
            choosing === 'aceptado' ? 'Marcar como intencionado' : 'Marcar como que no aplica'
          }
        >
          <input
            className="finding-reason"
            aria-label="Motivo (opcional)"
            placeholder="Motivo (opcional)"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <Button size="sm" onClick={() => decide.mutate(choosing)} disabled={decide.isPending}>
            {decide.isPending ? 'Guardando…' : 'Guardar'}
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setChoosing(null)}>
            Cancelar
          </Button>
        </span>
      ) : open ? (
        <span className="finding-actions">
          <Button size="sm" variant="secondary" onClick={() => setChoosing('aceptado')}>
            Es intencionado
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setChoosing('irrelevante')}>
            No aplica
          </Button>
        </span>
      ) : human ? (
        <span className="finding-actions">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => decide.mutate('abierto')}
            disabled={decide.isPending}
          >
            Deshacer
          </Button>
        </span>
      ) : null}
      <ErrorMessage error={decide.error} />
    </li>
  )
}

function location(f: FindingView): string | null {
  if (!f.file) return null
  return f.line != null ? `${f.file}:${f.line}` : f.file
}
