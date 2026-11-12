import { Link } from 'react-router-dom'
import { AlertTriangle, Bell, CalendarX, FileText, MessageSquare, TrendingDown, UserCheck, UserX, Users } from 'lucide-react'
import { api } from '../../lib/api'
import { Async, Badge, Empty, Page, SectionHeader, Stat, fmtDate, statusTone, useLoad } from '../../components/ui'

export default function MentorDashboard() {
  const st = useLoad<any>(() => api.get('/api/mentor/dashboard').then((r) => r.data), [])
  return (
    <Page title="Mentor Dashboard" subtitle="Overview of your assigned students">
      <Async state={st}>
        {(d) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <Stat label="Assigned students" value={d.totalStudents} icon={<Users size={16} />} />
              <Stat label="Present today" value={d.presentToday} icon={<UserCheck size={16} />} tone="good" hint={d.referenceDate} />
              <Stat label="Absent today" value={d.absentToday} icon={<UserX size={16} />} tone={d.absentToday ? 'bad' : 'neutral'} />
              <Stat label="Low attendance" value={d.lowAttendance} icon={<CalendarX size={16} />} tone={d.lowAttendance ? 'bad' : 'neutral'} />
              <Stat label="Declining marks" value={d.declining} icon={<TrendingDown size={16} />} tone={d.declining ? 'warn' : 'neutral'} />
              <Stat label="Pending leave requests" value={d.pendingLeaves} icon={<FileText size={16} />} tone={d.pendingLeaves ? 'warn' : 'neutral'} />
              <Stat label="Unread messages" value={d.unreadMessages} icon={<MessageSquare size={16} />} tone={d.unreadMessages ? 'warn' : 'neutral'} />
              <Stat label="Require attention" value={d.needAttention} icon={<AlertTriangle size={16} />} tone={d.needAttention ? 'bad' : 'good'} />
            </div>
            <div className="grid lg:grid-cols-2 gap-4">
              <div className="card">
                <SectionHeader>Students requiring attention</SectionHeader>
                {d.attentionList.length === 0 ? <Empty text="Everyone is on track" /> : (
                  <ul className="divide-y">{d.attentionList.map((s: any) => (
                    <li key={s.studentId} className="px-3 py-2 flex items-center gap-2">
                      <div className="flex-1"><Link className="font-semibold text-blue-600 hover:underline" to={`/mentor/students/${s.studentId}`}>{s.name}</Link> <span className="text-xs text-gray-500">{s.usn}</span>
                        <div className="text-xs text-gray-600">{s.flags.join(' · ')}</div></div>
                      <Badge tone={statusTone(s.status)}>{s.attendancePct}%</Badge>
                    </li>
                  ))}</ul>
                )}
              </div>
              <div className="card">
                <SectionHeader>Recent activity</SectionHeader>
                {d.recentActivity.length === 0 ? <Empty text="No recent activity" /> : (
                  <ul className="divide-y">{d.recentActivity.map((n: any) => (
                    <li key={n.id} className="px-3 py-2 flex gap-2"><Bell size={14} className={`mt-1 ${n.read ? 'text-gray-300' : 'text-teal-600'}`} />
                      <div className="flex-1"><div className="font-semibold">{n.title}</div><div className="text-xs text-gray-600">{n.description}</div></div>
                      <span className="text-[11px] text-gray-400 whitespace-nowrap">{fmtDate(n.createdAt)}</span></li>
                  ))}</ul>
                )}
                <div className="p-2 text-right"><Link className="text-blue-600 text-xs hover:underline" to="/notifications">All notifications →</Link></div>
              </div>
            </div>
          </div>
        )}
      </Async>
    </Page>
  )
}
