import * as Tooltip from '@radix-ui/react-tooltip'
import { useQuery } from '@tanstack/react-query'
import { PanelLeftClose, PanelLeftOpen, Sparkles } from 'lucide-react'
import type { ReactElement } from 'react'
import { NavLink } from 'react-router'
import { api } from '../../api'
import { HealthIndicator } from '../../HealthIndicator'
import { NAV } from './nav'

/**
 * Barra lateral del escritorio: marca, secciones y, al pie, el estado del control plane y de los
 * runners. Plegada solo enseña los iconos; el nombre sigue siendo el nombre accesible del enlace
 * y aparece como pista al pasar o enfocar.
 */
export function Sidebar({ collapsed, onToggle }: { collapsed: boolean; onToggle: () => void }) {
  return (
    <aside className="sidebar" data-collapsed={collapsed || undefined}>
      <span className="brand">
        <span className="brand-mark" aria-hidden="true">
          <Sparkles size={20} strokeWidth={2.5} />
        </span>
        <span className="brand-name">Skynet</span>
      </span>
      <nav className="sidenav" aria-label="Navegación principal">
        <ul>
          {NAV.map((item) => (
            <li key={item.to}>
              <Hint label={item.label} enabled={collapsed}>
                <NavLink to={item.to} end={item.end}>
                  <item.icon size={26} strokeWidth={2.25} aria-hidden="true" />
                  <span className="nav-label">{item.label}</span>
                </NavLink>
              </Hint>
            </li>
          ))}
        </ul>
      </nav>
      <div className="sidebar-foot">
        <RunnerSummary />
        <HealthIndicator />
        <Hint label="Desplegar la barra" enabled={collapsed}>
          <button
            type="button"
            className="sidebar-toggle"
            onClick={onToggle}
            aria-expanded={!collapsed}
            aria-label={collapsed ? 'Desplegar la barra lateral' : 'Plegar la barra lateral'}
          >
            {collapsed ? (
              <PanelLeftOpen size={22} strokeWidth={2.25} aria-hidden="true" />
            ) : (
              <PanelLeftClose size={22} strokeWidth={2.25} aria-hidden="true" />
            )}
            <span className="nav-label">Plegar</span>
          </button>
        </Hint>
      </div>
    </aside>
  )
}

/** Resumen de los runners: cuántos están en línea y cuántos agentes llevan. */
export function RunnerSummary() {
  const runners = useQuery({
    queryKey: ['runners'],
    queryFn: () => api.runners(),
    refetchInterval: 15_000,
  })
  if (!runners.data || runners.data.length === 0) return null
  const online = runners.data.filter((r) => r.status === 'ONLINE')
  const agents = online.reduce((sum, r) => sum + r.activeAgents, 0)
  const stale = runners.data.length - online.length
  return (
    <NavLink to="/runners" className="runner-mini" data-state={stale > 0 ? 'warn' : 'ok'}>
      <span className="runner-mini-dot" aria-hidden="true" />
      <span className="runner-mini-text">
        <b>
          {online.length} {online.length === 1 ? 'runner en línea' : 'runners en línea'}
        </b>
        <span className="muted small">
          {agents} {agents === 1 ? 'agente' : 'agentes'}
          {stale > 0 && ` · ${stale} sin latido`}
        </span>
      </span>
    </NavLink>
  )
}

function Hint({
  label,
  enabled,
  children,
}: {
  label: string
  enabled: boolean
  children: ReactElement
}) {
  if (!enabled) return children
  return (
    <Tooltip.Root>
      <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
      <Tooltip.Portal>
        <Tooltip.Content className="tooltip" side="right" sideOffset={10}>
          {label}
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  )
}
