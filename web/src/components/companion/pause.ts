import { createContext } from 'react'

/**
 * Con la cortina del chat abierta (CH-E), los Skyny 3D de la página se quedan quietos: no se ven
 * bajo el fondo atenuado y así no gastan fotogramas mientras el de la cortina habla.
 */
export const SkynyPaused = createContext(false)
