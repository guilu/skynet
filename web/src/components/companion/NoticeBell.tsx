import * as Menu from '@radix-ui/react-dropdown-menu'
import { useNavigate } from 'react-router'
import { formatTime } from '../../format'
import { Companion } from './Companion'
import { useCompanion } from './context'
import { NOTICE_FACE } from './notices'
import { Icon } from '../icons/Icon'

/** La campana: los últimos avisos con su cara; al abrirla quedan leídos. */
export function NoticeBell({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { notices, unread, markRead, clearNotices } = useCompanion()
  const navigate = useNavigate()
  const change = (next: boolean) => {
    onOpenChange(next)
    // Se marcan al cerrar: así, mientras está abierta, se distinguen los nuevos.
    if (!next) markRead()
  }
  return (
    <Menu.Root modal={false} open={open} onOpenChange={change}>
      <Menu.Trigger
        className="icon-btn bell-btn"
        aria-label={unread ? `Avisos: ${unread} sin leer` : 'Avisos'}
      >
        <Icon name="notices" size={22} strokeWidth={2.25} />
        {unread > 0 && (
          <span className="bell-count" aria-hidden="true">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu notices" align="end" sideOffset={8}>
          <Menu.Label className="menu-label">Avisos</Menu.Label>
          {notices.length === 0 ? (
            <p className="notices-empty">
              <Companion state="ready" size={56} />
              Aún no hay avisos. Te contaré aquí lo que termine, falle o te necesite.
            </p>
          ) : (
            <>
              {notices.map((n) => (
                <Menu.Item
                  key={n.id}
                  className="menu-item notice-item"
                  data-unread={!n.read || undefined}
                  data-kind={n.kind}
                  onSelect={() => navigate(n.href)}
                >
                  <Companion state={NOTICE_FACE[n.kind]} size={30} still />
                  <span className="notice-text">
                    <strong>{n.title}</strong>
                    {n.body !== '' && <span className="muted">{n.body}</span>}
                  </span>
                  <time className="notice-time" dateTime={new Date(n.at).toISOString()}>
                    {formatTime(new Date(n.at).toISOString())}
                  </time>
                  {!n.read && <span className="visually-hidden">(nuevo)</span>}
                </Menu.Item>
              ))}
              <Menu.Separator className="menu-sep" />
              <Menu.Item className="menu-item" onSelect={clearNotices}>
                Vaciar los avisos
              </Menu.Item>
            </>
          )}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}
