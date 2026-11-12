import { Link } from 'react-router-dom'
import { Bell, CalendarCheck, FileText, GraduationCap, Lightbulb, MessageSquare, TrendingUp, BookOpen, AlertTriangle } from 'lucide-react'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { Async, Avatar, Badge, Empty, Page, SectionHeader, Stat, fmtDate, statusTone, useLoad } from '../../components/ui'

export default function StudentDashboard() {
  const { user } = useAuth()
  const st = useLoad<any>(() => api.get('/api/student/dashboard').then((r) => r.data), [])
  return (
    <Page title={`Welcome back, ${user!.fullName}`} subtitle="Here is a short overview of your academic progress">
      <Async state={st}>
        {(d) => {
          const low = d.attendancePct < d.attendanceThreshold
          return (
            <div className="space-y-4">
              <div className="card p-4 flex flex-wrap items-center gap-4">
                <Avatar name={d.name} size={56} />
                <div className="flex-1 min-w-[200px]">
                  <div className="font-bold text-lg">{d.name}</div>
                  <div className="text-gray-500 text-xs">{d.usn} · {d.department} · Semester {d.semester}</div>
                  <div className="text-xs mt-1">Mentor: <b>{d.mentorName || 'Not assigned'}</b></div>
                </div>
                <Badge tone={statusTone(d.status)}>{d.status}</Badge>
              </div>
              {low && (
                <div role="alert" className="flex items-center gap-2 bg-red-50 border border-red-300 text-red-700 rounded p-3">
                  <AlertTriangle size={18} />Your attendance ({d.attendancePct}%) is below the required {d.attendanceThreshold}%. <Link className="underline font-semibold" to="/student/attendance">View details</Link>
                </div>
              )}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <Stat label="CGPA" value={d.cgpa ?? '—'} icon={<GraduationCap size={16} />} tone="good" />
                <Stat label="Attendance" value={`${d.attendancePct}%`} icon={<CalendarCheck size={16} />} tone={low ? 'bad' : 'good'} hint={`Requirement ${d.attendanceThreshold}%`} />
                <Stat label={`Latest IA${d.latestIaLabel ? ` (${d.latestIaLabel})` : ''}`} value={d.latestIa != null ? `${d.latestIa}/25` : '—'} icon={<TrendingUp size={16} />} hint="Average across subjects" />
                <Stat label="Subjects" value={d.totalSubjects} icon={<BookOpen size={16} />} />
                <Stat label="Pending leaves" value={d.pendingLeaves} icon={<FileText size={16} />} tone={d.pendingLeaves ? 'warn' : 'neutral'} />
                <Stat label="Unread messages" value={d.unreadMessages} icon={<MessageSquare size={16} />} tone={d.unreadMessages ? 'warn' : 'neutral'} />
              </div>
              <div className="grid lg:grid-cols-2 gap-4">
                <div className="card">
                  <SectionHeader>Recent notifications</SectionHeader>
                  {d.recentNotifications.length === 0 ? <Empty text="No notifications" /> : (
                    <ul className="divide-y">{d.recentNotifications.map((n: any) => (
                      <li key={n.id} className="px-3 py-2 flex gap-2"><Bell size={14} className="mt-1 text-teal-600" /><div className="flex-1"><div className="font-semibold">{n.title}</div><div className="text-gray-600 text-xs">{n.description}</div></div><span className="text-[11px] text-gray-400">{fmtDate(n.createdAt)}</span></li>
                    ))}</ul>
                  )}
                </div>
                <div className="card">
                  <SectionHeader>Academic overview</SectionHeader>
                  <ul className="p-3 space-y-1.5">
                    {d.insights.map((x: string, i: number) => <li key={i} className="flex gap-2"><Lightbulb size={14} className="mt-1 text-amber-500 shrink-0" />{x}</li>)}
                  </ul>
                  <div className="px-3 pb-3 flex gap-2"><Link className="btn btn-ghost" to="/student/analytics">Analytics</Link><Link className="btn btn-ghost" to="/student/marks">Internal marks</Link></div>
                </div>
              </div>
            </div>
          )
        }}
      </Async>
    </Page>
  )
}
