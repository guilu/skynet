import type { Risk } from '../../api'

/** El motivo de un fichero, para el texto al pasar por encima: «`V49__risk.sql` (ruta)». */
export function riskReason(reason: Risk['reasons'][number]): string {
  const why =
    reason.tier === 2
      ? `${reason.label ?? 'ruta'}${reason.match ? `: «${reason.match}»` : ''}`
      : reason.kind === 'docs'
        ? 'documentación'
        : reason.kind === 'tests'
          ? 'tests'
          : 'código'
  return `${reason.path} (${why})`
}

/** El texto completo del nivel: su resumen y los ficheros que lo fijaron. */
export function riskTitle(risk: Risk): string {
  const top = risk.reasons.filter((r) => r.tier === risk.tier).slice(0, 5)
  return [risk.summary, ...top.map(riskReason)].join('\n')
}
