import { useQuery } from '@tanstack/react-query'
import { GitMerge, GitPullRequest } from 'lucide-react'
import {
  api,
  type CiCheck,
  type CiCheckView,
  type MergeView,
  type PullRequestView,
  type Run,
} from '../../api'
import { pullRequestKey } from './pullRequest'
import type { StatusTone } from '../../format'
import { Pill } from '../ui/Pill'

/**
 * La PR de la ejecución (EN-C): su número con el enlace, la rama, el commit, la CI de ese commit
 * check a check con sus rondas de corrección y, si hay fase `github-merge`, cómo va la fusión.
 */
export function PullRequestCard({ run }: { run: Run }) {
  const data = useQuery({
    queryKey: pullRequestKey(run.id),
    queryFn: async () => {
      const [pullRequests, ciChecks, merges] = await Promise.all([
        api.pullRequests(run.id),
        api.ciChecks(run.id),
        api.merges(run.id),
      ])
      return { pullRequests, ciChecks, merges }
    },
  })
  const pr = data.data?.pullRequests[0]
  if (!pr) return null
  const ci = data.data?.ciChecks.at(-1)
  const merge = data.data?.merges.at(-1)
  return (
    <section className="card pr-card" aria-label="Pull request">
      <div className="pr-card-head">
        {pr.status === 'MERGED' ? (
          <GitMerge size={18} aria-hidden="true" />
        ) : (
          <GitPullRequest size={18} aria-hidden="true" />
        )}
        <h2 className="pr-card-title">
          {pr.url && pr.number != null ? (
            <a href={pr.url} target="_blank" rel="noreferrer">
              PR #{pr.number}
            </a>
          ) : (
            'Pull request'
          )}
          {pr.title && <span className="muted"> · {pr.title}</span>}
        </h2>
        <Pill tone={PR_TONE[pr.status]}>{PR_LABEL[pr.status]}</Pill>
      </div>
      <dl className="pr-card-facts small">
        <div>
          <dt>Rama</dt>
          <dd>
            <code>{pr.branch}</code>
            {pr.base && (
              <>
                {' → '}
                <code>{pr.base}</code>
              </>
            )}
          </dd>
        </div>
        {pr.headSha && (
          <div>
            <dt>Commit</dt>
            <dd>
              <code title={pr.headSha}>{pr.headSha.slice(0, 7)}</code>
            </dd>
          </div>
        )}
        {ci && (
          <div>
            <dt>CI</dt>
            <dd>
              <Pill tone={CI_TONE[ci.status]}>{CI_LABEL[ci.status]}</Pill>
              {ci.round > 0 && (
                <span className="muted">
                  {' '}
                  {ci.round === 1 ? '1 ronda de corrección' : `${ci.round} rondas de corrección`}
                </span>
              )}
            </dd>
          </div>
        )}
        {merge && (
          <div>
            <dt>Fusión</dt>
            <dd>
              <Pill tone={MERGE_TONE[merge.status]}>{mergeLabel(merge)}</Pill>
            </dd>
          </div>
        )}
      </dl>
      {pr.error && pr.status === 'FAILED' && <p className="error small">{pr.error}</p>}
      {ci?.error && <p className="error small">{ci.error}</p>}
      {merge?.error && merge.status === 'FAILED' && <p className="error small">{merge.error}</p>}
      {ci && ci.checks.length > 0 && <CiChecksList checks={ci.checks} />}
    </section>
  )
}

/** Los checks de un commit, uno por línea: su estado con icono y texto, y el enlace. */
export function CiChecksList({ checks }: { checks: CiCheck[] }) {
  return (
    <ul className="ci-checks small" aria-label="Checks de la CI">
      {checks.map((check, i) => (
        <li key={`${check.name}-${i}`}>
          <Pill tone={CHECK_TONE[check.state] ?? 'neutral'}>
            {CHECK_LABEL[check.state] ?? check.state}
          </Pill>
          {check.url ? (
            <a href={check.url} target="_blank" rel="noreferrer">
              {check.name}
            </a>
          ) : (
            <span>{check.name}</span>
          )}
        </li>
      ))}
    </ul>
  )
}

function mergeLabel(merge: MergeView): string {
  if (merge.status === 'MERGED') {
    return merge.mergedSha ? `Fusionada (${merge.mergedSha.slice(0, 7)})` : 'Fusionada'
  }
  if (merge.status === 'FAILED') return 'No se fusionó'
  return `Fusionando (${merge.method})`
}

const PR_LABEL: Record<PullRequestView['status'], string> = {
  PUSHING: 'Subiendo la rama',
  PUSHED: 'Abriendo la PR',
  OPEN: 'Abierta',
  FAILED: 'Falló',
  MERGED: 'Fusionada',
  CLOSED: 'Cerrada sin fusionar',
}

const PR_TONE: Record<PullRequestView['status'], StatusTone> = {
  PUSHING: 'active',
  PUSHED: 'active',
  OPEN: 'neutral',
  FAILED: 'bad',
  MERGED: 'ok',
  CLOSED: 'warn',
}

const CI_LABEL: Record<CiCheckView['status'], string> = {
  WAITING: 'Esperando',
  FIXING: 'Corrigiendo',
  PUSHING: 'Subiendo la corrección',
  PASSED: 'En verde',
  FAILED: 'En rojo',
}

const CI_TONE: Record<CiCheckView['status'], StatusTone> = {
  WAITING: 'active',
  FIXING: 'warn',
  PUSHING: 'active',
  PASSED: 'ok',
  FAILED: 'bad',
}

const MERGE_TONE: Record<MergeView['status'], StatusTone> = {
  PENDING: 'active',
  MERGED: 'ok',
  FAILED: 'bad',
}

const CHECK_LABEL: Record<string, string> = {
  success: 'Pasó',
  failure: 'Falló',
  pending: 'En curso',
  skipped: 'Omitido',
}

const CHECK_TONE: Record<string, StatusTone> = {
  success: 'ok',
  failure: 'bad',
  pending: 'active',
  skipped: 'neutral',
}
