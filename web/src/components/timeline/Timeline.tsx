import { useVirtualizer } from '@tanstack/react-virtual'
import { useEffect, useRef, useState } from 'react'
import type { StoredEvent } from '../../api'
import { describeEvent, formatTime, TONE_ICONS } from '../../format'
import { indexOfSequence, type TimelineEntry } from './timeline'

type Row =
  | { kind: 'entry'; entry: TimelineEntry; depth: number }
  | { kind: 'event'; event: StoredEvent; parent: TimelineEntry }

const SEVERITY_TONE = { info: null, warn: 'warn', error: 'bad' } as const

/**
 * Lista virtualizada del timeline semántico. Cada entrada se abre para ver lo que resume (las
 * llamadas de un grupo o los eventos originales de una llamada) y al elegirla se abre su evento en
 * el inspector. Si hay un evento elegido, se desplaza hasta él; si no, sigue lo último que llega.
 */
export function Timeline({
  entries,
  selected,
  onSelect,
}: {
  entries: TimelineEntry[]
  selected: number | null
  onSelect: (event: StoredEvent) => void
}) {
  const [toggled, setToggled] = useState<Set<string>>(new Set())
  const scrollRef = useRef<HTMLDivElement>(null)
  const followRef = useRef(true)
  const scrolledToRef = useRef<number | null>(null)

  // El grupo que contiene el evento elegido se abre solo; pulsar su botón lo invierte.
  const selectedIndex = selected == null ? -1 : indexOfSequence(entries, selected)
  const autoOpen = selectedIndex >= 0 ? entries[selectedIndex].id : null
  const isOpen = (id: string) => toggled.has(id) !== (id === autoOpen)

  const rows: Row[] = []
  for (const entry of entries) {
    rows.push({ kind: 'entry', entry, depth: 0 })
    if (!isOpen(entry.id)) continue
    if (entry.children.length > 0) {
      for (const child of entry.children) rows.push({ kind: 'entry', entry: child, depth: 1 })
    } else if (entry.events.length > 1) {
      for (const event of entry.events) rows.push({ kind: 'event', event, parent: entry })
    }
  }

  // No usamos React Compiler: el aviso de librería incompatible con la memoización no aplica.
  // oxlint-disable-next-line react/incompatible-library
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 34,
    overscan: 12,
    getItemKey: (i) => rowKey(rows[i]),
  })

  const selectedRow =
    selected == null
      ? -1
      : rows.findLastIndex((r) => rowEvents(r).some((e) => e.sequence === selected))

  useEffect(() => {
    if (selectedRow >= 0 && scrolledToRef.current !== selected) {
      scrolledToRef.current = selected
      followRef.current = false
      virtualizer.scrollToIndex(selectedRow, { align: 'center' })
    } else if (selected == null && followRef.current && rows.length > 0) {
      virtualizer.scrollToIndex(rows.length - 1, { align: 'end' })
    }
  }, [selected, selectedRow, rows.length, virtualizer])

  if (entries.length === 0) {
    return <p className="muted">Sin eventos que mostrar.</p>
  }

  const onScroll = () => {
    const el = scrollRef.current
    if (el) followRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  }

  return (
    <div ref={scrollRef} className="timeline-scroll" onScroll={onScroll}>
      <ol className="timeline" style={{ height: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((item) => {
          const row = rows[item.index]
          return (
            <li
              key={item.key}
              data-index={item.index}
              ref={virtualizer.measureElement}
              className="timeline-item"
              style={{ transform: `translateY(${item.start}px)` }}
            >
              {row.kind === 'entry' ? (
                <EntryRow
                  entry={row.entry}
                  depth={row.depth}
                  current={item.index === selectedRow}
                  open={row.depth === 0 && isOpen(row.entry.id)}
                  onToggle={() =>
                    setToggled((prev) => {
                      const next = new Set(prev)
                      if (!next.delete(row.entry.id)) next.add(row.entry.id)
                      return next
                    })
                  }
                  onSelect={() => onSelect(row.entry.events[0])}
                />
              ) : (
                <button
                  type="button"
                  className="timeline-row depth-2"
                  aria-current={item.index === selectedRow ? 'true' : undefined}
                  onClick={() => onSelect(row.event)}
                >
                  <span className="timeline-time">{formatTime(row.event.occurredAt)}</span>
                  <code className="timeline-type">{row.event.type}</code>
                  <span>
                    #{row.event.sequence} · {describeEvent(row.event.type, row.event.payload)}
                  </span>
                </button>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

function EntryRow({
  entry,
  depth,
  current,
  open,
  onToggle,
  onSelect,
}: {
  entry: TimelineEntry
  depth: number
  current: boolean
  open: boolean
  onToggle: () => void
  onSelect: () => void
}) {
  const expandable = depth === 0 && (entry.children.length > 0 || entry.events.length > 1)
  const tone = SEVERITY_TONE[entry.severity]
  return (
    <div className={`timeline-entry depth-${depth}`}>
      {expandable ? (
        <button
          type="button"
          className="timeline-toggle"
          aria-expanded={open}
          aria-label={`${open ? 'Cerrar' : 'Abrir'}: ${entry.title}`}
          onClick={onToggle}
        >
          {open ? '▾' : '▸'}
        </button>
      ) : (
        <span className="timeline-toggle" aria-hidden="true" />
      )}
      <button
        type="button"
        className="timeline-row"
        aria-current={current ? 'true' : undefined}
        onClick={onSelect}
      >
        <span className="timeline-time">{formatTime(entry.occurredAt)}</span>
        <code className="timeline-type">{entry.events[0].type}</code>
        <span>
          {tone && (
            <span className={`badge badge-${tone}`}>
              <span aria-hidden="true">{TONE_ICONS[tone]}</span>{' '}
              {entry.severity === 'error' ? 'Error' : 'Aviso'}
            </span>
          )}{' '}
          {entry.title}
          {entry.children.length > 0 && (
            <span className="muted small"> · {entry.events.length} eventos</span>
          )}
        </span>
      </button>
    </div>
  )
}

const rowKey = (row: Row) =>
  row.kind === 'entry' ? `${row.depth}-${row.entry.id}` : `ev-${row.event.sequence}`

const rowEvents = (row: Row) => (row.kind === 'entry' ? row.entry.events : [row.event])
