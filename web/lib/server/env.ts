import { join } from "node:path"

const HOUR = 3_600_000

/** Read per call: the secret and paths come from the container environment at runtime. */
export function env() {
  return {
    secret: process.env.PROVENANCE_SECRET ?? "",
    dbPath: join(process.env.DATA_DIR ?? "data", "reports.db"),
    maxTtl: Number(process.env.MAX_TTL_HOURS ?? 72) * HOUR,
    refreshWindow: Number(process.env.REFRESH_WINDOW_MINUTES ?? 10) * 60_000,
    maxBody: Number(process.env.MAX_BODY_MB ?? 8) * 1024 * 1024,
  }
}

export const TOKEN = /^[A-Za-z0-9_-]{16,64}$/
