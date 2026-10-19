import { isMap, isSeq, parseDocument } from 'yaml'
import type { Problem } from '../../api'

/**
 * El `id` de cada elemento de `stages` del texto, por su posición (null si no lo tiene). Un
 * problema de una fase sin `id` legible dice su fase por posición (`stages[3]…`).
 */
export function stageIdsByIndex(text: string): (string | null)[] {
  const stages = parseDocument(text).get('stages', true)
  if (!isSeq(stages)) return []
  return stages.items.map((item) => {
    const id = isMap(item) ? item.get('id') : null
    return typeof id === 'string' ? id : null
  })
}

/** `stages.<id>…` (lo normal) o `stages[<i>]…` (una fase que el control plane no pudo leer). */
const BY_ID = /^stages\.([a-z][a-z0-9-]*)(?:\.|\[|$)/
const BY_INDEX = /^stages\[(\d+)\]/

/**
 * El control plane cuenta un ciclo una sola vez, en la fase donde lo encontró: «Las dependencias
 * forman un ciclo: build → review → build». Las demás fases del camino también lo llevan.
 */
const CYCLE = /^Las dependencias forman un ciclo: (.+)$/

/** Los problemas de cada fase (también los de sus hijos), por su id; los demás no salen. */
export function problemsByStage(text: string, problems: Problem[]): Map<string, Problem[]> {
  const byStage = new Map<string, Problem[]>()
  const add = (id: string, problem: Problem) => {
    const current = byStage.get(id) ?? []
    if (!current.includes(problem)) byStage.set(id, [...current, problem])
  }
  const ids = problems.some((p) => BY_INDEX.test(p.path)) ? stageIdsByIndex(text) : []
  for (const problem of problems) {
    const index = BY_INDEX.exec(problem.path)
    const id = BY_ID.exec(problem.path)?.[1] ?? (index ? ids[Number(index[1])] : null)
    if (!id) continue
    add(id, problem)
    const cycle = CYCLE.exec(problem.message)
    if (cycle) for (const member of cycle[1].split(' → ')) add(member, problem)
  }
  return byStage
}

interface StageDeps {
  id: string
  dependsOn: { stage: string }[]
}

/** Las fases que se nombran en `dependsOn` y no existen, con las fases que las nombran. */
export function missingDependencies(stages: StageDeps[]): Map<string, string[]> {
  const ids = new Set(stages.map((s) => s.id))
  const missing = new Map<string, string[]>()
  for (const stage of stages)
    for (const dep of stage.dependsOn)
      if (!ids.has(dep.stage)) missing.set(dep.stage, [...(missing.get(dep.stage) ?? []), stage.id])
  return missing
}

/**
 * Las flechas que forman parte de un ciclo (`dep->fase`): las que unen dos fases de la misma
 * componente fuertemente conexa (Tarjan).
 */
export function cycleEdges(stages: StageDeps[]): Set<string> {
  const ids = new Set(stages.map((s) => s.id))
  // La flecha va de la dependencia a la fase: se recorre de la fase a sus dependencias, igual da.
  const next = new Map(
    stages.map((s) => [
      s.id,
      s.dependsOn.map((d) => d.stage).filter((d) => ids.has(d) && d !== s.id),
    ]),
  )
  const index = new Map<string, number>()
  const low = new Map<string, number>()
  const component = new Map<string, number>()
  const stack: string[] = []
  const onStack = new Set<string>()
  let counter = 0
  let components = 0
  const connect = (id: string) => {
    index.set(id, counter)
    low.set(id, counter)
    counter++
    stack.push(id)
    onStack.add(id)
    for (const dep of next.get(id) ?? []) {
      if (!index.has(dep)) {
        connect(dep)
        low.set(id, Math.min(low.get(id)!, low.get(dep)!))
      } else if (onStack.has(dep)) low.set(id, Math.min(low.get(id)!, index.get(dep)!))
    }
    if (low.get(id) === index.get(id)) {
      let member: string
      do {
        member = stack.pop()!
        onStack.delete(member)
        component.set(member, components)
      } while (member !== id)
      components++
    }
  }
  for (const stage of stages) if (!index.has(stage.id)) connect(stage.id)
  const edges = new Set<string>()
  for (const [id, deps] of next)
    for (const dep of deps) if (component.get(dep) === component.get(id)) edges.add(`${dep}->${id}`)
  return edges
}

/** «2 errores», «1 aviso»: lo más grave que tiene la fase, para su insignia. */
export function problemBadge(
  problems: Problem[],
): { severity: Problem['severity']; label: string } | null {
  if (problems.length === 0) return null
  const errors = problems.filter((p) => p.severity === 'ERROR').length
  if (errors > 0)
    return { severity: 'ERROR', label: errors === 1 ? '1 error' : `${errors} errores` }
  const unsupported = problems.filter((p) => p.severity === 'UNSUPPORTED').length
  if (unsupported > 0)
    return {
      severity: 'UNSUPPORTED',
      label: unsupported === 1 ? '1 aún no se ejecuta' : `${unsupported} aún no se ejecutan`,
    }
  return {
    severity: 'WARNING',
    label: problems.length === 1 ? '1 aviso' : `${problems.length} avisos`,
  }
}
