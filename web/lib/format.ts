const words = (id: string) =>
  id
    .split("_")
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(" ")

/** `minecraft:netherite_ingot` -> `Netherite Ingot`; other namespaces stay visible: `Ruby Gem (mymod)`. */
export function itemName(key: string): string {
  const at = key.indexOf(":")
  if (at < 0) return words(key)
  const namespace = key.slice(0, at)
  const name = words(key.slice(at + 1))
  return namespace === "minecraft" ? name : `${name} (${namespace})`
}

/** The id a holder is known by, less important than its name: block coordinates or a short uuid. */
export function holderDetail(id: string, full = false): string {
  const at = id.indexOf("/")
  const kind = id.slice(0, at)
  const key = id.slice(at + 1)
  if (kind === "BLOCK") return key.replaceAll(":", " ")
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

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" })

/** "in 23 hours" / "in 12 minutes" */
export function formatIn(millis: number, now = Date.now()): string {
  const minutes = Math.round((millis - now) / 60_000)
  return Math.abs(minutes) >= 90
    ? rtf.format(Math.round(minutes / 60), "hour")
    : rtf.format(minutes, "minute")
}
