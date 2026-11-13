# FEATURE_AUDIT

Audit date: 2026-11-13. Method: static inspection of backend (Spring Boot 3 / JPA / Flyway V1–V2), frontend routes (React + Vite), and the schema. The app was **not** run for this audit (Docker daemon is not running; see Environment).

## Stack found
- Backend: Java 21, Spring Boot, JPA, Flyway, JWT + role-based security, STOMP WebSocket (`/user/queue/notifications`). Package `com.mentora`.
- Frontend: React + TypeScript + Tailwind, roles STUDENT / MENTOR / ADMIN.
- DB: PostgreSQL (port 5433), 21 tables in `V1__init_schema.sql`, `V2` renames demo mentors.
- Existing tests: backend 14 unit + 12 integration, frontend 12 (per PROJECT_PROGRESS.md, not re-run here).

## Naming mismatch
The codebase is named **Mentora**, not SAARA. No screens named "Department Overview" or "Student Support Navigator" exist, and no screenshots were supplied. The brief's "existing screens" for these are therefore treated as missing.

## Classification

| # | Feature | Status | Evidence |
|---|---|---|---|
| 1 | Auth, JWT, student/mentor/admin roles | Fully implemented (not re-verified in this audit) | `AuthController`, `SecurityConfig`, `AccessService` |
| 2 | Mentor ↔ student assignment, mentor-only data scoping | Fully implemented | `mentor_assignments`, `AccessService.mentorOf` |
| 3 | Daily attendance (per subject, statuses incl. NC) and % calc | Fully implemented | `attendance`, `AttendanceCalc` |
| 4 | Attendance < threshold alert to mentor | Partially implemented | `AttendanceAlertService`: fires only if the student's day status ≠ NC; dedupe key is per **date** so it repeats daily; no resolve/re-open lifecycle; uses `<` (correct for 75 exactly). No alert history beyond notifications |
| 5 | Consecutive absence alert | Implemented but not matching spec | Default threshold is 2 (setting `attendance.consecutive_absence_days`) and rule is `>=`, spec needs 3; dedupe per date → repeats daily instead of updating one episode; holiday/weekend handling lives in `AttendanceCalc.daily` (unverified) |
| 6 | Assignments (entity, deadline, submission) | **Completely missing** | No table, entity, API or UI |
| 7 | Assignment non-submission alert | Completely missing | depends on #6 |
| 8 | IA marks (IA1–3, activities, absent flag) | Fully implemented | `marks`, `MarksCalculator` |
| 9 | IA exam attendance tracked separately from marks, NOT_CONDUCTED status, exam schedule | Completely missing | `marks.absent` is a boolean mixed with the marks row; no schedule |
| 10 | IA attendance < 2/3 alert, HIGH priority at 0 | Completely missing | |
| 11 | Support signal entity with lifecycle (active/resolved/history) | Completely missing | only notification dedupe keys |
| 12 | Consolidated multi-signal summary | Completely missing | |
| 13 | Notifications: persistence, unread count, mark read, mark all read, WebSocket push, ownership check | Fully implemented | `NotificationService`, `Notifications.tsx` |
| 14 | Notification categories, priority, student name/USN fields, filters by category/read | Partially implemented | has `type` and link only; no priority or structured student fields; filters not verified |
| 15 | Department Overview page | Completely missing | only `mentor/Dashboard.tsx` and `admin/Dashboard.tsx` |
| 16 | Four summary cards / support distribution / Attention Required / clickable metrics | Completely missing (mentor dashboard exists with different content) | |
| 17 | Student Support Navigator, 7 categories, request form | Completely missing | |
| 18 | Support request status workflow, owners, internal notes vs student-visible feedback, follow-ups | Completely missing | closest: `mentor_feedback`, `academic_observations` |
| 19 | Mentor intervention workflow and dashboard sections | Completely missing | |
| 20 | Follow-up reminders | Completely missing | |
| 21 | Student dashboard (attendance, IA marks, leaves, messages) | Partially implemented | lacks IA attendance, assignments, support requests, follow-ups |
| 22 | Admin settings (thresholds) | Partially implemented | `settings` table + `SettingsPage`; lacks IA min attendance, assignment rules, priorities, categories, owners |
| 23 | Counselling privacy tier | Completely missing | |
| 24 | AI / analytics | Fully implemented (Gemini untested live) | `ai/*`, `AnalyticsService` |
| 25 | Docker / deployment | Implemented (not re-verified) | `docker-compose.yml`, Dockerfiles |
| 26 | Demo data (Students A–E scenarios) | Partially implemented | `DemoDataSeeder` exists; no assignments or IA-attendance scenarios |

## Environment observations
- Docker Desktop daemon is not running; PostgreSQL 18 is installed locally, Java 21, Maven, Node available.
- Existing migration tool: Flyway (next version is V3).

## Implementation plan (migrations only add, never drop)
1. V3: `ia_exams`, `ia_exam_attendance`, `assignments`, `assignment_submissions`, `holidays`/instructional calendar, `support_signals`, notification priority/category/student columns, new settings rows (consecutive default stays configurable, seeded to 3 via new key, existing key untouched).
2. `SupportSignalService`: four rule evaluators, lifecycle (open → updated → resolved → reopened as new), consolidated signal, notifications via existing `NotificationService`.
3. `support_requests`, `support_actions`, history, follow-ups, scheduled reminder job; Navigator + intervention APIs with role checks.
4. Overview, Navigator, intervention UI; student dashboard additions; admin settings.
5. Tests per the brief's Phase 13, then run the app and verify in a browser.


## Status after implementation (2026-11-13)
All items marked "Completely missing", "Partially implemented" or "Implemented but not matching spec" above (rows 4-7, 9-23, 26) are now implemented and covered by `SupportTests` (backend, real PostgreSQL), `support.test.tsx` and a browser run. Remaining caveats are listed in PROJECT_PROGRESS.md (no HOD login role, Docker image not rebuilt this session, parent contact is record-only).
