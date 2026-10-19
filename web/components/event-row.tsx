import { Fragment, type ReactNode } from "react"
import { Badge } from "@/components/ui/badge"
import { formatChunk, formatSpan, itemName } from "@/lib/format"
import { createdBy } from "@/lib/graph"
import type { ReportEvent } from "@/lib/report"
import { cn } from "@/lib/utils"

function Item({ id, amount }: { id: string; amount: number }) {
  return (
    <span className="whitespace-nowrap" title={id}>
      {amount} × <strong className="font-semibold">{itemName(id)}</strong>
    </span>
  )
}

function Stack({ items }: { items: Record<string, number> }) {
  return Object.entries(items).map(([item, n], i) => (
    <Fragment key={item}>
      {i > 0 ? ", " : null}
      <Item id={item} amount={n} />
    </Fragment>
  ))
}

const SELF_VERB: Record<string, string> = { mine: "mined", kill: "hunted", loot: "looted" }

const Who = ({ children }: { children: string }) => <strong className="font-semibold">{children}</strong>

function describe(event: ReportEvent, label: (holder: string) => string): ReactNode {
  // an event names its holders as they were then ("Chest" before the block became a furnace)
  const who = <Who>{event.holderAs?.label ?? label(event.holder)}</Who>
  switch (event.type) {
    case "MINTED": {
      const name = event.holderAs?.label ?? label(event.holder)
      const verb = name === event.origin ? SELF_VERB[event.cause] : undefined
      if (verb) {
        return (
          <>
            {who} {verb} <Item id={event.item} amount={event.amount} />
          </>
        )
      }
      return (
        <>
          <Item id={event.item} amount={event.amount} /> appeared in {who}, {createdBy(event.cause).toLowerCase()} {event.origin}
        </>
      )
    }
    case "TRANSFERRED":
      return (
        <>
          {who} gave <Item id={event.item} amount={event.amount} /> to <Who>{event.otherAs?.label ?? label(event.other)}</Who>
        </>
      )
    case "BURNED":
      return (
        <>
          {who} lost <Item id={event.item} amount={event.amount} />
        </>
      )
    case "CONVERTED":
      return (
        <>
          {who} turned <Stack items={event.inputs} /> into <Stack items={event.outputs} />
        </>
      )
    case "CLEARED":
      return <>{who} was cleared</>
  }
}

/** Who held the items, as a share of the stack that moved. */
function Custody({ shares }: { shares: Record<string, number> }) {
  const rows = Object.entries(shares).sort((a, b) => b[1] - a[1])
  return (
    <ul className="mt-2 space-y-1">
      {rows.map(([name, share]) => (
        <li key={name} className="grid grid-cols-[1fr_auto] items-center gap-x-2 text-xs">
          <span className="truncate">{name}</span>
          <span className="text-muted-foreground">{Math.round(share * 100)}%</span>
          <span className="col-span-2 h-1 rounded-full bg-secondary">
            <span
              className="block h-full rounded-full bg-copper"
              style={{ width: `${Math.max(2, share * 100)}%` }}
            />
          </span>
        </li>
      ))}
    </ul>
  )
}

export function EventRow({
  event,
  label,
}: {
  event: ReportEvent
  label: (holder: string) => string
}) {
  return (
    <li
      className={cn(
        "border-b border-border py-3 pl-3 [contain-intrinsic-size:auto_72px] [content-visibility:auto]",
        event.suspicious && "border-l-4 border-l-redstone pl-2",
      )}
    >
      <p className="text-sm leading-relaxed">{describe(event, label)}</p>
      <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
        <span>{formatSpan(event.time, event.until)}</span>
        {event.chunk ? <span>{formatChunk(event.chunk)}</span> : null}
        <span>#{event.seq}</span>
        <Badge variant="outline">{event.cause}</Badge>
        {event.suspicious ? <Badge variant="destructive">suspicious</Badge> : null}
      </p>
      {event.type === "TRANSFERRED" && event.custody ? <Custody shares={event.custody} /> : null}
    </li>
  )
}
