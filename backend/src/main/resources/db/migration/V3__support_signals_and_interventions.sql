-- V3: early-warning support signals, assignments, IA exam attendance, support requests/interventions.
-- Additive only: nothing is dropped or rewritten.

-- ---------- notifications: priority, structured student link ----------
alter table notifications add column priority varchar(10) not null default 'NORMAL';
alter table notifications add column student_id bigint references students(id);
create index ix_notifications_student on notifications(student_id);

-- ---------- instructional calendar (weekends are skipped in code) ----------
create table holidays (
  holiday_date date primary key,
  name varchar(120) not null
);

-- ---------- assignments ----------
create table assignments (
  id bigserial primary key,
  subject_id bigint not null references subjects(id),
  title varchar(200) not null,
  deadline date not null,
  created_by bigint references users(id),
  created_at timestamptz not null default now()
);
create index ix_assignments_subject on assignments(subject_id);

create table assignment_submissions (
  id bigserial primary key,
  assignment_id bigint not null references assignments(id) on delete cascade,
  student_id bigint not null references students(id),
  submitted_at timestamptz not null default now(),
  constraint ux_assignment_submission unique (assignment_id, student_id)
);
create index ix_submissions_student on assignment_submissions(student_id);

-- ---------- IA examinations: schedule + attendance (kept apart from marks) ----------
create table ia_exams (
  id bigserial primary key,
  semester int not null,
  ia_number int not null check (ia_number between 1 and 3),
  exam_date date,
  conducted boolean not null default false,
  constraint ux_ia_exam unique (semester, ia_number)
);

create table ia_exam_attendance (
  id bigserial primary key,
  exam_id bigint not null references ia_exams(id) on delete cascade,
  student_id bigint not null references students(id),
  status varchar(10) not null check (status in ('ATTENDED','ABSENT')),
  constraint ux_ia_att unique (exam_id, student_id)
);
create index ix_ia_att_student on ia_exam_attendance(student_id);

-- ---------- support signals (explainable, with history) ----------
create table support_signals (
  id bigserial primary key,
  student_id bigint not null references students(id),
  signal_type varchar(30) not null,      -- ATTENDANCE, ASSIGNMENT, IA_ATTENDANCE, CONSECUTIVE_ABSENCE, MULTIPLE
  status varchar(10) not null default 'ACTIVE',  -- ACTIVE | RESOLVED
  priority varchar(10) not null,
  title varchar(200) not null,
  summary text not null,
  details text not null default '{}',     -- JSON with the exact numbers behind the signal
  first_detected_at timestamptz not null default now(),
  last_updated_at timestamptz not null default now(),
  resolved_at timestamptz
);
create unique index ux_active_signal on support_signals(student_id, signal_type) where status = 'ACTIVE';
create index ix_signal_student on support_signals(student_id, status);

-- ---------- support categories and ownership ----------
create table support_categories (
  code varchar(40) primary key,
  name varchar(80) not null,
  default_owner varchar(30) not null,
  confidential boolean not null default false,
  active boolean not null default true,
  sort_order int not null default 0
);
insert into support_categories(code, name, default_owner, confidential, sort_order) values
 ('ACADEMIC_COUNSELLING', 'Academic Counselling', 'FACULTY_MENTOR', false, 1),
 ('ATTENDANCE_IMPROVEMENT', 'Attendance Improvement', 'FACULTY_MENTOR', false, 2),
 ('ASSIGNMENT_SUPPORT', 'Assignment Support', 'ACADEMIC_SUPPORT_TEAM', false, 3),
 ('TECHNICAL_SKILL_DEVELOPMENT', 'Technical Skill Development', 'ACADEMIC_SUPPORT_TEAM', false, 4),
 ('CAREER_GUIDANCE', 'Career Guidance', 'CAREER_GUIDANCE_TEAM', false, 5),
 ('PARENT_INTERACTION', 'Parent Interaction', 'DEPARTMENT_HOD', false, 6),
 ('PERSONAL_COUNSELLING_REFERRAL', 'Personal Counselling Referral', 'COUNSELLOR', true, 7);

alter table faculty add column is_counsellor boolean not null default false;

-- ---------- support requests / interventions ----------
create table support_requests (
  id bigserial primary key,
  student_id bigint not null references students(id),
  category varchar(40) not null references support_categories(code),
  source varchar(10) not null,             -- STUDENT | MENTOR
  concern text not null,
  help_needed text,
  status varchar(20) not null default 'SUBMITTED',
  owner_role varchar(30) not null,
  owner_user_id bigint references users(id),
  confidential boolean not null default false,
  signal_id bigint references support_signals(id),
  created_by bigint not null references users(id),
  follow_up_date date,
  reminded_for date,
  outcome text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  resolved_at timestamptz
);
create index ix_support_student on support_requests(student_id);
create index ix_support_status on support_requests(status);

-- one row per timeline entry; visibility keeps mentor-only notes away from students
create table support_request_events (
  id bigserial primary key,
  request_id bigint not null references support_requests(id) on delete cascade,
  event_type varchar(20) not null,         -- CREATED, ASSIGNED, STATUS, NOTE, FEEDBACK, FOLLOW_UP, OUTCOME
  visibility varchar(10) not null,         -- STUDENT | INTERNAL
  status varchar(20),
  message text,
  author_user_id bigint references users(id),
  created_at timestamptz not null default now()
);
create index ix_support_events_req on support_request_events(request_id, created_at);

-- ---------- configuration (new keys; existing keys untouched) ----------
insert into settings(setting_key, setting_value, description) values
 ('signals.consecutive_absence_days', '3', 'Consecutive scheduled absent class days that trigger a support signal'),
 ('signals.ia_min_attended', '2', 'Minimum IA examinations a student should attend (out of 3)'),
 ('signals.assignment_grace_days', '0', 'Days after the deadline before a missing assignment is flagged'),
 ('priority.attendance', 'MEDIUM', 'Priority of attendance support signals (NORMAL, MEDIUM or HIGH)'),
 ('priority.assignment', 'MEDIUM', 'Priority of assignment support signals'),
 ('priority.ia_partial', 'MEDIUM', 'Priority when fewer than the minimum IA examinations were attended'),
 ('priority.ia_none', 'HIGH', 'Priority when no IA examination was attended'),
 ('priority.consecutive', 'MEDIUM', 'Priority of consecutive absence signals'),
 ('priority.multiple', 'HIGH', 'Priority of consolidated multiple-signal summaries'),
 ('priority.request', 'NORMAL', 'Priority of student support request notifications'),
 ('priority.followup', 'NORMAL', 'Priority of follow-up reminders');
