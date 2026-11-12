import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Lock } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import Chat from '../../components/Chat'
import { LeaveTable, type Leave } from '../../components/Leaves'
import { AnalyticsView, AttendanceView, MarksView, ResultsView } from '../../components/StudentViews'
import { Async, Avatar, Badge, ErrorBox, Page, SectionHeader, Stat, fmtDate, statusTone, useLoad } from '../../components/ui'

const TABS = ['Overview', 'Attendance', 'Internal marks', 'Results', 'Analytics', 'Leaves', 'Messages', 'Notes & feedback']

function Notes({ id, kind, title, hint }: { id: string; kind: 'feedback' | 'observations'; title: string; hint: string }) {
  const st = useLoad<any[]>(() => api.get(`/api/students/${id}/${kind}`).then((r) => r.data), [id, kind])
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const add = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    try { await api.post(`/api/students/${id}/${kind}`, { content: text }); setText(''); st.reload() } catch (err) { setError(errMsg(err)) }
  }
  return (
    <div className="card">
      <SectionHeader>{title}</SectionHeader>
      <p className="text-xs text-gray-500 px-3 pt-2 flex items-center gap-1">{kind === 'observations' && <Lock size={12} />}{hint}</p>
      <form onSubmit={add} className="p-3 flex gap-2">
        <textarea className="input flex-1" rows={2} maxLength={2000} required placeholder={kind === 'feedback' ? 'Write feedback or improvement suggestions' : 'Write a private observation'} aria-label={title} value={text} onChange={(e) => setText(e.target.value)} />
        <button className="btn btn-primary self-start">Add</button>
      </form>
      {error && <p role="alert" className="px-3 text-red-600">{error}</p>}
      <Async state={st}>{(list) => list.length === 0 ? <p className="p-3 text-gray-400">Nothing yet</p> : (
        <ul className="divide-y">{list.map((n) => <li key={n.id} className="px-3 py-2"><p className="whitespace-pre-wrap">{n.content}</p><p className="text-xs text-gray-400">{n.author} · {fmtDate(n.createdAt)}</p></li>)}</ul>
      )}</Async>
    </div>
  )
}

export default function StudentProfile() {
  const { id } = useParams()
  const sid = Number(id)
  const [tab, setTab] = useState(TABS[0])
  const prof = useLoad<any>(() => api.get(`/api/students/${id}/profile`).then((r) => r.data), [id])
  const leaves = useLoad<Leave[]>(() => api.get(`/api/students/${id}/leaves`).then((r) => r.data), [id])

  if (prof.error) return <Page title="Student"><ErrorBox error={prof.error} /><Link to="/mentor/students" className="text-blue-600 underline text-sm mt-3 inline-block">Back to my students</Link></Page>
  return (
    <Page title="Student profile" actions={<Link className="btn btn-ghost" to="/mentor/students"><ArrowLeft size={14} />My students</Link>}>
      <Async state={prof}>
        {(p) => (
          <div className="space-y-4">
            <div className="card p-4 flex flex-wrap items-center gap-4">
              <Avatar name={p.name} size={56} />
              <div className="flex-1 min-w-[220px]"><div className="font-bold text-lg">{p.name}</div>
                <div className="text-xs text-gray-500">{p.usn} · {p.department} · Semester {p.semester} · {p.email}</div></div>
              <Badge tone={statusTone(p.status)}>{p.status}</Badge>
            </div>
            <div className="flex gap-1 border-b overflow-x-auto" role="tablist">
              {TABS.map((t) => <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)} className={`px-3 py-2 text-sm whitespace-nowrap border-b-2 ${tab === t ? 'border-teal-600 text-teal-700 font-semibold' : 'border-transparent text-gray-500 hover:text-gray-800'}`}>{t}</button>)}
            </div>
            {tab === 'Overview' && (
              <div className="space-y-3">
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                  <Stat label="CGPA" value={p.cgpa ?? '—'} /><Stat label="Attendance" value={`${p.attendancePct}%`} tone={p.flags.some((f: string) => f.startsWith('Low')) ? 'bad' : 'good'} />
                  <Stat label="Pending leaves" value={leaves.data?.filter((l) => l.status === 'PENDING').length ?? '…'} /><Stat label="Phone" value={<span className="text-base">{p.phone || '—'}</span>} />
                </div>
                {p.flags.length > 0 && <div className="card p-3 border-red-200 bg-red-50"><b className="text-red-700">Attention flags:</b><ul className="list-disc pl-5">{p.flags.map((f: string) => <li key={f}>{f}</li>)}</ul></div>}
                <AnalyticsView studentId={sid} />
              </div>
            )}
            {tab === 'Attendance' && <AttendanceView studentId={sid} />}
            {tab === 'Internal marks' && <MarksView studentId={sid} />}
            {tab === 'Results' && <ResultsView studentId={sid} />}
            {tab === 'Analytics' && <AnalyticsView studentId={sid} />}
            {tab === 'Leaves' && <div className="card"><SectionHeader>Leave request history</SectionHeader><Async state={leaves}>{(l) => <LeaveTable leaves={l} />}</Async></div>}
            {tab === 'Messages' && <div className="card" style={{ height: '65vh' }}><Chat studentId={sid} /></div>}
            {tab === 'Notes & feedback' && (
              <div className="grid lg:grid-cols-2 gap-4">
                <Notes id={id!} kind="feedback" title="Feedback to student" hint="Visible to the student and sent as a notification." />
                <Notes id={id!} kind="observations" title="Private observations" hint="Only you can see these. They are never shown to the student." />
              </div>
            )}
          </div>
        )}
      </Async>
    </Page>
  )
}
