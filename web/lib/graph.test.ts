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
