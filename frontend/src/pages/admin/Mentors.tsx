import { useState } from 'react'
import { Pencil, Plus } from 'lucide-react'
import { api } from '../../lib/api'
import FormModal, { type Field } from '../../components/FormModal'
import { Async, Badge, Empty, Page, useLoad } from '../../components/ui'

export default function AdminMentors() {
  const st = useLoad<any[]>(() => api.get('/api/admin/mentors').then((r) => r.data), [])
  const depts = useLoad<any[]>(() => api.get('/api/admin/departments').then((r) => r.data), [])
  const [edit, setEdit] = useState<any | 'new' | null>(null)
  const fields = (isNew: boolean): Field[] => [
    { key: 'name', label: 'Full name', required: true },
    { key: 'email', label: 'Email', type: 'email', required: isNew },
    { key: 'employeeId', label: 'Employee ID', required: isNew },
    { key: 'password', label: isNew ? 'Password (min 8)' : 'New password (leave blank to keep)', type: 'password', required: isNew },
    { key: 'designation', label: 'Designation' },
    { key: 'departmentId', label: 'Department', type: 'select', required: true, options: (depts.data || []).map((d) => ({ value: d.id, label: d.name })) },
    ...(isNew ? [] : [{ key: 'enabled', label: 'Account enabled', type: 'checkbox' as const }]),
  ]
  return (
    <Page title="Mentors" subtitle="Faculty mentor accounts" actions={<button className="btn btn-primary" onClick={() => setEdit('new')}><Plus size={16} />New mentor</button>}>
      <Async state={st}>
        {(list) => list.length === 0 ? <Empty text="No mentors" /> : (
          <div className="overflow-x-auto"><table className="erp-table"><thead><tr><th>Emp. ID</th><th className="text-left">Name</th><th>Email</th><th>Department</th><th>Designation</th><th>Students</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>{list.map((m) => (
              <tr key={m.id}><td>{m.employeeId}</td><td className="text-left text-blue-600">{m.name}</td><td className="text-xs">{m.email}</td><td>{m.department}</td><td>{m.designation}</td><td>{m.students}</td>
                <td><Badge tone={m.enabled ? 'good' : 'bad'}>{m.enabled ? 'Active' : 'Disabled'}</Badge></td>
                <td><button className="btn btn-ghost" onClick={() => setEdit(m)} aria-label={`Edit ${m.name}`}><Pencil size={14} />Edit</button></td></tr>
            ))}</tbody></table></div>
        )}
      </Async>
      {edit && <FormModal title={edit === 'new' ? 'New mentor' : `Edit ${edit.name}`} fields={fields(edit === 'new')} initial={edit === 'new' ? {} : { ...edit, password: '' }}
        onClose={() => setEdit(null)} onSubmit={async (v) => { if (edit === 'new') await api.post('/api/admin/mentors', v); else await api.put(`/api/admin/mentors/${edit.id}`, v); st.reload() }} />}
    </Page>
  )
}
