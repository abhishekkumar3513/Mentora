import type { Role } from './api'

export const priorityTone = (p?: string) => (p === 'HIGH' ? 'bad' : p === 'MEDIUM' ? 'warn' : 'info')
export const priorityLabel = (p?: string) => (p === 'HIGH' ? 'High priority' : p === 'MEDIUM' ? 'Medium priority' : 'Normal')

export const STATUS_LABEL: Record<string, string> = {
  SUBMITTED: 'Submitted', ASSIGNED: 'Assigned', IN_PROGRESS: 'In progress', FOLLOW_UP_REQUIRED: 'Follow-up required', RESOLVED: 'Resolved', CLOSED: 'Closed',
}
export const statusToneOf = (s: string) => (s === 'RESOLVED' || s === 'CLOSED' ? 'good' : s === 'FOLLOW_UP_REQUIRED' ? 'warn' : s === 'IN_PROGRESS' ? 'info' : 'neutral')

export const NOTIFICATION_CATEGORIES: { value: string; label: string }[] = [
  { value: 'LOW_ATTENDANCE', label: 'Attendance Below 75%' },
  { value: 'ASSIGNMENT_OVERDUE', label: 'Assignment Not Submitted' },
  { value: 'IA_ATTENDANCE', label: 'Internal Assessment Attendance' },
  { value: 'CONSECUTIVE_ABSENCE', label: 'Consecutive Absence' },
  { value: 'MULTIPLE_SIGNALS', label: 'Multiple Academic Support Signals' },
  { value: 'SUPPORT_REQUEST', label: 'Student Support Request' },
  { value: 'FOLLOW_UP_REMINDER', label: 'Follow-up Reminder' },
]
export const categoryLabel = (t: string) => NOTIFICATION_CATEGORIES.find((c) => c.value === t)?.label ?? t.replace(/_/g, ' ').toLowerCase()

export const SIGNAL_LABEL: Record<string, string> = {
  ATTENDANCE: 'Attendance below threshold', ASSIGNMENT: 'Overdue assignments', IA_ATTENDANCE: 'IA examination attendance',
  CONSECUTIVE_ABSENCE: 'Consecutive absences', MULTIPLE: 'Multiple support signals',
}

export const studentLink = (role: Role | undefined, id: number) => (role === 'ADMIN' ? `/admin/students/${id}` : `/mentor/students/${id}`)
export const today = () => new Date().toISOString().slice(0, 10)
