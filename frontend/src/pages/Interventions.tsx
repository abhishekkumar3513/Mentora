import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Lock, Plus } from 'lucide-react'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useBus } from '../lib/bus'
import { STATUS_LABEL, statusToneOf, studentLink } from '../lib/support'
import { Async, Badge, Empty, Page, SectionHeader, fmtDay, useLoad } from '../components/ui'
import RequestDetail from '../components/RequestDetail'
import SupportActionModal from '../components/SupportActionModal'

const isOpen = (r: any) => r.status !== 'RESOLVED' && r.status !== 'CLOSED'

/** Mentor / admin intervention management: pending, in progress, follow-ups due, overdue, resolved. */
export default function Interventions() {
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const st = useLoad<any[]>(() => api.get('/api/support/requests').then((r) => r.data), [])
  const [creating, setCreating] = useState(false)
  const open = params.get('open') ? Number(params.get('open')) : null
  const reload = st.reload
  useBus('notifications', useCallback(() => reload(), [reload]))

  const sections: [string, string, (r: any) => boolean][] = [
    ['Pending Interventions', 'Submitted or assigned, work not started', (r) => r.status === 'SUBMITTED' || r.status === 'ASSIGNED'],
    ['In Progress', 'Being worked on', (r) => (r.status === 'IN_PROGRESS' || r.status === 'FOLLOW_UP_REQUIRED') && r.followUpState !== 'OVERDUE' && r.followUpState !== 'DUE'],
    ['Follow-ups Due', 'Follow-up date is today', (r) => isOpen(r) && r.followUpState === 'DUE'],
    ['Overdue Follow-ups', 'Follow-up date has passed', (r) => isOpen(r) && r.followUpState === 'OVERDUE'],
    ['Resolved Interventions', 'Resolved or closed', (r) => !isOpen(r)],
  ]
  return (
    <Page title="Support Actions" subtitle="Interventions and student support requests" actions={<button className="btn btn-primary" onClick={() => setCreating(true)}><Plus size={15} />Create Support Action</button>}>
      <Async state={st}>
        {(list) => (
          <div className="grid lg:grid-cols-2 gap-4">
            {sections.map(([title, hint, fn]) => {
              const rows = list.filter(fn)
              return (
                <div key={title} className="card">
                  <SectionHeader>{title} ({rows.length})</SectionHeader>
                  <p className="text-xs text-gray-500 px-3 pt-2">{hint}</p>
                  {rows.length === 0 ? <Empty text="Nothing here" /> : (
                    <ul className="divide-y">
                      {rows.map((r) => (
                        <li key={r.id}>
                          <button className="w-full text-left px-3 py-2 hover:bg-gray-50 focus:outline-none focus:bg-gray-50" onClick={() => setParams({ open: String(r.id) })}>
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="font-semibold">{r.studentName}</span><span className="text-xs text-gray-500">{r.usn}</span>
                              <Badge tone={statusToneOf(r.status)}>{STATUS_LABEL[r.status]}</Badge>
                              {r.confidential && <Lock size={12} className="text-amber-600" aria-label="Confidential" />}
                            </div>
                            <div className="text-xs text-gray-600">{r.categoryName} · Owner: {r.ownerRoleLabel}{r.ownerName ? ` (${r.ownerName})` : ''}{r.source === 'STUDENT' ? ' · Student request' : ''}</div>
                            <div className="text-xs text-gray-500 truncate">{r.concern}</div>
                            {r.followUpDate && isOpen(r) && <div className={`text-xs ${r.followUpState === 'OVERDUE' ? 'text-red-600' : 'text-gray-500'}`}>Follow-up: {fmtDay(r.followUpDate)}</div>}
                          </button>
                          <div className="px-3 pb-2"><Link className="text-xs text-blue-700 hover:underline" to={studentLink(user?.role, r.studentId)}>Open student profile</Link></div>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </Async>
      {open && <RequestDetail id={open} onClose={() => setParams({})} onChanged={reload} />}
      {creating && <SupportActionModal onClose={() => setCreating(false)} onDone={(c) => { reload(); setParams({ open: String(c.id) }) }} />}
    </Page>
  )
}
