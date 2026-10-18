/** `Report` v1, produced by the plugin's `ReportJson`. Keep both in sync (see WEB-PLAN.md §1). */

export type HolderKind = "PLAYER" | "BLOCK" | "ENTITY" | (string & {})

/** `type` is the namespaced block or entity type ("minecraft:zombie"); `label` its custom name ("Steve") or else the type's name */
export type Holder = { label: string; kind: HolderKind; type?: string }

/** What a block or entity was when the event happened, when that differs from its entry in `holders` */
export type HolderAs = { label: string; type?: string }

type Base = {
  seq: number
  time: number
  cause: string
  holder: string
  holderAs?: HolderAs
  suspicious: boolean
}

export type ReportEvent = Base &
  (
    | { type: "MINTED"; item: string; amount: number; origin: string }
    | {
        type: "TRANSFERRED"
        other: string
        otherAs?: HolderAs
        item: string
        amount: number
        custody?: Record<string, number>
      }
    | { type: "BURNED"; item: string; amount: number }
    | {
        type: "CONVERTED"
        inputs: Record<string, number>
        outputs: Record<string, number>
      }
    | { type: "CLEARED" }
  )

export type Report = {
  v: 1
  created: number
  expires: number
  query: { kind: string; root: string; item?: string; depth: number }
  holders: Record<string, Holder>
  events: ReportEvent[]
  truncated: boolean
}
