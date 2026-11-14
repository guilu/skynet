import { FileText, GitPullRequest, Hand, Layers, Lock, Scale } from 'lucide-react'
import type { WorkflowOverview } from '../../api'

/** Nombre y monograma de cada proveedor; los desconocidos, con su clave y su inicial. */
const PROVIDERS: Record<string, { name: string; mono: string }> = {
  'claude-code': { name: 'Claude Code', mono: 'CC' },
  codex: { name: 'Codex', mono: 'Cx' },
  antigravity: { name: 'Antigravity', mono: 'AG' },
  repository: { name: 'El del repositorio', mono: 'R' },
}

export const providerInfo = (provider: string) =>
  PROVIDERS[provider] ?? { name: provider, mono: provider.charAt(0).toUpperCase() }

export const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** Las etiquetas de la tarjeta: fases, aprobaciones, jueces, entrega, solo lectura y datos. */
export function overviewChips(o: WorkflowOverview): { icon: typeof Layers; text: string }[] {
  const chips: { icon: typeof Layers; text: string }[] = []
  const stages = plural(o.stages, 'fase', 'fases')
  chips.push({
    icon: Layers,
    text: o.parallelChildren > 0 ? `${stages} · ${o.parallelChildren} a la vez` : stages,
  })
  if (o.gates > 0) chips.push({ icon: Hand, text: plural(o.gates, 'aprobación', 'aprobaciones') })
  if (o.judges > 0) chips.push({ icon: Scale, text: plural(o.judges, 'juez', 'jueces') })
  if (o.delivers) chips.push({ icon: GitPullRequest, text: 'Entrega por PR' })
  if (o.readOnly) chips.push({ icon: Lock, text: 'Solo lee' })
  if (o.inputs.length > 0)
    chips.push({ icon: FileText, text: `Pide ${plural(o.inputs.length, 'dato', 'datos')}` })
  return chips
}
