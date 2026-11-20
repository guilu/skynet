import { useQuery } from '@tanstack/react-query'

/** Estado del control plane: un punto verde, rojo o gris y «CP disponible». */
export function HealthIndicator() {
  const health = useQuery({
    queryKey: ['health'],
    queryFn: async () => {
      const res = await fetch('/actuator/health')
      if (!res.ok) throw new Error(String(res.status))
      return (await res.json()) as { status?: string }
    },
    refetchInterval: 15_000,
    retry: false,
  })
  const state = health.isPending ? 'checking' : health.data?.status === 'UP' ? 'up' : 'down'
  const label = { checking: 'comprobando…', up: 'disponible', down: 'no disponible' }[state]
  return (
    <span className={`health health-${state}`} title="Estado del control plane">
      <span className="status-dot" aria-hidden="true" />
      <span className="health-text">
        <abbr title="Control plane">CP</abbr> <strong data-testid="health">{label}</strong>
      </span>
    </span>
  )
}
