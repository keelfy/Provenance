import { Graph, layout } from "@dagrejs/dagre"
import type { Graph as Model, GraphEdge } from "@/lib/graph"
import { stackLabel } from "@/lib/format"

export const NODE_W = 200
export const NODE_H = 68

const LABEL_H = 26
/** Rough width of the 12px semibold label plus its padding; dagre reserves this much room for it. */
const labelWidth = (text: string) => Math.ceil(text.length * 7 + 22)

export type Point = { x: number; y: number }

export const edgeLabel = (edge: GraphEdge) => stackLabel(edge.items)

/**
 * Left-to-right layers: origins on the left, burn on the right. Only the node positions come from dagre (top-left
 * corners); the edges are drawn by `RoutedEdgeView`. A pair of opposite edges is one link for the layout, with room for
 * the wider of its two labels.
 */
export function place(model: Model): Map<string, Point> {
  const g = new Graph()
  g.setGraph({ rankdir: "LR", acyclicer: "greedy", nodesep: 56, ranksep: 60, marginx: 32, marginy: 32 })
  g.setDefaultEdgeLabel(() => ({}))
  for (const node of model.nodes) g.setNode(node.id, { width: NODE_W, height: NODE_H })

  // Items flowed first in the direction of the older event, so that is the direction the layers follow.
  const first = (edge: GraphEdge) => model.byEdge.get(edge.id)!.at(-1)!.seq
  const opposite = (edge: GraphEdge) => model.edges.find((e) => e.source === edge.target && e.target === edge.source)
  for (const edge of model.edges) {
    const back = opposite(edge)
    if (back && first(back) < first(edge)) continue
    const width = Math.max(labelWidth(edgeLabel(edge)), back ? labelWidth(edgeLabel(back)) : 0)
    g.setEdge(edge.source, edge.target, { width, height: back ? LABEL_H * 2 : LABEL_H, labelpos: "c" })
  }
  layout(g)

  const out = new Map<string, Point>()
  for (const node of model.nodes) {
    const p = g.node(node.id)
    out.set(node.id, { x: p.x - NODE_W / 2, y: p.y - NODE_H / 2 })
  }
  return out
}
