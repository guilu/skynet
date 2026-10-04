import { statusLabel, statusTone } from '../format'

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${statusTone(status)}`}>{statusLabel(status)}</span>
}
