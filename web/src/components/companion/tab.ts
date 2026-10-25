import { useEffect } from 'react'
import type { Notice } from './notices'
import type { CompanionState } from './state'

/** Avisos que salen también fuera de la pestaña: lo que te necesita, los fallos y los éxitos. */
export const BROWSER_KINDS: Notice['kind'][] = ['approval', 'input', 'error', 'success']

/** Colores fijos: el favicon no ve los tokens de la página. */
const FACE = {
  waiting: { body: '#a9b8ff', line: '#3b5bdb', eyes: 'M48 57h8v15h-8zM64 57h8v15h-8z' },
  error: { body: '#ffb4b4', line: '#c92a2a', eyes: '' },
}

/** La cara de la mascota como favicon (SVG), para «Te necesita» y «Error». */
export function faceIcon(state: 'waiting' | 'error'): string {
  const { body, line, eyes } = FACE[state]
  const node = (cx: number, cy: number) =>
    `<circle cx="${cx}" cy="${cy}" r="15" fill="#fff" stroke="${line}" stroke-width="6"/>`
  const face =
    state === 'error'
      ? `<path d="M46 60l9 5.5-9 5.5M74 60l-9 5.5 9 5.5" fill="none" stroke="#ffd2d2" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>`
      : `<path d="${eyes}" fill="#dfe6ff"/>`
  const svg = [
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="2 2 116 120">',
    `<polygon points="60,30 22,92 98,92" fill="${body}" stroke="${body}" stroke-width="30" stroke-linejoin="round"/>`,
    node(60, 22),
    `<circle cx="60" cy="66" r="28" fill="${line}"/>`,
    '<circle cx="60" cy="66" r="21" fill="#1c2345"/>',
    face,
    node(20, 96),
    node(100, 96),
    '</svg>',
  ].join('')
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
 * «Error», el favicon pasa a la cara de la mascota; si no, el logo de siempre.
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
    icon: '/favicon.svg',
  })
  n.onclick = () => {
    window.focus()
    open(notice.href)
    n.close()
  }
  return true
}
