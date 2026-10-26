import { useState } from 'react'
import { useCompanionPrefs } from './prefs'

/** Accesorios de temporada de Skyny: un sombrero ladeado a la izquierda de la cabeza. */
export type Season = 'halloween' | 'christmas' | 'summer'

export const SEASONS: { season: Season; label: string; when: string }[] = [
  { season: 'halloween', label: 'Halloween', when: 'Del 24 de octubre al 2 de noviembre.' },
  { season: 'christmas', label: 'Navidad', when: 'Del 15 de diciembre al 6 de enero.' },
  { season: 'summer', label: 'Verano', when: 'Julio y agosto.' },
]

/** La temporada de una fecha (hora local), o null si no toca ningún accesorio. */
export function seasonOf(date: Date): Season | null {
  const month = date.getMonth() + 1
  const day = date.getDate()
  if ((month === 10 && day >= 24) || (month === 11 && day <= 2)) return 'halloween'
  if ((month === 12 && day >= 15) || (month === 1 && day <= 6)) return 'christmas'
  if (month === 7 || month === 8) return 'summer'
  return null
}

/** El accesorio de hoy, salvo que se haya quitado en Ajustes › Companion. */
export function useSeason(): Season | null {
  const { seasonal } = useCompanionPrefs()
  // La fecha se mira al montar: el accesorio no cambia a mitad de una visita.
  const [today] = useState(() => seasonOf(new Date()))
  return seasonal ? today : null
}
