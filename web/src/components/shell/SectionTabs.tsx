import { NavLink, useLocation } from 'react-router'
import { PendingCount } from '../approval/PendingCount'
import { Icon } from '../icons/Icon'
import { sectionOf } from './nav'

/**
 * Pestañas de una sección que agrupa varias páginas (Memoria, Agentes): cada pestaña es la página
 * de siempre, con su ruta, así que los enlaces y el historial no cambian.
 */
export function SectionTabs() {
  const { pathname } = useLocation()
  const group = sectionOf(pathname)?.group
  if (!group?.tabs) return null
  return (
    <nav className="section-tabs" aria-label={group.label}>
      {group.tabs.map((t) => (
        <NavLink key={t.to} to={t.to}>
          <Icon name={t.icon} size={18} strokeWidth={2} />
          {t.label}
          {t.pendingApprovals && <PendingCount />}
        </NavLink>
      ))}
    </nav>
  )
}
