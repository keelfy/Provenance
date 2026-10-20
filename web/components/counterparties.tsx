import { Sparkles } from "lucide-react"
import type { Selection } from "@/components/board/board"
import { ScrollArea } from "@/components/ui/scroll-area"
import { stackLabel } from "@/lib/format"
import type { Graph } from "@/lib/graph"
import { cn } from "@/lib/utils"

const amounts = (items: ReadonlyMap<string, number>) => (items.size > 0 ? stackLabel(items) : "–")

/**
 * The player's history as a list: where things came from and where they went. The board draws the same links, but a
 * long history has more of them than fit on a canvas; a table scrolls.
 */
export function Counterparties({
  graph,
  selection,
  onSelect,
}: {
  graph: Graph
  selection: Selection
  onSelect: (selection: Selection) => void
}) {
  const nodes = new Map(graph.nodes.map((node) => [node.id, node]))
  return (
    <ScrollArea className="size-full bg-background">
      <table className="w-full text-sm">
        <thead className="sticky top-0 bg-card text-left text-xs text-muted-foreground">
          <tr className="border-b border-border">
            <th className="px-4 py-2 font-medium">Who or what</th>
            <th className="px-4 py-2 font-medium">Received</th>
            <th className="px-4 py-2 font-medium">Gave</th>
          </tr>
        </thead>
        <tbody>
          {graph.counterparties.map((row) => {
            const node = nodes.get(row.id)!
            const detail = node.kind === "origin" ? node.how : node.kind === "holder" ? (node.type ?? node.detail) : null
            return (
              <tr
                key={row.id}
                onClick={() => onSelect({ kind: "node", id: row.id })}
                className={cn(
                  "cursor-pointer border-b border-border hover:bg-muted",
                  selection?.kind === "node" && selection.id === row.id && "bg-lapis/10",
                )}
              >
                <td className={cn("px-4 py-2", row.suspicious && "text-redstone")}>
                  <span className="flex items-center gap-1.5 font-semibold">
                    {row.suspicious ? <Sparkles className="size-3.5" aria-hidden /> : null}
                    {node.label}
                  </span>
                  {detail ? <span className="text-xs text-muted-foreground">{detail}</span> : null}
                </td>
                <td className="px-4 py-2">{amounts(row.received)}</td>
                <td className="px-4 py-2">{amounts(row.gave)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </ScrollArea>
  )
}
