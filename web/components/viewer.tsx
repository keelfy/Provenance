"use client"

import dynamic from "next/dynamic"
import { useCallback, useDeferredValue, useEffect, useMemo, useState } from "react"
import type { Selection } from "@/components/board/board"
import { Details } from "@/components/details"
import { Toolbar } from "@/components/toolbar"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { buildGraph, facets, NO_FILTERS, type Filters } from "@/lib/graph"
import { foldDrops } from "@/lib/ground"
import type { Report } from "@/lib/report"

// The canvas pulls in xyflow and dagre; the shell and error states do not need them (`bundle-dynamic-imports`).
const Board = dynamic(() => import("@/components/board/board"), {
  ssr: false,
  loading: () => <Skeleton className="size-full rounded-none" />,
})

type State =
  | { phase: "loading" }
  | { phase: "ready"; report: Report }
  | { phase: "error"; reason: "no-token" | "missing" | "gone" | "offline" }

const MESSAGES = {
  "no-token": {
    title: "This page needs a report link",
    hint: "Run /prov web history in game and open the link it gives you.",
  },
  missing: {
    title: "Report not found",
    hint: "The link is mistyped or an admin revoked it. Run the command in game again.",
  },
  gone: {
    title: "This link has expired or was already opened",
    hint: "Links work for a short time after the first visit. Run the command in game again.",
  },
  offline: {
    title: "Could not reach the server",
    hint: "Check your connection and reload the page.",
  },
} as const

async function load(token: string, signal: AbortSignal): Promise<State> {
  if (!token) return { phase: "error", reason: "no-token" }
  try {
    const response = await fetch(`/api/reports/${encodeURIComponent(token)}`, { cache: "no-store", signal })
    if (response.ok) return { phase: "ready", report: await response.json() }
    return { phase: "error", reason: response.status === 410 ? "gone" : "missing" }
  } catch {
    return { phase: "error", reason: "offline" }
  }
}

export function Viewer() {
  const [state, setState] = useState<State>({ phase: "loading" })

  useEffect(() => {
    const abort = new AbortController()
    load(location.hash.slice(1), abort.signal).then((next) => {
      if (!abort.signal.aborted) setState(next)
    })
    return () => abort.abort()
  }, [])

  if (state.phase === "loading") return <Skeleton className="h-full w-full rounded-none" />
  if (state.phase === "error") return <Failure reason={state.reason} />
  return <Workspace report={state.report} />
}

function Failure({ reason }: { reason: keyof typeof MESSAGES }) {
  const { title, hint } = MESSAGES[reason]
  return (
    <main className="grid h-full place-items-center p-6">
      <div className="max-w-md">
        <h1 className="text-2xl font-bold">{title}</h1>
        <p className="mt-2 text-muted-foreground">{hint}</p>
        {reason === "offline" ? (
          <Button className="mt-4" onClick={() => location.reload()}>
            Reload
          </Button>
        ) : null}
      </div>
    </main>
  )
}

function Workspace({ report: original }: { report: Report }) {
  // Dropped items are plumbing, not part of the story (see `foldDrops`)
  const report = useMemo(() => foldDrops(original), [original])
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [selection, setSelection] = useState<Selection>(null)
  /** Groups of creatures or blocks shown one by one, by group id */
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set())

  const toggle = useCallback((group: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (!next.delete(group)) next.add(group)
      return next
    })
  }, [])
  // Opens a group and picks one entry of it, from the list in the details
  const reveal = useCallback((group: string, member: string) => {
    setExpanded((prev) => new Set(prev).add(group))
    setSelection({ kind: "node", id: member })
  }, [])

  // Typing in a filter must stay responsive while the graph and layout recompute (`rerender-use-deferred-value`).
  const deferred = useDeferredValue(filters)
  const options = useMemo(() => facets(report), [report])
  const graph = useMemo(() => buildGraph(report, deferred, expanded), [report, deferred, expanded])

  const present =
    selection &&
    (selection.kind === "node"
      ? graph.nodes.some((n) => n.id === selection.id)
      : graph.edges.some((e) => e.id === selection.id))
  const active = present ? selection : null

  return (
    <div className="flex h-full flex-col">
      <Toolbar report={original} facets={options} filters={filters} onFilters={setFilters} />
      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <div className="relative min-h-[55vh] flex-1">
          <Board graph={graph} selection={active} onSelect={setSelection} onToggle={toggle} />
        </div>
        <Details report={report} graph={graph} selection={active} onSelect={setSelection} onToggle={toggle} onReveal={reveal} />
      </div>
    </div>
  )
}
