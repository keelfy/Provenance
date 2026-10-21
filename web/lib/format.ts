const words = (id: string) =>
  id
    .split("_")
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(" ")

/**
 * `minecraft:netherite_ingot` -> `Netherite Ingot`; other namespaces stay visible: `Ruby Gem (mymod)`.
 * A stack with its own components carries a hash: `minecraft:diamond_sword#3f9a…` -> `Diamond Sword #3f9a`.
 */
export function itemName(key: string): string {
  const hash = key.indexOf("#")
  if (hash >= 0) return `${itemName(key.slice(0, hash))} #${key.slice(hash + 1, hash + 5)}`
  const at = key.indexOf(":")
  if (at < 0) return words(key)
  const namespace = key.slice(0, at)
  const name = words(key.slice(at + 1))
  return namespace === "minecraft" ? name : `${name} (${namespace})`
}

/** "445 × Diamond +9": the largest item and how many others ride along. */
export function stackLabel(items: ReadonlyMap<string, number>): string {
  let top: [string, number] | undefined
  let amount = 0
  for (const entry of items) {
    amount += entry[1]
    if (!top || entry[1] > top[1]) top = entry
  }
  if (!top) return "no items"
  const more = items.size - 1
  return `${amount} × ${itemName(top[0])}${more > 0 ? ` +${more}` : ""}`
}

/** "445 × Diamond, 9 × Iron Ingot": every item, the most numerous first. */
export const itemList = (items: ReadonlyMap<string, number>) =>
  [...items]
    .sort((a, b) => b[1] - a[1])
    .map(([item, n]) => `${n} × ${itemName(item)}`)
    .join(", ")

/** The id a holder is known by, less important than its name: block coordinates or a short uuid. */
export function holderDetail(id: string, full = false): string {
  const at = id.indexOf("/")
  const kind = id.slice(0, at)
  const key = id.slice(at + 1)
  if (kind === "BLOCK") return key.replaceAll(":", " ")
  // creatures one player killed in one chunk: "mob:minecraft:zombie@world:1:-4#p:…" is all of those zombies
  const chunk = key.startsWith("mob:") ? key.indexOf("@") : -1
  if (chunk >= 0) return `chunk ${key.slice(chunk + 1).split("#")[0].replaceAll(":", " ")}`
  return full ? key : key.slice(0, 8)
}

const time = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
})

export const formatTime = (millis: number) => time.format(millis)

/** "Oct 18, 10:02:01" for one moment, "Oct 18, 10:02:01 – 10:58:40" for a span */
export function formatSpan(from: number, until?: number): string {
  if (until === undefined || until <= from) return formatTime(from)
  const sameDay = new Date(from).toDateString() === new Date(until).toDateString()
  return `${formatTime(from)} – ${sameDay ? clock.format(until) : formatTime(until)}`
}

const clock = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" })

/** "world chunk 1, -4" */
export const formatChunk = (chunk: { world: string; x: number; z: number }) =>
  `${chunk.world} chunk ${chunk.x}, ${chunk.z}`

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" })

/** "in 23 hours" / "in 12 minutes" */
export function formatIn(millis: number, now = Date.now()): string {
  const minutes = Math.round((millis - now) / 60_000)
  return Math.abs(minutes) >= 90
    ? rtf.format(Math.round(minutes / 60), "hour")
    : rtf.format(minutes, "minute")
}
