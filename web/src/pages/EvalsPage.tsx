import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FlaskConical } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import {
  api,
  type EvalAccuracy,
  type EvalCase,
  type EvalJudgeChoice,
  type EvalRunView,
} from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import { LinesSkeleton } from '../components/ui/Skeleton'
import { formatDateTime } from '../format'

const evalsKey = ['factory', 'evals'] as const

const EXPECTED_LABEL: Record<EvalCase['expected'], string> = {
  'debe-suspender': 'Debe suspender',
  'debe-aprobar': 'Debe aprobar',
}

/** «80 %». */
function percent(ratio: number): string {
  return `${Math.round(ratio * 100)} %`
}

/** «4,8». */
function score(n: number): string {
  return n.toLocaleString('es-ES', { maximumFractionDigits: 2 })
}

function modelOf(r: { provider: string | null; model: string | null }): string {
  return [r.provider, r.model].filter(Boolean).join(' · ') || 'por defecto'
}

/**
 * Fábrica › Evaluaciones (EV-C): el banco de casos del juez. Cada escape es un caso que un buen
 * juez debería suspender; a mano se añaden controles que debería aprobar. Un caso se repite con uno
 * o dos jueces (A/B), en una ejecución de evaluación propia, y se ve si aciertan. Arriba, el acierto
 * de cada juez y modelo en el banco. El repositorio va en la URL (`?repository=`).
 */
export function EvalsPage() {
  const [params, setParams] = useSearchParams()
  const repositoryId = params.get('repository') ?? undefined
  const all = useQuery({ queryKey: [...evalsKey, 'all'], queryFn: () => api.evalBank() })
  const bank = useQuery({
    queryKey: [...evalsKey, repositoryId ?? 'all'],
    queryFn: () => api.evalBank(repositoryId),
    refetchInterval: (q) =>
      q.state.data?.cases.some((c) =>
        c.runs.some((r) => r.status === 'PENDING' || r.status === 'RUNNING'),
      )
        ? 5000
        : false,
  })
  const repositories = new Map<string, string>()
  for (const c of all.data?.cases ?? []) repositories.set(c.repositoryId, c.repositoryName)
  const cases = bank.data?.cases ?? []

  return (
    <>
      <h1>Evaluaciones</h1>
      <p className="muted">
        Cambios ya juzgados que se guardan para volver a juzgarlos con otro juez o con otro modelo.
        Cada escape entra solo, como algo que el juez debería haber suspendido. Cada repetición es
        una invocación real del modelo.
      </p>
      {repositories.size > 1 && (
        <label className="inline-field">
          Repositorio{' '}
          <select
            value={repositoryId ?? ''}
            onChange={(e) =>
              setParams(e.target.value ? { repository: e.target.value } : {}, { replace: true })
            }
          >
            <option value="">Todos</option>
            {[...repositories].map(([id, name]) => (
              <option key={id} value={id}>
                {name}
              </option>
            ))}
          </select>
        </label>
      )}
      <ErrorMessage error={bank.error} />
      {bank.isPending && <LinesSkeleton label="el banco" />}
      {bank.data && cases.length === 0 && (
        <EmptyState icon={FlaskConical} title="El banco está vacío.">
          <p>
            Cada escape del juez entra aquí. También puedes añadir una ejecución desde la tarjeta de
            su PR.
          </p>
        </EmptyState>
      )}
      {bank.data && bank.data.accuracy.length > 0 && <AccuracyTable rows={bank.data.accuracy} />}
      {repositoryId && cases.length > 0 && (
        <RepeatForm
          caseId={cases[0].id}
          cases={cases.length}
          label={`Repetir el banco de ${repositories.get(repositoryId) ?? 'este repositorio'}`}
          launch={(judges) => api.repeatEvalBank(repositoryId, judges)}
        />
      )}
      {cases.map((c) => (
        <CaseCard key={c.id} item={c} />
      ))}
    </>
  )
}

function AccuracyTable({ rows }: { rows: EvalAccuracy[] }) {
  const columns: Column<EvalAccuracy>[] = [
    { id: 'judge', header: 'Juez', hideable: false, cell: (r) => r.judge },
    { id: 'model', header: 'Proveedor · modelo', cell: (r) => modelOf(r) },
    { id: 'runs', header: 'Repeticiones', numeric: true, cell: (r) => r.runs },
    {
      id: 'correct',
      header: 'Acierto',
      numeric: true,
      cell: (r) => (
        <>
          {r.correct} de {r.runs} ({percent(r.ratio)})
        </>
      ),
    },
    {
      id: 'failed',
      header: 'Sin veredicto',
      numeric: true,
      cell: (r) => (r.failed > 0 ? <Pill tone="warn">{r.failed}</Pill> : 0),
    },
  ]
  return (
    <DataTable
      label="Acierto en el banco"
      columns={columns}
      rows={rows}
      rowKey={(r) => `${r.judge}|${r.provider ?? ''}|${r.model ?? ''}`}
      cards
      empty={null}
    />
  )
}

function CaseCard({ item }: { item: EvalCase }) {
  const queryClient = useQueryClient()
  const [repeating, setRepeating] = useState(false)
  const remove = useMutation({
    mutationFn: () => api.removeEvalCase(item.id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: evalsKey }),
  })
  const groups = pairs(item.runs)
  return (
    <section className="card eval-case" aria-label={`Caso ${item.workItemKey}`}>
      <div className="eval-case-head">
        <h2>
          <Link to={`/runs/${item.runId}`}>{item.workItemKey}</Link>{' '}
          <span className="muted">· {item.title}</span>
        </h2>
        <Pill tone={item.expected === 'debe-suspender' ? 'bad' : 'ok'}>
          {EXPECTED_LABEL[item.expected]}
        </Pill>
        {item.fromEscape && <Pill tone="neutral">escape</Pill>}
      </div>
      <p className="small muted">
        {item.repositoryName} · fase <code>{item.stageKey}</code> ·{' '}
        <code title={item.headSha}>{item.headSha.slice(0, 7)}</code> contra{' '}
        <code title={item.baseSha}>{item.baseSha.slice(0, 7)}</code>
        {item.note && <> · {item.note}</>} · {item.createdBy} el {formatDateTime(item.createdAt)}
      </p>
      {groups.length === 0 ? (
        <p className="small muted">Aún no se ha repetido.</p>
      ) : (
        groups.map((g) => (
          <div key={g[0].pairId ?? g[0].id} className={g.length > 1 ? 'eval-pair' : undefined}>
            {g.map((r) => (
              <EvalRunCard key={r.id} run={r} />
            ))}
          </div>
        ))
      )}
      {repeating ? (
        <RepeatForm
          caseId={item.id}
          cases={1}
          label="Repetir con…"
          launch={(judges) => api.repeatEvalCase(item.id, judges)}
          onDone={() => setRepeating(false)}
        />
      ) : (
        <div className="finding-actions">
          <Button size="sm" onClick={() => setRepeating(true)}>
            Repetir con…
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => remove.mutate()}
            disabled={remove.isPending}
          >
            Quitar del banco
          </Button>
          <ErrorMessage error={remove.error} />
        </div>
      )}
    </section>
  )
}

/** Las repeticiones agrupadas: las dos de un A/B juntas, en el orden en que llegan. */
function pairs(runs: EvalRunView[]): EvalRunView[][] {
  const out: EvalRunView[][] = []
  const byPair = new Map<string, EvalRunView[]>()
  for (const r of runs) {
    if (!r.pairId) {
      out.push([r])
      continue
    }
    const group = byPair.get(r.pairId)
    if (group) group.push(r)
    else {
      const fresh = [r]
      byPair.set(r.pairId, fresh)
      out.push(fresh)
    }
  }
  return out
}

function EvalRunCard({ run }: { run: EvalRunView }) {
  const title = `${run.judge} · ${modelOf(run)}`
  return (
    <article className="eval-run small" aria-label={title}>
      <p>
        <strong>{title}</strong>{' '}
        {run.status === 'DONE' ? (
          <>
            <Pill tone={run.passed ? 'ok' : 'bad'}>{run.passed ? 'Aprueba' : 'Suspende'}</Pill>{' '}
            <Pill tone={run.correct ? 'ok' : 'warn'}>{run.correct ? 'Acertó' : 'Falló'}</Pill>
            {run.score != null && <span className="muted"> · nota {score(run.score)}</span>}
          </>
        ) : run.status === 'FAILED' ? (
          <Pill tone="bad">Sin veredicto</Pill>
        ) : (
          <Pill tone="active">{run.status === 'RUNNING' ? 'Juzgando' : 'En cola'}</Pill>
        )}
        {run.workflowRunId && (
          <span className="muted">
            {' '}
            · <Link to={`/runs/${run.workflowRunId}`}>{formatDateTime(run.createdAt)}</Link>
          </span>
        )}
      </p>
      {run.error && <p className="error">{run.error}</p>}
      {run.summary && <p className="muted">{run.summary}</p>}
      {run.findings.length > 0 && (
        <ul className="stage-findings-list">
          {run.findings.map((f, i) => (
            <li key={i}>
              {f.file && <code>{f.line != null ? `${f.file}:${f.line}` : f.file}</code>} {f.text}
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}

/**
 * Elegir uno o dos jueces (A/B) entre los agentes del workflow del caso, con otro modelo si se
 * quiere, ver cuántas invocaciones serán y lanzarlas.
 */
function RepeatForm({
  caseId,
  cases,
  label,
  launch,
  onDone,
}: {
  caseId: string
  cases: number
  label: string
  launch: (judges: EvalJudgeChoice[]) => Promise<EvalRunView[]>
  onDone?: () => void
}) {
  const queryClient = useQueryClient()
  const options = useQuery({
    queryKey: ['eval-judges', caseId],
    queryFn: () => api.evalJudges(caseId),
  })
  const first = options.data?.find((o) => o.judge) ?? options.data?.[0]
  const [a, setA] = useState<string | null>(null)
  const [modelA, setModelA] = useState('')
  const [ab, setAb] = useState(false)
  const [b, setB] = useState<string | null>(null)
  const [modelB, setModelB] = useState('')
  const judgeA = a ?? first?.agent ?? ''
  const judgeB = b ?? first?.agent ?? ''
  const judges: EvalJudgeChoice[] = [
    { agent: judgeA, model: modelA.trim() || null },
    ...(ab ? [{ agent: judgeB, model: modelB.trim() || null }] : []),
  ]
  const invocations = judges.length * cases
  const run = useMutation({
    mutationFn: () => launch(judges),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: evalsKey })
      onDone?.()
    },
  })
  const select = (value: string, onChange: (v: string) => void, name: string) => (
    <select aria-label={name} value={value} onChange={(e) => onChange(e.target.value)}>
      {(options.data ?? []).map((o) => (
        <option key={o.agent} value={o.agent}>
          {o.agent}
          {o.judge ? ' (juez de la fase)' : ''}
        </option>
      ))}
    </select>
  )
  return (
    <div className="eval-repeat" role="group" aria-label={label}>
      <h3>{label}</h3>
      <ErrorMessage error={options.error} />
      <div className="eval-repeat-row">
        {select(judgeA, setA, 'Juez A')}
        <input
          className="finding-reason"
          aria-label="Modelo del juez A"
          placeholder="Modelo (opcional)"
          value={modelA}
          onChange={(e) => setModelA(e.target.value)}
        />
      </div>
      <label className="inline-field">
        <input type="checkbox" checked={ab} onChange={(e) => setAb(e.target.checked)} /> Comparar
        con otro juez (A/B)
      </label>
      {ab && (
        <div className="eval-repeat-row">
          {select(judgeB, setB, 'Juez B')}
          <input
            className="finding-reason"
            aria-label="Modelo del juez B"
            placeholder="Modelo (opcional)"
            value={modelB}
            onChange={(e) => setModelB(e.target.value)}
          />
        </div>
      )}
      <p className="small muted">
        {invocations === 1
          ? 'Será 1 invocación real del modelo.'
          : `Serán ${invocations} invocaciones reales del modelo.`}
      </p>
      <div className="finding-actions">
        <Button size="sm" onClick={() => run.mutate()} disabled={run.isPending || !judgeA}>
          {run.isPending ? 'Lanzando…' : 'Lanzar'}
        </Button>
        {onDone && (
          <Button size="sm" variant="secondary" onClick={onDone}>
            Cancelar
          </Button>
        )}
      </div>
      <ErrorMessage error={run.error} />
    </div>
  )
}
