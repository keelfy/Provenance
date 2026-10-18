import { holderDetail, itemName } from "@/lib/format"
import type { Holder, Report, ReportEvent } from "@/lib/report"

export type Filters = {
  item: string | null
  cause: string | null
  suspiciousOnly: boolean
}

export const NO_FILTERS: Filters = { item: null, cause: null, suspiciousOnly: false }

export type NodeKind = "holder" | "origin" | "sink"

export type GraphNode = {
  id: string
  kind: NodeKind
  label: string
  /** PLAYER / BLOCK / ... for holders */
  holderKind?: string
  /** Holders: coordinates or a short uuid, shown under the name */
  detail?: string
  /** Holders with a custom name: what they are */
  type?: string
  /** Origins: how the items were created ("Mined by"), above the name */
  how?: string
  root: boolean
  suspicious: boolean
  /** Conversions and clears, which have no edge */
  notes: number
}

export type GraphEdge = {
  id: string
  source: string
  target: string
  /** All items moved, summed */
  amount: number
  items: Map<string, number>
  suspicious: boolean
}

export type Graph = {
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** Events behind each node / edge id, newest first (`js-index-maps`) */
  byNode: Map<string, ReportEvent[]>
  byEdge: Map<string, ReportEvent[]>
  events: ReportEvent[]
}

export const BURN = "sink:burned"

/** What the cause says about a creation, ahead of the origin's name */
const CREATED_BY: Record<string, string> = { mine: "Mined by", kill: "Hunted by", loot: "Looted by" }
export const createdBy = (cause: string) => CREATED_BY[cause] ?? "Created by"

export function matches(event: ReportEvent, f: Filters): boolean {
  if (f.cause && event.cause !== f.cause) return false
  if (f.suspiciousOnly && !event.suspicious) return false
  if (f.item && !involves(event, f.item)) return false
  return true
}

function involves(event: ReportEvent, item: string): boolean {
  switch (event.type) {
    case "CONVERTED":
      return item in event.inputs || item in event.outputs
    case "CLEARED":
      return false
    default:
      return event.item === item
  }
}

/** The set of values the filter bars offer, taken from the unfiltered report. */
export function facets(report: Report) {
  const items = new Set<string>()
  const causes = new Set<string>()
  for (const e of report.events) {
    causes.add(e.cause)
    if (e.type === "CONVERTED") {
      Object.keys(e.inputs).forEach((i) => items.add(i))
      Object.keys(e.outputs).forEach((i) => items.add(i))
    } else if (e.type !== "CLEARED") {
      items.add(e.item)
    }
  }
  return { items: [...items].sort((a, b) => itemName(a).localeCompare(itemName(b))), causes: [...causes].sort() }
}

/** The type's name, unless the label already says it. */
function typeName(info: Holder | undefined): string | undefined {
  const name = info?.type ? itemName(info.type) : undefined
  return name !== info?.label ? name : undefined
}

export function buildGraph(report: Report, filters: Filters): Graph {
  const nodes = new Map<string, GraphNode>()
  const edges = new Map<string, GraphEdge>()
  const byNode = new Map<string, ReportEvent[]>()
  const byEdge = new Map<string, ReportEvent[]>()
  const events = report.events.filter((e) => matches(e, filters)).sort((a, b) => b.seq - a.seq)

  const holder = (id: string) => {
    let node = nodes.get(id)
    if (!node) {
      const info = report.holders[id]
      node = {
        id,
        kind: "holder",
        label: info?.label ?? id,
        holderKind: info?.kind ?? id.split("/")[0],
        detail: holderDetail(id),
        type: typeName(info),
        root: id === report.query.root,
        suspicious: false,
        notes: 0,
      }
      nodes.set(id, node)
    }
    return node
  }
  const other = (id: string, kind: NodeKind, label: string) => {
    let node = nodes.get(id)
    if (!node) {
      node = { id, kind, label, root: false, suspicious: false, notes: 0 }
      nodes.set(id, node)
    }
    return node
  }
  const push = (map: Map<string, ReportEvent[]>, id: string, event: ReportEvent) => {
    const list = map.get(id)
    if (list) list.push(event)
    else map.set(id, [event])
  }
  const link = (source: string, target: string, item: string, amount: number, event: ReportEvent) => {
    const id = `${source}>${target}`
    let edge = edges.get(id)
    if (!edge) {
      edge = { id, source, target, amount: 0, items: new Map(), suspicious: false }
      edges.set(id, edge)
    }
    edge.amount += amount
    edge.items.set(item, (edge.items.get(item) ?? 0) + amount)
    edge.suspicious ||= event.suspicious
    push(byEdge, id, event)
    push(byNode, source, event)
    push(byNode, target, event)
  }

  for (const event of events) {
    const subject = holder(event.holder)
    switch (event.type) {
      case "MINTED": {
        const how = createdBy(event.cause)
        const origin = other(`origin:${how}:${event.origin}`, "origin", event.origin)
        origin.how = how
        origin.suspicious ||= event.suspicious
        subject.suspicious ||= event.suspicious
        link(origin.id, subject.id, event.item, event.amount, event)
        break
      }
      case "TRANSFERRED":
        holder(event.other)
        link(event.holder, event.other, event.item, event.amount, event)
        break
      case "BURNED":
        other(BURN, "sink", "Burned")
        link(event.holder, BURN, event.item, event.amount, event)
        break
      default:
        subject.notes++
        push(byNode, subject.id, event)
    }
  }
  holder(report.query.root)

  return { nodes: [...nodes.values()], edges: [...edges.values()], byNode, byEdge, events }
}

/**
 * Display names for events: the holder's name, plus its id when another holder in the report is called the same
 * (two "Chest"s).
 */
export function holderNames(report: Report): (id: string) => string {
  const counts = new Map<string, number>()
  for (const { label } of Object.values(report.holders)) counts.set(label, (counts.get(label) ?? 0) + 1)
  return (id) => {
    const label = report.holders[id]?.label ?? id
    return (counts.get(label) ?? 0) > 1 ? `${label} (${holderDetail(id)})` : label
  }
}
