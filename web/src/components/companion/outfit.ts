import type { BackKind, FaceKind, HatKind } from './three/extras'
import { useCompanionPrefs } from './prefs'
import { useSeason } from './season'

/** Lo que lleva puesto Skyny 3D: gorro, complemento de cara y de espalda. */
export interface Outfit {
  hat: HatKind
  face: FaceKind
  back: BackKind
}

/**
 * Lo que eligió en Ajustes › Companion; sin gorro elegido, el de temporada (si no se han quitado
 * los accesorios de temporada).
 */
export function useOutfit(): Outfit {
  const prefs = useCompanionPrefs()
  const season = useSeason()
  return {
    hat: (prefs.hat || season || '') as HatKind,
    face: prefs.face as FaceKind,
    back: prefs.back as BackKind,
  }
}
