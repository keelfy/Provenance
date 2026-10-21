import Link from "next/link"

export default function Home() {
  return (
    <main className="grid h-full place-items-center p-6">
      <div className="max-w-sm space-y-3 text-center">
        <p className="text-muted-foreground">
          Reports open from links that admins get in game with <code>/prov web</code>.
        </p>
        <Link href="/wiki" className="font-medium text-lapis underline underline-offset-2">
          Read the wiki
        </Link>
      </div>
    </main>
  )
}
