import type { Point } from "./layout"
import { BaseEdge, EdgeLabelRenderer, Position, type Edge, type EdgeProps } from "@xyflow/react"

export type RoutedEdge = Edge<
  { label: string; color: string; dim: boolean; lane: -1 | 0 | 1; labelAt: number; onSelect: () => void },
  "routed"
>

/** Gap between the two edges of an opposite pair, so they do not lie on top of each other. */
const LANE = 14

const vertical = (position: Position) => position === Position.Top || position === Position.Bottom

/** Where a curve leaves (or enters) a node: straight out of the side the handle sits on. */
function lead(position: Position, reach: number) {
  switch (position) {
    case Position.Left:
      return { x: -reach, y: 0 }
    case Position.Top:
      return { x: 0, y: -reach }
    case Position.Bottom:
      return { x: 0, y: reach }
    default:
      return { x: reach, y: 0 }
  }
}

function bezier(u: number, p0: Point, p1: Point, p2: Point, p3: Point): Point {
  const v = 1 - u
  const k = [v * v * v, 3 * v * v * u, 3 * v * u * u, u * u * u]
  return {
    x: k[0] * p0.x + k[1] * p1.x + k[2] * p2.x + k[3] * p3.x,
    y: k[0] * p0.y + k[1] * p1.y + k[2] * p2.y + k[3] * p3.y,
  }
}

/** One smooth curve per edge. The label sits at `labelAt` (0 = source end, 1 = target end). */
export function RoutedEdgeView({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps<RoutedEdge>) {
  const { label, color, dim, lane, labelAt, onSelect } = data!
  const shift = lane * LANE
  const s = vertical(sourcePosition) ? { x: sourceX + shift, y: sourceY } : { x: sourceX, y: sourceY + shift }
  const t = vertical(targetPosition) ? { x: targetX + shift, y: targetY } : { x: targetX, y: targetY + shift }

  const reach = Math.max(48, Math.hypot(t.x - s.x, t.y - s.y) / 2.5)
  const a = lead(sourcePosition, reach)
  const b = lead(targetPosition, reach)
  const c1 = { x: s.x + a.x, y: s.y + a.y }
  const c2 = { x: t.x + b.x, y: t.y + b.y }
  const at = bezier(labelAt, s, c1, c2, t)
  const path = `M ${s.x} ${s.y} C ${c1.x} ${c1.y} ${c2.x} ${c2.y} ${t.x} ${t.y}`

  return (
    <>
      <BaseEdge path={path} markerEnd={markerEnd} style={style} interactionWidth={24} />
      <EdgeLabelRenderer>
        <button
          type="button"
          onClick={onSelect}
          style={{
            transform: `translate(-50%, -50%) translate(${at.x}px, ${at.y}px)`,
            borderColor: color,
            opacity: dim ? 0.35 : 1,
          }}
          className="nodrag nopan pointer-events-auto absolute cursor-pointer rounded-full border-[1.5px] bg-card px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap text-ink shadow-sm hover:bg-accent"
        >
          {label}
        </button>
      </EdgeLabelRenderer>
    </>
  )
}
