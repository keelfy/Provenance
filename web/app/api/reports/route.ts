import { NextResponse } from "next/server"
import { env } from "@/lib/server/env"
import { create } from "@/lib/server/reports"
import { verify } from "@/lib/server/signature"

const json = (body: object, status: number) => NextResponse.json(body, { status })

export async function POST(request: Request) {
  const { secret, maxBody, maxTtl } = env()
  if (!secret) return json({ error: "PROVENANCE_SECRET is not set" }, 503)

  const length = Number(request.headers.get("content-length") ?? 0)
  if (length > maxBody) return json({ error: "report too large" }, 413)

  const body = await request.text()
  if (body.length > maxBody) return json({ error: "report too large" }, 413)
  if (!verify(secret, request, body)) return json({ error: "bad signature" }, 401)

  let report: { v?: unknown; expires?: unknown; events?: unknown; holders?: unknown }
  try {
    report = JSON.parse(body)
  } catch {
    return json({ error: "not JSON" }, 400)
  }
  if (report.v !== 1 || !Array.isArray(report.events) || typeof report.holders !== "object") {
    return json({ error: "unsupported report" }, 400)
  }

  const now = Date.now()
  const asked = typeof report.expires === "number" ? report.expires : now + maxTtl
  const expires = Math.min(asked, now + maxTtl)
  return json({ token: create(body, expires, now), expires }, 201)
}
