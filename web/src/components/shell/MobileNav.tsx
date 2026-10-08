import * as Dialog from '@radix-ui/react-dialog'
import { Menu, X } from 'lucide-react'
import { useState } from 'react'
import { NavLink } from 'react-router'
import { HealthIndicator } from '../../HealthIndicator'
import { NAV } from './nav'
import { RunnerSummary } from './Sidebar'

/**
 * Navegación del móvil: las cuatro secciones más usadas en una barra inferior y el resto, con el
 * estado del control plane y de los runners, en un cajón que se abre con «Más».
 */
export function MobileNav() {
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  return (
    <nav className="mobile-nav" aria-label="Navegación móvil">
      {NAV.filter((n) => n.mobile).map((n) => (
        <NavLink key={n.to} to={n.to} end={n.end}>
          <n.icon size={26} strokeWidth={2.25} aria-hidden="true" />
          {n.label}
        </NavLink>
      ))}
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Trigger className="mobile-more">
          <Menu size={26} strokeWidth={2.25} aria-hidden="true" />
          Más
        </Dialog.Trigger>
        <Dialog.Portal>
          <Dialog.Overlay className="drawer-overlay" />
          <Dialog.Content className="drawer" aria-describedby={undefined}>
            <div className="drawer-head">
              <Dialog.Title className="drawer-title">Más</Dialog.Title>
              <Dialog.Close className="icon-btn" aria-label="Cerrar">
                <X size={22} strokeWidth={2.5} aria-hidden="true" />
              </Dialog.Close>
            </div>
            <nav className="sidenav" aria-label="Todas las secciones">
              <ul>
                {NAV.map((n) => (
                  <li key={n.to}>
                    <NavLink to={n.to} end={n.end} onClick={close}>
                      <n.icon size={26} strokeWidth={2.25} aria-hidden="true" />
                      {n.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
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
