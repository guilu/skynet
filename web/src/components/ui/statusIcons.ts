import {
  Check,
  CircleDashed,
  Hand,
  Hourglass,
  Pause,
  Play,
  Plug,
  RotateCw,
  SkipForward,
  Sparkles,
  Square,
  Terminal,
  TriangleAlert,
  X,
  type LucideIcon,
} from 'lucide-react'
import type { StatusTone } from '../../format'

/** Icono por tono, cuando no hay uno propio del estado. */
export const TONE_ICON: Record<StatusTone, LucideIcon> = {
  neutral: CircleDashed,
  active: Play,
  ok: Check,
  bad: X,
  warn: TriangleAlert,
}

/** Icono propio de cada estado conocido. */
const STATUS_ICON: Record<string, LucideIcon> = {
  PENDING: Hourglass,
  QUEUED: Hourglass,
  READY: CircleDashed,
  STARTING: RotateCw,
  RUNNING: Play,
  THINKING: Sparkles,
  EXECUTING: Terminal,
  WAITING_FOR_INPUT: Hand,
  WAITING_FOR_APPROVAL: Hand,
  UNRESPONSIVE: Pause,
  STALE: Plug,
  CANCELLED: Square,
  SKIPPED: SkipForward,
}

export const statusIcon = (status: string, tone: StatusTone) =>
  STATUS_ICON[status] ?? TONE_ICON[tone]
