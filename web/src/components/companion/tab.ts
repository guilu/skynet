import { useEffect } from 'react'
import { skynySvg } from '../../lib/brand'
import type { Notice } from './notices'
import type { CompanionState } from './state'

/** Avisos que salen también fuera de la pestaña: lo que te necesita, los fallos y los éxitos. */
export const BROWSER_KINDS: Notice['kind'][] = ['approval', 'input', 'error', 'success']

/** Colores fijos de la cara (los de Te necesita y Error): el favicon no ve los tokens. */
export const FACE_COLORS = { waiting: '#f4b400', error: '#ec4646' }

/** Skyny como favicon (SVG), amarillo para «Te necesita» y rojo con «> <» para «Error». */
export function faceIcon(state: 'waiting' | 'error'): string {
  const svg = skynySvg(FACE_COLORS[state], state === 'error' ? 'error' : 'calm')
  return `data:image/svg+xml,${encodeURIComponent(svg)}`
}

const COUNT = /^\(\d+\) /

/** El título con el número de avisos sin leer delante: «(2) Skynet». */
export function titleWith(title: string, unread: number): string {
  const base = title.replace(COUNT, '')
  return unread > 0 ? `(${unread}) ${base}` : base
}

/**
 * La pestaña cuenta lo que ha pasado: el título lleva los avisos sin leer y, con «Te necesita» o
 * «Error», el favicon pasa a Skyny de ese color; si no, el de siempre (Skyny en reposo).
 */
export function useTabSignals(expression: CompanionState, unread: number) {
  useEffect(() => {
    document.title = titleWith(document.title, unread)
  }, [unread])

  const face = expression === 'waiting' || expression === 'error' ? expression : null
  useEffect(() => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
    if (!link) return
    link.dataset.logo ??= link.getAttribute('href') ?? '/favicon.svg'
    link.setAttribute('href', face ? faceIcon(face) : link.dataset.logo)
  }, [face])

  // Al salir (cerrar sesión), la pestaña vuelve a estar como al principio.
  useEffect(
    () => () => {
      document.title = titleWith(document.title, 0)
      const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
      if (link?.dataset.logo) link.setAttribute('href', link.dataset.logo)
    },
    [],
  )
}

/**
 * Aviso del navegador (Notification API): solo con la pestaña oculta, con permiso y para lo que
 * importa. Lleva claves, nombres y fases, nunca prompts ni salidas. Un `tag` por ejecución hace
 * que el siguiente sustituya al anterior; al pulsarlo se abre lo que cuenta.
 */
export function browserNotice(notice: Notice, open: (href: string) => void): boolean {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return false
  if (!document.hidden || !BROWSER_KINDS.includes(notice.kind)) return false
  const n = new Notification(`Skynet · ${notice.title}`, {
    body: notice.body,
    tag: notice.runId ?? notice.id,
    // PNG: no todos los sistemas pintan un SVG en los avisos.
    icon: '/icon-192.png',
  })
  n.onclick = () => {
    window.focus()
    open(notice.href)
    n.close()
  }
  return true
}
