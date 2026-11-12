# PROJECT_PROGRESS

_Last updated: 2026-11-13_

## Current phase
All 24 planned phases implemented; final verification complete. Remaining work = live-Gemini validation (needs API key) and polish.

## Completed modules
Database + Flyway · Auth/JWT/RBAC · student & mentor management · attendance · internal assessment calculator · semester results · student dashboard · mentor dashboard + grids + student profile · analytics · leave + documents + audit · STOMP chat · moderation/anti-spam · notifications · Gemini message understanding · Gemini mentor assistant · admin (CRUD, assignments, settings) · CSV/XLSX import · demo seed · Docker Compose · README.

## Incomplete / not verified
- Live Gemini calls (no API key in this environment): code paths tested with mocks only.
- Streaming assistant responses (not implemented).
- No browser-based visual QA was performed; UI verified by type-check, production build, component tests and API/WebSocket e2e only.

## Known bugs / caveats
- `scripts/e2e.mjs` is not re-runnable on the same data (intended moderation restriction + alert de-duplication).
- Running vitest from a Git-Bash `/c/...` path fails ("failed to find the runner"); use PowerShell/CMD path form.
- Pre-existing unrelated containers on the dev machine use ports 80/8080 (`mentora-*`); this project uses 3000/8081/5433.

## Build / test status (executed)
- Backend `mvn test`: 26/26 passed (14 unit, 12 integration on PostgreSQL).
- Frontend `npm test`: 12/12 passed; `tsc -b` + `vite build` succeed.
- Docker Compose (db + backend + frontend): built, healthy; e2e script 100% pass through nginx on :3000.

## Resume instructions
1. `docker compose up -d` (or see README §6 for local dev).
2. Add `GEMINI_API_KEY` to `.env`, restart backend, test the assistant and message summaries live.
3. Open items above.
