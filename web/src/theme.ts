import { useEffect, useState } from 'react'

export type ThemeChoice = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'skynet.theme'

function readChoice(): ThemeChoice {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

/**
 * Tema claro/oscuro. Por defecto sigue al sistema; la elección se guarda en este navegador y se
 * aplica como `data-theme` en `<html>`, que es lo que leen los tokens de `index.css`.
 */
export function useTheme(): [ThemeChoice, (choice: ThemeChoice) => void] {
  const [choice, setChoice] = useState<ThemeChoice>(readChoice)
  useEffect(() => {
    const root = document.documentElement
    if (choice === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', choice)
    try {
      if (choice === 'system') localStorage.removeItem(STORAGE_KEY)
      else localStorage.setItem(STORAGE_KEY, choice)
    } catch {
      // Sin almacenamiento (modo privado): el tema dura lo que la pestaña.
    }
  }, [choice])
  return [choice, setChoice]
}
