import { useQuery } from '@tanstack/react-query'
import { api } from '../../api'

/** Las aprobaciones pendientes, que usan el menú y el dashboard. */
export function usePendingApprovals() {
  return useQuery({
    queryKey: ['approvals', 'pending'],
    queryFn: () => api.approvals({ status: ['PENDING'] }),
    refetchInterval: 10_000,
  })
}
