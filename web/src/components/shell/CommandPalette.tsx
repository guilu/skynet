import { useQueries, useQuery } from '@tanstack/react-query'
import * as Dialog from '@radix-ui/react-dialog'
import { Command } from 'cmdk'
import { FileText, Folder, Search, Server } from 'lucide-react'
import { useNavigate } from 'react-router'
import { api } from '../../api'
import { formatDateTime } from '../../format'
import type { ThemeChoice } from '../../theme'
import { StatusBadge } from '../StatusBadge'
import { NAV, THEMES } from './nav'

/**
 * Paleta de órdenes (⌘K o Ctrl+K): ir a cualquier sección, proyecto, trabajo, ejecución reciente
 * o runner escribiendo su clave o su nombre, y cambiar el tema. Los datos se piden al abrirla.
 */
export function CommandPalette({
  open,
  onOpenChange,
  onTheme,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onTheme: (theme: ThemeChoice) => void
}) {
  const navigate = useNavigate()
  const projects = useQuery({ queryKey: ['projects'], queryFn: api.projects, enabled: open })
  const workItems = useQueries({
    queries: (projects.data ?? []).map((p) => ({
      queryKey: ['work-items', p.id],
      queryFn: () => api.workItems(p.id),
      enabled: open,
    })),
  })
  const runs = useQuery({
    queryKey: ['runs', 'palette'],
    queryFn: () => api.listRuns({ size: 30 }),
    enabled: open,
  })
  const runners = useQuery({ queryKey: ['runners'], queryFn: api.runners, enabled: open })

  const go = (to: string) => {
    onOpenChange(false)
    navigate(to)
  }
  const items = workItems.flatMap((q) => q.data ?? [])
  const loading = projects.isPending || runs.isPending || runners.isPending

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="palette-overlay" />
        <Dialog.Content className="palette" aria-describedby={undefined}>
          <Dialog.Title className="visually-hidden">Buscar o ejecutar una orden</Dialog.Title>
          <Command label="Buscar o ejecutar una orden">
            <div className="palette-search">
              <Search size={20} strokeWidth={2.5} aria-hidden="true" />
              <Command.Input placeholder="Buscar ejecución, trabajo, proyecto o runner…" />
              <kbd className="kbd">Esc</kbd>
            </div>
            <Command.List className="palette-list">
              {loading && <Command.Loading>Cargando…</Command.Loading>}
              <Command.Empty className="palette-empty">
                Nada coincide con la búsqueda.
              </Command.Empty>
              <Command.Group heading="Ir a">
                {NAV.map((n) => (
                  <Command.Item key={n.to} value={`ir a ${n.label}`} onSelect={() => go(n.to)}>
                    <n.icon size={20} strokeWidth={2.25} aria-hidden="true" />
                    {n.label}
                  </Command.Item>
                ))}
              </Command.Group>
              {!!projects.data?.length && (
                <Command.Group heading="Proyectos">
                  {projects.data.map((p) => (
                    <Command.Item
                      key={p.id}
                      value={`proyecto ${p.key} ${p.name}`}
                      onSelect={() => go(`/projects/${p.id}`)}
                    >
                      <Folder size={20} strokeWidth={2.25} aria-hidden="true" />
                      <span className="palette-key">{p.key}</span>
                      {p.name}
                    </Command.Item>
                  ))}
                </Command.Group>
              )}
              {items.length > 0 && (
                <Command.Group heading="Trabajos">
                  {items.map((w) => (
                    <Command.Item
                      key={w.id}
                      value={`trabajo ${w.key} ${w.title}`}
                      onSelect={() => go(`/work-items/${w.id}`)}
                    >
                      <FileText size={20} strokeWidth={2.25} aria-hidden="true" />
                      <span className="palette-key">{w.key}</span>
                      <span className="palette-text">{w.title}</span>
                    </Command.Item>
                  ))}
                </Command.Group>
              )}
              {!!runs.data?.items.length && (
                <Command.Group heading="Ejecuciones recientes">
                  {runs.data.items.map((r) => (
                    <Command.Item
                      key={r.id}
                      value={`ejecución ${r.workItemKey ?? ''} ${r.workItemTitle ?? ''} ${r.id}`}
                      onSelect={() => go(`/runs/${r.id}`)}
                    >
                      <StatusBadge status={r.status} />
                      <span className="palette-key">{r.workItemKey}</span>
                      <span className="palette-text">{r.workItemTitle}</span>
                      <span className="palette-meta">{formatDateTime(r.createdAt)}</span>
                    </Command.Item>
                  ))}
                </Command.Group>
              )}
              {!!runners.data?.length && (
                <Command.Group heading="Runners">
                  {runners.data.map((r) => (
                    <Command.Item
                      key={r.id}
                      value={`runner ${r.name}`}
                      onSelect={() => go('/runners')}
                    >
                      <Server size={20} strokeWidth={2.25} aria-hidden="true" />
                      {r.name}
                      <span className="palette-meta">
                        {r.status === 'ONLINE' ? 'en línea' : 'sin latido'}
                      </span>
                    </Command.Item>
                  ))}
                </Command.Group>
              )}
              <Command.Group heading="Tema">
                {THEMES.map((t) => (
                  <Command.Item
                    key={t.value}
                    value={`tema ${t.label}`}
                    onSelect={() => {
                      onTheme(t.value)
                      onOpenChange(false)
                    }}
                  >
                    <t.icon size={20} strokeWidth={2.25} aria-hidden="true" />
                    Tema {t.label.toLowerCase()}
                  </Command.Item>
                ))}
              </Command.Group>
            </Command.List>
          </Command>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
