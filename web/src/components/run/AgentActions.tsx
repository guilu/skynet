import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api, type AgentRun, type Run } from '../../api'
import { formatDateTime, isTerminal, workspaceUsable } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'

type Action = 'retry' | 'fork' | 'cleanup'

/**
 * Reintentar, bifurcar y eliminar el worktree de un agente terminado. Reintentar y bifurcar crean
 * otro agente con coste propio, así que antes de confirmar explican su alcance: qué worktree usa,
 * de qué sesión parte y qué repite (ADR-0001 §3.8). Cada una lleva a la ejecución nueva.
 */
export function AgentActions({ agent }: { agent: AgentRun }) {
  const [open, setOpen] = useState<Action | null>(null)
  if (!isTerminal(agent.status)) return null
  const usable = workspaceUsable(agent.workspace)
  // Un reintento repite el lanzamiento: solo tiene sentido sobre uno (o sobre otro reintento).
  const canRetry = agent.kind === 'START' || agent.kind === 'RETRY'
  const canFork = !!agent.providerSessionId && usable
  if (!canRetry && !canFork && !agent.workspace) return null
  const close = (isOpen: boolean) => !isOpen && setOpen(null)

  return (
    <div className="agent-actions">
      <WorkspaceState agent={agent} />
      <div className="run-actions">
        {canRetry && (
          <Button type="button" aria-haspopup="dialog" onClick={() => setOpen('retry')}>
            Reintentar…
          </Button>
        )}
        {canFork && (
          <Button type="button" aria-haspopup="dialog" onClick={() => setOpen('fork')}>
            Bifurcar…
          </Button>
        )}
        {usable && (
          <Button
            type="button"
            variant="secondary-danger"
            aria-haspopup="dialog"
            onClick={() => setOpen('cleanup')}
          >
            Eliminar worktree…
          </Button>
        )}
      </div>
      <Sheet open={open === 'retry'} onOpenChange={close} title="Reintentar el agente">
        <RetryPanel agent={agent} onClose={() => setOpen(null)} />
      </Sheet>
      <Sheet open={open === 'fork'} onOpenChange={close} title="Bifurcar la sesión">
        {canFork && <ForkPanel agent={agent} onClose={() => setOpen(null)} />}
      </Sheet>
      <Sheet open={open === 'cleanup'} onOpenChange={close} title="Eliminar el worktree">
        {usable && <CleanupPanel agent={agent} onClose={() => setOpen(null)} />}
      </Sheet>
    </div>
  )
}

/** Si el worktree se eliminó, se está eliminando o falló al eliminarlo. */
function WorkspaceState({ agent }: { agent: AgentRun }) {
  const workspace = agent.workspace
  if (!workspace) return null
  if (workspace.removedAt) {
    return (
      <p className="muted small" role="status">
        Worktree eliminado el {formatDateTime(workspace.removedAt)}: ya no se puede continuar,
        bifurcar ni verificar. La rama <code>{workspace.branch}</code> sigue en el repositorio.
      </p>
    )
  }
  if (workspace.cleanupRequestedAt) {
    return (
      <p className="muted small" role="status">
        Eliminando el worktree <code>{workspace.path}</code>…
      </p>
    )
  }
  if (workspace.cleanupError) {
    return (
      <p className="error small" role="status">
        No se pudo eliminar el worktree: {workspace.cleanupError}
      </p>
    )
  }
  return null
}

function CleanupPanel({ agent, onClose }: { agent: AgentRun; onClose: () => void }) {
  const queryClient = useQueryClient()
  const cleanup = useMutation({
    mutationFn: () => api.cleanupWorkspace(agent.id),
    onSuccess: () => {
      onClose()
      void queryClient.invalidateQueries({ queryKey: ['run'] })
    },
  })
  const workspace = agent.workspace!
  return (
    <div className="action-panel">
      <ul>
        <li>
          Borra <code>{workspace.path}</code> en el runner, cambios sin confirmar incluidos.
        </li>
        <li>
          La rama <code>{workspace.branch}</code> y sus commits se conservan en el repositorio.
        </li>
        <li>
          Después no se podrá continuar, bifurcar ni verificar esta sesión; reintentar sí, en un
          worktree nuevo.
        </li>
      </ul>
      <div className="form-actions">
        <Button
          variant="danger"
          type="button"
          onClick={() => cleanup.mutate()}
          disabled={cleanup.isPending}
        >
          {cleanup.isPending ? 'Eliminando…' : 'Eliminar'}
        </Button>
        <Button variant="secondary" onClick={onClose}>
          Cancelar
        </Button>
      </div>
      <ErrorMessage error={cleanup.error} />
    </div>
  )
}

function useGoToRun() {
  const navigate = useNavigate()
  return (run: Run) => navigate(`/runs/${run.id}`)
}

function RetryPanel({ agent, onClose }: { agent: AgentRun; onClose: () => void }) {
  const goTo = useGoToRun()
  const retry = useMutation({ mutationFn: () => api.retryAgent(agent.id), onSuccess: goTo })
  return (
    <div className="action-panel">
      <ul>
        <li>Lanza un agente nuevo con el mismo prompt y los mismos límites.</li>
        <li>
          Usa un worktree nuevo desde la rama base y una sesión nueva: no ve lo que hizo este.
        </li>
        <li>Su coste se suma aparte del de este agente.</li>
      </ul>
      <div className="form-actions">
        <Button onClick={() => retry.mutate()} disabled={retry.isPending}>
          {retry.isPending ? 'Reintentando…' : 'Reintentar'}
        </Button>
        <Button variant="secondary" onClick={onClose}>
          Cancelar
        </Button>
      </div>
      <ErrorMessage error={retry.error} />
    </div>
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
    <form className="action-panel form" onSubmit={submit}>
      <ul>
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
      <div className="form-actions">
        <Button type="submit" disabled={!text.trim() || fork.isPending}>
          {fork.isPending ? 'Bifurcando…' : 'Bifurcar'}
        </Button>
        <Button variant="secondary" onClick={onClose}>
          Cancelar
        </Button>
      </div>
      <ErrorMessage error={fork.error} />
    </form>
  )
}
