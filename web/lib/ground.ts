import type { Report, ReportEvent } from "@/lib/report"

/** Everything still lying on the ground is one holder: a death can leave dozens of stacks, and each would be a node. */
export const GROUND = "DROP/ground"
const GROUND_HOLDER = { label: "Dropped items", kind: "DROP" }

/** A dropped stack is an item entity: the game's way to carry items between a block or player and a player. */
const isDrop = (report: Report, id: string) =>
  report.holders[id]?.kind === "ENTITY" && report.holders[id].type === "minecraft:item"

type Lying = { amount: number; event: ReportEvent }

/**
 * Removes dropped items from the story. To the plugin the stack on the ground is a holder, so a log goes
 * "mined -> item entity -> picked up". The reader only needs "mined -> keelfy" and "Bob -> Alice".
 *
 * Each stack's pickups are matched with what put the items there, oldest first. Items nobody picked up in the report
 * (and pickups whose cause is outside it) keep their events and share one holder, {@link GROUND}.
 */
export function foldDrops(report: Report): Report {
  const lying = new Map<string, Lying[]>()
  const stack = (id: string) => {
    let list = lying.get(id)
    if (!list) lying.set(id, (list = []))
    return list
  }
  const events: ReportEvent[] = []

  /** `amount` of what `head` put on the ground went on to the holder `pickup` names */
  const hand = ({ event }: Lying, amount: number, pickup: Extract<ReportEvent, { type: "TRANSFERRED" }>) => {
    const suspicious = event.suspicious || pickup.suspicious
    let folded: ReportEvent
    if (event.type === "MINTED") {
      folded = { ...event, holder: pickup.other, holderAs: pickup.otherAs, amount, suspicious }
    } else if (event.type === "TRANSFERRED") {
      if (event.holder === pickup.other) return // dropped and taken back: nothing moved
      folded = {
        ...event,
        seq: pickup.seq,
        time: pickup.time,
        until: undefined,
        other: pickup.other,
        otherAs: pickup.otherAs,
        amount,
        custody: pickup.custody ?? event.custody,
        suspicious,
      }
    } else {
      return
    }
    if (isDrop(report, pickup.other)) stack(pickup.other).push({ amount, event: folded })
    else events.push(folded)
  }

  for (const event of [...report.events].sort((a, b) => a.seq - b.seq)) {
    if (event.type === "MINTED" && isDrop(report, event.holder)) {
      stack(event.holder).push({ amount: event.amount, event })
    } else if (event.type === "TRANSFERRED" && isDrop(report, event.holder)) {
      const pile = stack(event.holder)
      let left = event.amount
      while (left > 0 && pile.length > 0) {
        const head = pile[0]
        const taken = Math.min(left, head.amount)
        left -= taken
        head.amount -= taken
        if (head.amount === 0) pile.shift()
        hand(head, taken, event)
      }
      if (left > 0) events.push({ ...event, amount: left })
    } else if (event.type === "TRANSFERRED" && isDrop(report, event.other)) {
      stack(event.other).push({ amount: event.amount, event })
    } else {
      events.push(event)
    }
  }
  for (const pile of lying.values()) {
    for (const { amount, event } of pile) {
      if (event.type === "MINTED" || event.type === "TRANSFERRED") events.push({ ...event, amount })
    }
  }

  // Whatever is left lies on the ground, however many stacks it was
  const onGround = (id: string) => (isDrop(report, id) ? GROUND : id)
  const merged: ReportEvent[] = []
  for (const event of events) {
    let next = event
    if (onGround(next.holder) !== next.holder) next = { ...next, holder: GROUND, holderAs: undefined }
    if (next.type === "TRANSFERRED" && onGround(next.other) !== next.other) {
      next = { ...next, other: GROUND, otherAs: undefined }
    }
    if (next.type === "TRANSFERRED" && next.holder === next.other) continue // from one stack to another: nothing moved
    merged.push(next)
  }

  const holders = Object.fromEntries(Object.entries(report.holders).filter(([id]) => !isDrop(report, id)))
  if (merged.some((e) => e.holder === GROUND || (e.type === "TRANSFERRED" && e.other === GROUND))) {
    holders[GROUND] = GROUND_HOLDER
  }
  return { ...report, holders, events: merged }
}
