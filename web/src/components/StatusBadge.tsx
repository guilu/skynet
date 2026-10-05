import { statusLabel, statusTone, TONE_ICONS } from '../format'

export function StatusBadge({ status }: { status: string }) {
  const tone = statusTone(status)
  return (
    <span className={`badge badge-${tone}`}>
      <span aria-hidden="true">{TONE_ICONS[tone]}</span> {statusLabel(status)}
    </span>
  )
}
