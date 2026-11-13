import { useState, type FormEvent } from 'react'
import { Check, Circle, Lock } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { STATUS_LABEL, statusToneOf } from '../../lib/support'
import { Async, Badge, Empty, Page, SectionHeader, fmtDate, fmtDay, useLoad } from '../../components/ui'

const GUIDE: Record<string, string> = {
  ACADEMIC_COUNSELLING: 'Difficulty understanding subjects, study planning, performance support, remedial learning',
  ATTENDANCE_IMPROVEMENT: 'Attendance concerns, an attendance improvement plan, mentor guidance',
  ASSIGNMENT_SUPPORT: 'Pending assignments, difficulty completing them, subject-specific help',
  TECHNICAL_SKILL_DEVELOPMENT: 'Programming help, projects, workshops and certifications',
  CAREER_GUIDANCE: 'Resume, placement and interview preparation, career planning, higher studies',
  PARENT_INTERACTION: 'Ask for a parent interaction. Nobody contacts your parents automatically',
  PERSONAL_COUNSELLING_REFERRAL: 'A confidential referral to an authorised counsellor. Details stay private',
}

/** Student Support Navigator: the student is identified by the session only, never by a field in this form. */
export default function StudentSupport() {
  const { user } = useAuth()
  const cats = useLoad<any[]>(() => api.get('/api/support/categories').then((r) => r.data), [])
  const mine = useLoad<any[]>(() => api.get('/api/support/my-requests').then((r) => r.data), [])
  const [category, setCategory] = useState('')
  const [concern, setConcern] = useState('')
  const [help, setHelp] = useState('')
  const [error, setError] = useState('')
  const [ok, setOk] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError(''); setOk('')
    try {
      await api.post('/api/support/requests', { category, concern, helpNeeded: help })
      setOk('Your request was submitted. You can follow its progress below.')
      setCategory(''); setConcern(''); setHelp(''); mine.reload()
    } catch (err) { setError(errMsg(err)) }
    setBusy(false)
  }
  const selected = cats.data?.find((c) => c.code === category)

  return (
    <Page title="Student Support Navigator" subtitle="Ask for academic, career, technical or personal support. A person will review every request.">
      <div className="grid lg:grid-cols-5 gap-4">
        <form onSubmit={submit} className="card lg:col-span-2 h-fit">
          <SectionHeader>Create Support Action</SectionHeader>
          <div className="p-3 space-y-3">
            <div>
              <label htmlFor="ss-student" className="text-xs font-semibold text-gray-600">Student</label>
              <input id="ss-student" className="input w-full bg-gray-50" disabled value={`${user!.fullName}${user!.usn ? ` (${user!.usn})` : ''}`} />
            </div>
            <div>
              <label htmlFor="ss-cat" className="text-xs font-semibold text-gray-600">Support Category *</label>
              <select id="ss-cat" required className="input w-full" value={category} onChange={(e) => setCategory(e.target.value)}>
                <option value="">Select a category…</option>
                {cats.data?.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
              </select>
              {category && <p className="text-xs text-gray-500 mt-1">{GUIDE[category]}</p>}
              {selected?.confidential && <p className="text-xs text-amber-700 mt-1 flex items-center gap-1"><Lock size={12} />Confidential: only authorised counsellors can see this request.</p>}
            </div>
            <div>
              <label htmlFor="ss-concern" className="text-xs font-semibold text-gray-600">Describe your concern *</label>
              <textarea id="ss-concern" required rows={4} maxLength={2000} className="input w-full" value={concern} onChange={(e) => setConcern(e.target.value)} />
            </div>
            <div>
              <label htmlFor="ss-help" className="text-xs font-semibold text-gray-600">What help do you need?</label>
              <textarea id="ss-help" rows={3} maxLength={2000} className="input w-full" value={help} onChange={(e) => setHelp(e.target.value)} />
            </div>
            {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
            {ok && <p role="status" className="text-green-700 text-sm">{ok}</p>}
            <button className="btn btn-primary w-full justify-center" disabled={busy || !category}>{busy ? 'Submitting…' : 'Submit request'}</button>
            <p className="text-[11px] text-gray-400">Requests are support requests, not complaints. Nothing here affects your marks or standing.</p>
          </div>
        </form>

        <div className="lg:col-span-3 space-y-3">
          <h2 className="font-bold text-gray-700">My requests</h2>
          <Async state={mine}>
            {(list) => list.length === 0 ? <div className="card"><Empty text="You have not made any support requests yet" /></div> : (
              <>{list.map((r) => (
                <div key={r.id} className="card p-3 space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{r.categoryName}</span><Badge tone={statusToneOf(r.status)}>{STATUS_LABEL[r.status]}</Badge>
                    {r.confidential && <Lock size={12} className="text-amber-600" aria-label="Confidential" />}
                    <span className="ml-auto text-xs text-gray-400">{fmtDate(r.createdAt)}</span>
                  </div>
                  <p className="text-sm text-gray-700 whitespace-pre-wrap">{r.concern}</p>
                  <ol className="flex flex-wrap gap-x-4 gap-y-1 text-xs" aria-label="Progress">
                    {r.progress.map((p: any) => <li key={p.label} className={`flex items-center gap-1 ${p.done ? 'text-teal-700 font-semibold' : 'text-gray-400'}`}>{p.done ? <Check size={12} /> : <Circle size={10} />}{p.label}</li>)}
                  </ol>
                  <div className="text-xs text-gray-600">Action owner: {r.ownerRoleLabel}{r.ownerName ? ` (${r.ownerName})` : ''}{r.followUpDate && <> · Follow-up: <b>{fmtDay(r.followUpDate)}</b></>}</div>
                  {r.outcome && <div className="text-sm bg-green-50 border border-green-200 rounded p-2"><b>Resolution:</b> {r.outcome}</div>}
                  {r.timeline.filter((t: any) => t.type === 'FEEDBACK').map((t: any) => (
                    <div key={t.id} className="text-sm bg-blue-50 border border-blue-200 rounded p-2"><b>Feedback from {t.author}:</b> {t.message}<div className="text-[11px] text-gray-400">{fmtDate(t.createdAt)}</div></div>
                  ))}
                </div>
              ))}</>
            )}
          </Async>
        </div>
      </div>
    </Page>
  )
}
