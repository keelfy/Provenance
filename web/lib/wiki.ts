type Page = { href: string; title: string }

/** Wiki pages in reading order: the sidebar and the previous / next links follow it */
export const WIKI: { title: string; pages: Page[] }[] = [
  {
    title: "Start",
    pages: [
      { href: "/wiki", title: "Overview" },
      { href: "/wiki/install", title: "Installation" },
    ],
  },
  {
    title: "Use",
    pages: [
      { href: "/wiki/concepts", title: "How it works" },
      { href: "/wiki/commands", title: "Commands" },
      { href: "/wiki/moderation", title: "Moderation recipes" },
    ],
  },
  {
    title: "Set up",
    pages: [
      { href: "/wiki/configuration", title: "Configuration" },
      { href: "/wiki/web-viewer", title: "Web viewer" },
      { href: "/wiki/networks", title: "Server networks" },
    ],
  },
  {
    title: "Reference",
    pages: [{ href: "/wiki/limitations", title: "Known limitations" }],
  },
]

export const WIKI_PAGES: Page[] = WIKI.flatMap((section) => section.pages)
