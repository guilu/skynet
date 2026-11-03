import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, type Run, type StageRun } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { Icon } from '../icons/Icon'
import { waitsForQuota } from './stageNotes'

/**
 * «Reintentar ahora» en una fase que espera a que se renueve la cuota de su proveedor (CQ-A):
 * arranca ya, sin mirar la cuota. Si sigue sin ella, vuelve a fallar y a esperar.
 */
export function StageQuotaSkip({ stage, run }: { stage: StageRun; run: Run }) {
  const queryClient = useQueryClient()
  const skip = useMutation({
    mutationFn: () => api.skipQuotaWait(run.id, stage.stageKey),
    onSuccess: (updated) => {
      queryClient.setQueryData(['run', run.id], updated)
      void queryClient.invalidateQueries({ queryKey: ['run', run.id] })
    },
  })
  if (!waitsForQuota(stage)) return null
  return (
    <div className="stage-retry">
      <Button variant="secondary" onClick={() => skip.mutate()} disabled={skip.isPending}>
        <Icon name="retry" size={16} strokeWidth={2.75} />
        {skip.isPending ? 'Reintentando…' : 'Reintentar ahora'}
      </Button>
      <ErrorMessage error={skip.error} />
    </div>
  )
}
