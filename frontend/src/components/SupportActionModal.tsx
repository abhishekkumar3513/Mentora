import { useEffect, useState, type FormEvent } from 'react'
import { api, errMsg } from '../lib/api'
import { Modal } from './ui'

/** Create a support action (intervention) for a student. Used from the overview, notifications, student profile and the actions page. */
export default function SupportActionModal({ studentId, signalId, initialObservation, onClose, onDone }: {
  studentId?: number
  signalId?: number
  initialObservation?: string
  onClose: () => void
  onDone: (created: any) => void
}) {
  const [students, setStudents] = useState<any[]>([])
  const [cats, setCats] = useState<any[]>([])
  const [owners, setOwners] = useState<{ roles: any[]; people: any[] }>({ roles: [], people: [] })
  const [sid, setSid] = useState<string>(studentId ? String(studentId) : '')
  const [category, setCategory] = useState('')
  const [ownerRole, setOwnerRole] = useState('')
  const [ownerUserId, setOwnerUserId] = useState('')
  const [observation, setObservation] = useState(initialObservation ?? '')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get('/api/support/categories').then((r) => setCats(r.data)).catch(() => {})
    api.get('/api/support/owners').then((r) => setOwners(r.data)).catch(() => {})
    if (!studentId) api.get('/api/overview/students?category=ALL').then((r) => setStudents(r.data)).catch(() => {})
  }, [studentId])

  const pickCategory = (code: string) => {
    setCategory(code)
    const c = cats.find((x) => x.code === code)
    if (c) setOwnerRole(c.defaultOwner) // category-based suggestion; the staff member can change it
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      const r = await api.post('/api/support/actions', { studentId: Number(sid), category, ownerRole: ownerRole || null, ownerUserId: ownerUserId ? Number(ownerUserId) : null, observation, signalId: signalId ?? null })
      onDone(r.data)
      onClose()
    } catch (err) { setError(errMsg(err)); setBusy(false) }
  }

  const confidential = cats.find((c) => c.code === category)?.confidential
  return (
    <Modal title="Create Support Action" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <div>
          <label htmlFor="sa-student" className="text-xs font-semibold text-gray-600">Student *</label>
          {studentId ? (
            <input id="sa-student" className="input w-full bg-gray-50" disabled value={`Selected student (#${studentId})`} />
          ) : (
            <select id="sa-student" required className="input w-full" value={sid} onChange={(e) => setSid(e.target.value)}>
              <option value="">Select a student…</option>
              {students.map((s) => <option key={s.studentId} value={s.studentId}>{s.name} ({s.usn})</option>)}
            </select>
          )}
        </div>
        <div>
          <label htmlFor="sa-cat" className="text-xs font-semibold text-gray-600">Support Category *</label>
          <select id="sa-cat" required className="input w-full" value={category} onChange={(e) => pickCategory(e.target.value)}>
            <option value="">Select a category…</option>
            {cats.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
          </select>
          {confidential && <p className="text-xs text-amber-700 mt-1">Confidential referral: details are visible only to authorised counsellors and administrators.</p>}
        </div>
        <div className="grid sm:grid-cols-2 gap-3">
          <div>
            <label htmlFor="sa-owner" className="text-xs font-semibold text-gray-600">Action Owner *</label>
            <select id="sa-owner" required className="input w-full" value={ownerRole} onChange={(e) => setOwnerRole(e.target.value)}>
              <option value="">Select…</option>
              {owners.roles.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="sa-person" className="text-xs font-semibold text-gray-600">Assigned person (optional)</label>
            <select id="sa-person" className="input w-full" value={ownerUserId} onChange={(e) => setOwnerUserId(e.target.value)}>
              <option value="">Default for this owner</option>
              {owners.people.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
        </div>
        <div>
          <label htmlFor="sa-obs" className="text-xs font-semibold text-gray-600">Observation / Action Required *</label>
          <textarea id="sa-obs" required rows={4} maxLength={2000} className="input w-full" value={observation} onChange={(e) => setObservation(e.target.value)} placeholder="What was observed and what support is planned?" />
        </div>
        {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={busy || !sid || !category}>{busy ? 'Creating…' : 'Create Support Action'}</button>
        </div>
      </form>
    </Modal>
  )
}
