import { expect, test } from "bun:test"
import { buildGraph, BURN, NO_FILTERS } from "./graph"
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
