import { createHmac, timingSafeEqual } from "node:crypto"

const SKEW = 5 * 60_000

/**
 * Checks `X-Provenance-Signature`: hex HMAC-SHA256 of `timestamp \n path \n body` under the shared secret,
 * with a timestamp no older (or newer) than five minutes.
 */
export function verify(
  secret: string,
  request: Request,
  body: string,
  now = Date.now(),
): boolean {
  if (!secret) return false
  const timestamp = request.headers.get("x-provenance-timestamp") ?? ""
  const signature = request.headers.get("x-provenance-signature") ?? ""
  if (!/^\d{10,16}$/.test(timestamp)) return false
  if (Math.abs(now - Number(timestamp)) > SKEW) return false

  const path = new URL(request.url).pathname
  const expected = createHmac("sha256", secret)
    .update(`${timestamp}\n${path}\n${body}`)
    .digest()
  const given = Buffer.from(signature, "hex")
  return given.length === expected.length && timingSafeEqual(given, expected)
}
