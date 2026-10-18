import { Database } from "bun:sqlite"
import { createHash, randomBytes } from "node:crypto"
import { mkdirSync } from "node:fs"
import { dirname } from "node:path"
import { env } from "./env"

const DAY = 86_400_000

let db: Database | undefined

function database(): Database {
  if (db) return db
  const { dbPath } = env()
  mkdirSync(dirname(dbPath), { recursive: true })
  db = new Database(dbPath, { create: true })
  db.run("PRAGMA journal_mode = WAL")
  // Only sha256(token) is stored: a leaked database yields no working links.
  db.run(`CREATE TABLE IF NOT EXISTS report (
    hash TEXT PRIMARY KEY,
    body TEXT,
    expires INTEGER NOT NULL,
    first_seen INTEGER
  )`)
  return db
}

const hash = (token: string) => createHash("sha256").update(token).digest("hex")

/** Stores a snapshot and returns its token (128 bits, base64url). */
export function create(body: string, expires: number, now = Date.now()) {
  const store = database()
  store.run("DELETE FROM report WHERE expires < ?", [now - DAY])
  const token = randomBytes(16).toString("base64url")
  store.run("INSERT INTO report (hash, body, expires) VALUES (?, ?, ?)", [
    hash(token),
    body,
    expires,
  ])
  return token
}

export type Opened =
  | { status: "ok"; body: string }
  | { status: "missing" | "gone" }

/**
 * The first read starts the refresh window; once it closes the link is spent. A spent or expired row keeps its
 * hash (without the body) so the viewer can tell "gone" from "never existed" until it is purged.
 */
export function read(token: string, now = Date.now()): Opened {
  const store = database()
  const { refreshWindow } = env()
  return store.transaction((): Opened => {
    const row = store
      .query<
        { body: string | null; expires: number; first_seen: number | null },
        [string]
      >("SELECT body, expires, first_seen FROM report WHERE hash = ?")
      .get(hash(token))
    if (!row) return { status: "missing" }
    if (row.body === null) return { status: "gone" }

    const spent = row.first_seen !== null && now > row.first_seen + refreshWindow
    if (spent || now > row.expires) {
      store.run("UPDATE report SET body = NULL WHERE hash = ?", [hash(token)])
      return { status: "gone" }
    }
    if (row.first_seen === null) {
      store.run("UPDATE report SET first_seen = ? WHERE hash = ?", [now, hash(token)])
    }
    return { status: "ok", body: row.body }
  })()
}

/** @returns whether a report with this token existed */
export function revoke(token: string): boolean {
  return database().run("DELETE FROM report WHERE hash = ?", [hash(token)]).changes > 0
}
