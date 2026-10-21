import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { formatIn, holderDetail, itemName } from "@/lib/format"
import type { Filters, Folding } from "@/lib/graph"
import type { Report } from "@/lib/report"

const ALL = "all"

function Pick({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: string | null
  options: { value: string; label: string }[]
  onChange: (value: string | null) => void
}) {
  const items = [{ value: ALL, label }, ...options]
  return (
    <Select
      items={items}
      value={value ?? ALL}
      onValueChange={(next) => onChange(next === ALL ? null : next)}
    >
      <SelectTrigger size="sm" aria-label={label} className="min-w-32">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {items.map((o) => (
          <SelectItem key={o.value} value={o.value}>
            {o.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

export type View = "board" | "table"

const HOUR = 3_600_000

/** How far back to read, counted from when the report was made */
const PERIODS = [
  { value: String(24 * HOUR), label: "Last 24 hours" },
  { value: String(7 * 24 * HOUR), label: "Last 7 days" },
]

const FOLDS: { key: keyof Folding; label: string; hint: string }[] = [
  { key: "drops", label: "Fold drops", hint: "Show items taken from the ground as moving straight to whoever took them" },
  { key: "groups", label: "Group similar", hint: "Show many mobs, chests or boxes of one type as one plate" },
  { key: "visits", label: "Merge visits", hint: "Show each holder as one node, not a new node each time items come back to it" },
  { key: "netting", label: "Net two-way", hint: "Show items passed back and forth between two holders as one link with what is left" },
]

type Props = {
  report: Report
  /** The table lists a player's counterparties; other roots only have the board */
  view: View | null
  onView: (view: View) => void
  facets: { items: string[]; causes: string[] }
  filters: Filters
  onFilters: (filters: Filters) => void
  folding: Folding
  onFolding: (folding: Folding) => void
}

export function Toolbar({ report, view, onView, facets, filters, onFilters, folding, onFolding }: Props) {
  const root = report.holders[report.query.root]?.label ?? report.query.root
  return (
    <header className="flex flex-wrap items-center gap-x-6 gap-y-3 border-b border-border bg-card px-4 py-3">
      <div className="min-w-0">
        <h1 className="truncate text-xl leading-tight font-bold">
          {root}
          <span className="ml-2 text-sm font-normal text-muted-foreground">
            {holderDetail(report.query.root, true)}
          </span>
        </h1>
        <p className="mt-0.5 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>{report.query.item ? itemName(report.query.item) : "All items"}</span>
          <span>{report.query.depth} hops</span>
          <span>Link expires {formatIn(report.expires)}</span>
        </p>
      </div>

      {report.truncated ? (
        <Badge variant="outline" className="border-copper text-copper">
          Newest {report.events.length} events only
        </Badge>
      ) : null}

      <div className="ml-auto flex flex-wrap items-center gap-3">
        {view ? (
          <div className="flex gap-1" role="group" aria-label="View">
            {(["board", "table"] as const).map((v) => (
              <Button key={v} size="sm" variant={view === v ? "default" : "outline"} onClick={() => onView(v)}>
                {v === "board" ? "Board" : "Table"}
              </Button>
            ))}
          </div>
        ) : null}
        <Pick
          label="Any time"
          value={filters.since === null ? null : String(report.created - filters.since)}
          options={PERIODS}
          onChange={(period) => onFilters({ ...filters, since: period === null ? null : report.created - Number(period) })}
        />
        <Pick
          label="Any item"
          value={filters.item}
          options={facets.items.map((i) => ({ value: i, label: itemName(i) }))}
          onChange={(item) => onFilters({ ...filters, item })}
        />
        <Pick
          label="Any cause"
          value={filters.cause}
          options={facets.causes.map((c) => ({ value: c, label: c }))}
          onChange={(cause) => onFilters({ ...filters, cause })}
        />
        <label className="flex items-center gap-2 text-sm">
          <Switch
            checked={filters.suspiciousOnly}
            onCheckedChange={(suspiciousOnly) => onFilters({ ...filters, suspiciousOnly })}
          />
          Suspicious only
        </label>
        <div className="flex flex-wrap items-center gap-3" role="group" aria-label="Folding">
          {FOLDS.map(({ key, label, hint }) => (
            <label key={key} title={hint} className="flex items-center gap-2 text-sm">
              <Switch checked={folding[key]} onCheckedChange={(on) => onFolding({ ...folding, [key]: on })} />
              {label}
            </label>
          ))}
        </div>
      </div>
    </header>
  )
}
