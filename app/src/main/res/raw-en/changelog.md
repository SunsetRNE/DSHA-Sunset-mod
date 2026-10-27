# DSHA 0.2.1-rc.3-20261027.1435-dsh0.2.1-rc.3 / build204

- Environment admission: plugin queries, DSH start and terminal creation now use shared environment leases; plugin list scans no longer block DSH start/stop or terminal open/create.
- Read-only queries: list detection only verifies ready tools and does not install or repair Python, pnpm or managed runtime assets; keep the old snapshot and report maintenance when tools are incomplete.
- User priority: DSH start cancels automatic plugin queries and uses a high-priority startup script lane; permissions and exit verification remain unchanged.
- Build identity: versionCode 204; test package dsh.android.client.SunsetRNE with the CI test certificate, not the E7E3 release identity.
- Terminal: the first tap fix and bundled dsh-web-mobile 3.2.2 are retained.
