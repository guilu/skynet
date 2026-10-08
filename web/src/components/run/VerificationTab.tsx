import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { api, type AgentRun, type StoredEvent, type VerificationResult } from '../../api'
import { elapsed, formatDateTime, formatDuration, isTerminal, workspaceUsable } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { agentOutcome } from './agentEvents'
import { artifactOf, type TestReport } from './artifacts'
import { useNow } from '../../useNow'
import { LongText } from './LongText'
import { Button } from '../ui/Button'

/**
 * Lo que declara el agente frente a lo que comprobó Skynet al ejecutar el comando de verificación
 * del repositorio en su worktree (ADR-0001 criterio 8).
 */
export function VerificationTab({ agent, events }: { agent: AgentRun; events: StoredEvent[] }) {
  const queryClient = useQueryClient()
  const verifications = useQuery({
    queryKey: ['verifications', agent.id],
    queryFn: () => api.verifications(agent.id),
  })
  const rerun = useMutation({
    mutationFn: () => api.verify(agent.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['verifications', agent.id] })
    },
  })
  const { result } = agentOutcome(events)
  const [latest, ...older] = verifications.data ?? []
  const live = latest?.live ?? false
  const canRerun = isTerminal(agent.status) && workspaceUsable(agent.workspace) && !live

  return (
    <>
      <div className="side-by-side">
        <section aria-labelledby="declared-title">
          <h3 id="declared-title">Declarado por el agente</h3>
          <p className="small">
            Resultado: <code>{agent.resultSubtype ?? '—'}</code>
          </p>
          {result ? (
            <LongText text={result} />
          ) : (
            <p className="muted small">Sin respuesta final.</p>
          )}
        </section>
        <section aria-labelledby="verified-title">
          <h3 id="verified-title">Verificado por Skynet</h3>
          <ErrorMessage error={verifications.error} />
          {verifications.data == null && !verifications.error && (
            <p className="muted small">Cargando…</p>
          )}
          {verifications.data?.length === 0 && (
            <p className="muted small">
              Sin verificaciones. Skynet verifica cada invocación completada si el repositorio tiene
              un comando de verificación (se configura en la página del proyecto).
            </p>
          )}
          {latest && <Verification verification={latest} agentId={agent.id} />}
        </section>
      </div>
      <p>
        <Button
          type="button"
          onClick={() => rerun.mutate()}
          disabled={!canRerun || rerun.isPending}
        >
          {rerun.isPending ? 'Encolando…' : 'Reejecutar verificación'}
        </Button>{' '}
        {live && <span className="muted small">Hay una verificación en curso.</span>}
        {agent.workspace?.removedAt && (
          <span className="muted small">El worktree se eliminó: ya no se puede verificar.</span>
        )}
        {!isTerminal(agent.status) && (
          <span className="muted small">Se puede reejecutar cuando termine la invocación.</span>
        )}
      </p>
      <ErrorMessage error={rerun.error} />
      {older.length > 0 && (
        <>
          <h3>Anteriores</h3>
          <ol className="list small">
            {older.map((v) => (
              <li key={v.id}>
                <details>
                  <summary>
                    <StatusBadge status={v.status} /> {triggerLabel(v)} ·{' '}
                    {formatDateTime(v.createdAt)}
                    {v.tests && ` · ${testsSummary(v)}`}
                  </summary>
                  <Verification verification={v} agentId={agent.id} />
                </details>
              </li>
            ))}
          </ol>
        </>
      )}
    </>
  )
}

const triggerLabel = (v: VerificationResult) => (v.trigger === 'MANUAL' ? 'Manual' : 'Automática')

const testsSummary = (v: VerificationResult) =>
  v.tests
    ? `${v.tests.total} tests · ${v.tests.failed + v.tests.errors} fallidos · ${v.tests.skipped} omitidos`
    : ''

function Verification({
  verification: v,
  agentId,
}: {
  verification: VerificationResult
  agentId: string
}) {
  const now = useNow(v.live)
  const facts: [string, ReactNode][] = [
    ['Estado', <StatusBadge key="s" status={v.status} />],
    ['Origen', triggerLabel(v)],
    ['Comando', <code key="c">{v.command}</code>],
    ['Código de salida', v.exitCode == null ? '—' : String(v.exitCode)],
    ['Duración', formatDuration(v.startedAt ? elapsed(v.startedAt, v.finishedAt, now) : null)],
    ['Tests', v.tests ? testsSummary(v) : 'sin informes JUnit'],
  ]
  if (v.signal) facts.push(['Señal', v.signal])
  return (
    <>
      <dl className="agent-facts small">
        {facts.map(([label, value]) => (
          <div key={label} className="fact">
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {v.error && <p className="error small">{v.error}</p>}
      {v.tests && v.tests.failed + v.tests.errors > 0 && (
        <Failures verificationId={v.id} agentId={agentId} />
      )}
    </>
  )
}

/** Tests fallidos, del artefacto `TEST_REPORT` de la verificación. */
function Failures({ verificationId, agentId }: { verificationId: string; agentId: string }) {
  const artifacts = useQuery({
    queryKey: ['artifacts', agentId],
    queryFn: () => api.artifacts(agentId),
  })
  const report = artifacts.data && artifactOf(artifacts.data, 'TEST_REPORT', verificationId)
  const content = useQuery({
    queryKey: ['artifact-content', report?.id, 0, report?.size],
    queryFn: async () => {
      const chunk = await api.artifactChunk(report!.id, 0, report!.size)
      return JSON.parse(new TextDecoder().decode(chunk.bytes)) as TestReport
    },
    enabled: report != null,
    staleTime: Infinity,
  })
  if (content.error) return <ErrorMessage error={content.error} />
  if (!content.data) return null
  return (
    <>
      <h4>Tests fallidos</h4>
      <ul className="list small">
        {content.data.failures.map((f, i) => (
          <li key={`${f.className}.${f.name}.${i}`}>
            <details>
              <summary>
                <strong>{f.className ? `${f.className}.${f.name}` : f.name}</strong>
                {f.message && <span className="muted"> · {f.message}</span>}
              </summary>
              {f.details && <LongText text={f.details} code />}
            </details>
          </li>
        ))}
      </ul>
      {content.data.failures.length < content.data.failed + content.data.errors && (
        <p className="muted small">Solo se detallan los primeros casos fallidos.</p>
      )}
    </>
  )
}
