import { useState } from 'react'
import { api } from '../../lib/api'
import { Async, Page, useLoad } from '../../components/ui'
import { LeaveTable, ReviewDialog, type Leave } from '../../components/Leaves'

export default function MentorLeaves() {
  const [status, setStatus] = useState('')
  const st = useLoad<Leave[]>(() => api.get('/api/mentor/leaves', { params: { status: status || undefined } }).then((r) => r.data), [status])
  const [review, setReview] = useState<Leave | null>(null)
  return (
    <Page title="Leave Requests" subtitle="Applications from your assigned students"
      actions={<select className="input" aria-label="Status filter" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All</option><option value="PENDING">Pending</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option></select>}>
      <div className="card"><Async state={st}>{(l) => <LeaveTable leaves={l} showStudent onReview={setReview} />}</Async></div>
      {review && <ReviewDialog leave={review} onClose={() => setReview(null)} onDone={() => { setReview(null); st.reload() }} />}
    </Page>
  )
}
