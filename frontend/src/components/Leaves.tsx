import { useState } from 'react'
import { Download, Eye, FileText } from 'lucide-react'
import { api, errMsg, fetchBlobUrl } from '../lib/api'
import { Badge, Confirm, Empty, Modal, fmtDate, fmtDay, statusTone } from './ui'

export interface Leave {
  id: number; studentId: number; studentName: string; usn: string; type: string; startDate: string; endDate: string; reason: string; status: string
  reviewComment?: string; createdAt: string; reviewedAt?: string
  documents: { id: number; fileName: string; contentType: string; size: number }[]
  history: { action: string; actor: string; comment?: string; at: string }[]
}

export const typeLabel = (t: string) => t.replace('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())

export function DocButtons({ leave }: { leave: Leave }) {
  const [preview, setPreview] = useState<{ url: string; type: string; name: string } | null>(null)
  const [err, setErr] = useState('')
  const view = async (d: Leave['documents'][0]) => {
    try { setPreview({ url: await fetchBlobUrl(`/api/leaves/${leave.id}/documents/${d.id}`), type: d.contentType, name: d.fileName }) } catch (e) { setErr(errMsg(e)) }
  }
  const download = async (d: Leave['documents'][0]) => {
    try {
      const a = document.createElement('a')
      a.href = await fetchBlobUrl(`/api/leaves/${leave.id}/documents/${d.id}?download=true`)
      a.download = d.fileName; a.click()
    } catch (e) { setErr(errMsg(e)) }
  }
  if (!leave.documents.length) return <span className="text-xs text-gray-400">No documents</span>
  return (
    <div className="space-y-1">
      {leave.documents.map((d) => (
        <div key={d.id} className="flex items-center gap-2 text-xs">
          <FileText size={14} className="text-gray-500" /><span className="truncate max-w-[180px]">{d.fileName}</span>
          <button className="text-blue-600 hover:underline flex items-center gap-0.5" onClick={() => view(d)}><Eye size={12} />Preview</button>
          <button className="text-blue-600 hover:underline flex items-center gap-0.5" onClick={() => download(d)}><Download size={12} />Download</button>
        </div>
      ))}
      {err && <p role="alert" className="text-xs text-red-600">{err}</p>}
      {preview && (
        <Modal title={preview.name} wide onClose={() => setPreview(null)}>
          {preview.type.startsWith('image/') ? <img src={preview.url} alt={preview.name} className="max-w-full mx-auto" /> : <iframe title={preview.name} src={preview.url} className="w-full h-[70vh] border" />}
        </Modal>
      )}
    </div>
  )
}

export function LeaveTable({ leaves, showStudent, onReview }: { leaves: Leave[]; showStudent?: boolean; onReview?: (l: Leave) => void }) {
  const [detail, setDetail] = useState<Leave | null>(null)
  if (!leaves.length) return <Empty text="No leave applications" />
  return (
    <>
      <div className="overflow-x-auto">
        <table className="erp-table">
          <thead><tr>{showStudent && <th>Student</th>}<th>Type</th><th>Period</th><th className="text-left">Reason</th><th>Documents</th><th>Submitted</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>
            {leaves.map((l) => (
              <tr key={l.id}>
                {showStudent && <td className="text-left"><div className="font-semibold text-blue-600">{l.studentName}</div><div className="text-xs text-gray-500">{l.usn}</div></td>}
                <td>{typeLabel(l.type)}</td>
                <td className="whitespace-nowrap">{fmtDay(l.startDate)} – {fmtDay(l.endDate)}</td>
                <td className="text-left max-w-xs"><div className="line-clamp-2">{l.reason}</div></td>
                <td><DocButtons leave={l} /></td>
                <td className="whitespace-nowrap text-xs">{fmtDate(l.createdAt)}</td>
                <td><Badge tone={statusTone(l.status)}>{l.status}</Badge></td>
                <td className="space-x-1 whitespace-nowrap">
                  <button className="btn btn-ghost" onClick={() => setDetail(l)}>Details</button>
                  {onReview && l.status === 'PENDING' && <button className="btn btn-primary" onClick={() => onReview(l)}>Review</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {detail && (
        <Modal title="Leave application" wide onClose={() => setDetail(null)}>
          <div className="space-y-3 text-sm">
            <p><b>{detail.studentName}</b> ({detail.usn}) · {typeLabel(detail.type)} · {fmtDay(detail.startDate)} – {fmtDay(detail.endDate)} · <Badge tone={statusTone(detail.status)}>{detail.status}</Badge></p>
            <p className="whitespace-pre-wrap bg-gray-50 border rounded p-2">{detail.reason}</p>
            {detail.reviewComment && <p><b>Mentor comment:</b> {detail.reviewComment}</p>}
            <DocButtons leave={detail} />
            <div><h3 className="font-semibold mb-1">History</h3>
              <ul className="text-xs space-y-1">{detail.history.map((h, i) => <li key={i}>{fmtDate(h.at)} – <b>{h.action}</b> by {h.actor}{h.comment ? ` (${h.comment})` : ''}</li>)}</ul></div>
          </div>
        </Modal>
      )}
    </>
  )
}

export function ReviewDialog({ leave, onClose, onDone }: { leave: Leave; onClose: () => void; onDone: () => void }) {
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')
  const [confirm, setConfirm] = useState<boolean | null>(null)
  const submit = async (approve: boolean) => {
    try { await api.put(`/api/leaves/${leave.id}/review`, { approve, comment }); onDone() } catch (e) { setError(errMsg(e)) }
  }
  return (
    <Modal title={`Review: ${leave.studentName}`} wide onClose={onClose}>
      <div className="space-y-3 text-sm">
        <p>{typeLabel(leave.type)} leave · {fmtDay(leave.startDate)} – {fmtDay(leave.endDate)}</p>
        <p className="whitespace-pre-wrap bg-gray-50 border rounded p-2">{leave.reason}</p>
        <DocButtons leave={leave} />
        <div><label htmlFor="rc" className="text-xs font-semibold text-gray-600">Review comment (required when rejecting)</label>
          <textarea id="rc" className="input w-full" rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} /></div>
        {error && <p role="alert" className="text-red-600">{error}</p>}
        <p className="text-xs text-gray-500">Approving marks the student's absent classes in this period as approved leave (L).</p>
        <div className="flex justify-end gap-2">
          <button className="btn btn-danger" onClick={() => (comment.trim() ? setConfirm(false) : setError('Please add a comment before rejecting'))}>Reject</button>
          <button className="btn btn-primary" onClick={() => setConfirm(true)}>Approve</button>
        </div>
      </div>
      {confirm !== null && <Confirm title={confirm ? 'Approve leave?' : 'Reject leave?'} message={confirm ? 'The student will be notified and attendance will be updated.' : 'The student will be notified with your comment.'} confirmLabel={confirm ? 'Approve' : 'Reject'} danger={!confirm} onConfirm={() => submit(confirm)} onClose={() => setConfirm(null)} />}
    </Modal>
  )
}
