import * as Dialog from '@radix-ui/react-dialog'
import { useState } from 'react'
import { NavLink } from 'react-router'
import { HealthIndicator } from '../../HealthIndicator'
import { PendingCount } from '../approval/PendingCount'
import { MOBILE_NAV, NAV_AREAS } from './nav'
import { RunnerSummary } from './Sidebar'
import { Icon } from '../icons/Icon'

/**
 * Navegación del móvil (FS-A): Inicio, Proyectos, Fábrica y Aprobar en la barra inferior y, en
 * «Más», una hoja con todas las secciones agrupadas por área, el estado de los runners y el del
 * control plane.
 */
export function MobileNav() {
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  return (
    <nav className="mobile-nav" aria-label="Navegación móvil">
      {MOBILE_NAV.map((n) => (
        <NavLink key={n.to} to={n.to} end={n.end}>
          <span className="mobile-nav-icon">
            <Icon name={n.icon} size={24} strokeWidth={1.9} />
            {n.pendingApprovals && <PendingCount />}
          </span>
          {n.label}
        </NavLink>
      ))}
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Trigger className="mobile-more">
          <span className="mobile-nav-icon">
            <Icon name="menu" size={24} strokeWidth={1.9} />
          </span>
          Más
        </Dialog.Trigger>
        <Dialog.Portal>
          <Dialog.Overlay className="drawer-overlay" />
          <Dialog.Content className="drawer" aria-describedby={undefined}>
            <div className="drawer-handle" aria-hidden="true" />
            <div className="drawer-head">
              <Dialog.Title className="drawer-title">Todas las secciones</Dialog.Title>
              <Dialog.Close className="icon-btn" aria-label="Cerrar">
                <Icon name="close" size={22} strokeWidth={2.5} />
              </Dialog.Close>
            </div>
            <nav className="area-tiles" aria-label="Todas las secciones">
              {NAV_AREAS.map((area) => (
                <div key={area.id} role="group" aria-labelledby={`more-area-${area.id}`}>
                  {area.id === 'admin' && (
                    <NavLink to="/admin" onClick={close} className="nav-area-more">
                      Resumen
                    </NavLink>
                  )}
                  <span id={`more-area-${area.id}`} className="nav-area-label">
                    {area.label}
                  </span>
                  <ul>
                    {area.items.map((n) => (
                      <li key={n.to}>
                        <NavLink to={n.to} end={n.end} onClick={close} className="area-tile">
                          <Icon name={n.icon} size={24} strokeWidth={1.9} />
                          {n.label}
                          {n.pendingApprovals && <PendingCount />}
                        </NavLink>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </nav>
            <div className="sidebar-foot">
              <RunnerSummary />
              <HealthIndicator />
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </nav>
  )
}
