"use client"

import "@xyflow/react/dist/style.css"
import { Background, BackgroundVariant, Controls, MarkerType, ReactFlow, type ReactFlowInstance } from "@xyflow/react"
import { useEffect, useMemo, useState } from "react"
import type { Graph } from "@/lib/graph"
import { edgeLabel, NODE_W, place, type Point } from "./layout"
import { HolderNode, OriginNode, SinkNode, type BoardNode } from "./nodes"
import { RoutedEdgeView, type RoutedEdge } from "./routed-edge"

export type Selection = { kind: "node" | "edge"; id: string } | null

const nodeTypes = { holder: HolderNode, origin: OriginNode, sink: SinkNode }
const edgeTypes = { routed: RoutedEdgeView }
const FIT = { padding: 0.15, maxZoom: 1, duration: 200 }

const COPPER = "#b5703a"
const LAPIS = "#2e4e9e"
const REDSTONE = "#c0302a"

/** Stroke grows with the log of the items moved: a stack and a chest-full stay comparable. */
const width = (amount: number) => 1.5 + Math.min(4.5, Math.log2(amount + 1) * 0.7)

/** Handles facing each other: left to right normally, right to left for a back edge, top/bottom within a layer. */
function sides(source: Point, target: Point) {
  const dx = target.x - source.x
  if (Math.abs(dx) < NODE_W / 2) {
    return target.y >= source.y
      ? { sourceHandle: "out-b", targetHandle: "in-t" }
      : { sourceHandle: "out-t", targetHandle: "in-b" }
  }
  return dx > 0 ? { sourceHandle: "out-r", targetHandle: "in-l" } : { sourceHandle: "out-l", targetHandle: "in-r" }
}

export default function Board({
  graph,
  selection,
  onSelect,
}: {
  graph: Graph
  selection: Selection
  onSelect: (selection: Selection) => void
}) {
  const placement = useMemo(() => place(graph), [graph])

  // `fitView` on the component only runs once; a new layout (filters) needs its own fit.
  const [flow, setFlow] = useState<ReactFlowInstance | null>(null)
  useEffect(() => {
    if (!flow) return
    const frame = requestAnimationFrame(() => flow.fitView(FIT))
    return () => cancelAnimationFrame(frame)
  }, [flow, placement])

  const nodes = useMemo<BoardNode[]>(
    () =>
      graph.nodes.map((node) => ({
        id: node.id,
        type: node.kind,
        position: placement.get(node.id)!,
        data: { node, selected: selection?.kind === "node" && selection.id === node.id },
      })),
    [graph, placement, selection],
  )

  const edges = useMemo<RoutedEdge[]>(() => {
    const ids = new Set(graph.edges.map((edge) => edge.id))
    const degree = new Map<string, number>()
    for (const edge of graph.edges) {
      degree.set(edge.source, (degree.get(edge.source) ?? 0) + 1)
      degree.set(edge.target, (degree.get(edge.target) ?? 0) + 1)
    }
    return graph.edges.map((edge) => {
      const selected = selection?.kind === "edge" && selection.id === edge.id
      // With something selected, only the edges around it stay at full strength.
      const dim =
        selection !== null &&
        !(selection.kind === "edge" ? selected : edge.source === selection.id || edge.target === selection.id)
      const color = selected ? LAPIS : edge.suspicious ? REDSTONE : COPPER
      const opposite = ids.has(`${edge.target}>${edge.source}`)
      return {
        id: edge.id,
        type: "routed",
        source: edge.source,
        target: edge.target,
        ...sides(placement.get(edge.source)!, placement.get(edge.target)!),
        data: {
          label: edgeLabel(edge),
          color,
          dim,
          // Labels go near the quieter end: around a hub the edges are bunched up, at the far end they fan out.
          labelAt: Math.sign(degree.get(edge.source)! - degree.get(edge.target)!) * 0.2 + 0.5,
          lane: !opposite ? 0 : edge.source < edge.target ? -1 : 1,
          onSelect: () => onSelect({ kind: "edge", id: edge.id }),
        },
        style: { stroke: color, strokeWidth: width(edge.amount), opacity: dim ? 0.3 : 0.9 },
        markerEnd: { type: MarkerType.ArrowClosed, color, width: 16, height: 16, markerUnits: "userSpaceOnUse" },
      }
    })
  }, [graph, placement, selection, onSelect])

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      onNodeClick={(_, node) => onSelect({ kind: "node", id: node.id })}
      onEdgeClick={(_, edge) => onSelect({ kind: "edge", id: edge.id })}
      onPaneClick={() => onSelect(null)}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      onInit={setFlow}
      minZoom={0.1}
      proOptions={{ hideAttribution: true }}
    >
      <Background variant={BackgroundVariant.Dots} gap={22} size={1.4} color="#b9c1b5" />
      <Controls showInteractive={false} />
    </ReactFlow>
  )
}
