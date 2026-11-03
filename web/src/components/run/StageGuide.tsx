import type { GuideStep, StageJudge as Judge } from '../../api'
import { cn } from '../../lib/cn'

const STATUS_LABEL: Record<GuideStep['status'], string> = {
  passed: 'Pasa',
  failed: 'Falla',
  blocked: 'No se pudo',
  skipped: 'Saltado',
}

/**
 * La guía de pruebas como fase (VA-D): qué guías se ejecutaron y cada paso, por sección, con su
 * estado y la evidencia plegada. Mientras se corrige, los pasos de la ronda anterior.
 */
export function StageGuide({ judge }: { judge: Judge }) {
  const guide = judge.guide
  if (!guide) return null
  const sections = groupBySection(guide.steps)
  return (
    <section className="stage-judge stage-guide small" aria-label="Guía de pruebas de la fase">
      <p className="stage-judge-head">
        <strong>Guía de pruebas</strong>
        {guide.note && (
          <>
            {' · '}
            <span
              className={cn(
                'stage-judge-score',
                judge.passed == null ? undefined : judge.passed ? 'passed' : 'failed',
              )}
            >
              {guide.note}
            </span>
          </>
        )}
        <span className="muted">
          {guide.guides.length > 0
            ? ` · ${guide.guides.join(', ')}`
            : ` · busca ${guide.paths.join(', ')}`}
          {judge.shownRound > 1 && ` · ronda ${judge.shownRound}`}
          {judge.fix && ` · corrige ${judge.fix} (hasta ${judge.maxRounds} rondas)`}
        </span>
      </p>
      {judge.judgements
        .filter((j) => j.status === 'FAILED')
        .map((j) => (
          <p key={j.judge} className="error">
            {j.judge}: {j.error}
          </p>
        ))}
      {sections.map(([section, steps]) => (
        <div key={section} className="stage-guide-section">
          {section && <p className="stage-guide-title">{section.replace(/^#+\s*/, '')}</p>}
          <ul className="stage-guide-steps" aria-label={`Pasos de ${section || 'la guía'}`}>
            {steps.map((s, i) => (
              <li key={i} className={cn('stage-guide-step', s.status)}>
                <span className="stage-judge-severity">{STATUS_LABEL[s.status]}</span>{' '}
                {s.step ?? '(sin descripción)'}
                {s.evidence && s.status !== 'skipped' && (
                  <details>
                    <summary>Evidencia</summary>
                    <pre>{s.evidence}</pre>
                  </details>
                )}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {guide.summary && (
        <details>
          <summary>Resumen</summary>
          <p>{guide.summary}</p>
        </details>
      )}
    </section>
  )
}

/** Los pasos agrupados por sección, en el orden en que llegan. */
function groupBySection(steps: GuideStep[]): [string, GuideStep[]][] {
  const out: [string, GuideStep[]][] = []
  for (const s of steps) {
    const key = s.section ?? ''
    const last = out[out.length - 1]
    if (last && last[0] === key) last[1].push(s)
    else out.push([key, [s]])
  }
  return out
}
