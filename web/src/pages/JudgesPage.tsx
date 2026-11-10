import { useQuery } from '@tanstack/react-query'
import { Scale } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { api, type JudgeLens, type JudgeStatsRow } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { FilterChips, ListToolbar, type ChipOption } from '../components/list/Toolbar'
import { OUTCOME_LABEL } from '../components/run/findingFormat'
import { LENS_LABEL } from '../components/run/stageNotes'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import { TableSkeleton } from '../components/ui/Skeleton'
import { formatDateTime } from '../format'

const PERIODS: ChipOption[] = [
  { value: '7', label: '7 días' },
  { value: '30', label: '30 días' },
  { value: '90', label: '90 días' },
]

/** «33 %». */
function percent(ratio: number): string {
  return `${Math.round(ratio * 100)} %`
}

/** La clave de una fila: el juez con su lente, en su proveedor y su modelo. */
function judgeKey(r: JudgeStatsRow): string {
  return [r.provider ?? '', r.model ?? '', r.judge, r.lens ?? ''].join('|')
}

function lensOf(r: JudgeStatsRow): string {
  return r.lens ? (LENS_LABEL[r.lens as JudgeLens] ?? r.lens) : 'general'
}

/**
 * Fábrica › Jueces (RH-C): el ruido y el acierto de cada juez, por proveedor, modelo y lente, en
 * los últimos 7, 30 o 90 días. Ruido es lo que tumbó un escéptico o una persona dijo que no
 * aplicaba; acierto, lo que el agente corrigió. Al elegir una fila salen sus últimos hallazgos con
 * ruido. El periodo y la fila van en la URL (`?days=`, `?judge=`).
 */
export function JudgesPage() {
  const [params, setParams] = useSearchParams()
  const asked = params.get('days')
  const days = PERIODS.some((p) => p.value === asked) ? Number(asked) : 30
  const chosen = params.get('judge')
  const stats = useQuery({
    queryKey: ['factory', 'judges', days],
    queryFn: () => api.judgeStats(days),
  })
  const set = (next: { days?: number; judge?: string | null }) => {
    const p = new URLSearchParams()
    const d = next.days ?? days
    if (d !== 30) p.set('days', String(d))
    const j = next.judge === undefined ? chosen : next.judge
    if (j) p.set('judge', j)
    setParams(p, { replace: true })
  }
  const rows = stats.data ?? []
  const selected = rows.find((r) => judgeKey(r) === chosen)

  const columns: Column<JudgeStatsRow>[] = [
    {
      id: 'judge',
      header: 'Juez',
      hideable: false,
      cell: (r) => (
        <Button
          variant="link"
          aria-pressed={selected === r}
          onClick={() => set({ judge: selected === r ? null : judgeKey(r) })}
        >
          {r.judge} · {lensOf(r)}
        </Button>
      ),
    },
    {
      id: 'model',
      header: 'Proveedor · modelo',
      cell: (r) => [r.provider, r.model].filter(Boolean).join(' · ') || '—',
    },
    { id: 'findings', header: 'Hallazgos', numeric: true, cell: (r) => r.findings },
    {
      id: 'noise',
      header: 'Ruido',
      numeric: true,
      cell: (r) => (
        <>
          {percent(r.noise)}
          {r.fewData && (
            <>
              {' '}
              <Pill tone="neutral">pocos datos</Pill>
            </>
          )}
        </>
      ),
    },
    { id: 'hits', header: 'Acierto', numeric: true, cell: (r) => percent(r.hits) },
    {
      id: 'detail',
      header: 'Desenlaces',
      cardWide: true,
      cell: (r) => (
        <span className="muted">
          {r.refuted} refutados · {r.irrelevant} no aplica · {r.fixed} corregidos · {r.accepted}{' '}
          intencionados · {r.parked} aparcados
          {r.open > 0 && ` · ${r.open} abiertos`}
        </span>
      ),
    },
  ]

  return (
    <>
      <h1>Jueces</h1>
      <p className="muted">
        Ruido: lo que tumbó un escéptico o no aplicaba. Acierto: lo que el agente corrigió. Con
        menos de 10 hallazgos, pocos datos.
      </p>
      <ListToolbar>
        <FilterChips
          label="Periodo"
          options={PERIODS}
          selected={[String(days)]}
          onChange={(next) => {
            const picked = next.find((v) => v !== String(days))
            if (picked) set({ days: Number(picked) })
          }}
        />
      </ListToolbar>
      <ErrorMessage error={stats.error} />
      {stats.isPending && <TableSkeleton label="los jueces" />}
      {stats.data && (
        <DataTable
          label="Jueces"
          columns={columns}
          rows={rows}
          rowKey={judgeKey}
          rowClassName={(r) => (r === selected ? 'judge-row-selected' : undefined)}
          cards
          empty={
            <EmptyState icon={Scale} title={`Ningún hallazgo en ${days} días.`}>
              <p>Cada hallazgo de un juez saldrá aquí, con lo que pasó con él.</p>
            </EmptyState>
          }
        />
      )}
      {selected && <NoisyFindings row={selected} days={days} />}
    </>
  )
}

function NoisyFindings({ row, days }: { row: JudgeStatsRow; days: number }) {
  const title = `Ruido de ${row.judge} · ${lensOf(row)}`
  return (
    <section className="judge-noise" aria-label={title}>
      <h2>{title}</h2>
      {row.noisy.length === 0 ? (
        <p className="muted">Nada refutado ni que no aplicara en {days} días.</p>
      ) : (
        <ul className="stage-findings-list small">
          {row.noisy.map((f) => (
            <li key={f.id} className={`stage-finding ${f.outcome}`}>
              <span className={`finding-outcome ${f.outcome}`}>{OUTCOME_LABEL[f.outcome]}</span>{' '}
              {f.file && <code>{f.line != null ? `${f.file}:${f.line}` : f.file}</code>} {f.text}
              {f.outcomeReason && (
                <span className="muted stage-judge-reason">
                  {' '}
                  {f.outcomeBy && f.outcomeBy !== 'skynet' ? `${f.outcomeBy}: ` : ''}
                  {f.outcomeReason}
                </span>
              )}{' '}
              <span className="muted">
                · <Link to={`/runs/${f.runId}`}>{formatDateTime(f.createdAt)}</Link>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
