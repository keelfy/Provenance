import { expect, test } from "bun:test"
import { foldDrops, GROUND } from "./ground"
import type { Report, ReportEvent } from "./report"

const e = (seq: number, rest: Record<string, unknown>) =>
  ({ seq, time: seq, cause: "pickup", suspicious: false, ...rest }) as ReportEvent

const drop = (id: string) => ({ [`ENTITY/${id}`]: { label: "Item", kind: "ENTITY", type: "minecraft:item" } })
const LOG = "minecraft:birch_log"
const amount = (x: ReportEvent) => ("amount" in x ? x.amount : 0)

const report = (events: ReportEvent[]): Report => ({
  v: 1,
  created: 0,
  expires: 1,
  query: { kind: "history.player", root: "PLAYER/bob", depth: 2 },
  holders: {
    "PLAYER/bob": { label: "Bob", kind: "PLAYER" },
    "PLAYER/amy": { label: "Amy", kind: "PLAYER" },
    ...drop("a"),
    ...drop("b"),
  },
  events,
  truncated: false,
})

test("mined, dropped and picked up items go straight to the one who took them", () => {
  const folded = foldDrops(
    report([
      e(1, { type: "MINTED", cause: "mine", holder: "ENTITY/a", item: LOG, amount: 1, origin: "Bob" }),
      e(2, { type: "TRANSFERRED", holder: "ENTITY/a", other: "PLAYER/bob", item: LOG, amount: 1 }),
      e(3, { type: "TRANSFERRED", cause: "drop", holder: "PLAYER/bob", other: "ENTITY/b", item: LOG, amount: 1 }),
      e(4, { type: "TRANSFERRED", holder: "ENTITY/b", other: "PLAYER/amy", item: LOG, amount: 1 }),
    ]),
  )
  expect(folded.events.map((x) => [x.seq, x.type, x.holder, "other" in x ? x.other : null])).toEqual([
    [1, "MINTED", "PLAYER/bob", null],
    [4, "TRANSFERRED", "PLAYER/bob", "PLAYER/amy"],
  ])
})

test("a stack picked up in parts is shared between its sources, oldest first", () => {
  const folded = foldDrops(
    report([
      e(1, { type: "MINTED", cause: "mine", holder: "ENTITY/a", item: LOG, amount: 1, origin: "Bob" }),
      e(2, { type: "TRANSFERRED", cause: "drop", holder: "PLAYER/amy", other: "ENTITY/a", item: LOG, amount: 2 }),
      e(3, { type: "TRANSFERRED", holder: "ENTITY/a", other: "PLAYER/bob", item: LOG, amount: 2 }),
    ]),
  )
  expect(folded.events.map((x) => [x.holder, "other" in x ? x.other : null, amount(x)])).toEqual([
    ["PLAYER/bob", null, 1], // mined
    ["PLAYER/amy", "PLAYER/bob", 1], // the part Bob took
    ["PLAYER/amy", GROUND, 1], // the part still lying there
  ])
})

test("items nobody picked up stay on the ground; taking your own drop back moves nothing", () => {
  const folded = foldDrops(
    report([
      e(1, { type: "TRANSFERRED", cause: "drop", holder: "PLAYER/bob", other: "ENTITY/a", item: LOG, amount: 3 }),
      e(2, { type: "TRANSFERRED", holder: "ENTITY/a", other: "PLAYER/bob", item: LOG, amount: 1 }),
    ]),
  )
  expect(folded.events.map((x) => [x.type, "other" in x ? x.other : x.holder, amount(x)])).toEqual([
    ["TRANSFERRED", GROUND, 2],
  ])
  expect(folded.holders[GROUND].label).toBe("Dropped items")
  expect(folded.holders["ENTITY/a"]).toBeUndefined()
})

test("a death's stacks all lie in one place", () => {
  const folded = foldDrops(
    report([
      e(1, { type: "TRANSFERRED", cause: "death", holder: "PLAYER/bob", other: "ENTITY/a", item: LOG, amount: 3 }),
      e(2, { type: "TRANSFERRED", cause: "death", holder: "PLAYER/bob", other: "ENTITY/b", item: "minecraft:diamond", amount: 5 }),
    ]),
  )
  expect(folded.events.map((x) => [x.holder, "other" in x ? x.other : null])).toEqual([
    ["PLAYER/bob", GROUND],
    ["PLAYER/bob", GROUND],
  ])
  expect(Object.keys(folded.holders).sort()).toEqual([GROUND, "PLAYER/amy", "PLAYER/bob"])
})

test("stacks that merged on the ground are followed to whoever picked the result up", () => {
  const folded = foldDrops(
    report([
      e(1, { type: "MINTED", cause: "mine", holder: "ENTITY/a", item: LOG, amount: 1, origin: "Bob" }),
      e(2, { type: "TRANSFERRED", cause: "drop", holder: "ENTITY/a", other: "ENTITY/b", item: LOG, amount: 1 }),
      e(3, { type: "TRANSFERRED", holder: "ENTITY/b", other: "PLAYER/bob", item: LOG, amount: 1 }),
    ]),
  )
  expect(folded.events.map((x) => [x.seq, x.type, x.holder])).toEqual([[1, "MINTED", "PLAYER/bob"]])
})
