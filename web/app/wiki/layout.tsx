import type { Metadata } from "next"
import Link from "next/link"
import { WikiNav, WikiPager } from "@/components/wiki/nav"

export const metadata: Metadata = {
  title: { template: "%s · Provenance wiki", default: "Provenance wiki" },
  description: "Item provenance for Paper servers: install, commands, configuration and the web viewer.",
  // Reports stay hidden; the documentation is meant to be found.
  robots: { index: true, follow: true },
}

export default function WikiLayout({ children }: LayoutProps<"/wiki">) {
  return (
    <div className="min-h-full">
      <header className="sticky top-0 z-10 border-b border-border bg-card/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-baseline gap-3 px-4 py-3">
          <Link href="/wiki" className="font-heading text-lg font-bold">
            Provenance
          </Link>
          <span className="text-sm text-muted-foreground">wiki</span>
        </div>
      </header>
      <div className="mx-auto max-w-6xl gap-10 px-4 md:grid md:grid-cols-[13rem_minmax(0,1fr)]">
        <aside className="-mx-4 overflow-x-auto border-b border-border px-4 py-4 md:sticky md:top-14 md:mx-0 md:h-[calc(100dvh-3.5rem)] md:overflow-y-auto md:border-b-0 md:px-0 md:py-8">
          <WikiNav />
        </aside>
        <main className="max-w-3xl py-8 md:py-10">
          <article>{children}</article>
          <WikiPager />
        </main>
      </div>
    </div>
  )
}
