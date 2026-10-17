import { useMutation, useQueryClient } from '@tanstack/react-query'
import { RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { api, type Run, type StageRun } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { joinNames, retryScope } from './stageNotes'

/**
 * «Reintentar fase» en una ejecución fallida o cancelada (W6-C). Antes de confirmar dice el alcance:
 * qué intento es y qué fases vuelven a lanzarse después. La misma ejecución vuelve a «En curso».
 */
export function StageRetry({ stage, run }: { stage: StageRun; run: Run }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const retry = useMutation({
    mutationFn: () => api.retryStage(run.id, stage.stageKey),
    onSuccess: (updated) => {
      setConfirming(false)
      queryClient.setQueryData(['run', run.id], updated)
      void queryClient.invalidateQueries({ queryKey: ['run', run.id] })
    },
  })
  const scope = retryScope(stage, run)
  if (!scope) return null
  const name = stage.name ?? stage.stageKey

  if (!confirming)
    return (
      <div className="stage-retry">
        <Button variant="secondary" onClick={() => setConfirming(true)}>
          <RotateCcw size={16} strokeWidth={2.75} aria-hidden="true" />
          Reintentar fase
        </Button>
      </div>
    )
  return (
    <div className="stage-retry confirm" role="group" aria-label="Confirmar el reintento">
      <p className="small">
        Se reintenta <strong>{name}</strong> (intento {stage.attempt + 1}).
        {scope.then.length > 0 && ` Después vuelven a lanzarse ${joinNames(scope.then)}.`} Lo que ya
        terminó bien y no depende de {name} no se repite.
      </p>
      <Button onClick={() => retry.mutate()} disabled={retry.isPending}>
        {retry.isPending ? 'Reintentando…' : 'Sí, reintentar'}
      </Button>
      <Button variant="secondary" onClick={() => setConfirming(false)}>
        No
      </Button>
      <ErrorMessage error={retry.error} />
    </div>
  )
}
