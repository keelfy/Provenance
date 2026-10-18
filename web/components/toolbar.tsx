import { Badge } from "@/components/ui/badge"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { formatIn, holderDetail, itemName } from "@/lib/format"
import type { Filters } from "@/lib/graph"
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

type Props = {
  report: Report
  facets: { items: string[]; causes: string[] }
  filters: Filters
  onFilters: (filters: Filters) => void
}

export function Toolbar({ report, facets, filters, onFilters }: Props) {
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
      </div>
    </header>
  )
}
