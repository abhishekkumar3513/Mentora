import { useState, type FormEvent } from 'react'
import { Plus } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { Async, Modal, Page, SectionHeader, useLoad } from '../../components/ui'
import { LeaveTable, type Leave } from '../../components/Leaves'

const today = () => new Date().toISOString().slice(0, 10)

export default function StudentLeaves() {
  const { user } = useAuth()
  const st = useLoad<Leave[]>(() => api.get(`/api/students/${user!.studentId}/leaves`).then((r) => r.data), [])
  const [open, setOpen] = useState(false)
  const [type, setType] = useState('MEDICAL')
  const [start, setStart] = useState(today())
  const [end, setEnd] = useState(today())
  const [reason, setReason] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    const big = files.find((f) => f.size > 5 * 1024 * 1024)
    if (big) return setError(`${big.name} exceeds 5 MB`)
    setBusy(true)
    try {
      const fd = new FormData()
      fd.append('type', type); fd.append('startDate', start); fd.append('endDate', end); fd.append('reason', reason)
      files.forEach((f) => fd.append('files', f))
      await api.post('/api/leaves', fd)
      setOpen(false); setReason(''); setFiles([])
      st.reload()
    } catch (err) { setError(errMsg(err)) }
    setBusy(false)
  }

  return (
    <Page title="Leave Applications" subtitle="Submitting does not change attendance; only an approval by your mentor does"
      actions={<button className="btn btn-primary" onClick={() => setOpen(true)}><Plus size={16} />New application</button>}>
      <div className="card"><SectionHeader>My applications</SectionHeader>
        <Async state={st}>{(list) => <LeaveTable leaves={list} />}</Async></div>
      {open && (
        <Modal title="New leave application" onClose={() => setOpen(false)}>
          <form onSubmit={submit} className="space-y-3">
            <div><label htmlFor="lt" className="text-xs font-semibold text-gray-600">Leave type</label>
              <select id="lt" className="input w-full" value={type} onChange={(e) => setType(e.target.value)}>
                <option value="MEDICAL">Medical</option><option value="PERSONAL">Personal</option><option value="COLLEGE_EVENT">College event participation</option><option value="OTHER">Other</option>
              </select></div>
            <div className="grid grid-cols-2 gap-2">
              <div><label htmlFor="ls" className="text-xs font-semibold text-gray-600">Start date</label><input id="ls" type="date" required className="input w-full" value={start} onChange={(e) => setStart(e.target.value)} /></div>
              <div><label htmlFor="le" className="text-xs font-semibold text-gray-600">End date</label><input id="le" type="date" required min={start} className="input w-full" value={end} onChange={(e) => setEnd(e.target.value)} /></div>
            </div>
            <div><label htmlFor="lr" className="text-xs font-semibold text-gray-600">Reason</label><textarea id="lr" required rows={4} maxLength={2000} className="input w-full" value={reason} onChange={(e) => setReason(e.target.value)} /></div>
            <div><label htmlFor="lf" className="text-xs font-semibold text-gray-600">Supporting documents (PDF, JPG, PNG; max 5 MB each)</label>
              <input id="lf" type="file" multiple accept="application/pdf,image/jpeg,image/png" className="block text-xs" onChange={(e) => setFiles(Array.from(e.target.files || []))} /></div>
            {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
            <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-primary" disabled={busy}>Submit</button></div>
          </form>
        </Modal>
      )}
    </Page>
  )
}
