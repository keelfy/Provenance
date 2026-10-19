import { Handle, Position, type Node, type NodeProps } from "@xyflow/react"
import { Flame, Box, User, Sparkles, CircleHelp, PawPrint, Package, ChevronsDownUp, ChevronsUpDown } from "lucide-react"
import type { GraphNode } from "@/lib/graph"
import { cn } from "@/lib/utils"
import { itemName } from "@/lib/format"
import { NODE_H, NODE_W, nodeHeight, PILE_ROWS } from "./layout"

export type BoardNode = Node<
  { node: GraphNode; selected: boolean; /** Folds or unfolds the group this node is part of */ onToggle?: () => void },
  "holder" | "origin" | "sink"
>

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

const HOLDER_ICON: Record<string, typeof User> = { PLAYER: User, BLOCK: Box, ENTITY: PawPrint, DROP: Package }

/** Players are pills, everything else is a plate. The root of the report is filled. */
export function HolderNode({ data: { node, selected, onToggle } }: NodeProps<BoardNode>) {
  if (node.items) return <PileNode node={node} selected={selected} />
  const Icon = HOLDER_ICON[node.holderKind ?? ""] ?? CircleHelp
  return (
    <div
      style={{ width: NODE_W, height: NODE_H }}
      className={cn(
        "flex items-center gap-2.5 border-[1.5px] px-4 font-heading text-base font-semibold transition-shadow",
        node.holderKind === "PLAYER" ? "rounded-full" : "rounded-md",
        // a stack of plates: there is more than one creature behind this
        node.folded && "shadow-[5px_5px_0_-1.5px_var(--color-card),5px_5px_0_0_var(--color-ink)]",
        node.root ? "border-ink bg-ink text-card" : "border-ink bg-card text-ink",
        node.suspicious && !node.root && "border-redstone",
        selected && "ring-3 ring-lapis ring-offset-2 ring-offset-background",
      )}
    >
      <Icon className="size-4 shrink-0 opacity-70" aria-hidden />
      <span className="flex min-w-0 flex-col leading-tight" title={`${node.label}\n${node.id}`}>
        {node.killedBy ? (
          <span className="truncate text-xs font-normal opacity-60">Killed by {node.killedBy}</span>
        ) : null}
        <span className="truncate">{node.label}</span>
        {node.holderKind !== "PLAYER" ? (
          <span className="truncate text-xs font-normal opacity-60">{node.type ? `${node.type} · ${node.detail}` : node.detail}</span>
        ) : null}
      </span>
      {onToggle ? <GroupToggle node={node} onToggle={onToggle} /> : null}
      {node.notes > 0 && (
        <span className="ml-auto rounded-full bg-copper px-1.5 text-xs text-card" title="Conversions and clears">
          {node.notes}
        </span>
      )}
      <Ports />
    </div>
  )
}

/** The folded plate opens up into its creatures; each of them folds the group back. */
function GroupToggle({ node, onToggle }: { node: GraphNode; onToggle: () => void }) {
  const Chevrons = node.folded ? ChevronsUpDown : ChevronsDownUp
  return (
    <button
      type="button"
      title={node.folded ? `Show the ${node.folded} entries separately` : "Fold the group back into one"}
      aria-label={node.folded ? `Expand ${node.label}` : `Fold ${node.label}`}
      onClick={(event) => {
        event.stopPropagation()
        onToggle()
      }}
      className={cn(
        "nodrag nopan ml-auto flex shrink-0 items-center rounded-full p-1 hover:bg-ink/10 focus-visible:ring-2 focus-visible:ring-lapis focus-visible:outline-none",
        node.root && "hover:bg-card/20",
      )}
    >
      <Chevrons className="size-3.5" aria-hidden />
    </button>
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

/** Dropped items: one plate with the list of what lies there, instead of a node per stack. */
function PileNode({ node, selected }: { node: GraphNode; selected: boolean }) {
  const items = [...node.items!].sort((a, b) => b[1] - a[1])
  return (
    <div
      style={{ width: NODE_W, height: nodeHeight(node) }}
      className={cn(
        "flex flex-col justify-center gap-1 rounded-md border-[1.5px] border-ink bg-card px-4 text-ink",
        node.suspicious && "border-redstone",
        selected && "ring-3 ring-lapis ring-offset-2 ring-offset-background",
      )}
    >
      <span className="flex items-center gap-2.5 font-heading text-base font-semibold" title={node.id}>
        <Package className="size-4 shrink-0 opacity-70" aria-hidden />
        <span className="truncate">{node.label}</span>
        {node.notes > 0 && (
          <span className="ml-auto rounded-full bg-copper px-1.5 text-xs font-normal text-card" title="Conversions and clears">
            {node.notes}
          </span>
        )}
      </span>
      <ul className="text-xs leading-[18px]">
        {items.slice(0, PILE_ROWS).map(([item, n]) => (
          <li key={item} className="flex justify-between gap-2" title={`${n} × ${itemName(item)}`}>
            <span className="truncate">{itemName(item)}</span>
            <span className="shrink-0 opacity-60">× {n}</span>
          </li>
        ))}
        {items.length > PILE_ROWS ? <li className="opacity-60">+{items.length - PILE_ROWS} more</li> : null}
      </ul>
      <Ports />
    </div>
  )
}
