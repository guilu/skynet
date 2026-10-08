import { describe, expect, it } from 'vitest'
import runView from '../../../../fixtures/contracts/run-view.json'
import type { Run } from '../../api'
import type { ToolCall } from './agentEvents'
import { flatten, runSpans, spanDuration } from './runSpans'

const run = runView as Run
const agent = run.stages[0].agents[0]

function call(id: string, start: string, end: string | null, extra: Partial<ToolCall> = {}) {
  return {
    toolUseId: id,
    name: 'Read',
    input: { file_path: `/w/${id}` },
    startedSequence: 1,
    startedAt: start,
    completedSequence: end ? 2 : null,
    completedAt: end,
    isError: false,
    output: null,
    outputTruncated: false,
    parentToolUseId: null,
    ...extra,
  } satisfies ToolCall
}

describe('runSpans', () => {
  it('ordena fases, agentes y herramientas, con las de un subagente bajo su Task', () => {
    const calls = [
      call('task', '2026-10-05T09:00:05Z', '2026-10-05T09:00:20Z', { name: 'Task' }),
      call('sub', '2026-10-05T09:00:06Z', '2026-10-05T09:00:08Z', { parentToolUseId: 'task' }),
      call('edit', '2026-10-05T09:00:21Z', '2026-10-05T09:00:22Z', { isError: true }),
    ]
    const spans = runSpans(run, (id) => (id === agent.id ? calls : []))
    const rows = flatten(spans).map(({ span, depth }) => [span.kind, span.id, depth, span.status])
    expect(rows).toEqual([
      ['stage', run.stages[0].id, 0, run.stages[0].status],
      ['agent', agent.id, 1, agent.status],
      ['tool', 'task', 2, 'COMPLETED'],
      ['tool', 'sub', 3, 'COMPLETED'],
      ['tool', 'edit', 2, 'ERROR'],
    ])
    expect(spanDuration(flatten(spans)[2].span, 0)).toBe(15_000)
  })

  it('una herramienta sin resultado sigue en curso, o se quedó sin respuesta si el agente acabó', () => {
    const open = [call('t', '2026-10-05T09:00:05Z', null)]
    const done = runSpans(run, () => open)[0].children[0].children[0]
    // El agente del fixture ha terminado: la llamada acaba con él.
    expect(done.status).toBe('INTERRUPTED')
    expect(done.end).toBe(new Date(agent.finishedAt!).getTime())

    const running = {
      ...run,
      stages: [{ ...run.stages[0], agents: [{ ...agent, status: 'EXECUTING', finishedAt: null }] }],
    } as Run
    const live = runSpans(running, () => open)[0].children[0].children[0]
    expect(live.status).toBe('EXECUTING')
    expect(live.end).toBeNull()
    expect(spanDuration(live, new Date('2026-10-05T09:00:15Z').getTime())).toBe(10_000)
  })
})
