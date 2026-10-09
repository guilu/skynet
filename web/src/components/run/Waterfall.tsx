import { formatDuration } from '../../format'
import { cn } from '../../lib/cn'
import { flatten, spanDuration, toolStatusText, type Span } from './runSpans'

const TICKS = 4

/**
 * Cascada (Gantt) de la ejecución: cada agente y cada llamada a una herramienta como una barra
 * sobre el mismo eje de tiempo. Lo que sigue en curso crece hasta `now`, así que en vivo la cascada
 * avanza sola. Cada barra es un botón que abre su agente o su llamada en el inspector.
 */
export function Waterfall({
  spans,
  now,
  selectedAgentId,
  selectedToolUseId,
  onSelectAgent,
  onSelectTool,
}: {
  spans: Span[]
  now: number
  selectedAgentId: string | undefined
  selectedToolUseId: string | null
  onSelectAgent: (agentId: string) => void
  onSelectTool: (agentId: string, toolUseId: string) => void
}) {
  const rows = flatten(spans).filter(({ span }) => span.start != null)
  if (!rows.some(({ span }) => span.kind !== 'stage')) {
    return <p className="muted">La cascada aparece cuando arranca el primer agente.</p>
  }
  const from = Math.min(...rows.map(({ span }) => span.start!))
  const to = Math.max(from + 1000, ...rows.map(({ span }) => span.end ?? now))
  const total = to - from
  const pct = (t: number) => ((t - from) / total) * 100
  const live = rows.some(({ span }) => span.end == null)

  return (
    <div className="waterfall">
      <div className="wf-axis" aria-hidden="true">
        <span />
        <div className="wf-ticks">
          {Array.from({ length: TICKS + 1 }, (_, i) => (
            <span key={i} style={{ left: `${(i / TICKS) * 100}%` }}>
              {i === 0 ? '0 s' : formatDuration((total * i) / TICKS)}
            </span>
          ))}
        </div>
        <span />
      </div>
      <ol className="wf-rows">
        {rows.map(({ span, depth }) => {
          const duration = spanDuration(span, now)
          const left = pct(span.start!)
          const width = Math.max(0, pct(span.end ?? now) - left)
          const selected =
            span.kind === 'tool'
              ? span.toolUseId === selectedToolUseId
              : span.kind === 'agent' && span.id === selectedAgentId && selectedToolUseId == null
          const name =
            span.kind === 'tool'
              ? `${span.label}${span.detail ? ` ${span.detail}` : ''}: ${formatDuration(duration)}, ${toolStatusText(span)}`
              : `${span.label} ${span.detail}: ${formatDuration(duration)}`
          const bar = (
            <span
              className={cn('wf-bar', `wf-${span.tone}`, span.end == null && 'wf-running')}
              style={{ left: `${left}%`, width: `${width}%` }}
            />
          )
          return (
            <li key={`${span.kind}-${span.id}`} className={cn('wf-row', `wf-row-${span.kind}`)}>
              <span
                className="wf-label"
                style={{ paddingLeft: `${Math.max(0, depth - 1) * 12}px` }}
              >
                {span.kind === 'stage' ? (
                  <strong>{span.label}</strong>
                ) : (
                  <>
                    <span className={span.kind === 'agent' ? 'wf-agent' : undefined}>
                      {span.label}
                    </span>
                    {span.detail && <span className="wf-detail"> {span.detail}</span>}
                  </>
                )}
              </span>
              {span.kind === 'stage' ? (
                <span className="wf-track wf-track-stage" aria-hidden="true">
                  {bar}
                </span>
              ) : (
                <button
                  type="button"
                  className="wf-track"
                  aria-label={name}
                  aria-current={selected ? 'true' : undefined}
                  onClick={() =>
                    span.kind === 'tool'
                      ? onSelectTool(span.agentId!, span.toolUseId!)
                      : onSelectAgent(span.id)
                  }
                >
                  {bar}
                </button>
              )}
              <span className="wf-time">{formatDuration(duration)}</span>
            </li>
          )
        })}
      </ol>
      {live && (
        <p className="muted small wf-note">
          Las barras rayadas siguen en curso y crecen en directo.
        </p>
      )}
    </div>
  )
}
