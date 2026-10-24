import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, type AgentMemory, type AgentRun, type MemoryVia } from '../../api'
import { kindLabel, VIA_LABEL } from '../memory/memoryText'
import { ErrorMessage } from '../ErrorMessage'
import { LinesSkeleton } from '../ui/Skeleton'

type Access = AgentMemory['accesses'][number]

const GROUPS: { title: string; vias: MemoryVia[]; empty: string }[] = [
  {
    title: 'Lo que recibió',
    vias: ['PROMPT', 'CONTEXT'],
    empty: 'No recibió ninguna entrada.',
  },
  { title: 'Lo que buscó o leyó', vias: ['SEARCH', 'GET'], empty: 'No buscó ni leyó nada.' },
  { title: 'Lo que guardó', vias: ['SAVE'], empty: 'No guardó nada.' },
]

const MODE_TEXT = {
  WRITE: 'Esta invocación leía y guardaba en la memoria del proyecto.',
  READ: 'Esta invocación solo leía la memoria del proyecto (memory: read).',
}

/**
 * El apartado «Memoria» del agente (ME-D): qué entradas recibió al empezar, cuáles buscó o leyó y
 * cuáles guardó, cada una en la versión que vio y enlazada a la pestaña Memoria del proyecto.
 */
export function MemoryTab({ agent }: { agent: AgentRun }) {
  const memory = useQuery({
    queryKey: ['agent-memory', agent.id],
    queryFn: () => api.agentMemory(agent.id),
  })
  if (memory.error) return <ErrorMessage error={memory.error} />
  if (!memory.data) return <LinesSkeleton label="la memoria del agente" />
  const { mode, accesses, projectId } = memory.data
  if (mode == null) {
    return (
      <p className="muted">
        Esta invocación no usó la memoria: es un comando, su fase tiene <code>memory: off</code> o
        es anterior a la memoria.
      </p>
    )
  }
  return (
    <>
      <p className="small muted">{MODE_TEXT[mode]}</p>
      {GROUPS.map((g) => {
        const items = accesses.filter((a) => g.vias.includes(a.via))
        return (
          <section key={g.title} className="memory-access-group" aria-label={g.title}>
            <h3>{g.title}</h3>
            {items.length === 0 ? (
              <p className="small muted">{g.empty}</p>
            ) : (
              <ul className="repo-facts small">
                {items.map((a) => (
                  <AccessRow
                    key={`${a.via}-${a.observationId}-${a.version}`}
                    access={a}
                    projectId={projectId}
                  />
                ))}
              </ul>
            )}
          </section>
        )
      })}
    </>
  )
}

function AccessRow({ access: a, projectId }: { access: Access; projectId: string }) {
  return (
    <li>
      <Link to={`/projects/${projectId}?tab=memory&entry=${a.observationId}`}>{a.title}</Link>{' '}
      <span className="muted">
        · {kindLabel(a.kind)} · v{a.version} · {VIA_LABEL[a.via]}
        {a.times > 1 && ` (${a.times} veces)`}
        {a.archived && ' · archivada'}
      </span>
    </li>
  )
}
