import { useMemo } from "react"
import { ArrowRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import type { Selection } from "@/components/board/board"
import { EventRow } from "@/components/event-row"
import type { Graph } from "@/lib/graph"
import { holderDetail, itemName } from "@/lib/format"
import { holderNames } from "@/lib/graph"
import type { Report, ReportEvent } from "@/lib/report"

type Props = {
  report: Report
  graph: Graph
  selection: Selection
  onSelect: (selection: Selection) => void
}

export function Details({ report, graph, selection, onSelect }: Props) {
  const label = useMemo(() => holderNames(report), [report])
  const node = selection?.kind === "node" ? graph.nodes.find((n) => n.id === selection.id) : undefined
  const edge = selection?.kind === "edge" ? graph.edges.find((e) => e.id === selection.id) : undefined

  let title = "All events"
  let subtitle: string | null = null
  let events: ReportEvent[] = graph.events
  if (node) {
    title = node.kind === "holder" ? label(node.id) : node.label
    subtitle = node.kind === "holder" ? [node.type, holderDetail(node.id, true)].filter(Boolean).join(" · ") : null
    events = graph.byNode.get(node.id) ?? []
  } else if (edge) {
    const name = (id: string) => (id in report.holders ? label(id) : (graph.nodes.find((n) => n.id === id)?.label ?? id))
    title = `${name(edge.source)} → ${name(edge.target)}`
    subtitle = [...edge.items]
      .sort((a, b) => b[1] - a[1])
      .map(([item, n]) => `${n} × ${itemName(item)}`)
      .join(", ")
    events = graph.byEdge.get(edge.id) ?? []
  }

  const suspicious = selection ? [] : graph.events.filter((e) => e.suspicious)

  return (
    <aside className="flex min-h-0 flex-col border-t border-border bg-card md:w-[380px] md:border-t-0 md:border-l">
      <header className="border-b border-border p-4">
        <h2 className="text-lg leading-tight font-semibold break-words">{title}</h2>
        <p className="mt-1 text-sm break-all text-muted-foreground">
          {subtitle ?? `${events.length} events`}
        </p>
        {node && subtitle ? <p className="mt-0.5 text-xs text-muted-foreground">{events.length} events</p> : null}
        {selection ? (
          <Button variant="ghost" size="sm" className="mt-2 -ml-2" onClick={() => onSelect(null)}>
            Show all events
          </Button>
        ) : null}
      </header>

      <ScrollArea className="min-h-0 flex-1">
        {suspicious.length > 0 ? (
          <section className="border-b border-border bg-redstone/5 p-4">
            <h3 className="font-semibold text-redstone">
              {suspicious.length} suspicious {suspicious.length === 1 ? "mint" : "mints"}
            </h3>
            <p className="mt-1 text-sm text-muted-foreground">
              Items created by an unknown source or in creative mode.
            </p>
            <ul className="mt-2 space-y-1">
              {suspicious.slice(0, 5).map((event, i) => (
                <li key={`${event.seq}:${i}`}>
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-auto w-full justify-between py-1.5 text-left whitespace-normal"
                    onClick={() => onSelect({ kind: "node", id: event.holder })}
                  >
                    {event.type === "MINTED" ? `${event.amount} × ${itemName(event.item)} in ${label(event.holder)}` : label(event.holder)}
                    <ArrowRight aria-hidden />
                  </Button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {events.length === 0 ? (
          <p className="p-4 text-sm text-muted-foreground">No events match the current filters.</p>
        ) : (
          <ul className="px-4">
            {events.map((event, i) => (
              <EventRow key={`${event.seq}:${i}`} event={event} label={label} />
            ))}
          </ul>
        )}
      </ScrollArea>
    </aside>
  )
}
