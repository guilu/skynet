import { useCallback, useEffect, useState } from 'react'

/**
 * Gestos de una vez de Skyny (SY-C), encima de su expresión: un saltito con risa, asentir serio o
 * un bostezo al despertarse.
 */
export type Gesture = 'hop' | 'nod' | 'yawn'

/** Lo que dura cada gesto; después Skyny vuelve a su expresión. */
export const GESTURE_MS: Record<Gesture, number> = { hop: 700, nod: 900, yawn: 1600 }

/** El gesto en curso y cómo lanzar otro; se quita solo al terminar. */
export function useGesture(): [Gesture | null, (gesture: Gesture) => void] {
  const [current, setCurrent] = useState<{ gesture: Gesture; n: number } | null>(null)
  useEffect(() => {
    if (!current) return
    const t = window.setTimeout(() => setCurrent(null), GESTURE_MS[current.gesture])
    return () => window.clearTimeout(t)
  }, [current])
  const play = useCallback(
    (gesture: Gesture) => setCurrent((c) => ({ gesture, n: (c?.n ?? 0) + 1 })),
    [],
  )
  return [current?.gesture ?? null, play]
}

/** Cuánto se ve la frase al pulsar a Skyny. */
export const QUIP_MS = 5_000

/** Frases al pulsar a Skyny: un saludo y consejos de la web, por turnos. */
export const SKYNY_QUIPS = [
  '¡Eh, que tengo cosquillas!',
  'Truco: Ctrl+K (⌘K en Mac) abre la paleta de comandos.',
  'En una aprobación puedes pedir cambios en vez de rechazar.',
  'En Uso ves cuánta cuota le queda a cada CLI.',
  'Si una fase falla, puedes reintentarla desde la ejecución.',
  'Los workflows se editan en el editor visual o en YAML.',
  'En Ajustes › Companion puedes quitarme los sombreros.',
  'Me llamo Skyny, de Skynet. ¡Encantado!',
]

let nextQuip = 0

/** La siguiente frase de la lista (vuelve a empezar al acabar). */
export function takeQuip(): string {
  const quip = SKYNY_QUIPS[nextQuip % SKYNY_QUIPS.length]
  nextQuip += 1
  return quip
}

/** Solo para las pruebas: vuelve a la primera frase. */
export function resetQuips() {
  nextQuip = 0
}

/**
 * Pulsar a Skyny: da un saltito, se ríe y dice una frase durante unos segundos.
 */
export function usePoke(): { gesture: Gesture | null; quip: string | null; poke: () => void } {
  const [gesture, play] = useGesture()
  const [quip, setQuip] = useState<{ text: string; n: number } | null>(null)
  useEffect(() => {
    if (!quip) return
    const t = window.setTimeout(() => setQuip(null), QUIP_MS)
    return () => window.clearTimeout(t)
  }, [quip])
  const poke = useCallback(() => {
    play('hop')
    setQuip((q) => ({ text: takeQuip(), n: (q?.n ?? 0) + 1 }))
  }, [play])
  return { gesture, quip: quip?.text ?? null, poke }
}
