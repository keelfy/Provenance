# Provenance web viewer

Receives report snapshots from the plugin and shows them as a board. Runs on bun.

```
bun install
PROVENANCE_SECRET=dev bun --bun run dev      # http://localhost:3000
PROVENANCE_SECRET=dev bun scripts/seed.ts    # prints a link to a sample report
bun test
```

| Variable | Default | |
| --- | --- | --- |
| `PROVENANCE_SECRET` | | Shared with the plugin's `web.secret`. Without it every request is refused. |
| `DATA_DIR` | `data` | Where `reports.db` lives. |
| `MAX_TTL_HOURS` | `72` | Cap on a report's lifetime, whatever the plugin asks for. |
| `REFRESH_WINDOW_MINUTES` | `10` | How long a link keeps working after its first read. |
| `MAX_BODY_MB` | `8` | Largest accepted report. |

Deploy with the `Dockerfile` behind a reverse proxy that terminates HTTPS and limits request size. Set the
plugin's `web.endpoint` to the public URL. The proxy must not rewrite the path: the signature covers it.
