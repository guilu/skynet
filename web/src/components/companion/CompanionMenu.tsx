import * as Menu from '@radix-ui/react-dropdown-menu'
import * as Tooltip from '@radix-ui/react-tooltip'
import { useNavigate } from 'react-router'
import { useNow } from '../../useNow'
import { Companion } from './Companion'
import { useCompanion } from './context'
import { MUTE_MS, setCompanionPrefs, useCompanionPrefs } from './prefs'
import { companionLabel } from './state'
import { Icon } from '../icons/Icon'

/**
 * La mascota de la barra superior con el estado global. Al pasar el ratón dice qué hay en marcha;
 * al pulsarla abre el menú rápido (plan, §5).
 */
export function CompanionMenu({ onOpenNotices }: { onOpenNotices: () => void }) {
  const { expression, summary, activeRuns, pendingApprovals } = useCompanion()
  const prefs = useCompanionPrefs()
  const navigate = useNavigate()
  const now = useNow(true, 30_000)
  const muted = prefs.mutedUntil != null && prefs.mutedUntil > now
  const live = activeRuns.length > 0
  const name = `Skynet: ${companionLabel(expression)}. ${summary}`
  return (
    <Menu.Root modal={false}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <Menu.Trigger className="companion-btn" aria-label={name} data-live={live || undefined}>
            <Companion state={expression} size={34} />
          </Menu.Trigger>
        </Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content className="tooltip" side="bottom" sideOffset={8}>
            {summary}
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
      <Menu.Portal>
        <Menu.Content className="menu companion-menu" align="end" sideOffset={8}>
          <div className="companion-menu-head">
            <Companion state={expression} size={48} />
            <div>
              <strong>{companionLabel(expression)}</strong>
              <span className="muted">{summary}</span>
            </div>
          </div>
          <Menu.Separator className="menu-sep" />
          <Menu.Item className="menu-item" onSelect={() => navigate('/runs?status=RUNNING')}>
            <Icon name="play" size={18} strokeWidth={2.25} />
            Ejecuciones activas
            <span className="menu-count">{activeRuns.length}</span>
          </Menu.Item>
          <Menu.Item className="menu-item" onSelect={() => navigate('/approvals')}>
            <Icon name="verification" size={18} strokeWidth={2.25} />
            Te necesitan
            <span className="menu-count">{pendingApprovals}</span>
          </Menu.Item>
          <Menu.Item className="menu-item" onSelect={onOpenNotices}>
            <Icon name="inbox" size={18} strokeWidth={2.25} />
            Avisos recientes
          </Menu.Item>
          <Menu.Item
            className="menu-item"
            onSelect={() => setCompanionPrefs({ mutedUntil: muted ? null : Date.now() + MUTE_MS })}
          >
            {muted ? (
              <Icon name="notices" size={18} strokeWidth={2.25} />
            ) : (
              <Icon name="notices-off" size={18} strokeWidth={2.25} />
            )}
            {muted ? 'Quitar el silencio' : 'Silenciar 1 h'}
          </Menu.Item>
          <Menu.Item className="menu-item" onSelect={() => navigate('/settings?tab=companion')}>
            <Icon name="settings" size={18} strokeWidth={2.25} />
            Ajustes del companion
          </Menu.Item>
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}
