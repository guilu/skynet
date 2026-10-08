import * as Menu from '@radix-ui/react-dropdown-menu'
import { Check, LogOut } from 'lucide-react'
import type { ThemeChoice } from '../../theme'
import { THEMES } from './nav'

/** Botón redondo con el icono del tema elegido y un menú con las tres opciones. */
export function ThemeMenu({
  theme,
  onChange,
}: {
  theme: ThemeChoice
  onChange: (theme: ThemeChoice) => void
}) {
  const current = THEMES.find((t) => t.value === theme) ?? THEMES[0]
  return (
    <Menu.Root modal={false}>
      <Menu.Trigger className="icon-btn" aria-label={`Tema: ${current.label}`}>
        <current.icon size={22} strokeWidth={2.25} aria-hidden="true" />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu" align="end" sideOffset={8}>
          <Menu.Label className="menu-label">Tema</Menu.Label>
          <Menu.RadioGroup value={theme} onValueChange={(v) => onChange(v as ThemeChoice)}>
            {THEMES.map((t) => (
              <Menu.RadioItem key={t.value} value={t.value} className="menu-item">
                <t.icon size={18} strokeWidth={2.25} aria-hidden="true" />
                {t.label}
                <Menu.ItemIndicator className="menu-check">
                  <Check size={16} strokeWidth={3} aria-hidden="true" />
                </Menu.ItemIndicator>
              </Menu.RadioItem>
            ))}
          </Menu.RadioGroup>
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}

/** Inicial del usuario en una pastilla; el menú lleva su nombre y «Salir». */
export function UserMenu({
  username,
  onLogout,
  pending,
}: {
  username: string
  onLogout: () => void
  pending: boolean
}) {
  return (
    <Menu.Root modal={false}>
      <Menu.Trigger className="avatar-btn" aria-label={`Cuenta: ${username}`}>
        <span className="avatar" aria-hidden="true">
          {username.slice(0, 1).toUpperCase()}
        </span>
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu" align="end" sideOffset={8}>
          <Menu.Label className="menu-label menu-user">{username}</Menu.Label>
          <Menu.Item className="menu-item" disabled={pending} onSelect={onLogout}>
            <LogOut size={18} strokeWidth={2.25} aria-hidden="true" />
            Salir
          </Menu.Item>
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}
