// Publishes a sample report to a local dev server: `PROVENANCE_SECRET=... bun scripts/seed.ts [base-url]`
import { createHmac } from "node:crypto"
import type { Report, ReportEvent } from "../lib/report"

const base = process.argv[2] ?? "http://localhost:3000"
const secret = process.env.PROVENANCE_SECRET ?? "dev"
const now = Date.now()
const P = (name: string) => `PLAYER/${name.toLowerCase()}`
const CHEST = "BLOCK/world:10:64:-3"
const FURNACE = "BLOCK/world:12:64:-3"

let seq = 100
const at = (minutesAgo: number, event: Record<string, unknown>) =>
  ({ seq: seq++, time: now - minutesAgo * 60_000, suspicious: false, ...event }) as ReportEvent

const report: Report = {
  v: 1,
  created: now,
  expires: now + 24 * 3_600_000,
  query: { kind: "history.player", root: P("Bob"), item: "minecraft:diamond", depth: 2 },
  holders: {
    [P("Bob")]: { label: "Bob", kind: "PLAYER" },
    [P("Mallory")]: { label: "Mallory", kind: "PLAYER" },
    [P("Alice")]: { label: "Alice", kind: "PLAYER" },
    [CHEST]: { label: "chest @ world 10 64 -3", kind: "BLOCK" },
    [FURNACE]: { label: "furnace @ world 12 64 -3", kind: "BLOCK" },
  },
  events: [
    at(240, { type: "MINTED", cause: "creative", holder: P("Mallory"), item: "minecraft:diamond", amount: 64, origin: "Creative", suspicious: true }),
    at(200, { type: "MINTED", cause: "mine", holder: P("Alice"), item: "minecraft:diamond", amount: 12, origin: "Alice mining" }),
    at(180, { type: "TRANSFERRED", cause: "container", holder: P("Mallory"), other: CHEST, item: "minecraft:diamond", amount: 64, custody: { Creative: 1 } }),
    at(120, { type: "TRANSFERRED", cause: "container", holder: CHEST, other: P("Bob"), item: "minecraft:diamond", amount: 40, custody: { Creative: 1 } }),
    at(100, { type: "TRANSFERRED", cause: "trade", holder: P("Alice"), other: P("Bob"), item: "minecraft:diamond", amount: 12, custody: { "Alice mining": 1 } }),
    at(60, { type: "TRANSFERRED", cause: "drop", holder: P("Bob"), other: P("Alice"), item: "minecraft:diamond", amount: 3, custody: { Creative: 0.75, "Alice mining": 0.25 } }),
    at(40, { type: "CONVERTED", cause: "craft", holder: P("Bob"), inputs: { "minecraft:diamond": 9 }, outputs: { "minecraft:diamond_block": 1 } }),
    at(10, { type: "BURNED", cause: "lava", holder: P("Bob"), item: "minecraft:diamond", amount: 5 }),
  ],
  truncated: true,
}

const body = JSON.stringify(report)
const timestamp = String(Date.now())
const signature = createHmac("sha256", secret).update(`${timestamp}\n/api/reports\n${body}`).digest("hex")
const response = await fetch(`${base}/api/reports`, {
  method: "POST",
  body,
  headers: { "X-Provenance-Timestamp": timestamp, "X-Provenance-Signature": signature },
})
if (!response.ok) throw new Error(`${response.status} ${await response.text()}`)
console.log(`${base}/view#${(await response.json()).token}`)
