import { NextResponse } from "next/server"
import { env, TOKEN } from "@/lib/server/env"
import { read, revoke } from "@/lib/server/reports"
import { verify } from "@/lib/server/signature"

const json = (body: object, status: number) =>
  NextResponse.json(body, { status, headers: { "Cache-Control": "no-store" } })

export async function GET(_: Request, ctx: RouteContext<"/api/reports/[token]">) {
  const { token } = await ctx.params
  if (!TOKEN.test(token)) return json({ error: "missing" }, 404)

  const result = read(token)
  if (result.status !== "ok") return json({ error: result.status }, result.status === "gone" ? 410 : 404)
  return new Response(result.body, {
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  })
}

export async function DELETE(request: Request, ctx: RouteContext<"/api/reports/[token]">) {
  const { token } = await ctx.params
  if (!verify(env().secret, request, await request.text())) return json({ error: "bad signature" }, 401)
  if (!TOKEN.test(token) || !revoke(token)) return json({ error: "missing" }, 404)
  return json({}, 200)
}
