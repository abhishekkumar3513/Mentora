import { useEffect, useState, type FormEvent } from 'react'
import { Lock } from 'lucide-react'
import { api, errMsg } from '../lib/api'
import { STATUS_LABEL, statusToneOf } from '../lib/support'
import { Badge, Loading, Modal, fmtDate, fmtDay } from './ui'

/** Staff view of one request/intervention: timeline (including internal notes) and the controls to progress it. */
export default function RequestDetail({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const [r, setR] = useState<any>(null)
  const [owners, setOwners] = useState<{ roles: any[]; people: any[] }>({ roles: [], people: [] })
  const [f, setF] = useState<any>({ status: '', ownerRole: '', ownerUserId: '', followUpDate: '', internalNote: '', studentFeedback: '', outcome: '' })
  const [error, setError] = useState('')
  const [ok, setOk] = useState('')
  const [busy, setBusy] = useState(false)

  const load = () => api.get(`/api/support/requests/${id}`).then((x) => { setR(x.data); setF((p: any) => ({ ...p, status: x.data.status, ownerRole: x.data.ownerRole, ownerUserId: x.data.ownerUserId ?? '' })) }).catch((e) => setError(errMsg(e)))
  useEffect(() => { load(); api.get('/api/support/owners').then((x) => setOwners(x.data)).catch(() => {}) }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError(''); setOk('')
    const body: any = {}
    if (f.status && f.status !== r.status) body.status = f.status
    if (f.ownerRole !== r.ownerRole || String(f.ownerUserId) !== String(r.ownerUserId ?? '')) { body.ownerRole = f.ownerRole; if (f.ownerUserId) body.ownerUserId = Number(f.ownerUserId) }
    for (const k of ['followUpDate', 'internalNote', 'studentFeedback', 'outcome']) if (f[k]) body[k] = f[k]
    try {
      const x = await api.put(`/api/support/requests/${id}`, body)
      setR(x.data); setF({ status: x.data.status, ownerRole: x.data.ownerRole, ownerUserId: x.data.ownerUserId ?? '', followUpDate: '', internalNote: '', studentFeedback: '', outcome: '' })
      setOk('Saved'); onChanged()
    } catch (err) { setError(errMsg(err)) }
    setBusy(false)
  }

  if (!r) return <Modal title="Support request" onClose={onClose}>{error ? <p role="alert" className="text-red-600">{error}</p> : <Loading />}</Modal>
  const closed = r.status === 'CLOSED'
  return (
    <Modal title={`${r.categoryName} – ${r.studentName}`} onClose={onClose} wide>
      <div className="space-y-4">
        <div className="flex flex-wrap gap-2 items-center text-sm">
          <Badge tone={statusToneOf(r.status)}>{STATUS_LABEL[r.status]}</Badge>
          <span className="text-gray-500">{r.usn} · Owner: {r.ownerRoleLabel}{r.ownerName ? ` (${r.ownerName})` : ''}</span>
          {r.followUpDate && <Badge tone={r.followUpState === 'OVERDUE' ? 'bad' : r.followUpState === 'DUE' ? 'warn' : 'neutral'}>Follow-up {fmtDay(r.followUpDate)}{r.followUpState === 'OVERDUE' ? ' (overdue)' : ''}</Badge>}
          {r.confidential && <Badge tone="warn"><Lock size={11} className="inline mr-1" />Confidential</Badge>}
        </div>
        <div className="bg-gray-50 border rounded p-3 text-sm whitespace-pre-wrap">
          <div className="text-xs font-semibold text-gray-500 mb-1">{r.source === 'STUDENT' ? 'Raised by the student' : 'Support action'}</div>
          {r.concern}
          {r.helpNeeded && <div className="mt-2"><span className="text-xs font-semibold text-gray-500">Help required: </span>{r.helpNeeded}</div>}
        </div>
        {r.restricted ? <p className="text-sm text-amber-700">This is a confidential referral. Only authorised counsellors and administrators can see the details or update it.</p> : (
          <>
            {r.outcome && <div className="text-sm"><span className="text-xs font-semibold text-gray-500">Outcome: </span>{r.outcome}</div>}
            <div>
              <div className="text-xs font-semibold text-gray-500 mb-1">Timeline</div>
              <ul className="border rounded divide-y text-sm max-h-48 overflow-auto">
                {r.timeline.map((t: any) => (
                  <li key={t.id} className="px-3 py-1.5 flex gap-2">
                    <span className="flex-1">{t.type === 'NOTE' && <Badge tone="warn">Internal</Badge>}{t.type === 'FEEDBACK' && <Badge tone="info">Shared with student</Badge>} {t.message}</span>
                    <span className="text-xs text-gray-400 whitespace-nowrap">{t.author ?? 'System'} · {fmtDate(t.createdAt)}</span>
                  </li>
                ))}
              </ul>
            </div>
            {!closed && (
              <form onSubmit={save} className="space-y-3 border-t pt-3">
                <div className="grid sm:grid-cols-3 gap-3">
                  <div><label htmlFor="rd-status" className="text-xs font-semibold text-gray-600">Status</label>
                    <select id="rd-status" className="input w-full" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}>
                      {Object.entries(STATUS_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select></div>
                  <div><label htmlFor="rd-owner" className="text-xs font-semibold text-gray-600">Action owner</label>
                    <select id="rd-owner" className="input w-full" value={f.ownerRole} onChange={(e) => setF({ ...f, ownerRole: e.target.value })}>
                      {owners.roles.map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}
                    </select></div>
                  <div><label htmlFor="rd-person" className="text-xs font-semibold text-gray-600">Assigned person</label>
                    <select id="rd-person" className="input w-full" value={f.ownerUserId} onChange={(e) => setF({ ...f, ownerUserId: e.target.value })}>
                      <option value="">Unassigned</option>
                      {owners.people.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </select></div>
                </div>
                <div className="grid sm:grid-cols-2 gap-3">
                  <div><label htmlFor="rd-fu" className="text-xs font-semibold text-gray-600">Schedule follow-up</label>
                    <input id="rd-fu" type="date" className="input w-full" value={f.followUpDate} onChange={(e) => setF({ ...f, followUpDate: e.target.value })} /></div>
                  <div><label htmlFor="rd-out" className="text-xs font-semibold text-gray-600">Outcome (required to resolve)</label>
                    <input id="rd-out" className="input w-full" maxLength={2000} value={f.outcome} onChange={(e) => setF({ ...f, outcome: e.target.value })} /></div>
                </div>
                <div><label htmlFor="rd-note" className="text-xs font-semibold text-gray-600 flex items-center gap-1"><Lock size={11} />Internal note (never shown to the student)</label>
                  <textarea id="rd-note" rows={2} maxLength={2000} className="input w-full" value={f.internalNote} onChange={(e) => setF({ ...f, internalNote: e.target.value })} /></div>
                <div><label htmlFor="rd-fb" className="text-xs font-semibold text-gray-600">Feedback visible to the student</label>
                  <textarea id="rd-fb" rows={2} maxLength={2000} className="input w-full" value={f.studentFeedback} onChange={(e) => setF({ ...f, studentFeedback: e.target.value })} /></div>
                {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
                {ok && <p role="status" className="text-green-700 text-sm">{ok}</p>}
                <div className="flex justify-end"><button className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</button></div>
              </form>
            )}
          </>
        )}
      </div>
    </Modal>
  )
}
