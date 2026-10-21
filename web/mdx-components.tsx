import type { MDXComponents } from "mdx/types"
import Link from "next/link"
import type { ComponentProps } from "react"

/** Internal links go through the router, outside ones open in a new tab */
function Anchor({ href = "", ...props }: ComponentProps<"a">) {
  const className = "font-medium text-lapis underline decoration-lapis/30 underline-offset-2 hover:decoration-lapis"
  if (href.startsWith("/") || href.startsWith("#")) return <Link href={href} className={className} {...props} />
  return <a href={href} target="_blank" rel="noreferrer" className={className} {...props} />
}

const components = {
  h1: (props) => <h1 className="mb-4 text-4xl font-extrabold tracking-tight text-balance" {...props} />,
  h2: (props) => (
    <h2 className="mt-12 mb-3 scroll-mt-20 border-b border-border pb-1.5 text-2xl font-bold tracking-tight" {...props} />
  ),
  h3: (props) => <h3 className="mt-8 mb-2 scroll-mt-20 text-lg font-bold" {...props} />,
  p: (props) => <p className="my-4 leading-7 text-pretty" {...props} />,
  a: Anchor,
  ul: (props) => <ul className="my-4 list-disc space-y-1.5 pl-6 leading-7 marker:text-copper" {...props} />,
  ol: (props) => <ol className="my-4 list-decimal space-y-1.5 pl-6 leading-7 marker:text-muted-foreground" {...props} />,
  strong: (props) => <strong className="font-semibold" {...props} />,
  blockquote: (props) => (
    <blockquote
      className="my-6 rounded-r-lg border-l-4 border-copper bg-card px-4 py-1 [&>p]:my-2.5"
      {...props}
    />
  ),
  hr: () => <hr className="my-10 border-border" />,
  code: (props) => (
    <code
      className="rounded bg-muted px-1.5 py-0.5 font-mono text-[0.85em] [pre_&]:bg-transparent [pre_&]:p-0 [pre_&]:text-inherit"
      {...props}
    />
  ),
  pre: (props) => (
    <pre
      className="my-5 overflow-x-auto rounded-lg bg-primary px-4 py-3 font-mono text-sm leading-6 text-primary-foreground"
      {...props}
    />
  ),
  table: (props) => (
    <div className="my-6 overflow-x-auto rounded-lg border border-border">
      <table className="w-full border-collapse text-sm" {...props} />
    </div>
  ),
  th: (props) => <th className="border-b border-border bg-card px-3 py-2 text-left font-semibold" {...props} />,
  td: (props) => <td className="border-b border-border px-3 py-2 align-top [tr:last-child_&]:border-b-0" {...props} />,
} satisfies MDXComponents

export function useMDXComponents(): MDXComponents {
  return components
}
