import { useQuery } from '@tanstack/react-query'

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
      Control plane: <strong data-testid="health">{label}</strong>
    </span>
  )
}
