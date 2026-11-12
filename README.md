# Mentora

**AI-Powered Student Mentorship and Academic Performance Monitoring System**

Mentora connects students with their assigned faculty mentors. It tracks daily attendance, internal assessments (in the dense ERP-table style colleges use), semester results and leave requests, supports real-time mentor chat, raises automatic alerts, and gives mentors a Gemini-powered assistant that answers questions about *their own* students using verified data.

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green) ![React](https://img.shields.io/badge/React-19-blue) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue) ![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)

---

## Features

### Student
- Dashboard: CGPA, attendance %, latest IA performance, pending leaves, unread messages, notifications.
- **Attendance**: calendar view (P / A / OD / L / NC), month and subject filters, subject-wise table, shortage warning below the configurable threshold.
- **Internal marks**: ERP-style table with IA 1–3, activities, bucket marks, converted marks and total.
- **Results**: semester-wise grades, SGPA and CGPA.
- **Analytics**: IA comparison, attendance trend, SGPA/CGPA progression and plain-language insights computed from real data.
- Real-time chat with the mentor, leave applications with PDF/JPG/PNG documents, notifications.

### Mentor (sees only their assigned students)
- Dashboard with today's attendance, low-attendance and declining-marks students, pending leaves and recent activity.
- Searchable, sortable, paginated student table and a full per-student profile (attendance, marks, results, analytics, leaves, chat, feedback, private observations).
- **Manage own roster**: add unassigned students, remove their own students.
- **Mark / correct attendance** for any of their students (today or up to 60 days back); the backend raises the alerts.
- Consolidated attendance and marks grids, leave review (preview, download, approve, reject with comment).
- Real-time chat with AI-generated message summaries.
- **AI Mentor Assistant**: natural-language questions about attendance, marks, leave requests and students needing attention.

### Admin
- Students, mentors, departments, subjects, semesters and mentor assignments.
- CSV / XLSX import of attendance, marks and results with a per-row validation report.
- Assessment rules (IA bucket, conversion, activities) and attendance / performance thresholds.

### Platform
- JWT authentication, BCrypt, role-based access, **backend-enforced** mentor↔student and student↔student isolation.
- STOMP WebSocket chat with authenticated principals; spam, profanity, link and rate moderation with temporary restriction.
- Persisted, de-duplicated notifications pushed in real time; audit trail for leave decisions.

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Spring Security (JWT), Spring Data JPA / Hibernate, Flyway, WebSocket (STOMP), Apache POI |
| Database | PostgreSQL 16 |
| Frontend | React 19, Vite, TypeScript, Tailwind CSS, React Router, Axios, Recharts, Lucide, Framer Motion |
| AI | Google Gemini API (called from the backend only) |
| Infrastructure | Docker, Docker Compose, nginx |

---

## Quick start

**Requirements:** Docker Desktop (running). On Windows run the script from Git Bash or WSL.

```bash
git clone https://github.com/abhishekkumar3513/Mentora.git
cd Mentora
./run.sh
```

`run.sh` builds and starts the database, backend and frontend, waits until the backend is healthy, prints the link and opens it in your browser.

```bash
./run.sh restart   # rebuild and restart (picks up code and .env changes)
./run.sh stop      # stop, keeping data
./run.sh logs      # follow backend logs
./run.sh status
```

Without the script: `docker compose up --build -d`.

| Service | URL |
|---|---|
| **App** | http://localhost:3000 |
| Backend API | http://localhost:8081 (`BACKEND_PORT`) |
| PostgreSQL | localhost:5433 |

> On Windows, if the project lives inside OneDrive, Docker cannot read OneDrive placeholder files. `run.sh` detects this and builds from a plain copy in `~/.mentora-build`; moving the project outside OneDrive also avoids it.

### Enable the AI features
Copy `.env.example` to `.env` and set your key (get one at https://aistudio.google.com/apikey):
```
GEMINI_API_KEY=your_key
```
then `./run.sh restart`. Without a key everything else works; the assistant returns verified data without AI wording. `GEMINI_MODEL` accepts a comma-separated list; the next model is tried when one is retired, rate-limited or slow.

### Demo accounts
Demo data (60 students, 2 mentors, attendance, marks, results, leaves, messages) is seeded on first start. Disable it with `SEED_DEMO_DATA=false`; never enable it in production.

| Role | Email | Password |
|---|---|---|
| Admin | `admin@mentora.edu` | `Admin@123` |
| Mentor – Dr. Sunitha K (30 students) | `mentor1@mentora.edu` | `Mentor@123` |
| Mentor – Ms. Nivedita G. Y. (30 students) | `mentor2@mentora.edu` | `Mentor@123` |
| Student (60) | `1rn23is001@mentora.edu` … `1rn23is060@mentora.edu` | `Student@123` |

Dr. Sunitha K: USN 1RN23IS001–010 and 021–040. Ms. Nivedita G. Y.: 011–020 and 041–060. Change any account in **Admin → Students / Mentors → Edit**; users change their own password under **Profile**.

---

## Architecture

Modular monolith: `Controller → Service → Repository → PostgreSQL`, DTOs at the edge.

```
backend/src/main/java/com/mentora
  config/   security, JWT, STOMP config + auth interceptor
  domain/   JPA entities            repo/  Spring Data repositories
  service/  AccessService (central authorization), MarksCalculator, AttendanceCalc, AnalyticsService,
            LeaveService, MessageService, ModerationService, NotificationService, AttendanceAlertService,
            RosterService, AdminService, ImportService, DemoDataSeeder
  ai/       GeminiService, StudentMessageUnderstandingService, MentorAnalyticsAssistantService,
            AcademicInsightService, AIConversationService
  web/      REST controllers and exception handling
backend/src/main/resources/db/migration   Flyway migrations
frontend/src   lib/ (api, auth + STOMP) · components/ · pages/{student,mentor,admin}
```

**Authorization.** `AccessService.requireStudentAccess` is the single gate: a student reaches only their own record, a mentor only actively assigned students, an admin everything. It protects REST endpoints, chat history, leave documents, attachments and AI summaries. WebSocket CONNECT needs a valid JWT; clients can only subscribe to their own `/user/queue/*` destinations.

**Gemini integration.** The model never sees the database. A mentor's question is mapped to an approved analytics operation, Java computes every number from authorized rows, and only that verified result is sent to Gemini to word the answer. The reply is validated (a USN outside the mentor's students rejects it). Message summaries are mentor-only, clearly labelled AI-generated, never invent facts and never decide leave.

**Assessment rules** (admin-configurable): IA bucket = best 2 of 3 tests (each /25) = 50, converted to 25; activities bucket converted to 25; total 50. Rounding is half-up, matching the ERP.

---

## Local development

```bash
docker compose up -d db                                   # PostgreSQL on :5433
cd backend  && SERVER_PORT=8090 mvn spring-boot:run       # API (default 8080)
cd frontend && npm install && VITE_BACKEND=http://localhost:8090 npm run dev   # http://localhost:5173
```

Environment variables (see `.env.example`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `SEED_DEMO_DATA`, `CORS_ORIGINS`, `STORAGE_DIR`, `SERVER_PORT`, `APP_TIMEZONE` (default `Asia/Kolkata`; decides what "today" means for attendance). Flyway migrations run automatically at startup.

## Testing

```bash
cd backend  && mvn test      # 14 unit + 12 integration tests (needs database `mentora_test` on :5433; wiped each run; Gemini mocked)
cd frontend && npm test      # 12 component / flow tests (Vitest)
cd frontend && BASE=http://localhost:3000 node scripts/e2e.mjs   # end-to-end scenarios incl. WebSocket, on a FRESH seed
```
Create the test database once: `docker compose exec db psql -U mentora -c "create database mentora_test"`.

## API overview

All JSON with `Authorization: Bearer <jwt>`.

- **Auth**: `POST /api/auth/login`, `GET /api/auth/me`, `PUT /api/auth/profile`, `POST /api/auth/password`
- **Student data**: `GET /api/students/{id}/{profile|attendance|marks|results|analytics|leaves|feedback|observations}`, `GET /api/student/dashboard`
- **Mentor**: `GET /api/mentor/{dashboard|students|attendance-grid|marks-grid|leaves|conversations}`; roster `GET /api/mentor/students/available`, `POST /api/mentor/students`, `DELETE /api/mentor/students/{id}`; attendance `GET /api/mentor/attendance/day`, `POST /api/mentor/attendance/mark`
- **Leaves**: `POST /api/leaves` (multipart), `PUT /api/leaves/{id}/review`, `GET /api/leaves/{id}/documents/{docId}`
- **Chat**: `GET|POST /api/messages/{studentId}`, `POST /api/messages/{studentId}/read`; STOMP send `/app/chat.send`, listen `/user/queue/{messages|notifications|read|errors|ai-summary}`
- **Notifications**: `GET /api/notifications`, `POST /api/notifications/{id}/read`, `/read-all`
- **AI (mentor)**: `POST /api/ai/assistant`, `GET /api/ai/conversations[/{id}]`, `POST /api/ai/messages/{id}/summary`
- **Admin**: `/api/admin/**` (students, mentors, departments, semesters, subjects, assignments, settings, `import/{attendance|marks|results}`, `attendance/mark-day`, stats)

Import formats: `usn,subjectCode,date,status` · `usn,subjectCode,component,score` (score or `AB`) · `usn,subjectCode,grade`.

## Known limitations
- Gemini features are tested with a mocked client in the automated tests and were checked manually against the live API; streaming responses are not implemented.
- Rate limiting is in-memory (per instance); moderation uses a small built-in word list.
- Uploaded documents are stored on a local Docker volume.
- Before production: HTTPS, a strong `JWT_SECRET` and database password, `SEED_DEMO_DATA=false`.
