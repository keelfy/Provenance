import type { Metadata } from "next"
import "@fontsource-variable/bricolage-grotesque"
import "@fontsource-variable/hanken-grotesk"
import "./globals.css"

export const metadata: Metadata = {
  title: "Provenance",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
}

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="h-full">
        {children}
      </body>
    </html>
  )
}
