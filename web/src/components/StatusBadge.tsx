import { statusLabel, statusTone } from '../format'
import { Pill } from './ui/Pill'
import { statusIcon } from './ui/statusIcons'

export function StatusBadge({ status }: { status: string }) {
  const tone = statusTone(status)
  return (
    <Pill tone={tone} icon={statusIcon(status, tone)}>
      {statusLabel(status)}
    </Pill>
  )
}
