import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Notice } from './notices'
import { browserNotice, faceIcon, titleWith } from './tab'

const notice: Notice = {
  id: 'n1',
  kind: 'approval',
  runKey: 'DEMO-9',
  runId: 'run-1',
  approvalId: 'ap-1',
  title: 'DEMO-9 te necesita',
  body: 'La fase «Plan» espera tu aprobación',
  href: '/approvals/ap-1',
  action: 'Revisar',
  at: 0,
  read: false,
}

class FakeNotification {
  static permission: NotificationPermission = 'granted'
  static created: FakeNotification[] = []
  onclick: (() => void) | null = null
  close = vi.fn()
  title: string
  options: NotificationOptions
  constructor(title: string, options: NotificationOptions) {
    this.title = title
    this.options = options
    FakeNotification.created.push(this)
  }
}

function hidden(value: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => value })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  FakeNotification.created = []
  FakeNotification.permission = 'granted'
  hidden(false)
})

describe('Título y favicon de la pestaña', () => {
  it('pone y quita el número de avisos sin leer', () => {
    expect(titleWith('Skynet', 2)).toBe('(2) Skynet')
    expect(titleWith('(2) Skynet', 5)).toBe('(5) Skynet')
    expect(titleWith('(5) Skynet', 0)).toBe('Skynet')
  })

  it('dibuja la cara de la mascota como SVG', () => {
    const icon = decodeURIComponent(faceIcon('error'))
    expect(icon).toMatch(/^data:image\/svg\+xml,<svg/)
    expect(icon).toContain('#c92a2a')
    expect(decodeURIComponent(faceIcon('waiting'))).toContain('#3b5bdb')
  })
})

describe('Avisos del navegador', () => {
  it('avisa con la pestaña oculta y abre el enlace al pulsarlo', () => {
    vi.stubGlobal('Notification', FakeNotification)
    vi.spyOn(window, 'focus').mockImplementation(() => {})
    hidden(true)
    const open = vi.fn()
    expect(browserNotice(notice, open)).toBe(true)
    const [n] = FakeNotification.created
    expect(n.title).toBe('Skynet · DEMO-9 te necesita')
    expect(n.options.tag).toBe('run-1')
    n.onclick?.()
    expect(open).toHaveBeenCalledWith('/approvals/ap-1')
    expect(n.close).toHaveBeenCalled()
  })

  it('no avisa con la pestaña visible, sin permiso o por lo que no importa', () => {
    vi.stubGlobal('Notification', FakeNotification)
    expect(browserNotice(notice, vi.fn())).toBe(false)
    hidden(true)
    expect(browserNotice({ ...notice, kind: 'started' }, vi.fn())).toBe(false)
    FakeNotification.permission = 'denied'
    expect(browserNotice(notice, vi.fn())).toBe(false)
    expect(FakeNotification.created).toHaveLength(0)
  })
})
