import {
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/base.css'
import { useMemo, type ReactNode } from 'react'
import { cn } from '../../lib/cn'
import { layoutStages, type GraphStage } from './layout'

/** Lo que se dibuja de cada fase y de cada hijo de una `parallel`. */
export interface GraphContent {
  /** El interior de la caja de la fase (en una `parallel`, su cabecera). */
  stage: (id: string) => ReactNode
  /** El interior de la caja de un hijo de una `parallel`. */
  child?: (stageId: string, childId: string) => ReactNode
  /** Clase de la caja, p. ej. según su estado. */
  stageClass?: (id: string) => string | undefined
  childClass?: (stageId: string, childId: string) => string | undefined
  /** Si la flecha que sale de esa fase va apagada (una fase omitida). */
  dimmedFrom?: (id: string) => boolean
}

type BoxData = { content: ReactNode; handles: boolean }

function StageBox({ data }: NodeProps<Node<BoxData>>) {
  return (
    <>
      {data.handles && <Handle type="target" position={Position.Left} isConnectable={false} />}
      {data.content}
      {data.handles && <Handle type="source" position={Position.Right} isConnectable={false} />}
    </>
  )
}

const NODE_TYPES = { box: StageBox }

/** Ajustar a pantalla sin agrandar las cajas ni encogerlas hasta no poder leerlas: si no cabe, se desplaza. */
const FIT = { padding: 0.08, minZoom: 0.6, maxZoom: 1 }

/** Con más nodos, el minimapa ayuda a no perderse; con menos, estorba. */
const MINIMAP_FROM = 8

/**
 * Las fases como un grafo de izquierda a derecha (React Flow), de solo lectura: zoom, ajustar a
 * pantalla y, si es grande, minimapa. Una fase `parallel` es una caja con sus hijos dentro. Qué se
 * ve en cada caja lo decide quien lo usa: la definición del workflow o una ejecución.
 */
export default function WorkflowGraph({
  stages,
  content,
  label,
}: {
  stages: GraphStage[]
  content: GraphContent
  label: string
}) {
  const layout = useMemo(() => layoutStages(stages), [stages])
  const nodes = useMemo(() => {
    const all: Node<BoxData>[] = []
    for (const box of layout.stages) {
      const group = box.children.length > 0
      all.push({
        id: box.id,
        type: 'box',
        position: { x: box.x, y: box.y },
        width: box.width,
        height: box.height,
        data: { content: content.stage(box.id), handles: true },
        className: cn('graph-node', group && 'graph-group', content.stageClass?.(box.id)),
        draggable: false,
        connectable: false,
      })
      for (const child of box.children)
        all.push({
          id: `${box.id}/${child.id}`,
          type: 'box',
          parentId: box.id,
          extent: 'parent',
          position: { x: child.x, y: child.y },
          width: child.width,
          height: child.height,
          data: { content: content.child?.(box.id, child.id) ?? child.id, handles: false },
          className: cn('graph-node graph-child', content.childClass?.(box.id, child.id)),
          draggable: false,
          connectable: false,
        })
    }
    return all
  }, [layout, content])
  const edges = useMemo<Edge[]>(
    () =>
      layout.edges.map((e) => ({
        id: e.id,
        source: e.from,
        target: e.to,
        className: cn(
          'graph-edge',
          e.optional && 'graph-edge-optional',
          content.dimmedFrom?.(e.from) && 'graph-edge-dim',
        ),
        markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 },
        focusable: false,
      })),
    [layout, content],
  )
  // Alto justo para el grafo a tamaño natural, sin pasar de un máximo razonable.
  const height = Math.min(Math.max(layout.height + 48, 200), 560)

  return (
    <div className="workflow-graph" style={{ height }} role="group" aria-label={label}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        fitView
        fitViewOptions={FIT}
        minZoom={0.25}
        maxZoom={1.5}
        nodesDraggable={false}
        nodesConnectable={false}
        nodesFocusable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        panOnScroll
        zoomOnScroll={false}
        zoomOnDoubleClick={false}
        proOptions={{ hideAttribution: false }}
      >
        <Background gap={24} size={1} />
        <Controls
          position="top-right"
          orientation="horizontal"
          showInteractive={false}
          aria-label="Zoom del grafo"
          fitViewOptions={FIT}
        />
        {layout.stages.length > MINIMAP_FROM && (
          <MiniMap pannable zoomable ariaLabel="Minimapa del grafo" />
        )}
      </ReactFlow>
    </div>
  )
}
