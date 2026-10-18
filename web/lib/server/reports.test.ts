import { beforeAll, expect, test } from "bun:test"
import { mkdtempSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

beforeAll(() => {
  process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "prov-"))
  process.env.REFRESH_WINDOW_MINUTES = "10"
})

const MIN = 60_000

test("a link burns after the refresh window that its first read opens", async () => {
  const { create, read } = await import("./reports")
  const token = create("{}", 1_000 * MIN, 0)

  expect(read(token, 5 * MIN).status).toBe("ok") // first read starts the window
  expect(read(token, 14 * MIN).status).toBe("ok") // refresh inside it
  expect(read(token, 16 * MIN).status).toBe("gone") // window closed
  expect(read(token, 17 * MIN).status).toBe("gone")
})

test("an unread link expires, and a revoked one is unknown", async () => {
  const { create, read, revoke } = await import("./reports")
  const old = create("{}", 10 * MIN, 0)
  expect(read(old, 11 * MIN).status).toBe("gone")

  const token = create("{}", 1_000 * MIN, 0)
  expect(revoke(token)).toBe(true)
  expect(revoke(token)).toBe(false)
  expect(read(token, MIN).status).toBe("missing")
})
