import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, type Lesson } from '../../api'
import { formatDateTime } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { CATEGORY_LABEL, OUTCOME_LABEL } from '../run/findingFormat'
import { Button } from '../ui/Button'
import { KIND_LABEL, lessonsKey } from './lessonFormat'

/** Lo que hace aplicarla, según el tipo. */
const APPLY_LABEL: Record<Lesson['kind'], string> = {
  prevenir: 'Aplicar en la memoria',
  'no-senalar': 'Aplicar en los criterios del juez',
}

/**
 * Una lección (AP-C): su clase, su tipo y cuántas veces salió, el texto (editable mientras está
 * propuesta) y sus hallazgos con enlace a la ejecución. Propuesta, se aplica o se descarta con un
 * motivo; aplicada, se desactiva con un motivo. El texto se puede copiar para `REVIEW.md`.
 */
export function LessonCard({
  lesson: l,
  showRepository = true,
}: {
  lesson: Lesson
  showRepository?: boolean
}) {
  const queryClient = useQueryClient()
  const [text, setText] = useState(l.appliedText ?? l.suggestedText)
  const [dismissing, setDismissing] = useState(false)
  const [reason, setReason] = useState('')
  const [showFindings, setShowFindings] = useState(false)
  const [copied, setCopied] = useState(false)
  const done = () => {
    setDismissing(false)
    setReason('')
    void queryClient.invalidateQueries({ queryKey: lessonsKey })
  }
  const apply = useMutation({ mutationFn: () => api.applyLesson(l.id, text), onSuccess: done })
  const dismiss = useMutation({
    mutationFn: () => api.dismissLesson(l.id, reason.trim()),
    onSuccess: done,
  })
  const detail = useQuery({
    queryKey: [...lessonsKey, 'detail', l.id],
    queryFn: () => api.lesson(l.id),
    enabled: showFindings,
  })
  const proposed = l.status === 'propuesta'
  const title = `${KIND_LABEL[l.kind]}: ${CATEGORY_LABEL[l.category]}`
  async function copy() {
    await navigator.clipboard?.writeText(text)
    setCopied(true)
  }
  return (
    <article className="lesson-card" aria-label={`${title} en ${l.repositoryName}`}>
      <header className="lesson-card-head">
        <span className={`tag lesson-kind ${l.kind}`}>{KIND_LABEL[l.kind]}</span>{' '}
        <span className="tag finding-category">{CATEGORY_LABEL[l.category]}</span>{' '}
        <span className="muted small">
          {showRepository && `${l.projectName} › ${l.repositoryName} · `}
          en {l.runs} ejecuciones · {l.findings} {l.findings === 1 ? 'hallazgo' : 'hallazgos'}
        </span>
      </header>
      {proposed ? (
        <textarea
          className="lesson-text"
          aria-label="Texto de la lección"
          rows={Math.min(10, text.split('\n').length + 1)}
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
      ) : (
        <pre className="lesson-text">{text}</pre>
      )}
      {l.decidedBy && (
        <p className="muted small">
          {l.status === 'aplicada'
            ? `Aplicada por ${l.decidedBy}`
            : l.appliedText
              ? `Desactivada por ${l.decidedBy}`
              : `Descartada por ${l.decidedBy}`}
          {l.decidedAt && ` el ${formatDateTime(l.decidedAt)}`}
          {l.reason && `: ${l.reason}`}
        </p>
      )}
      {dismissing ? (
        <div
          className="finding-actions"
          role="group"
          aria-label={proposed ? 'Descartar la lección' : 'Desactivar la lección'}
        >
          <input
            className="finding-reason"
            aria-label="Motivo"
            placeholder="Motivo"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <Button
            size="sm"
            onClick={() => dismiss.mutate()}
            disabled={dismiss.isPending || reason.trim() === ''}
          >
            {dismiss.isPending ? 'Guardando…' : proposed ? 'Descartar' : 'Desactivar'}
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setDismissing(false)}>
            Cancelar
          </Button>
        </div>
      ) : (
        <div className="finding-actions">
          {proposed && (
            <Button
              size="sm"
              onClick={() => apply.mutate()}
              disabled={apply.isPending || text.trim() === ''}
            >
              {apply.isPending ? 'Aplicando…' : APPLY_LABEL[l.kind]}
            </Button>
          )}
          {l.status !== 'descartada' && (
            <Button size="sm" variant="secondary" onClick={() => setDismissing(true)}>
              {proposed ? 'Descartar' : 'Desactivar'}
            </Button>
          )}
          <Button size="sm" variant="secondary" onClick={() => void copy()}>
            {copied ? 'Copiado' : 'Copiar para REVIEW.md'}
          </Button>
          <Button
            size="sm"
            variant="link"
            aria-expanded={showFindings}
            onClick={() => setShowFindings((v) => !v)}
          >
            {showFindings ? 'Ocultar los hallazgos' : `Ver los hallazgos (${l.findings})`}
          </Button>
        </div>
      )}
      <ErrorMessage error={apply.error ?? dismiss.error ?? detail.error} />
      {showFindings && detail.data && (
        <ul className="stage-findings-list small" aria-label="Hallazgos de la lección">
          {detail.data.findings.map((f) => (
            <li key={f.id} className={`stage-finding ${f.outcome}`}>
              <span className={`finding-outcome ${f.outcome}`}>{OUTCOME_LABEL[f.outcome]}</span>{' '}
              {f.file && <code>{f.line != null ? `${f.file}:${f.line}` : f.file}</code>} {f.text}{' '}
              <span className="muted">
                · <Link to={`/runs/${f.runId}`}>{formatDateTime(f.outcomeAt)}</Link>
              </span>
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}
