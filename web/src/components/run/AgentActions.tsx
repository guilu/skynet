import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api, type AgentRun, type Run } from '../../api'
import { isTerminal } from '../../format'
import { ErrorMessage } from '../ErrorMessage'

type Action = 'retry' | 'fork'

/**
 * Reintentar y bifurcar un agente terminado. Las dos crean otro agente con coste propio, así que
 * antes de confirmar explican su alcance: qué worktree usa, de qué sesión parte y qué repite
 * (ADR-0001 §3.8). Cada acción lleva a la ejecución nueva.
 */
export function AgentActions({ agent }: { agent: AgentRun }) {
  const [open, setOpen] = useState<Action | null>(null)
  if (!isTerminal(agent.status)) return null
  // Un reintento repite el lanzamiento: solo tiene sentido sobre uno (o sobre otro reintento).
  const canRetry = agent.kind === 'START' || agent.kind === 'RETRY'
  const canFork = !!agent.providerSessionId && !!agent.workspace
  if (!canRetry && !canFork) return null

  return (
    <div className="agent-actions">
      <div className="run-actions">
        {canRetry && (
          <button
            type="button"
            aria-expanded={open === 'retry'}
            onClick={() => setOpen(open === 'retry' ? null : 'retry')}
          >
            Reintentar…
          </button>
        )}
        {canFork && (
          <button
            type="button"
            aria-expanded={open === 'fork'}
            onClick={() => setOpen(open === 'fork' ? null : 'fork')}
          >
            Bifurcar…
          </button>
        )}
      </div>
      {open === 'retry' && <RetryPanel agent={agent} onClose={() => setOpen(null)} />}
      {open === 'fork' && <ForkPanel agent={agent} onClose={() => setOpen(null)} />}
    </div>
  )
}

function useGoToRun() {
  const navigate = useNavigate()
  return (run: Run) => navigate(`/runs/${run.id}?tab=conversation`)
}

function RetryPanel({ agent, onClose }: { agent: AgentRun; onClose: () => void }) {
  const goTo = useGoToRun()
  const retry = useMutation({ mutationFn: () => api.retryAgent(agent.id), onSuccess: goTo })
  return (
    <section className="action-panel" role="group" aria-labelledby="retry-title">
      <h3 id="retry-title">Reintentar el agente</h3>
      <ul className="small">
        <li>Lanza un agente nuevo con el mismo prompt y los mismos límites.</li>
        <li>
          Usa un worktree nuevo desde la rama base y una sesión nueva: no ve lo que hizo este.
        </li>
        <li>Su coste se suma aparte del de este agente.</li>
      </ul>
      <div className="run-actions">
        <button type="button" onClick={() => retry.mutate()} disabled={retry.isPending}>
          {retry.isPending ? 'Reintentando…' : 'Reintentar'}
        </button>
        <button type="button" className="secondary" onClick={onClose}>
          Cancelar
        </button>
      </div>
      <ErrorMessage error={retry.error} />
    </section>
  )
}

function ForkPanel({ agent, onClose }: { agent: AgentRun; onClose: () => void }) {
  const goTo = useGoToRun()
  const [text, setText] = useState('')
  const fork = useMutation({ mutationFn: () => api.forkAgent(agent.id, text), onSuccess: goTo })
  function submit(e: FormEvent) {
    e.preventDefault()
    if (text.trim()) fork.mutate()
  }
  return (
    <form className="action-panel form" aria-labelledby="fork-title" onSubmit={submit}>
      <h3 id="fork-title">Bifurcar la sesión</h3>
      <ul className="small">
        <li>
          Continúa una copia de la conversación con tu mensaje; la original no cambia y puedes
          seguir con ella.
        </li>
        <li>
          Trabaja en un worktree nuevo que parte del estado actual de{' '}
          <code>{agent.workspace!.branch}</code>, cambios sin confirmar incluidos.
        </li>
        <li>Mismos límites que este agente; su coste se suma aparte.</li>
      </ul>
      <label>
        Mensaje para el fork
        <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} required />
      </label>
      <div className="run-actions">
        <button type="submit" disabled={!text.trim() || fork.isPending}>
          {fork.isPending ? 'Bifurcando…' : 'Bifurcar'}
        </button>
        <button type="button" className="secondary" onClick={onClose}>
          Cancelar
        </button>
      </div>
      <ErrorMessage error={fork.error} />
    </form>
  )
}
