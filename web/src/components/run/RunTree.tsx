import { ChevronRight } from 'lucide-react'
import { useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router'
import type { AgentRun, Run } from '../../api'
import { formatDuration, statusLabel } from '../../format'
import { cn } from '../../lib/cn'
import { StatusBadge } from '../StatusBadge'
import { spanDuration, toolStatusText, type Span } from './runSpans'

const ORIGIN: Record<Exclude<AgentRun['kind'], 'START'>, string> = {
  RESUME: 'reanudación del',
  RETRY: 'reintento del',
  FORK: 'fork del',
}

/** ↑/↓ mueven el foco entre las filas visibles del árbol. */
function moveFocus(e: KeyboardEvent<HTMLElement>) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const rows = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('.tree-row')]
  const current = rows.indexOf(e.target as HTMLButtonElement)
  if (current < 0) return
  e.preventDefault()
  const next = current + (e.key === 'ArrowDown' ? 1 : -1)
  rows[Math.min(rows.length - 1, Math.max(0, next))].focus()
}

/**
 * Árbol de la ejecución: fases, sus agentes y las herramientas de cada agente (las de un
 * subagente, bajo la herramienta que lo lanzó), con estado y duración. Elegir un agente lo abre en
 * el inspector; elegir una herramienta abre su llamada.
 */
export function RunTree({
  run,
  spans,
  now,
  selectedAgentId,
  selectedToolUseId,
  onSelectAgent,
  onSelectTool,
}: {
  run: Run
  spans: Span[]
  now: number
  selectedAgentId: string | undefined
  selectedToolUseId: string | null
  onSelectAgent: (agentId: string) => void
  onSelectTool: (agentId: string, toolUseId: string) => void
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const toggle = (id: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (!next.delete(id)) next.add(id)
      return next
    })
  const agents = new Map(run.stages.flatMap((s) => s.agents).map((a) => [a.id, a]))

  function tools(children: Span[], agentId: string, depth: number) {
    return (
      <ul className="tree-list">
        {children.map((tool) => (
          <li key={tool.id}>
            <button
              type="button"
              className="tree-row tree-tool"
              style={{ paddingLeft: `${depth * 14 + 10}px` }}
              aria-label={`${tool.label}${tool.detail ? ` ${tool.detail}` : ''}: ${formatDuration(spanDuration(tool, now))}, ${toolStatusText(tool)}`}
              aria-current={tool.toolUseId === selectedToolUseId ? 'true' : undefined}
              onClick={() => onSelectTool(agentId, tool.toolUseId!)}
            >
              <span className={cn('tree-dot', `tone-${tool.tone}`)} aria-hidden="true" />
              <span className="tree-label">
                <strong>{tool.label}</strong>
                {tool.detail && <span className="tree-detail"> {tool.detail}</span>}
              </span>
              <span className="tree-time">{formatDuration(spanDuration(tool, now))}</span>
            </button>
            {tool.children.length > 0 && tools(tool.children, agentId, depth + 1)}
          </li>
        ))}
      </ul>
    )
  }

  return (
    <nav className="run-tree" aria-label="Fases y agentes" onKeyDown={moveFocus}>
      <ol className="tree-stages">
        {spans.map((stage, i) => (
          <li key={stage.id} className={stage.id === run.currentStageRunId ? 'current' : undefined}>
            <p className="tree-stage">
              <span className="stepper-index" aria-hidden="true">
                {i + 1}
              </span>
              <strong>{stage.label}</strong> <StatusBadge status={stage.status} />
              {stage.detail && <span className="muted small"> {stage.detail}</span>}
            </p>
            <ul className="tree-list">
              {stage.children.map((span) => {
                const agent = agents.get(span.id)!
                const open = !collapsed.has(span.id)
                const count = countTools(span.children)
                return (
                  <li key={span.id}>
                    <div className="tree-agent">
                      <button
                        type="button"
                        className="tree-row"
                        aria-label={`${span.label} ${span.detail}, ${statusLabel(span.status)}, ${formatDuration(spanDuration(span, now))}`}
                        aria-current={span.id === selectedAgentId ? 'true' : undefined}
                        onClick={() => onSelectAgent(span.id)}
                      >
                        <span className="tree-label">
                          {span.label} <span className="muted small">{span.detail}</span>
                        </span>
                        <StatusBadge status={span.status} />
                        <span className="tree-time">{formatDuration(spanDuration(span, now))}</span>
                      </button>
                      {count > 0 && (
                        <button
                          type="button"
                          className="tree-toggle"
                          aria-expanded={open}
                          aria-label={`${open ? 'Ocultar' : 'Mostrar'} las ${count} herramientas`}
                          onClick={() => toggle(span.id)}
                        >
                          <ChevronRight size={16} aria-hidden="true" />
                          <span aria-hidden="true">{count}</span>
                        </button>
                      )}
                    </div>
                    {agent.kind !== 'START' && agent.parentAgentRunId && (
                      <p className="muted small agent-origin">
                        {ORIGIN[agent.kind]}{' '}
                        <Link to={`/agent-runs/${agent.parentAgentRunId}`}>
                          agente {agent.parentAgentRunId.slice(0, 8)}
                        </Link>
                      </p>
                    )}
                    {open && span.children.length > 0 && tools(span.children, span.id, 1)}
                  </li>
                )
              })}
            </ul>
          </li>
        ))}
      </ol>
      {spans.every((s) => s.children.length === 0) && (
        <p className="muted small">Esta ejecución aún no tiene agentes.</p>
      )}
    </nav>
  )
}

function countTools(spans: Span[]): number {
  return spans.reduce((n, s) => n + 1 + countTools(s.children), 0)
}
