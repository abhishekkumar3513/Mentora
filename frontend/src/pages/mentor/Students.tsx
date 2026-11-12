import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUp, Search, UserMinus, UserPlus } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import { Async, Avatar, Badge, Confirm, Empty, Modal, Page, Pagination, fmtDate, statusTone, useLoad } from '../../components/ui'

const COLS: [string, string][] = [['semester', 'Sem'], ['attendance', 'Attendance'], ['ia1', 'IA 1'], ['ia2', 'IA 2'], ['ia3', 'IA 3'], ['cgpa', 'CGPA']]

function AddStudentDialog({ onClose, onAdded }: { onClose: () => void; onAdded: () => void }) {
  const [q, setQ] = useState('')
  const [error, setError] = useState('')
  const [added, setAdded] = useState<string[]>([])
  const st = useLoad<any[]>(() => api.get('/api/mentor/students/available', { params: { search: q } }).then((r) => r.data), [q, added.length])
  const add = async (usn: string) => {
    setError('')
    try { await api.post('/api/mentor/students', { usn }); setAdded((a) => [...a, usn]); onAdded() } catch (e) { setError(errMsg(e)) }
  }
  return (
    <Modal title="Add student to my list" wide onClose={onClose}>
      <p className="text-xs text-gray-500 mb-2">Only students who currently have no mentor are listed. Students assigned to another mentor must be reassigned by the administrator.</p>
      <input className="input w-full mb-3" aria-label="Search available students" placeholder="Search name or USN" value={q} onChange={(e) => setQ(e.target.value)} />
      {error && <p role="alert" className="text-red-600 text-sm mb-2">{error}</p>}
      <Async state={st}>
        {(list) => list.length === 0 ? <Empty text="No unassigned students found" /> : (
          <table className="erp-table"><thead><tr><th>USN</th><th className="text-left">Name</th><th>Sem</th><th></th></tr></thead>
            <tbody>{list.map((s) => (
              <tr key={s.studentId}><td>{s.usn}</td><td className="text-left">{s.name}</td><td>{s.semester}</td>
                <td><button className="btn btn-primary" onClick={() => add(s.usn)} aria-label={`Add ${s.name}`}><UserPlus size={14} />Add</button></td></tr>
            ))}</tbody></table>
        )}
      </Async>
    </Modal>
  )
}

export default function MentorStudents() {
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [sort, setSort] = useState('usn')
  const [dir, setDir] = useState('asc')
  const [filter, setFilter] = useState('ALL')
  const [page, setPage] = useState(0)
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<any | null>(null)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  const st = useLoad<any>(() => api.get('/api/mentor/students', { params: { search: q, sort, dir, filter, page, size: 10 } }).then((r) => r.data), [q, sort, dir, filter, page])

  const toggle = (k: string) => { if (sort === k) setDir(dir === 'asc' ? 'desc' : 'asc'); else { setSort(k); setDir('asc') } setPage(0) }
  const Th = ({ k, children, left }: { k: string; children: any; left?: boolean }) => (
    <th className={left ? 'text-left' : ''}><button className="inline-flex items-center gap-1" onClick={() => toggle(k)} aria-label={`Sort by ${children}`}>{children}{sort === k && (dir === 'asc' ? <ArrowUp size={12} /> : <ArrowDown size={12} />)}</button></th>
  )

  const remove = async () => {
    try {
      await api.delete(`/api/mentor/students/${removing.studentId}`)
      setNotice({ ok: true, text: `${removing.name} was removed from your list.` })
      st.reload()
    } catch (e) { setNotice({ ok: false, text: errMsg(e) }) }
  }

  return (
    <Page title="My Students" subtitle="Only students assigned to you are listed"
      actions={<button className="btn btn-primary" onClick={() => setAdding(true)}><UserPlus size={16} />Add student</button>}>
      <form className="flex flex-wrap gap-2 mb-3" onSubmit={(e) => { e.preventDefault(); setQ(search); setPage(0) }}>
        <div className="relative"><Search size={14} className="absolute left-2 top-2.5 text-gray-400" />
          <input className="input pl-7 w-64" placeholder="Search name or USN" aria-label="Search students" value={search} onChange={(e) => setSearch(e.target.value)} /></div>
        <button className="btn btn-primary">Search</button>
        <select className="input" aria-label="Filter" value={filter} onChange={(e) => { setFilter(e.target.value); setPage(0) }}>
          <option value="ALL">All students</option><option value="ATTENTION">Needs attention</option><option value="LOW_ATTENDANCE">Low attendance</option><option value="DECLINING">Declining marks</option><option value="PENDING_LEAVE">Pending leave</option><option value="EXCELLENT">Excellent</option>
        </select>
      </form>
      {notice && <p role="status" className={`mb-2 text-sm ${notice.ok ? 'text-green-700' : 'text-red-600'}`}>{notice.text}</p>}
      <Async state={st}>
        {(d) => d.content.length === 0 ? <Empty text="No students match" /> : (
          <>
            <div className="overflow-x-auto">
              <table className="erp-table min-w-[960px]">
                <thead><tr><th>Sl.</th><Th k="name" left>Student</Th><Th k="usn">USN</Th>{COLS.map(([k, l]) => <Th key={k} k={k}>{l}</Th>)}<th>Status</th><th>Last message</th><th>Actions</th></tr></thead>
                <tbody>
                  {d.content.map((s: any, i: number) => (
                    <tr key={s.studentId}>
                      <td>{d.page * d.size + i + 1}</td>
                      <td className="text-left"><div className="flex items-center gap-2"><Avatar name={s.name} size={30} /><Link className="text-blue-600 hover:underline font-semibold" to={`/mentor/students/${s.studentId}`}>{s.name}</Link></div></td>
                      <td>{s.usn}</td><td>{s.semester}</td>
                      <td className={s.lowAttendance ? 'text-red-600 font-bold' : ''}>{s.attendancePct}%</td>
                      <td>{s.ia1 ?? '—'}</td><td>{s.ia2 ?? '—'}</td><td>{s.ia3 ?? '—'}</td><td>{s.cgpa ?? '—'}</td>
                      <td title={s.flags.join(', ')}><Badge tone={statusTone(s.status)}>{s.status}</Badge></td>
                      <td className="text-xs text-gray-500">{s.lastMessage ? fmtDate(s.lastMessage) : '—'}</td>
                      <td><button className="btn btn-ghost text-red-600" onClick={() => setRemoving(s)} aria-label={`Remove ${s.name}`}><UserMinus size={14} />Remove</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-gray-500 mt-1">{d.total} student(s). IA columns show the average marks out of 25 across subjects.</p>
            <Pagination page={d.page} totalPages={d.totalPages} onChange={setPage} />
          </>
        )}
      </Async>
      {adding && <AddStudentDialog onClose={() => setAdding(false)} onAdded={() => st.reload()} />}
      {removing && <Confirm danger title="Remove student" confirmLabel="Remove" message={`Remove ${removing.name} (${removing.usn}) from your list? You will lose access to their records and chat; the student becomes unassigned until the administrator or a mentor takes them on. Their history is kept.`}
        onClose={() => setRemoving(null)} onConfirm={remove} />}
    </Page>
  )
}
