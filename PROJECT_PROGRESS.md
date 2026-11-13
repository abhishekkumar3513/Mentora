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


---

# SAARA early-warning / support enhancement (2026-11-13)

## Work completed
- **V3 migration** (additive): notification priority + student link, holidays, assignments, submissions, IA exams + IA exam attendance (separate from marks), support signals (with history), support categories (7, configurable owner/active), support requests + timeline events (STUDENT / INTERNAL visibility), faculty.is_counsellor, new settings (`signals.*`, `priority.*`).
- **Rule engine** `SupportSignalService`: attendance < threshold (75 exactly = no alert), overdue assignments, IA attendance (not-conducted never absent, zero score never absent, 0 attended = HIGH), consecutive scheduled absences (weekends/holidays skipped, 3 default), consolidated multi-signal summary. One ACTIVE signal per student+type, resolved when the condition clears, a new row on recurrence; notifications de-duplicated per signal (assignments: per assignment). Runs on every record change and every 5 minutes (`SignalScheduler`). The legacy per-day LOW/CONSECUTIVE notifications were removed from `AttendanceAlertService`.
- **Navigator / interventions** `SupportService`: student identity from the session only, 7 categories, owners, statuses, internal notes vs student feedback, follow-ups + once-only reminders, outcome required to resolve, confidential counselling tier (student, admin, counsellor-flagged faculty or assigned owner only).
- **Overview** `OverviewService`: real counts, average attendance (mean of per-student %, students without records excluded), distribution, metrics, Attention Required, filters (category, priority, search), mentor scope vs admin scope.
- **Records API** (assignments, submissions, IA exams + attendance, holidays) triggers re-evaluation.
- **Frontend**: Department Overview, filtered student list, Support Actions (pending / in progress / due / overdue / resolved), request detail, Student Support Navigator, student dashboard support panel, Academic Records, notification centre filters/priority, student-profile Support tab, admin Support Configuration, new thresholds in Settings.
- **Demo data** `SupportDemoSeeder`: students 1RN23IS061-065 (scenarios A-E), sem-5 assignments, IA schedule, submissions/IA attendance for existing demo students, mentor 2 flagged as demo counsellor.

## Tests executed
- Backend `mvn test`: 47/47 (14 unit, 13 integration, 20 new `SupportTests`).
- Frontend `npm test`: 17/17 (12 existing + 5 new); `tsc -b` and `vite build` succeed.
- Real-app browser run (Chrome via Playwright, backend + Vite dev server on a fresh throw-away DB): 27/28 scripted checks passed; the one failure was a script selector (screenshot confirms the request is listed). Only console errors were the intentional 403 from a cross-mentor URL probe.

## Known limitations
- Docker image build was not re-run in this session (Dockerfiles unchanged; Docker Desktop was started only for the database). Test jar builds.
- Demo mentors/students on the original dev database may have non-default passwords; the new scenario students use `Student@123`.
- No HOD role exists: administrators act as HOD/institution users; "Department/HOD" is an action-owner label, not a login.
- Parent interaction only records a request; nothing contacts parents. Gemini unchanged (still untested live).
- Notification push uses the existing STOMP channel; the pages refresh on it, verified by code path and unit tests, not by a timed live-push test.
- IA rule interpretation: alert when attended + not-yet-recorded exams can no longer reach the minimum (so IA3 pending after two absences still waits).

## Resume
`docker start mentor-db-1`; backend `mvn spring-boot:run` (port 8080, or `SERVER_PORT=8090`); frontend `cd frontend; VITE_BACKEND=http://localhost:8090 npm run dev`.
