import { formatChunk, holderDetail, itemName } from "@/lib/format"
import type { Holder, HolderKind, Report, ReportEvent } from "@/lib/report"

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
  /** Creatures that dropped loot: who killed them, above the name. The creature is the source, not the killer. */
  killedBy?: string
  /** A plate standing for several creatures or blocks of one type: how many entries are folded into it */
  folded?: number
  /** A creature or block shown on its own from an expanded group: the group's id, to fold it back */
  group?: string
  root: boolean
  suspicious: boolean
  /** Holders that stand for a pile of things lying around (dropped items): what is in it, to list on the node */
  items?: Map<string, number>
  /** Conversions and clears, which have no edge */
  notes: number
}

export type GraphEdge = {
  id: string
  source: string
  target: string
  /** Items moved, summed; for two-way traffic what is left after the other direction is taken off */
  amount: number
  items: Map<string, number>
  suspicious: boolean
  /** Everything that went one way came back: `items` is the traffic in both directions, and nothing changed hands */
  settled?: boolean
}

/**
 * One entry of a group: a creature, the creatures of one type one player killed in one chunk, or a block.
 */
export type Member = {
  id: string
  /** Where it is: a block's coordinates; a creature's chunk when known, else its short id */
  where: string
  killedBy?: string
  from: number
  until: number
  /** What moved in or out */
  items: Map<string, number>
  events: number
}

/** Creatures or blocks of one type, drawn as one plate until the reader expands them. */
export type Group = { id: string; label: string; kind: HolderKind; expanded: boolean; members: Member[] }

/** What passed between the root and one other node, in both directions: a row of the table view. */
export type Counterparty = {
  id: string
  /** What came in from there / went out to there, by item */
  received: Map<string, number>
  gave: Map<string, number>
  suspicious: boolean
}

export type Graph = {
  nodes: GraphNode[]
  /** Everyone and everything the root exchanged items with, the busiest first */
  counterparties: Counterparty[]
  /** By the group's id; groups exist for every type with two or more entries, expanded or not */
  groups: Map<string, Group>
  /** The plate a folded holder is drawn in; holders not in the map have a node of their own */
  folded: Map<string, string>
  edges: GraphEdge[]
  /** Events behind each node / edge id, newest first (`js-index-maps`) */
  byNode: Map<string, ReportEvent[]>
  byEdge: Map<string, ReportEvent[]>
  events: ReportEvent[]
}

export const BURN = "sink:burned"

const NO_GROUPS: ReadonlySet<string> = new Set()

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

const groupId = (key: string) => `group:${key}`

/** The group of containers only the root player ever touched; no item type or block type can collide with it */
const OWN = "own"

/**
 * How many holders of one type it takes to fold them. Mobs of a farm are many holders (one per chunk and killer), so
 * two already fold. A few chests read better one by one: which chest it was matters when items go missing.
 */
const FOLD_AT: Partial<Record<HolderKind, number>> = { ENTITY: 2, BLOCK: 5, SHULKER: 5 }

const isContainer = (kind: HolderKind) => kind === "BLOCK" || kind === "SHULKER"

/**
 * The containers of a player's own history that nobody else ever touched: the chests and shulker boxes he sorts his
 * things into. Whatever goes in comes back out, and which box it was is no part of the story. A container another
 * player opened (a theft, a gift) is not in the set, nor is one that only machines use.
 */
function ownStorage(report: Report): ReadonlySet<string> {
  const root = report.query.root
  if (report.holders[root]?.kind !== "PLAYER") return new Set()
  const own = new Set<string>()
  const foreign = new Set<string>()
  const touch = (id: string, other: string) => {
    if (!isContainer(report.holders[id]?.kind)) return
    if (other === root) own.add(id)
    else if (report.holders[other]?.kind === "PLAYER") foreign.add(id)
  }
  for (const event of report.events) {
    if (event.type !== "TRANSFERRED") continue
    touch(event.holder, event.other)
    touch(event.other, event.holder)
  }
  for (const id of foreign) own.delete(id)
  return own
}

/** The group whose plate this holder folds into, if any. The root always stands alone. */
function groupKey(report: Report, id: string, own: ReadonlySet<string>): string | undefined {
  const info = report.holders[id]
  if (!info || id === report.query.root) return undefined
  if (own.has(id)) return OWN
  return info.kind in FOLD_AT ? info.type : undefined
}

/**
 * Creatures and blocks of one type, in the order events reach them. A base of hundreds of chests or a farm of
 * hundreds of mobs would be a node each; folding them is what keeps a long history readable.
 */
function groupMembers(report: Report, events: ReportEvent[]): Map<string, Map<string, Member>> {
  const own = ownStorage(report)
  const groups = new Map<string, Map<string, Member>>()
  const add = (member: Member | undefined, item: string, amount: number) =>
    member?.items.set(item, (member.items.get(item) ?? 0) + amount)
  const touch = (id: string, event: ReportEvent) => {
    const key = groupKey(report, id, own)
    if (!key) return undefined
    let members = groups.get(key)
    if (!members) groups.set(key, (members = new Map()))
    let member = members.get(id)
    if (!member) {
      member = { id, where: "", from: Infinity, until: -Infinity, items: new Map(), events: 0 }
      members.set(id, member)
    }
    member.events++
    member.from = Math.min(member.from, event.time)
    member.until = Math.max(member.until, event.until ?? event.time)
    if (report.holders[id].kind === "BLOCK") member.where ||= holderDetail(id)
    else member.where ||= event.holder === id && event.chunk ? formatChunk(event.chunk) : ""
    return member
  }
  for (const event of events) {
    const member = touch(event.holder, event)
    if (event.type === "TRANSFERRED") {
      add(member, event.item, event.amount)
      add(touch(event.other, event), event.item, event.amount)
    } else if (event.type === "BURNED") {
      add(member, event.item, event.amount)
    } else if (member && event.type === "MINTED" && event.cause === "kill" && !event.suspicious) {
      member.killedBy ??= event.origin
    }
  }
  for (const [key, members] of groups) {
    const kind = report.holders[members.keys().next().value!].kind
    if (members.size < FOLD_AT[kind]!) groups.delete(key)
    else for (const m of members.values()) m.where ||= holderDetail(m.id)
  }
  return groups
}

/**
 * `expanded` lists the groups (by id) shown as separate holders instead of one plate.
 */
export function buildGraph(report: Report, filters: Filters, expanded: ReadonlySet<string> = NO_GROUPS): Graph {
  const nodes = new Map<string, GraphNode>()
  const edges = new Map<string, GraphEdge>()
  const byNode = new Map<string, ReportEvent[]>()
  const byEdge = new Map<string, ReportEvent[]>()
  const events = report.events.filter((e) => matches(e, filters)).sort((a, b) => b.seq - a.seq)

  const groups = new Map<string, Group>()
  /** Holders that stand in a plate: holder id -> the plate's id */
  const folded = new Map<string, string>()
  /** Holders shown on their own from an expanded group */
  const groupOf = new Map<string, string>()
  for (const [key, members] of groupMembers(report, events)) {
    const id = groupId(key)
    const open = expanded.has(id)
    const list = [...members.values()].sort((a, b) => b.from - a.from) // newest first, like the events
    const kind = key === OWN ? "BLOCK" : report.holders[list[0].id].kind
    groups.set(id, { id, label: key === OWN ? "Own storage" : itemName(key), kind, expanded: open, members: list })
    for (const m of list) (open ? groupOf : folded).set(m.id, id)
  }
  const killers = new Map<string, Set<string>>()

  const holder = (real: string) => {
    const id = folded.get(real) ?? real
    let node = nodes.get(id)
    if (!node) {
      const plate = groups.get(id)
      const info = report.holders[real]
      node = plate
        ? {
            id,
            kind: "holder",
            label: plate.label,
            holderKind: plate.kind,
            detail: `${plate.members.length} entries`,
            folded: plate.members.length,
            root: false,
            suspicious: false,
            notes: 0,
          }
        : {
            id,
            kind: "holder",
            label: info?.label ?? id,
            holderKind: info?.kind ?? id.split("/")[0],
            detail: holderDetail(id),
            type: typeName(info),
            group: groupOf.get(id),
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
    if (source === target) return push(byNode, source, event) // within one plate: no edge to draw
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
        // Loot comes from the creature that died, not from the killer: a killer node on the left would read as
        // "player -> cow -> player". The creature is where the flow starts, and carries the killer's name.
        if (event.cause === "kill" && subject.holderKind === "ENTITY" && !event.suspicious) {
          const who = killers.get(subject.id)
          if (who) who.add(event.origin)
          else killers.set(subject.id, new Set([event.origin]))
          push(byNode, subject.id, event)
          break
        }
        const how = createdBy(event.cause)
        const origin = other(`origin:${how}:${event.origin}`, "origin", event.origin)
        origin.how = how
        origin.suspicious ||= event.suspicious
        subject.suspicious ||= event.suspicious
        link(origin.id, subject.id, event.item, event.amount, event)
        break
      }
      case "TRANSFERRED":
        link(subject.id, holder(event.other).id, event.item, event.amount, event)
        break
      case "BURNED":
        other(BURN, "sink", "Burned")
        link(subject.id, BURN, event.item, event.amount, event)
        break
      default:
        subject.notes++
        push(byNode, subject.id, event)
    }
  }
  holder(report.query.root)
  for (const [id, who] of killers) {
    nodes.get(id)!.killedBy = who.size === 1 ? [...who][0] : `${who.size} hunters`
  }

  // A pile holds what came in less what left; its stacks are not nodes of their own, so the node lists them
  for (const node of nodes.values()) {
    if (node.holderKind !== "DROP") continue
    node.items = new Map()
    for (const edge of edges.values()) {
      const sign = edge.target === node.id ? 1 : edge.source === node.id ? -1 : 0
      for (const [item, n] of edge.items) node.items.set(item, (node.items.get(item) ?? 0) + sign * n)
    }
    for (const [item, n] of node.items) if (n <= 0) node.items.delete(item)
  }

  const counterparties = counterpartiesOf(report.query.root, edges)
  netPairs(edges, byEdge)

  return { nodes: [...nodes.values()], counterparties, groups, folded, edges: [...edges.values()], byNode, byEdge, events }
}

/** Read off the edges before they are netted: what went each way is the point here. */
function counterpartiesOf(root: string, edges: Map<string, GraphEdge>): Counterparty[] {
  const rows = new Map<string, Counterparty>()
  for (const edge of edges.values()) {
    const received = edge.target === root
    if (!received && edge.source !== root) continue
    const id = received ? edge.source : edge.target
    let row = rows.get(id)
    if (!row) rows.set(id, (row = { id, received: new Map(), gave: new Map(), suspicious: false }))
    const side = received ? row.received : row.gave
    for (const [item, n] of edge.items) side.set(item, (side.get(item) ?? 0) + n)
    row.suspicious ||= edge.suspicious
  }
  const volume = (row: Counterparty) =>
    [...row.received.values(), ...row.gave.values()].reduce((a, b) => a + b, 0)
  return [...rows.values()].sort((a, b) => volume(b) - volume(a))
}

/**
 * Two holders that pass items back and forth are one link with what is left over: packing a stack into a box and
 * taking it out again is no flow. Both directions only stay when different items net out in different directions; a
 * pair that nets out entirely keeps one dashed edge, so the layout still ties the holders together.
 */
function netPairs(edges: Map<string, GraphEdge>, byEdge: Map<string, ReportEvent[]>) {
  for (const forward of [...edges.values()]) {
    const back = edges.get(`${forward.target}>${forward.source}`)
    if (!back || forward.source > forward.target) continue
    const events = [...byEdge.get(forward.id)!, ...byEdge.get(back.id)!].sort((a, b) => b.seq - a.seq)
    const suspicious = forward.suspicious || back.suspicious
    const net = (to: GraphEdge, from: GraphEdge) => {
      const left = new Map<string, number>()
      for (const [item, n] of to.items) {
        const rest = n - (from.items.get(item) ?? 0)
        if (rest > 0) left.set(item, rest)
      }
      return left
    }
    const sum = (items: Map<string, number>) => [...items.values()].reduce((a, b) => a + b, 0)
    const gross = new Map(forward.items)
    for (const [item, n] of back.items) gross.set(item, (gross.get(item) ?? 0) + n)

    const results = [
      { edge: forward, items: net(forward, back) },
      { edge: back, items: net(back, forward) },
    ].filter((r) => r.items.size > 0)
    if (results.length === 0) {
      // The one that started it stays; the direction is only for the layout
      const first = (edge: GraphEdge) => byEdge.get(edge.id)!.at(-1)!.seq
      const start = first(forward) <= first(back) ? forward : back
      results.push({ edge: { ...start, settled: true }, items: gross })
    }
    edges.delete(forward.id)
    edges.delete(back.id)
    byEdge.delete(forward.id)
    byEdge.delete(back.id)
    for (const { edge, items } of results) {
      edges.set(edge.id, { ...edge, items, amount: sum(items), suspicious })
      byEdge.set(edge.id, events)
    }
  }
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
