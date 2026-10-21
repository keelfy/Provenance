"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"
import { WIKI, WIKI_PAGES } from "@/lib/wiki"

export function WikiNav() {
  const path = usePathname()
  return (
    <nav aria-label="Wiki" className="flex gap-6 md:flex-col">
      {WIKI.map((section) => (
        <div key={section.title} className="shrink-0">
          <p className="mb-1.5 text-xs font-semibold tracking-wider text-muted-foreground uppercase">{section.title}</p>
          <ul className="flex gap-1 md:flex-col md:gap-0.5">
            {section.pages.map((page) => {
              const active = page.href === path
              return (
                <li key={page.href}>
                  <Link
                    href={page.href}
                    aria-current={active ? "page" : undefined}
                    className={cn(
                      "block rounded-md border-l-2 border-transparent px-2.5 py-1 text-sm whitespace-nowrap transition-colors hover:bg-accent",
                      active && "border-copper bg-accent font-semibold",
                    )}
                  >
                    {page.title}
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </nav>
  )
}

export function WikiPager() {
  const path = usePathname()
  const index = WIKI_PAGES.findIndex((page) => page.href === path)
  if (index < 0) return null
  const previous = WIKI_PAGES[index - 1]
  const next = WIKI_PAGES[index + 1]
  return (
    <nav aria-label="Pages" className="mt-16 grid grid-cols-2 gap-4 border-t border-border pt-6 text-sm">
      {previous ? (
        <Link href={previous.href} className="group rounded-lg border border-border p-3 hover:bg-card">
          <span className="text-muted-foreground">Previous</span>
          <span className="block font-semibold group-hover:text-lapis">{previous.title}</span>
        </Link>
      ) : (
        <span />
      )}
      {next ? (
        <Link href={next.href} className="group rounded-lg border border-border p-3 text-right hover:bg-card">
          <span className="text-muted-foreground">Next</span>
          <span className="block font-semibold group-hover:text-lapis">{next.title}</span>
        </Link>
      ) : null}
    </nav>
  )
}
