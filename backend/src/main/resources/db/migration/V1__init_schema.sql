create table users (
  id bigserial primary key,
  email varchar(150) not null unique,
  password_hash varchar(100) not null,
  full_name varchar(150) not null,
  role varchar(20) not null,
  phone varchar(20),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table departments (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(150) not null
);

create table semesters (
  id bigserial primary key,
  sem_number int not null unique,
  academic_year varchar(20) not null,
  is_current boolean not null default false
);

create table faculty (
  id bigserial primary key,
  user_id bigint not null unique references users(id),
  department_id bigint not null references departments(id),
  employee_id varchar(30) not null unique,
  designation varchar(80)
);

create table students (
  id bigserial primary key,
  user_id bigint not null unique references users(id),
  department_id bigint not null references departments(id),
  usn varchar(20) not null unique,
  semester int not null,
  section varchar(5),
  created_at timestamptz not null default now()
);

create table mentor_assignments (
  id bigserial primary key,
  student_id bigint not null references students(id),
  faculty_id bigint not null references faculty(id),
  active boolean not null default true,
  assigned_at timestamptz not null default now()
);
create unique index ux_mentor_assign_active on mentor_assignments(student_id) where active;
create index ix_mentor_assign_faculty on mentor_assignments(faculty_id) where active;

create table subjects (
  id bigserial primary key,
  code varchar(20) not null unique,
  name varchar(150) not null,
  credits int not null,
  semester int not null,
  department_id bigint not null references departments(id)
);

create table attendance (
  id bigserial primary key,
  student_id bigint not null references students(id),
  subject_id bigint not null references subjects(id),
  att_date date not null,
  status varchar(3) not null,
  constraint ux_attendance unique (student_id, subject_id, att_date)
);
create index ix_attendance_student_date on attendance(student_id, att_date);
create index ix_attendance_date on attendance(att_date);

create table marks (
  id bigserial primary key,
  student_id bigint not null references students(id),
  subject_id bigint not null references subjects(id),
  component varchar(10) not null,
  score numeric(5,2),
  absent boolean not null default false,
  constraint ux_marks unique (student_id, subject_id, component)
);
create index ix_marks_student on marks(student_id);

create table semester_results (
  id bigserial primary key,
  student_id bigint not null references students(id),
  subject_id bigint not null references subjects(id),
  semester int not null,
  grade varchar(3) not null,
  grade_points int not null,
  status varchar(10) not null,
  constraint ux_results unique (student_id, subject_id)
);
create index ix_results_student on semester_results(student_id);

create table leave_requests (
  id bigserial primary key,
  student_id bigint not null references students(id),
  leave_type varchar(30) not null,
  start_date date not null,
  end_date date not null,
  reason text not null,
  status varchar(15) not null default 'PENDING',
  review_comment text,
  reviewed_by bigint references users(id),
  created_at timestamptz not null default now(),
  reviewed_at timestamptz
);
create index ix_leave_student on leave_requests(student_id);
create index ix_leave_status on leave_requests(status);

create table leave_documents (
  id bigserial primary key,
  leave_id bigint not null references leave_requests(id) on delete cascade,
  file_name varchar(255) not null,
  content_type varchar(100) not null,
  size_bytes bigint not null,
  storage_path varchar(500) not null,
  uploaded_at timestamptz not null default now()
);

create table leave_audit (
  id bigserial primary key,
  leave_id bigint not null references leave_requests(id) on delete cascade,
  action varchar(30) not null,
  actor_id bigint not null references users(id),
  comment text,
  created_at timestamptz not null default now()
);

create table messages (
  id bigserial primary key,
  student_id bigint not null references students(id),
  sender_id bigint not null references users(id),
  content text not null,
  attachment_name varchar(255),
  attachment_path varchar(500),
  attachment_type varchar(100),
  created_at timestamptz not null default now(),
  read_at timestamptz,
  ai_summary text,
  ai_status varchar(20)
);
create index ix_messages_student on messages(student_id, created_at);

create table message_moderation_logs (
  id bigserial primary key,
  user_id bigint not null references users(id),
  content text not null,
  reason varchar(100) not null,
  created_at timestamptz not null default now()
);
create index ix_modlog_user on message_moderation_logs(user_id, created_at);

create table notifications (
  id bigserial primary key,
  user_id bigint not null references users(id),
  type varchar(40) not null,
  title varchar(200) not null,
  description text,
  link varchar(200),
  is_read boolean not null default false,
  dedupe_key varchar(200),
  created_at timestamptz not null default now(),
  constraint ux_notification_dedupe unique (user_id, dedupe_key)
);
create index ix_notifications_user on notifications(user_id, is_read, created_at);

create table mentor_feedback (
  id bigserial primary key,
  student_id bigint not null references students(id),
  faculty_id bigint not null references faculty(id),
  content text not null,
  created_at timestamptz not null default now()
);

create table academic_observations (
  id bigserial primary key,
  student_id bigint not null references students(id),
  faculty_id bigint not null references faculty(id),
  content text not null,
  created_at timestamptz not null default now()
);

create table ai_conversations (
  id bigserial primary key,
  user_id bigint not null references users(id),
  title varchar(200) not null,
  created_at timestamptz not null default now()
);

create table ai_messages (
  id bigserial primary key,
  conversation_id bigint not null references ai_conversations(id) on delete cascade,
  role varchar(10) not null,
  content text not null,
  payload text,
  created_at timestamptz not null default now()
);

create table settings (
  setting_key varchar(100) primary key,
  setting_value varchar(200) not null,
  description varchar(300)
);

insert into settings(setting_key, setting_value, description) values
 ('attendance.threshold', '75', 'Minimum attendance percentage'),
 ('attendance.consecutive_absence_days', '2', 'Consecutive absent days that trigger an alert'),
 ('attendance.leave_counts_as_present', 'true', 'Approved leave counts as present in percentage'),
 ('attendance.decline_points', '20', 'Drop in attendance percentage (last 7 days vs prior) that triggers an alert'),
 ('ia.max_per_test', '25', 'Maximum marks of one internal test'),
 ('ia.best_count', '2', 'Number of best IA tests summed into the bucket'),
 ('ia.bucket_max', '50', 'Maximum bucket marks for internal tests'),
 ('ia.converted_max', '25', 'Internal test marks after conversion'),
 ('activity.max_each', '10', 'Maximum marks of one activity'),
 ('activity.count', '3', 'Number of activities'),
 ('activity.bucket_max', '30', 'Maximum bucket marks for activities'),
 ('activity.converted_max', '25', 'Activity marks after conversion'),
 ('perf.good_percent', '80', 'IA percentage considered consistently good'),
 ('perf.weak_percent', '50', 'IA percentage considered weak'),
 ('perf.decline_percent', '8', 'IA percentage-point drop considered declining'),
 ('moderation.max_length', '1000', 'Maximum message length'),
 ('moderation.rate_per_minute', '8', 'Maximum messages per user per minute'),
 ('moderation.restrict_minutes', '5', 'Temporary restriction after repeated violations');
