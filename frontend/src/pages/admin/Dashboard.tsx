import { Link } from 'react-router-dom'
import { api } from '../../lib/api'
import { Async, Page, SectionHeader, Stat, useLoad } from '../../components/ui'

export default function AdminDashboard() {
  const st = useLoad<any>(() => api.get('/api/admin/stats').then((r) => r.data), [])
  return (
    <Page title="Administration" subtitle="Institution-wide statistics">
      <Async state={st}>
        {(d) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <Stat label="Students" value={d.students} /><Stat label="Mentors" value={d.mentors} /><Stat label="Departments" value={d.departments} /><Stat label="Subjects" value={d.subjects} />
              <Stat label="Unassigned students" value={d.unassignedStudents} tone={d.unassignedStudents ? 'warn' : 'good'} />
              <Stat label="Average attendance" value={`${d.averageAttendance}%`} />
              <Stat label="Low attendance" value={d.lowAttendance} tone={d.lowAttendance ? 'bad' : 'good'} />
              <Stat label="Need attention" value={d.needAttention} tone={d.needAttention ? 'bad' : 'good'} />
            </div>
            <div className="card"><SectionHeader>Quick actions</SectionHeader>
              <div className="p-3 flex flex-wrap gap-2">
                <Link className="btn btn-ghost" to="/admin/students">Manage students</Link><Link className="btn btn-ghost" to="/admin/mentors">Manage mentors</Link>
                <Link className="btn btn-ghost" to="/admin/assignments">Assign mentors</Link><Link className="btn btn-ghost" to="/admin/import">Import academic data</Link>
                <Link className="btn btn-ghost" to="/admin/assessment">Assessment rules</Link>
              </div></div>
          </div>
        )}
      </Async>
    </Page>
  )
}
