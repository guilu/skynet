import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Gauge, RefreshCw, Server } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, type AgentUsage, type QuotaWindowKind, type RunUsage, type UsageWindow } from '../api'
import { AttentionList } from '../components/AttentionList'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { StatusBadge } from '../components/StatusBadge'
import { Button } from '../components/ui/Button'
import { CardsSkeleton, TableSkeleton } from '../components/ui/Skeleton'
import { ProviderCard } from '../components/usage/ProviderCard'
import {
  formatAgo,
  formatPercent,
  formatReset,
  quotaAlerts,
  QUOTA_PROVIDERS,
  READ_ONLY_PROVIDERS,
  renewed,
  sharesByProvider,
  shortProvider,
} from '../components/usage/quotaFormat'
import { PERIODS, usePeriod } from '../components/usePeriod'
import { formatCompact } from '../format'
import { useNow } from '../useNow'

const UsageChart = lazy(() => import('../components/usage/UsageChart'))

/** Orden de las tarjetas: los CLIs que lanzan agentes primero. */
const ORDER = ['claude-code', 'codex', 'gemini', 'antigravity']
const order = (p: string) => (ORDER.includes(p) ? ORDER.indexOf(p) : ORDER.length)

/**
 * Uso de los CLIs (hito CU): las ventanas de 5 h y semanal de cada CLI del runner, con lo gastado
 * por Skynet y por fuera; la evolución de la ventana actual; y lo que cuesta cada agente y las
 * ejecuciones que más han gastado.
 */
export function UsagePage() {
  const [params, setParams] = useSearchParams()
  const now = useNow(true, 30_000)
  const queryClient = useQueryClient()
  const windows = useQuery({
    queryKey: ['usage', 'windows'],
    queryFn: () => api.usageWindows(),
    refetchInterval: 15_000,
  })
  const summary = useQuery({
    queryKey: ['dashboard'],
    queryFn: api.dashboard,
    refetchInterval: 15_000,
  })
  const runnerList = useQuery({ queryKey: ['runners'], queryFn: () => api.runners() })
  const runners = windows.data?.runners ?? []
  // El runner elegido o, si no, el primero con datos.
  const runner =
    runners.find((r) => r.id === params.get('runner')) ??
    runners.find((r) => r.windows.length > 0) ??
    runners[0]
  const chooseRunner = (id: string) => {
    const p = new URLSearchParams(params)
    p.set('runner', id)
    setParams(p, { replace: true })
  }
  const refresh = useMutation({
    mutationFn: () => api.refreshUsage(runner?.id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['usage'] }),
  })
  const own = runner?.windows ?? []
  const versions = runnerList.data?.find((r) => r.id === runner?.id)?.providers ?? []
  const sampled = [...new Set(own.map((w) => w.provider))]
  // También los CLIs del runner sin muestras aún (Claude Code solo informa al ejecutar un agente).
  const providers = [
    ...new Set([
      ...sampled,
      ...versions.map((v) => v.id).filter((id) => QUOTA_PROVIDERS.includes(id)),
    ]),
  ].sort((a, b) => order(a) - order(b))
  const warnings = (summary.data?.quotaWarnings ?? []).filter((w) => w.runnerId === runner?.id)
  const alerts = quotaAlerts(warnings, now)

  return (
    <div className="usage">
      <div className="usage-head">
        <div>
          <h1>Uso de los CLIs</h1>
          {windows.data && <p className="muted">{summaryLine(own, now)}</p>}
        </div>
        <div className="usage-actions">
          {runners.length > 1 ? (
            <label className="usage-runner">
              <Server size={16} strokeWidth={2.5} aria-hidden="true" />
              Runner
              <select value={runner?.id} onChange={(e) => chooseRunner(e.target.value)}>
                {runners.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            runner && (
              <span className="chip">
                <Server size={16} strokeWidth={2.5} aria-hidden="true" />
                Runner: {runner.name}
              </span>
            )
          )}
          {runner && (
            <Button
              variant="secondary"
              onClick={() => refresh.mutate()}
              disabled={refresh.isPending}
            >
              <RefreshCw size={16} strokeWidth={2.75} aria-hidden="true" />
              Actualizar
            </Button>
          )}
        </div>
      </div>
      {refresh.isSuccess && (
        <p role="status" className="small muted">
          Pedido: el runner consulta sus cuotas tras su próximo latido y el dato llega en unos
          segundos.
        </p>
      )}
      <ErrorMessage error={windows.error ?? refresh.error} />
      <AttentionList alerts={alerts} className="usage-section" />

      <section className="usage-section" aria-labelledby="windows-title">
        <div className="section-head">
          <h2 id="windows-title">Ventanas de uso</h2>
          <Legend pace="Ritmo uniforme" />
        </div>
        {windows.isPending && <CardsSkeleton label="las ventanas de uso" count={3} />}
        {windows.data && providers.length === 0 && (
          <EmptyState icon={Gauge} title="Aún no hay datos de cuota.">
            <p>
              El runner lee la cuota de cada CLI al terminar cada agente y consulta la de Codex y
              Antigravity cada 10 minutos. Pulsa «Actualizar» para pedirla ahora.
            </p>
          </EmptyState>
        )}
        {providers.length > 0 && (
          <div className="provs">
            {providers.map((p) => (
              <ProviderCard
                key={p}
                provider={p}
                windows={own.filter((w) => w.provider === p)}
                version={versions.find((v) => v.id === p)?.version ?? null}
                now={now}
              />
            ))}
          </div>
        )}
      </section>

      {runner && sampled.length > 0 && (
        <Evolution
          runnerId={runner.id}
          windows={own}
          providers={providers.filter((p) => sampled.includes(p))}
          now={now}
        />
      )}

      <Costs runnerId={runner?.id} />
    </div>
  )
}

/** Una línea con lo más importante: la ventana más gastada y cuándo se renueva. */
function summaryLine(windows: UsageWindow[], now: number): string {
  const live = windows.filter((w) => !renewed(w, now) && !READ_ONLY_PROVIDERS.includes(w.provider))
  if (live.length === 0) return 'Aún no hay datos de cuota de este runner.'
  const top = live.reduce((a, b) => (b.usedFraction > a.usedFraction ? b : a))
  const where = top.window === 'five_hour' ? 'su ventana de 5 h' : 'su semana'
  const reset = top.resetsAt ? `, que se renueva ${formatReset(top.resetsAt, now)}` : ''
  return `${top.providerName} va al ${formatPercent(top.usedFraction)} de ${where}${reset}.`
}

function Legend({ pace, cumulative }: { pace: string; cumulative?: boolean }) {
  return (
    <p className="usage-legend">
      <span>
        <i className="sk" aria-hidden="true" />
        Skynet{cumulative && ' (acumulado)'}
      </span>
      <span>
        <i className="ot" aria-hidden="true" />
        Fuera de Skynet
      </span>
      <span>
        <i className="pace" aria-hidden="true" />
        {pace}
      </span>
    </p>
  )
}

/** Evolución de la ventana actual del CLI y la ventana elegidos. */
function Evolution({
  runnerId,
  windows,
  providers,
  now,
}: {
  runnerId: string
  windows: UsageWindow[]
  providers: string[]
  now: number
}) {
  // Un CLI con varios grupos de modelos (agy) se ve por el primero.
  const options = providers.map((p) => {
    const w = windows.find((x) => x.provider === p)!
    return { provider: p, bucket: w.bucket, name: w.providerName, label: w.label }
  })
  const [chosen, setChosen] = useState(options[0].provider)
  const [win, setWin] = useState<QuotaWindowKind>('five_hour')
  const option = options.find((o) => o.provider === chosen) ?? options[0]
  const history = useQuery({
    queryKey: ['usage', 'history', runnerId, option.provider, option.bucket, win],
    queryFn: () => api.usageHistory(option.provider, option.bucket, win, runnerId),
    refetchInterval: 30_000,
    retry: false,
  })
  const current = windows.find(
    (w) => w.provider === option.provider && w.bucket === option.bucket && w.window === win,
  )
  return (
    <section className="card usage-section usage-chart" aria-labelledby="evolution-title">
      <div className="section-head">
        <h2 id="evolution-title">Evolución de la ventana</h2>
        <div className="usage-controls">
          <div className="segmented" role="group" aria-label="CLI">
            {options.map((o) => (
              <button
                key={o.provider}
                type="button"
                aria-pressed={o.provider === option.provider}
                onClick={() => setChosen(o.provider)}
              >
                {shortProvider(o.provider)}
              </button>
            ))}
          </div>
          <div className="segmented" role="group" aria-label="Ventana">
            {(['five_hour', 'seven_day'] as const).map((w) => (
              <button key={w} type="button" aria-pressed={w === win} onClick={() => setWin(w)}>
                {w === 'five_hour' ? '5 h' : 'Semana'}
              </button>
            ))}
          </div>
        </div>
      </div>
      <p className="muted small">
        {option.name}
        {option.label && ` (${option.label})`} ·{' '}
        {win === 'five_hour' ? 'ventana de 5 h actual' : 'semana actual'}
        {current &&
          !renewed(current, now) &&
          ` · ${formatPercent(current.usedFraction)} usado, se renueva ${formatReset(current.resetsAt, now)}`}
      </p>
      {history.isPending && <div className="chart-placeholder" />}
      {history.isError && <p className="muted">Aún no hay muestras de esta ventana.</p>}
      {history.data && (
        <figure
          className="chart-frame"
          role="img"
          aria-label={`Uso acumulado de la ventana: ${history.data.points.length} muestras`}
        >
          <Suspense fallback={<div className="chart-placeholder" />}>
            <UsageChart history={history.data} now={now} />
          </Suspense>
        </figure>
      )}
      <Legend pace="Ahora" cumulative />
    </section>
  )
}

/** Lo que cuesta cada agente y las ejecuciones que más han gastado, en el periodo elegido. */
function Costs({ runnerId }: { runnerId: string | undefined }) {
  const [period, choose] = usePeriod()
  const agents = useQuery({
    queryKey: ['usage', 'agents', period, runnerId],
    queryFn: () => api.usageAgents(period, runnerId),
  })
  const runs = useQuery({
    queryKey: ['usage', 'runs', period, runnerId],
    queryFn: () => api.usageRuns(period, 5, runnerId),
  })
  return (
    <section className="usage-section" aria-labelledby="costs-title">
      <div className="section-head">
        <h2 id="costs-title">Qué cuesta cada agente</h2>
        <div className="segmented" role="group" aria-label="Periodo">
          {PERIODS.map((p) => (
            <button
              key={p.id}
              type="button"
              aria-pressed={p.id === period}
              onClick={() => choose(p.id)}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>
      <div className="usage-two">
        <div className="card">
          <div className="section-head">
            <h3>Por agente</h3>
            <span className="muted small">% de la ventana semanal de su CLI</span>
          </div>
          <ErrorMessage error={agents.error} />
          {agents.isPending && <TableSkeleton label="los agentes" columns={5} rows={3} />}
          {agents.data && agents.data.length === 0 && (
            <p className="muted">Ningún agente ha gastado cuota en este periodo.</p>
          )}
          {agents.data && agents.data.length > 0 && <AgentsTable agents={agents.data} />}
          <p className="prov-note">
            <b>Por éxito</b> = % semanal gastado entre las ejecuciones completadas. Un agente que
            falla mucho cuesta más por resultado útil.
          </p>
        </div>
        <div className="card">
          <h3>Ejecuciones que más han consumido</h3>
          <ErrorMessage error={runs.error} />
          {runs.isPending && <TableSkeleton label="las ejecuciones" columns={3} rows={3} />}
          {runs.data && runs.data.length === 0 && (
            <p className="muted">Ninguna ejecución ha gastado cuota en este periodo.</p>
          )}
          {runs.data && runs.data.length > 0 && <TopRuns runs={runs.data} />}
        </div>
      </div>
    </section>
  )
}

function AgentsTable({ agents }: { agents: AgentUsage[] }) {
  const max = Math.max(...agents.map((a) => a.sevenDay), 0.0001)
  return (
    <div className="table-wrap">
      <table className="table usage-agents">
        <caption className="visually-hidden">Lo que cuesta cada agente</caption>
        <thead>
          <tr>
            <th scope="col">Agente</th>
            <th scope="col" className="num">
              Ejecuciones
            </th>
            <th scope="col" className="num">
              % semanal
            </th>
            <th scope="col" className="num hide-mobile">
              Por ejecución
            </th>
            <th scope="col" className="num hide-mobile">
              Por éxito
            </th>
          </tr>
        </thead>
        <tbody>
          {agents.map((a) => {
            const tokens =
              a.inputTokens == null && a.outputTokens == null
                ? null
                : (a.inputTokens ?? 0) + (a.outputTokens ?? 0)
            return (
              <tr key={`${a.workflowKey}-${a.agent}-${a.provider}`}>
                <td>
                  <strong>{a.agent}</strong>
                  <small>
                    {[
                      a.workflowName ?? a.workflowKey,
                      a.providerName,
                      tokens != null && `${formatCompact(tokens)} tokens`,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </small>
                </td>
                <td className="num">{a.runs}</td>
                <td className="num">
                  <span
                    className="mini-bar"
                    style={{ width: `${Math.round((a.sevenDay / max) * 64)}px` }}
                    aria-hidden="true"
                  />
                  {formatPercent(a.sevenDay)}
                </td>
                <td className="num hide-mobile">{formatPercent(a.sevenDayPerRun)}</td>
                <td className="num hide-mobile">
                  {a.sevenDayPerSuccess == null ? '—' : formatPercent(a.sevenDayPerSuccess)}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function TopRuns({ runs }: { runs: RunUsage[] }) {
  const now = useNow(false)
  return (
    <ul className="usage-runs">
      {runs.map((r) => {
        const top = sharesByProvider(r.quota)[0]
        const title =
          r.workItemKey && r.workItemTitle
            ? `${r.workItemKey} · ${r.workItemTitle}`
            : (r.workItemKey ?? r.workItemTitle ?? r.workflowName ?? r.workflowKey)
        return (
          <li key={r.id}>
            <Link to={`/runs/${r.id}`} className="usage-run">
              <span>
                <strong>{title}</strong>
                <small>
                  {r.workflowName ?? r.workflowKey} · {formatAgo(r.createdAt, now)}
                </small>
              </span>
              <span className="usage-run-status">
                <StatusBadge status={r.status} />
              </span>
              {top && (
                <span className="usage-run-value">
                  {top.fiveHour != null
                    ? `${formatPercent(top.fiveHour)} de 5 h`
                    : `${formatPercent(top.sevenDay ?? 0)} semanal`}
                  <small>
                    {shortProvider(top.provider)}
                    {top.fiveHour != null &&
                      top.sevenDay != null &&
                      ` · ${formatPercent(top.sevenDay)} semanal`}
                  </small>
                </span>
              )}
            </Link>
          </li>
        )
      })}
    </ul>
  )
}
