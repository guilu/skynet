import dagre from '@dagrejs/dagre'

/** Lo que el grafo necesita de una fase: de la definición (W7-A) o de una ejecución (W7-B). */
export interface GraphStage {
  id: string
  dependsOn: { stage: string; optional: boolean }[]
  /** Hijos de una fase `parallel`, en el orden del YAML; vacío en las demás. */
  children: string[]
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

export interface GraphLayout {
  stages: StageBox[]
  edges: GraphEdge[]
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
  return { stages: boxes, edges, width: graph.width ?? 0, height: graph.height ?? 0 }
}
