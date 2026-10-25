import { ILUSTRADOS } from './ilustrados'
import type { IconPack } from './pack'
import type { IconName } from './registry'

/** Cómo se pinta un dibujo ilustrado según su tamaño (estudio-iconos.md §2). */
export type IconVariant = 'ilustrado' | 'compacto' | 'trazo'

export function variantFor(size: number): IconVariant {
  if (size <= 16) return 'trazo'
  if (size <= 28) return 'compacto'
  return 'ilustrado'
}

/** ¿Tiene este paquete un dibujo propio para el nombre? Si no, se pinta el de Lucide. */
export const hasOwnDrawing = (pack: IconPack, name: IconName) =>
  pack === 'lucide' || ILUSTRADOS[name] !== undefined
