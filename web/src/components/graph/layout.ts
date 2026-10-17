import dagre from '@dagrejs/dagre'
import { PHASES, type Phase } from '../../api'

/** Lo que el grafo necesita de una fase: de la definición (W7-A) o de una ejecución (W7-B). */
export interface GraphStage {
  id: string
  dependsOn: { stage: string; optional: boolean }[]
  /** Hijos de una fase `parallel`, en el orden del YAML; vacío en las demás. */
  children: string[]
  /** Macrofase (`phase`) de la fase, para su carril; null si no la dice. */
  phase?: Phase | null
}

export interface Box {
  x: number
  y: number
  width: number
  height: number
}

export interface StageBox extends Box {
  id: string
  /** Un hijo de una `parallel` por caja, relativa a la de la fase. */
  children: (Box & { id: string })[]
}

export interface GraphEdge {
  id: string
  from: string
  to: string
  optional: boolean
}

/** Un carril por macrofase: una banda vertical con su nombre arriba. */
export interface LaneBox extends Box {
  id: Phase
  label: string
}

export interface GraphLayout {
  stages: StageBox[]
  edges: GraphEdge[]
  /** Vacío si ninguna fase dice su `phase`. */
  lanes: LaneBox[]
  width: number
  height: number
}

export const NODE_WIDTH = 200
export const NODE_HEIGHT = 76
/** Cabecera y márgenes de una `parallel` alrededor de sus hijos. */
export const GROUP_HEADER = 52
export const GROUP_PADDING = 12
const CHILD_HEIGHT = 60
const CHILD_GAP = 8
/** Cabecera de un carril, margen de sus fases y hueco entre carriles. */
export const LANE_HEADER = 36
const LANE_PADDING = 16
const LANE_GAP = 12
const MARGIN = 16

export const PHASE_LABEL: Record<Phase, string> = {
  spec: 'Especificación',
  plan: 'Planificación',
  implement: 'Implementación',
  test: 'Tests',
  review: 'Revisión',
  deliver: 'Entrega',
}

/**
 * El carril de cada fase: su `phase` o, si no la dice, el de sus dependencias (el último en el
 * orden de las macrofases, para no quedar a la izquierda de ninguna). Sin dependencias, el primero.
 * Vacío si ninguna fase dice su `phase`.
 */
export function lanesOf(stages: GraphStage[]): Map<string, Phase> {
  const lanes = new Map<string, Phase>()
  const used = PHASES.filter((p) => stages.some((s) => s.phase === p))
  if (used.length === 0) return lanes
  const byId = new Map(stages.map((s) => [s.id, s]))
  const visiting = new Set<string>()
  const laneOf = (stage: GraphStage): Phase => {
    const known = lanes.get(stage.id)
    if (known) return known
    let lane: Phase = stage.phase ?? used[0]
    if (!stage.phase && !visiting.has(stage.id)) {
      visiting.add(stage.id)
      for (const dep of stage.dependsOn) {
        const upstream = byId.get(dep.stage)
        if (!upstream || upstream === stage) continue
        const candidate = laneOf(upstream)
        if (PHASES.indexOf(candidate) > PHASES.indexOf(lane)) lane = candidate
      }
      visiting.delete(stage.id)
    }
    lanes.set(stage.id, lane)
    return lane
  }
  for (const stage of stages) laneOf(stage)
  return lanes
}

/** Alto de la caja de una fase: una `parallel` crece con sus hijos. */
function heightOf(stage: GraphStage): number {
  if (stage.children.length === 0) return NODE_HEIGHT
  return (
    GROUP_HEADER +
    stage.children.length * CHILD_HEIGHT +
    (stage.children.length - 1) * CHILD_GAP +
    GROUP_PADDING
  )
}

/**
 * Coloca las fases de izquierda a derecha según sus dependencias (dagre). Una fase `parallel` es
 * una caja con sus hijos apilados dentro. Una dependencia a una fase que no existe no se dibuja: la
 * marca la validación. Las cajas salen en el orden de `stages`, que es el del YAML.
 */
export function layoutStages(stages: GraphStage[]): GraphLayout {
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 32, ranksep: 48, marginx: 16, marginy: 16 })
  g.setDefaultEdgeLabel(() => ({}))
  const ids = new Set(stages.map((s) => s.id))
  for (const stage of stages) g.setNode(stage.id, { width: NODE_WIDTH, height: heightOf(stage) })
  const edges: GraphEdge[] = []
  for (const stage of stages)
    for (const dep of stage.dependsOn) {
      if (!ids.has(dep.stage) || dep.stage === stage.id) continue
      g.setEdge(dep.stage, stage.id)
      edges.push({
        id: `${dep.stage}->${stage.id}`,
        from: dep.stage,
        to: stage.id,
        optional: dep.optional,
      })
    }
  dagre.layout(g)

  const boxes = stages.map((stage) => {
    const node = g.node(stage.id)
    const height = heightOf(stage)
    return {
      id: stage.id,
      // dagre da el centro; React Flow, la esquina.
      x: node.x - NODE_WIDTH / 2,
      y: node.y - height / 2,
      width: NODE_WIDTH,
      height,
      children: stage.children.map((child, i) => ({
        id: child,
        x: GROUP_PADDING,
        y: GROUP_HEADER + i * (CHILD_HEIGHT + CHILD_GAP),
        width: NODE_WIDTH - 2 * GROUP_PADDING,
        height: CHILD_HEIGHT,
      })),
    }
  })
  const graph = g.graph()
  const layout = {
    stages: boxes,
    edges,
    lanes: [],
    width: graph.width ?? 0,
    height: graph.height ?? 0,
  }
  return withLanes(layout, lanesOf(stages))
}

/**
 * Separa las fases en carriles, uno por macrofase usada y en su orden, de izquierda a derecha:
 * dentro de cada carril las fases guardan la colocación de dagre, y cada carril empieza donde
 * termina el anterior.
 */
function withLanes(layout: GraphLayout, lanes: Map<string, Phase>): GraphLayout {
  if (lanes.size === 0) return layout
  const used = PHASES.filter((p) => [...lanes.values()].includes(p))
  const result: LaneBox[] = []
  const shift = new Map<string, number>()
  let start = MARGIN + LANE_PADDING
  for (const phase of used) {
    const members = layout.stages.filter((s) => lanes.get(s.id) === phase)
    const min = Math.min(...members.map((s) => s.x))
    const max = Math.max(...members.map((s) => s.x + s.width))
    for (const s of members) shift.set(s.id, start - min)
    result.push({
      id: phase,
      label: PHASE_LABEL[phase],
      x: start - LANE_PADDING,
      y: MARGIN,
      width: max - min + 2 * LANE_PADDING,
      height: 0,
    })
    start += max - min + 2 * LANE_PADDING + LANE_GAP
  }
  const stages = layout.stages.map((s) => ({
    ...s,
    x: s.x + (shift.get(s.id) ?? 0),
    y: s.y + LANE_HEADER,
  }))
  const bottom = Math.max(...stages.map((s) => s.y + s.height)) + LANE_PADDING
  for (const lane of result) lane.height = bottom - lane.y
  return {
    ...layout,
    stages,
    lanes: result,
    width: start - LANE_GAP + MARGIN,
    height: bottom + MARGIN,
  }
}
