import { useState } from 'react'
import { api, errMsg } from '../../lib/api'
import { Async, Badge, Confirm, Page, useLoad } from '../../components/ui'

export default function Assignments() {
  const students = useLoad<any[]>(() => api.get('/api/admin/students').then((r) => r.data), [])
  const mentors = useLoad<any[]>(() => api.get('/api/admin/mentors').then((r) => r.data), [])
  const [error, setError] = useState('')
  const [pending, setPending] = useState<{ student: any; mentorId: number } | null>(null)
  const [unassign, setUnassign] = useState<any | null>(null)
  const reloadAll = () => { students.reload(); mentors.reload() }
  return (
    <Page title="Mentor Assignments" subtitle="Each student has exactly one active mentor">
      {error && <p role="alert" className="text-red-600 mb-2">{error}</p>}
      <div className="grid lg:grid-cols-[1fr_260px] gap-4">
        <Async state={students}>{(list) => (
          <div className="overflow-x-auto"><table className="erp-table"><thead><tr><th>USN</th><th className="text-left">Student</th><th>Current mentor</th><th>Assign to</th><th></th></tr></thead>
            <tbody>{list.map((s) => (
              <tr key={s.id}><td>{s.usn}</td><td className="text-left">{s.name}</td><td>{s.mentorName || <Badge tone="warn">Unassigned</Badge>}</td>
                <td><select className="input" aria-label={`Assign mentor for ${s.name}`} value="" onChange={(e) => e.target.value && setPending({ student: s, mentorId: Number(e.target.value) })}>
                  <option value="">Choose mentor…</option>{(mentors.data || []).filter((m) => m.id !== s.mentorId && m.enabled).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}</select></td>
                <td>{s.mentorId && <button className="btn btn-ghost" onClick={() => setUnassign(s)}>Unassign</button>}</td></tr>
            ))}</tbody></table></div>
        )}</Async>
        <Async state={mentors}>{(list) => (
          <div className="card self-start"><div className="p-2 font-semibold border-b">Workload</div>
            <ul className="divide-y">{list.map((m) => <li key={m.id} className="p-2 flex justify-between"><span>{m.name}</span><Badge>{m.students} students</Badge></li>)}</ul></div>
        )}</Async>
      </div>
      {pending && <Confirm title="Change mentor assignment" message={`Assign ${pending.student.name} to ${(mentors.data || []).find((m) => m.id === pending.mentorId)?.name}? The previous mentor will lose access to this student immediately.`}
        onClose={() => setPending(null)} onConfirm={async () => { setError(''); try { await api.post('/api/admin/assignments', { studentId: pending.student.id, facultyId: pending.mentorId }); reloadAll() } catch (e) { setError(errMsg(e)) } }} />}
      {unassign && <Confirm danger title="Unassign student" message={`Remove the mentor from ${unassign.name}?`} confirmLabel="Unassign" onClose={() => setUnassign(null)}
        onConfirm={async () => { await api.delete(`/api/admin/assignments/${unassign.id}`); reloadAll() }} />}
    </Page>
  )
}
