import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search } from 'lucide-react'
import { api, errMsg } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useBus } from '../lib/bus'
import { priorityLabel, priorityTone, studentLink } from '../lib/support'
import { Badge, Empty, ErrorBox, Loading } from './ui'
import SupportActionModal from './SupportActionModal'

export const CATEGORY_OPTIONS = [
  { value: 'ANY', label: 'All students requiring support' },
  { value: 'ATTENDANCE', label: 'Attendance below threshold' },
  { value: 'ASSIGNMENT', label: 'Overdue assignments' },
  { value: 'IA_ATTENDANCE', label: 'IA attendance below minimum' },
  { value: 'CONSECUTIVE_ABSENCE', label: 'Consecutive absences' },
  { value: 'MULTIPLE', label: 'Multiple Support Signals' },
  { value: 'REVIEW', label: 'Support Review Recommended' },
  { value: 'NONE', label: 'No Current Support Signal' },
]

/** Students list backed by /api/overview/students (filters are applied by the backend). */
export default function SupportStudentList({ category, onCategory, limit }: { category: string; onCategory: (c: string) => void; limit?: number }) {
  const { user } = useAuth()
  const [priority, setPriority] = useState('')
  const [q, setQ] = useState('')
  const [rows, setRows] = useState<any[] | null>(null)
  const [error, setError] = useState('')
  const [action, setAction] = useState<number | null>(null)
  const [msg, setMsg] = useState('')

  const load = useCallback(() => {
    const p = new URLSearchParams({ category })
    if (priority) p.set('priority', priority)
    if (q.trim()) p.set('q', q.trim())
    api.get(`/api/overview/students?${p}`).then((r) => { setRows(r.data); setError('') }).catch((e) => setError(errMsg(e)))
  }, [category, priority, q])
  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t) }, [load])
  useBus('notifications', useCallback(() => load(), [load])) // new alerts refresh the list without a page reload

  const shown = rows && limit ? rows.slice(0, limit) : rows
  return (
    <div>
      <div className="flex flex-wrap gap-2 p-3 border-b bg-gray-50">
        <div className="relative flex-1 min-w-[180px]">
          <Search size={14} className="absolute left-2 top-2.5 text-gray-400" />
          <input aria-label="Search by name or USN" className="input w-full pl-7" placeholder="Search by name or USN" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <select aria-label="Filter by support category" className="input" value={category} onChange={(e) => onCategory(e.target.value)}>
          {CATEGORY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select aria-label="Filter by priority" className="input" value={priority} onChange={(e) => setPriority(e.target.value)}>
          <option value="">All priorities</option><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="NORMAL">Normal</option>
        </select>
      </div>
      {msg && <p role="status" className="px-3 pt-2 text-green-700 text-sm">{msg}</p>}
      {error ? <div className="p-3"><ErrorBox error={error} onRetry={load} /></div> : !shown ? <Loading /> : shown.length === 0 ? <Empty text="No students match these filters" /> : (
        <ul className="divide-y">
          {shown.map((s) => (
            <li key={s.studentId} className="px-3 py-3 flex flex-wrap items-start gap-3">
              <div className="flex-1 min-w-[220px]">
                <div className="flex flex-wrap items-center gap-2">
                  <Link className="font-semibold text-blue-700 hover:underline" to={studentLink(user?.role, s.studentId)}>{s.name}</Link>
                  <span className="text-xs text-gray-500">{s.usn} · {s.department} · Sem {s.semester}</span>
                </div>
                <div className="text-xs text-gray-700 mt-1 flex flex-wrap gap-x-4 gap-y-0.5">
                  {s.attendancePct != null && <span>Attendance: <b>{s.attendancePct}%</b></span>}
                  {s.iaAttended != null && <span>IA Attendance: <b>{s.iaAttended}/3</b></span>}
                  {s.overdueAssignments > 0 && <span>Overdue Assignments: <b>{s.overdueAssignments}</b></span>}
                  {s.consecutiveAbsences > 0 && <span>Consecutive absences: <b>{s.consecutiveAbsences}</b></span>}
                </div>
                {s.reasons.length > 0 && <ul className="text-xs text-gray-500 mt-1 list-disc ml-4">{s.reasons.map((r: string, i: number) => <li key={i}>{r}</li>)}</ul>}
              </div>
              <div className="flex flex-col items-end gap-1.5">
                {s.category !== 'NONE' && <Badge tone={priorityTone(s.priority)}>{s.label}</Badge>}
                {s.category !== 'NONE' && <span className="text-[11px] text-gray-400">{priorityLabel(s.priority)}</span>}
                <div className="flex gap-2">
                  <button className="btn btn-ghost" onClick={() => setAction(s.studentId)}>Create Support Action</button>
                  <Link className="btn btn-primary" to={studentLink(user?.role, s.studentId)}>View Details</Link>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
      {action != null && <SupportActionModal studentId={action} onClose={() => setAction(null)} onDone={() => { setMsg('Support action created'); setTimeout(() => setMsg(''), 4000) }} />}
    </div>
  )
}
