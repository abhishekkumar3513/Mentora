import { useState } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { api } from '../../lib/api'
import FormModal, { type Field } from '../../components/FormModal'
import { Async, Badge, Confirm, Empty, Page, useLoad } from '../../components/ui'

export function Departments() {
  const st = useLoad<any[]>(() => api.get('/api/admin/departments').then((r) => r.data), [])
  const [edit, setEdit] = useState<any | 'new' | null>(null)
  const fields: Field[] = [{ key: 'code', label: 'Code', required: true }, { key: 'name', label: 'Name', required: true }]
  return (
    <Page title="Departments" actions={<button className="btn btn-primary" onClick={() => setEdit('new')}><Plus size={16} />New department</button>}>
      <Async state={st}>{(l) => l.length === 0 ? <Empty text="No departments" /> : (
        <table className="erp-table"><thead><tr><th>Code</th><th className="text-left">Name</th><th>Actions</th></tr></thead>
          <tbody>{l.map((d) => <tr key={d.id}><td>{d.code}</td><td className="text-left">{d.name}</td><td><button className="btn btn-ghost" onClick={() => setEdit(d)}><Pencil size={14} />Edit</button></td></tr>)}</tbody></table>
      )}</Async>
      {edit && <FormModal title={edit === 'new' ? 'New department' : 'Edit department'} fields={fields} initial={edit === 'new' ? {} : edit} onClose={() => setEdit(null)}
        onSubmit={async (v) => { if (edit === 'new') await api.post('/api/admin/departments', v); else await api.put(`/api/admin/departments/${edit.id}`, v); st.reload() }} />}
    </Page>
  )
}

export function Semesters() {
  const st = useLoad<any[]>(() => api.get('/api/admin/semesters').then((r) => r.data), [])
  const [edit, setEdit] = useState<any | 'new' | null>(null)
  const fields: Field[] = [{ key: 'number', label: 'Semester number (1-8)', type: 'number', required: true, min: 1, max: 8 }, { key: 'academicYear', label: 'Academic year (e.g. 2025-26)', required: true }, { key: 'current', label: 'Current semester', type: 'checkbox' }]
  return (
    <Page title="Semesters" actions={<button className="btn btn-primary" onClick={() => setEdit('new')}><Plus size={16} />New semester</button>}>
      <Async state={st}>{(l) => l.length === 0 ? <Empty text="No semesters" /> : (
        <table className="erp-table"><thead><tr><th>Semester</th><th>Academic year</th><th>Current</th><th>Actions</th></tr></thead>
          <tbody>{l.map((s) => <tr key={s.id}><td>{s.number}</td><td>{s.academicYear}</td><td>{s.current ? <Badge tone="good">Current</Badge> : '—'}</td><td><button className="btn btn-ghost" onClick={() => setEdit(s)}><Pencil size={14} />Edit</button></td></tr>)}</tbody></table>
      )}</Async>
      {edit && <FormModal title={edit === 'new' ? 'New semester' : 'Edit semester'} fields={fields} initial={edit === 'new' ? {} : edit} onClose={() => setEdit(null)}
        onSubmit={async (v) => { if (edit === 'new') await api.post('/api/admin/semesters', v); else await api.put(`/api/admin/semesters/${edit.id}`, v); st.reload() }} />}
    </Page>
  )
}

export function Subjects() {
  const st = useLoad<any[]>(() => api.get('/api/admin/subjects').then((r) => r.data), [])
  const depts = useLoad<any[]>(() => api.get('/api/admin/departments').then((r) => r.data), [])
  const [edit, setEdit] = useState<any | 'new' | null>(null)
  const [del, setDel] = useState<any | null>(null)
  const [err, setErr] = useState('')
  const fields: Field[] = [
    { key: 'code', label: 'Subject code', required: true }, { key: 'name', label: 'Subject name', required: true },
    { key: 'credits', label: 'Credits', type: 'number', required: true, min: 0, max: 10 }, { key: 'semester', label: 'Semester', type: 'number', required: true, min: 1, max: 8 },
    { key: 'departmentId', label: 'Department', type: 'select', required: true, options: (depts.data || []).map((d) => ({ value: d.id, label: d.name })) },
  ]
  return (
    <Page title="Subjects" actions={<button className="btn btn-primary" onClick={() => setEdit('new')}><Plus size={16} />New subject</button>}>
      {err && <p role="alert" className="text-red-600 mb-2">{err}</p>}
      <Async state={st}>{(l) => l.length === 0 ? <Empty text="No subjects" /> : (
        <table className="erp-table"><thead><tr><th>Code</th><th className="text-left">Name</th><th>Credits</th><th>Semester</th><th>Actions</th></tr></thead>
          <tbody>{l.map((s) => <tr key={s.id}><td>{s.code}</td><td className="text-left">{s.name}</td><td>{s.credits}</td><td>{s.semester}</td>
            <td className="space-x-1"><button className="btn btn-ghost" onClick={() => setEdit(s)}><Pencil size={14} />Edit</button><button className="btn btn-ghost text-red-600" aria-label={`Delete ${s.code}`} onClick={() => setDel(s)}><Trash2 size={14} /></button></td></tr>)}</tbody></table>
      )}</Async>
      {edit && <FormModal title={edit === 'new' ? 'New subject' : 'Edit subject'} fields={fields} initial={edit === 'new' ? { semester: 5, credits: 4 } : edit} onClose={() => setEdit(null)}
        onSubmit={async (v) => { if (edit === 'new') await api.post('/api/admin/subjects', v); else await api.put(`/api/admin/subjects/${edit.id}`, v); st.reload() }} />}
      {del && <Confirm danger title="Delete subject" message={`Delete ${del.code} – ${del.name}? Subjects with recorded attendance, marks or results cannot be deleted.`} confirmLabel="Delete" onClose={() => setDel(null)}
        onConfirm={async () => { setErr(''); try { await api.delete(`/api/admin/subjects/${del.id}`); st.reload() } catch (e: any) { setErr(e?.response?.data?.error || 'Delete failed') } }} />}
    </Page>
  )
}
