import { useCallback, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Activity, ClipboardList, Users, LifeBuoy } from 'lucide-react'
import { api } from '../lib/api'
import { useBus } from '../lib/bus'
import { Async, Page, SectionHeader, Stat, useLoad } from '../components/ui'
import SupportStudentList from '../components/SupportStudentList'
import SupportActionModal from '../components/SupportActionModal'

function Bar({ label, value, total, color, to }: { label: string; value: number; total: number; color: string; to: string }) {
  const pct = total === 0 ? 0 : Math.round((value * 100) / total)
  return (
    <Link to={to} className="block group focus:outline-none focus:ring-2 focus:ring-teal-400 rounded" aria-label={`${label}: ${value} students. Open list`}>
      <div className="flex justify-between text-sm"><span className="group-hover:underline">{label}</span><span className="font-semibold">{value}</span></div>
      <div className="h-2.5 bg-gray-100 rounded mt-1" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
        <div className="h-2.5 rounded" style={{ width: `${pct}%`, background: color }} />
      </div>
    </Link>
  )
}

/** Department Overview: every number comes from /api/overview (database), scoped to the caller's students. */
export default function Overview() {
  const st = useLoad<any>(() => api.get('/api/overview').then((r) => r.data), [])
  const nav = useNavigate()
  const [category, setCategory] = useState('ANY')
  const [creating, setCreating] = useState(false)
  const reload = st.reload
  useBus('notifications', useCallback(() => reload(), [reload]))

  const go = (c: string) => nav(`/support-students?category=${c}`)
  return (
    <Page title="Department Overview" subtitle="Student success snapshot" actions={<button className="btn btn-primary" onClick={() => setCreating(true)}><LifeBuoy size={15} />Create Support Action</button>}>
      <Async state={st}>
        {(d) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
              <Stat label="Students in View" value={d.studentsInView} icon={<Users size={16} />} hint={d.scope === 'INSTITUTION' ? 'Institution-wide' : 'Your assigned students'} />
              <Stat label="Average Attendance" value={d.averageAttendance == null ? '—' : `${d.averageAttendance}%`} icon={<Activity size={16} />} hint="Mean of each student's own attendance" />
              <Link to="/support-students?category=ANY" className="block"><Stat label="Students Requiring Support" value={d.studentsRequiringSupport} icon={<LifeBuoy size={16} />} tone={d.studentsRequiringSupport ? 'warn' : 'good'} hint="At least one active support signal" /></Link>
              <Link to="/support" className="block"><Stat label="Open Interventions" value={d.openInterventions} icon={<ClipboardList size={16} />} hint="Not yet resolved or closed" /></Link>
            </div>

            <div className="grid lg:grid-cols-3 gap-4">
              <div className="card lg:col-span-1">
                <SectionHeader>Student Support Distribution</SectionHeader>
                <div className="p-3 space-y-3">
                  <Bar label="No Current Support Signal" value={d.distribution.none} total={d.studentsInView} color="#16a34a" to="/support-students?category=NONE" />
                  <Bar label="Support Review Recommended" value={d.distribution.review} total={d.studentsInView} color="#d97706" to="/support-students?category=REVIEW" />
                  <Bar label="Multiple Support Signals" value={d.distribution.multiple} total={d.studentsInView} color="#dc2626" to="/support-students?category=MULTIPLE" />
                  <p className="text-xs text-gray-500 pt-1">These indicators highlight potential support needs and are intended for human review, not automated decisions.</p>
                </div>
              </div>
              <div className="card lg:col-span-2">
                <SectionHeader>Academic support metrics</SectionHeader>
                <div className="p-3 grid grid-cols-2 gap-3">
                  {[
                    ['ATTENDANCE', 'Below attendance threshold', d.metrics.belowAttendance],
                    ['ASSIGNMENT', 'With overdue assignments', d.metrics.overdueAssignments],
                    ['IA_ATTENDANCE', 'Attended fewer than the minimum IA examinations', d.metrics.iaLow],
                    ['CONSECUTIVE_ABSENCE', 'With consecutive scheduled absences', d.metrics.consecutiveAbsence],
                  ].map(([c, label, n]) => (
                    <button key={c as string} onClick={() => go(c as string)} className="text-left border rounded p-3 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-teal-400">
                      <div className="text-2xl font-bold">{n as number}</div>
                      <div className="text-xs text-gray-600">{label as string}</div>
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="card">
              <SectionHeader>Attention Required</SectionHeader>
              <SupportStudentList category={category} onCategory={setCategory} limit={8} />
              <div className="p-3 border-t text-right">
                <Link className="text-blue-700 text-sm font-semibold hover:underline" to={`/support-students?category=${category}`}>View All Students Requiring Support →</Link>
              </div>
            </div>
          </div>
        )}
      </Async>
      {creating && <SupportActionModal onClose={() => setCreating(false)} onDone={() => reload()} />}
    </Page>
  )
}
