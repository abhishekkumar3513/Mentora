import { useState } from 'react'
import { Pencil, Plus } from 'lucide-react'
import { api } from '../../lib/api'
import FormModal, { type Field } from '../../components/FormModal'
import { Async, Badge, Empty, Page, useLoad } from '../../components/ui'

export default function AdminStudents() {
  const [q, setQ] = useState('')
  const [search, setSearch] = useState('')
  const st = useLoad<any[]>(() => api.get('/api/admin/students', { params: { search: q } }).then((r) => r.data), [q])
  const depts = useLoad<any[]>(() => api.get('/api/admin/departments').then((r) => r.data), [])
  const [edit, setEdit] = useState<any | 'new' | null>(null)

  const fields = (isNew: boolean): Field[] => [
    { key: 'name', label: 'Full name', required: true },
    { key: 'email', label: 'Email', type: 'email', required: true },
    { key: 'usn', label: 'USN', required: isNew },
    { key: 'password', label: isNew ? 'Password (min 8)' : 'New password (leave blank to keep)', type: 'password', required: isNew },
    { key: 'semester', label: 'Semester (1-8)', type: 'number', required: true, min: 1, max: 8 },
    { key: 'section', label: 'Section' },
    { key: 'departmentId', label: 'Department', type: 'select', required: true, options: (depts.data || []).map((d) => ({ value: d.id, label: d.name })) },
    ...(isNew ? [] : [{ key: 'enabled', label: 'Account enabled', type: 'checkbox' as const }]),
  ]

  return (
    <Page title="Students" subtitle="Create and manage student accounts" actions={<button className="btn btn-primary" onClick={() => setEdit('new')}><Plus size={16} />New student</button>}>
      <form className="flex gap-2 mb-3" onSubmit={(e) => { e.preventDefault(); setQ(search) }}>
        <input className="input w-64" aria-label="Search students" placeholder="Search name or USN" value={search} onChange={(e) => setSearch(e.target.value)} /><button className="btn btn-ghost">Search</button>
      </form>
      <Async state={st}>
        {(list) => list.length === 0 ? <Empty text="No students" /> : (
          <div className="overflow-x-auto"><table className="erp-table"><thead><tr><th>USN</th><th className="text-left">Name</th><th>Email</th><th>Sem</th><th>Department</th><th>Mentor</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>{list.map((s) => (
              <tr key={s.id}><td>{s.usn}</td><td className="text-left text-blue-600">{s.name}</td><td className="text-xs">{s.email}</td><td>{s.semester}</td><td>{s.department}</td>
                <td>{s.mentorName || <Badge tone="warn">Unassigned</Badge>}</td><td><Badge tone={s.enabled ? 'good' : 'bad'}>{s.enabled ? 'Active' : 'Disabled'}</Badge></td>
                <td><button className="btn btn-ghost" onClick={() => setEdit(s)} aria-label={`Edit ${s.name}`}><Pencil size={14} />Edit</button></td></tr>
            ))}</tbody></table></div>
        )}
      </Async>
      {edit && <FormModal title={edit === 'new' ? 'New student' : `Edit ${edit.name}`} fields={fields(edit === 'new')} initial={edit === 'new' ? { semester: 5 } : { ...edit, password: '' }}
        onClose={() => setEdit(null)} onSubmit={async (v) => { if (edit === 'new') await api.post('/api/admin/students', v); else await api.put(`/api/admin/students/${edit.id}`, v); st.reload() }} />}
    </Page>
  )
}
