import type { JudgeFinding, StageJudge as Judge } from '../../api'
import { cn } from '../../lib/cn'
import { formatScore, LENS_LABEL } from './stageNotes'

/**
 * El juez de una fase `verification` (VA-B): la nota de la fase, la de cada criterio por juez y lo
 * que encontraron, con su fichero. Mientras se corrige, el veredicto de la ronda anterior. Los
 * hallazgos que refutaron los escépticos salen tachados, con el motivo (VA-C), y los que se
 * descartaron por ya vistos, como «Ya visto» con lo que se decidió entonces (RH-B).
 */
export function StageJudge({ judge }: { judge: Judge }) {
  if (judge.status === 'SKIPPED') {
    return (
      <section className="stage-judge small" aria-label="Juez de la fase">
        <p className="stage-judge-head">
          <strong>Sin juez</strong>
          <span className="muted">
            {' · '}nivel {judge.risk ?? 0}: juzga desde el nivel {judge.minRisk ?? 1}
          </span>
        </p>
      </section>
    )
  }
  const done = judge.judgements.filter((j) => j.status === 'DONE')
  const criteria = [...new Set(done.flatMap((j) => j.criteria.map((c) => c.id)))]
  return (
    <section className="stage-judge small" aria-label="Juez de la fase">
      <p className="stage-judge-head">
        <strong>Juez</strong>
        {judge.score != null && (
          <>
            {' · '}
            <span className={cn('stage-judge-score', judge.passed ? 'passed' : 'failed')}>
              {formatScore(judge.score)} / 5
            </span>{' '}
            {judge.passed ? 'aprobado' : 'suspenso'} · aprueba {judge.approvals ?? 0} de{' '}
            {judge.judges ?? 0}
          </>
        )}
        <span className="muted">
          {' · '}umbral {formatScore(judge.threshold)}
          {judge.shownRound > 1 && ` · ronda ${judge.shownRound}`}
          {judge.fix && ` · corrige ${judge.fix} (hasta ${judge.maxRounds} rondas)`}
          {judge.refute > 0 &&
            ` · ${judge.refute === 1 ? '1 escéptico' : `${judge.refute} escépticos`} por hallazgo que bloquea`}
        </span>
      </p>
      {judge.refuteNote && <p className="stage-judge-note">⚠ {judge.refuteNote}</p>}
      {done.length > 0 && criteria.length > 0 && (
        <div className="table-scroll">
          <table className="stage-judge-table">
            <caption className="visually-hidden">Nota de cada criterio por juez</caption>
            <thead>
              <tr>
                <th scope="col">Criterio</th>
                {done.map((j) => (
                  <th key={j.judge} scope="col">
                    {j.judge}
                    {j.lens && <span className="muted"> · {LENS_LABEL[j.lens]}</span>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {criteria.map((id) => (
                <tr key={id}>
                  <th scope="row">
                    <code>{id}</code>
                    {done.some((j) => j.criteria.find((c) => c.id === id)?.required) && (
                      <span className="muted"> · obligatorio</span>
                    )}
                  </th>
                  {done.map((j) => {
                    const c = j.criteria.find((x) => x.id === id)
                    return (
                      <td key={j.judge} title={c?.reason ?? undefined}>
                        {c ? (
                          <span className={cn(c.score < 3 && 'stage-judge-low')}>{c.score}</span>
                        ) : (
                          '—'
                        )}
                        {c?.reason && <span className="muted stage-judge-reason"> {c.reason}</span>}
                      </td>
                    )
                  })}
                </tr>
              ))}
              <tr>
                <th scope="row">Media</th>
                {done.map((j) => (
                  <td key={j.judge}>
                    <strong>{j.score != null ? formatScore(j.score) : '—'}</strong>{' '}
                    {j.passed ? 'aprueba' : 'suspende'}
                  </td>
                ))}
              </tr>
            </tbody>
          </table>
        </div>
      )}
      {judge.judgements
        .filter((j) => j.status === 'FAILED')
        .map((j) => (
          <p key={j.judge} className="error">
            {j.judge}: {j.error}
          </p>
        ))}
      {judge.findings.length > 0 && (
        <ul className="stage-judge-findings" aria-label="Hallazgos del juez">
          {judge.findings.map((f, i) => (
            <li key={i} className={cn(f.severity, f.refuted && 'refuted')}>
              <span className="stage-judge-severity">
                {f.known
                  ? 'Ya visto'
                  : f.refuted
                    ? 'Refutado'
                    : f.severity === 'blocking'
                      ? 'Bloquea'
                      : 'Menor'}
              </span>{' '}
              {f.refuted ? (
                <del>
                  {location(f) && <code>{location(f)}</code>} {f.text}
                </del>
              ) : (
                <>
                  {location(f) && <code>{location(f)}</code>} {f.text}
                </>
              )}{' '}
              <span className="muted">({f.judge})</span>
              {f.refutation && (
                <span className="muted stage-judge-reason">
                  {' '}
                  {f.known ? '' : f.refuted ? 'Refutado: ' : 'No se pudo refutar: '}
                  {f.refutation}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {done
        .filter((j) => j.summary)
        .map((j) => (
          <details key={j.judge}>
            <summary>Resumen de {j.judge}</summary>
            <p>{j.summary}</p>
          </details>
        ))}
    </section>
  )
}

/** `fichero:línea`, `fichero` o null. */
function location(f: JudgeFinding): string | null {
  if (!f.file) return null
  return f.line != null ? `${f.file}:${f.line}` : f.file
}
