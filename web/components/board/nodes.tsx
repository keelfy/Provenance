import { Handle, Position, type Node, type NodeProps } from "@xyflow/react"
import { Flame, Box, User, Sparkles, CircleHelp } from "lucide-react"
import type { GraphNode } from "@/lib/graph"
import { cn } from "@/lib/utils"
import { NODE_H, NODE_W } from "./layout"

export type BoardNode = Node<{ node: GraphNode; selected: boolean }, "holder" | "origin" | "sink">

const handle = "!size-1 !border-0 !bg-transparent"
const SIDES = [
  ["l", Position.Left],
  ["r", Position.Right],
  ["t", Position.Top],
  ["b", Position.Bottom],
] as const

/** Edges attach to the side that faces the other node (see `ports()` in the board). */
function Ports() {
  return SIDES.flatMap(([id, position]) => [
    <Handle key={`in-${id}`} id={`in-${id}`} type="target" position={position} className={handle} />,
    <Handle key={`out-${id}`} id={`out-${id}`} type="source" position={position} className={handle} />,
  ])
}

const HOLDER_ICON: Record<string, typeof User> = { PLAYER: User, BLOCK: Box }

/** Players are pills, everything else is a plate. The root of the report is filled. */
export function HolderNode({ data: { node, selected } }: NodeProps<BoardNode>) {
  const Icon = HOLDER_ICON[node.holderKind ?? ""] ?? CircleHelp
  return (
    <div
      style={{ width: NODE_W, height: NODE_H }}
      className={cn(
        "flex items-center gap-2.5 border-[1.5px] px-4 font-heading text-base font-semibold transition-shadow",
        node.holderKind === "PLAYER" ? "rounded-full" : "rounded-md",
        node.root ? "border-ink bg-ink text-card" : "border-ink bg-card text-ink",
        node.suspicious && !node.root && "border-redstone",
        selected && "ring-3 ring-lapis ring-offset-2 ring-offset-background",
      )}
    >
      <Icon className="size-4 shrink-0 opacity-70" aria-hidden />
      <span className="flex min-w-0 flex-col leading-tight" title={`${node.label}\n${node.id}`}>
        <span className="truncate">{node.label}</span>
        {node.holderKind !== "PLAYER" ? (
          <span className="truncate text-xs font-normal opacity-60">{node.type ? `${node.type} · ${node.detail}` : node.detail}</span>
        ) : null}
      </span>
      {node.notes > 0 && (
        <span className="ml-auto rounded-full bg-copper px-1.5 text-xs text-card" title="Conversions and clears">
          {node.notes}
        </span>
      )}
      <Ports />
    </div>
  )
}

/** Where items came from: no plate, a bar on the right edge that turns red for UNKNOWN / CREATIVE. */
export function OriginNode({ data: { node, selected } }: NodeProps<BoardNode>) {
  return (
    <div
      style={{ width: NODE_W, height: NODE_H }}
      className={cn(
        "flex items-center justify-end gap-2 border-r-4 pr-3 text-right text-sm",
        node.suspicious ? "border-redstone font-semibold text-redstone" : "border-copper text-ink",
        selected && "outline-3 outline-offset-2 outline-lapis",
      )}
    >
      {node.suspicious ? <Sparkles className="size-4 shrink-0" aria-hidden /> : null}
      <span className="flex min-w-0 flex-col leading-tight" title={node.label}>
        <span className="text-xs font-normal text-muted-foreground">{node.how}</span>
        <span className="truncate font-semibold">{node.label}</span>
      </span>
      <Ports />
    </div>
  )
}

export function SinkNode({ data: { selected } }: NodeProps<BoardNode>) {
  return (
    <div
      style={{ width: NODE_W, height: NODE_H }}
      className={cn(
        "flex items-center gap-2 rounded-md border-[1.5px] border-dashed border-ink/60 px-3 text-sm text-muted-foreground",
        selected && "outline-3 outline-offset-2 outline-lapis",
      )}
    >
      <Flame className="size-4" aria-hidden />
      Burned
      <Ports />
    </div>
  )
}
