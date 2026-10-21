import { expect, test } from "bun:test"
import { buildGraph, BURN, FOLD_ALL, NO_FILTERS } from "./graph"
import type { Report, ReportEvent } from "./report"

const e = (seq: number, rest: Record<string, unknown>) =>
  ({ seq, time: seq, cause: "container", suspicious: false, ...rest }) as ReportEvent

const report: Report = {
  v: 1,
  created: 0,
  expires: 1,
  query: { kind: "history.player", root: "PLAYER/bob", depth: 2 },
  holders: {
    "PLAYER/bob": { label: "Bob", kind: "PLAYER" },
    "BLOCK/chest": { label: "chest", kind: "BLOCK" },
  },
  events: [
    e(1, { type: "MINTED", holder: "BLOCK/chest", item: "minecraft:diamond", amount: 64, origin: "Creative", suspicious: true }),
    e(2, { type: "TRANSFERRED", holder: "BLOCK/chest", other: "PLAYER/bob", item: "minecraft:diamond", amount: 10 }),
    e(3, { type: "TRANSFERRED", holder: "BLOCK/chest", other: "PLAYER/bob", item: "minecraft:gold_ingot", amount: 5 }),
    e(4, { type: "BURNED", holder: "PLAYER/bob", item: "minecraft:diamond", amount: 2 }),
    e(5, { type: "CONVERTED", holder: "PLAYER/bob", inputs: { "minecraft:diamond": 9 }, outputs: { "minecraft:diamond_block": 1 } }),
  ],
  truncated: false,
}

test("transfers between a pair merge into one edge; mints and burns get border nodes", () => {
  const g = buildGraph(report, NO_FILTERS)
  const pair = g.edges.find((x) => x.id === "BLOCK/chest>PLAYER/bob")!
  expect(pair.amount).toBe(15)
  expect(Object.fromEntries(pair.items)).toEqual({ "minecraft:diamond": 10, "minecraft:gold_ingot": 5 })
  expect(g.edges.map((x) => x.id)).toContain(`origin:Created by:Creative>BLOCK/chest`)
  expect(g.edges.map((x) => x.target)).toContain(BURN)
  expect(g.nodes.find((n) => n.id === "origin:Created by:Creative")!.suspicious).toBe(true)
  expect(g.nodes.find((n) => n.id === "PLAYER/bob")!.notes).toBe(1) // the conversion has no edge
  expect(g.byEdge.get(pair.id)!.map((x) => x.seq)).toEqual([3, 2]) // newest first
})

test("filters narrow events, including conversions by either side", () => {
  expect(buildGraph(report, { ...NO_FILTERS, item: "minecraft:gold_ingot" }).events.map((x) => x.seq)).toEqual([3])
  expect(buildGraph(report, { ...NO_FILTERS, item: "minecraft:diamond_block" }).events.map((x) => x.seq)).toEqual([5])
  expect(buildGraph(report, { ...NO_FILTERS, suspiciousOnly: true }).events.map((x) => x.seq)).toEqual([1])
})

test("a period keeps events that lasted into it", () => {
  const summed = { ...report, events: [...report.events, e(6, { type: "BURNED", holder: "PLAYER/bob", item: "minecraft:dirt", amount: 1, time: 1, until: 9 })] }
  expect(buildGraph(summed, { ...NO_FILTERS, since: 4 }).events.map((x) => x.seq)).toEqual([6, 5, 4])
})

test("loot of a killed creature starts at the creature, not at a node named after the killer", () => {
  const kill: Report = {
    ...report,
    holders: { "PLAYER/bob": report.holders["PLAYER/bob"], "ENTITY/cow": { label: "Cow", kind: "ENTITY", type: "minecraft:cow" } },
    events: [
      e(1, { type: "MINTED", cause: "kill", holder: "ENTITY/cow", item: "minecraft:leather", amount: 3, origin: "Bob" }),
      e(2, { type: "TRANSFERRED", cause: "kill", holder: "ENTITY/cow", other: "PLAYER/bob", item: "minecraft:leather", amount: 3 }),
    ],
  }
  const g = buildGraph(kill, NO_FILTERS)
  expect(g.nodes.map((n) => n.kind)).toEqual(["holder", "holder"]) // no origin node
  expect(g.nodes.find((n) => n.id === "ENTITY/cow")!.killedBy).toBe("Bob")
  expect(g.edges.map((x) => x.id)).toEqual(["ENTITY/cow>PLAYER/bob"])
  expect(g.byNode.get("ENTITY/cow")!.map((x) => x.seq)).toEqual([2, 1]) // the kill is still in the cow's story
})

const farm: Report = {
  ...report,
  holders: {
    "PLAYER/bob": report.holders["PLAYER/bob"],
    "ENTITY/mob:minecraft:zombie@w:1:2#bob": { label: "Zombie", kind: "ENTITY", type: "minecraft:zombie" },
    "ENTITY/mob:minecraft:zombie@w:9:9#bob": { label: "Zombie", kind: "ENTITY", type: "minecraft:zombie" },
    "ENTITY/cow": { label: "Cow", kind: "ENTITY", type: "minecraft:cow" },
  },
  events: [
    e(1, { type: "MINTED", cause: "kill", holder: "ENTITY/mob:minecraft:zombie@w:1:2#bob", item: "minecraft:rotten_flesh", amount: 900, origin: "Bob", time: 10, until: 50, chunk: { world: "w", x: 1, z: 2 } }),
    e(2, { type: "TRANSFERRED", cause: "kill", holder: "ENTITY/mob:minecraft:zombie@w:1:2#bob", other: "PLAYER/bob", item: "minecraft:rotten_flesh", amount: 900, time: 10 }),
    e(3, { type: "MINTED", cause: "kill", holder: "ENTITY/mob:minecraft:zombie@w:9:9#bob", item: "minecraft:rotten_flesh", amount: 300, origin: "Bob" }),
    e(4, { type: "TRANSFERRED", cause: "kill", holder: "ENTITY/mob:minecraft:zombie@w:9:9#bob", other: "PLAYER/bob", item: "minecraft:rotten_flesh", amount: 300 }),
    e(5, { type: "MINTED", cause: "kill", holder: "ENTITY/cow", item: "minecraft:leather", amount: 3, origin: "Bob" }),
    e(6, { type: "TRANSFERRED", cause: "kill", holder: "ENTITY/cow", other: "PLAYER/bob", item: "minecraft:leather", amount: 3 }),
  ],
}

test("creatures of one type fold into one plate; a lone creature stays on its own", () => {
  const g = buildGraph(farm, NO_FILTERS)
  expect(g.nodes.map((n) => n.id).sort()).toEqual(["ENTITY/cow", "PLAYER/bob", "group:minecraft:zombie"])
  const plate = g.nodes.find((n) => n.id === "group:minecraft:zombie")!
  expect(plate).toMatchObject({ label: "Zombie", folded: 2, killedBy: "Bob" })
  expect(g.edges.find((x) => x.id === "group:minecraft:zombie>PLAYER/bob")!.amount).toBe(1200)
  const [big, small] = g.groups.get(plate.id)!.members // newest first
  expect(big).toMatchObject({ where: "w chunk 1, 2", from: 10, until: 50, killedBy: "Bob" })
  expect(Object.fromEntries(big.items)).toEqual({ "minecraft:rotten_flesh": 900 })
  expect(small.where).toBe("chunk w 9 9") // no chunk on the event: the holder's own place
})

test("an expanded group shows its creatures as nodes that know their group", () => {
  const g = buildGraph(farm, NO_FILTERS, new Set(["group:minecraft:zombie"]))
  const zombies = g.nodes.filter((n) => n.group)
  expect(zombies).toHaveLength(2)
  expect(zombies.every((n) => n.group === "group:minecraft:zombie" && !n.folded)).toBe(true)
  expect(g.groups.get("group:minecraft:zombie")!.expanded).toBe(true)
})

test("dropped items are one node that lists what lies there", () => {
  const death: Report = {
    ...report,
    holders: { "PLAYER/bob": report.holders["PLAYER/bob"], "DROP/ground": { label: "Dropped items", kind: "DROP" } },
    events: [
      e(1, { type: "TRANSFERRED", cause: "death", holder: "PLAYER/bob", other: "DROP/ground", item: "minecraft:diamond", amount: 5 }),
      e(2, { type: "TRANSFERRED", cause: "death", holder: "PLAYER/bob", other: "DROP/ground", item: "minecraft:dirt", amount: 64 }),
      e(3, { type: "TRANSFERRED", cause: "pickup", holder: "DROP/ground", other: "PLAYER/bob", item: "minecraft:dirt", amount: 60 }),
    ],
  }
  const g = buildGraph(death, NO_FILTERS)
  expect(g.nodes).toHaveLength(2)
  expect(Object.fromEntries(g.nodes.find((n) => n.id === "DROP/ground")!.items!)).toEqual({
    "minecraft:diamond": 5,
    "minecraft:dirt": 4,
  })
})

const chests = (n: number): Report => {
  const id = (i: number) => `BLOCK/w:${i}:64:0`
  const holders: Report["holders"] = { "PLAYER/bob": report.holders["PLAYER/bob"], "PLAYER/eve": { label: "Eve", kind: "PLAYER" } }
  const events: ReportEvent[] = []
  for (let i = 0; i < n; i++) {
    holders[id(i)] = { label: "Chest", kind: "BLOCK", type: "minecraft:chest" }
    events.push(e(i + 1, { type: "TRANSFERRED", holder: "PLAYER/bob", other: id(i), item: "minecraft:cobblestone", amount: 64 }))
    // somebody else opened every chest too, so these are shared and fold by type, not as the player's own
    events.push(e(n + i + 1, { type: "TRANSFERRED", holder: id(i), other: "PLAYER/eve", item: "minecraft:cobblestone", amount: 1 }))
  }
  return { ...report, holders, events }
}

test("a base of chests is one plate that lists where each chest is and what went in", () => {
  const g = buildGraph(chests(500), NO_FILTERS)
  expect(g.nodes.map((n) => n.id).sort()).toEqual(["PLAYER/bob", "PLAYER/eve", "group:minecraft:chest"])
  expect(g.nodes.find((n) => n.id === "group:minecraft:chest")).toMatchObject({ holderKind: "BLOCK", folded: 500 })
  expect(g.edges.find((x) => x.id === "PLAYER/bob>group:minecraft:chest")!.amount).toBe(500 * 64)
  const [newest] = g.groups.get("group:minecraft:chest")!.members
  expect(newest.where).toBe("w 499 64 0")
  expect(Object.fromEntries(newest.items)).toEqual({ "minecraft:cobblestone": 65 })
})

test("a few chests stay on their own", () => {
  expect(buildGraph(chests(4), NO_FILTERS).nodes).toHaveLength(6)
})

const SHULKER = (i: number) => `SHULKER/box${i}`

/** Bob packs `n` shulker boxes and takes the stacks out again; Eve opens `shared` of them */
const packing = (n: number, shared = 0): Report => {
  const holders: Report["holders"] = { "PLAYER/bob": report.holders["PLAYER/bob"], "PLAYER/eve": { label: "Eve", kind: "PLAYER" } }
  const events: ReportEvent[] = []
  let seq = 1
  for (let i = 0; i < n; i++) {
    // different colours: not one type
    holders[SHULKER(i)] = { label: "Shulker Box", kind: "SHULKER", type: `minecraft:${i % 2 ? "red" : "blue"}_shulker_box` }
    events.push(e(seq++, { type: "TRANSFERRED", holder: "PLAYER/bob", other: SHULKER(i), item: "minecraft:stone", amount: 1728 }))
    events.push(e(seq++, { type: "TRANSFERRED", holder: SHULKER(i), other: "PLAYER/bob", item: "minecraft:stone", amount: 1728 }))
    if (i < shared) events.push(e(seq++, { type: "TRANSFERRED", holder: SHULKER(i), other: "PLAYER/eve", item: "minecraft:stone", amount: 1 }))
  }
  return { ...report, holders, events }
}

test("containers only the player touched fold into one plate, whatever their type", () => {
  const g = buildGraph(packing(8), NO_FILTERS)
  expect(g.nodes.map((n) => n.id).sort()).toEqual(["PLAYER/bob", "group:own"])
  expect(g.nodes.find((n) => n.id === "group:own")).toMatchObject({ label: "Own storage", folded: 8 })
})

test("a container another player opened stays on its own", () => {
  const g = buildGraph(packing(8, 2), NO_FILTERS)
  expect(g.nodes.filter((n) => n.holderKind === "SHULKER").map((n) => n.id).sort()).toEqual([SHULKER(0), SHULKER(1)])
  expect(g.nodes.find((n) => n.id === "group:own")).toMatchObject({ folded: 6 })
})

test("a pair that passed items back and forth is one link with what is left", () => {
  const trade: Report = {
    ...report,
    holders: { "PLAYER/bob": report.holders["PLAYER/bob"], "BLOCK/chest": report.holders["BLOCK/chest"] },
    events: [
      e(1, { type: "TRANSFERRED", holder: "PLAYER/bob", other: "BLOCK/chest", item: "minecraft:stone", amount: 100 }),
      e(2, { type: "TRANSFERRED", holder: "BLOCK/chest", other: "PLAYER/bob", item: "minecraft:stone", amount: 40 }),
      e(3, { type: "TRANSFERRED", holder: "BLOCK/chest", other: "PLAYER/bob", item: "minecraft:dirt", amount: 5 }),
    ],
  }
  const g = buildGraph(trade, NO_FILTERS)
  expect(g.edges.map((x) => x.id).sort()).toEqual(["BLOCK/chest>PLAYER/bob", "PLAYER/bob>BLOCK/chest"])
  expect(Object.fromEntries(g.edges.find((x) => x.id === "PLAYER/bob>BLOCK/chest")!.items)).toEqual({ "minecraft:stone": 60 })
  expect(Object.fromEntries(g.edges.find((x) => x.id === "BLOCK/chest>PLAYER/bob")!.items)).toEqual({ "minecraft:dirt": 5 })
  expect(g.byEdge.get("PLAYER/bob>BLOCK/chest")!.map((x) => x.seq)).toEqual([3, 2, 1])
})

test("a pair that nets out keeps one dashed edge, from the side that started it", () => {
  const g = buildGraph(packing(1), NO_FILTERS)
  expect(g.edges).toHaveLength(1)
  expect(g.edges[0]).toMatchObject({ id: `PLAYER/bob>${SHULKER(0)}`, settled: true, amount: 3456 })
})

test("counterparties list what came from and went to the root, before the traffic is netted", () => {
  const rows = buildGraph(packing(2), NO_FILTERS).counterparties
  expect(rows.map((r) => r.id).sort()).toEqual([SHULKER(0), SHULKER(1)]) // two boxes are too few to fold
  expect(Object.fromEntries(rows[0].received)).toEqual({ "minecraft:stone": 1728 })
  expect(Object.fromEntries(rows[0].gave)).toEqual({ "minecraft:stone": 1728 })
})

test("with grouping off, a base of chests is a node each and a pair that nets out stays two-way", () => {
  const off = { drops: true, groups: false, netting: true, visits: true }
  expect(buildGraph(chests(8), NO_FILTERS, new Set(), off).nodes).toHaveLength(10)
  expect(buildGraph(packing(8), NO_FILTERS, new Set(), off).nodes).toHaveLength(9)
})

test("with netting off, both directions of a pair keep their own traffic", () => {
  const g = buildGraph(packing(1), NO_FILTERS, new Set(), { drops: true, groups: true, netting: false, visits: true })
  expect(g.edges.map((x) => [x.id, x.amount, x.settled]).sort()).toEqual([
    [`PLAYER/bob>${SHULKER(0)}`, 1728, undefined],
    [`${SHULKER(0)}>PLAYER/bob`, 1728, undefined],
  ])
})

test("without merged visits, items coming back start a new node: bob -> chest -> bob -> furnace -> chest", () => {
  const chain: Report = {
    ...report,
    holders: { ...report.holders, "BLOCK/furnace": { label: "furnace", kind: "BLOCK" } },
    events: [
      e(1, { type: "TRANSFERRED", holder: "PLAYER/bob", other: "BLOCK/chest", item: "minecraft:iron_ore", amount: 8 }),
      e(2, { type: "TRANSFERRED", holder: "BLOCK/chest", other: "PLAYER/bob", item: "minecraft:iron_ore", amount: 8 }),
      e(3, { type: "TRANSFERRED", holder: "PLAYER/bob", other: "BLOCK/furnace", item: "minecraft:iron_ore", amount: 8 }),
      e(4, { type: "TRANSFERRED", holder: "BLOCK/furnace", other: "BLOCK/chest", item: "minecraft:iron_ingot", amount: 8 }),
    ],
  }
  const g = buildGraph(chain, NO_FILTERS, undefined, { ...FOLD_ALL, visits: false })
  expect(g.edges.map((x) => x.id).sort()).toEqual(
    ["BLOCK/chest>PLAYER/bob#2", "BLOCK/furnace>BLOCK/chest#2", "PLAYER/bob#2>BLOCK/furnace", "PLAYER/bob>BLOCK/chest"].sort(),
  )
  const later = g.nodes.find((n) => n.id === "PLAYER/bob#2")!
  expect(later).toMatchObject({ of: "PLAYER/bob", root: true, label: "Bob" })
  // the table still lists each counterparty once
  expect(g.counterparties.map((c) => c.id).sort()).toEqual(["BLOCK/chest", "BLOCK/furnace"])

  expect(buildGraph(chain, NO_FILTERS).nodes.map((n) => n.id).sort()).toEqual(["BLOCK/chest", "BLOCK/furnace", "PLAYER/bob"])
})
