import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Une clases condicionales y resuelve los conflictos entre utilidades de Tailwind. */
export const cn = (...inputs: ClassValue[]) => twMerge(clsx(inputs))
