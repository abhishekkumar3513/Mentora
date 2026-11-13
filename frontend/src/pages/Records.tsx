import { useState, type FormEvent } from 'react'
import { api, errMsg } from '../lib/api'
import { today } from '../lib/support'
import { Async, Badge, Empty, Page, SectionHeader, fmtDay, useLoad } from '../components/ui'

function Assignments() {
  const list = useLoad<any[]>(() => api.get('/api/records/assignments').then((r) => r.data), [])
  const subjects = useLoad<any[]>(() => api.get('/api/records/subjects').then((r) => r.data).catch(() => []), [])
  const [open, setOpen] = useState<number | null>(null)
  const subs = useLoad<any[]>(() => (open ? api.get(`/api/records/assignments/${open}/submissions`).then((r) => r.data) : Promise.resolve([])), [open])
  const [f, setF] = useState({ subjectId: '', title: '', deadline: '' })
  const [error, setError] = useState('')

  const create = async (e: FormEvent) => {
    e.preventDefault(); setError('')
    try { await api.post('/api/records/assignments', { subjectId: Number(f.subjectId), title: f.title, deadline: f.deadline }); setF({ subjectId: '', title: '', deadline: '' }); list.reload() } catch (err) { setError(errMsg(err)) }
  }
  const toggle = async (sid: number, submitted: boolean) => {
    setError('')
    try { await api.put(`/api/records/assignments/${open}/submissions/${sid}`, { submitted }); subs.reload(); list.reload() } catch (err) { setError(errMsg(err)) }
  }
  return (
    <div className="grid lg:grid-cols-2 gap-4">
      <div className="card">
        <SectionHeader>Assignments</SectionHeader>
        <form onSubmit={create} className="p-3 grid sm:grid-cols-4 gap-2 border-b">
          <select aria-label="Subject" required className="input" value={f.subjectId} onChange={(e) => setF({ ...f, subjectId: e.target.value })}>
            <option value="">Subject…</option>{subjects.data?.map((s) => <option key={s.id} value={s.id}>{s.name} (Sem {s.semester})</option>)}
          </select>
          <input aria-label="Assignment title" required className="input" placeholder="Title" maxLength={200} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />
          <input aria-label="Deadline" required type="date" className="input" value={f.deadline} onChange={(e) => setF({ ...f, deadline: e.target.value })} />
          <button className="btn btn-primary justify-center">Add</button>
        </form>
        {error && <p role="alert" className="px-3 pt-2 text-red-600 text-sm">{error}</p>}
        <Async state={list}>{(rows) => rows.length === 0 ? <Empty text="No assignments yet" /> : (
          <ul className="divide-y">{rows.map((a) => (
            <li key={a.id}><button onClick={() => setOpen(a.id)} className={`w-full text-left px-3 py-2 hover:bg-gray-50 ${open === a.id ? 'bg-teal-50' : ''}`}>
              <div className="font-semibold">{a.title} <span className="text-xs font-normal text-gray-500">· {a.subject}</span></div>
              <div className="text-xs text-gray-600">Deadline {fmtDay(a.deadline)} · {a.submitted}/{a.students} submitted {a.deadline < today() && a.submitted < a.students && <Badge tone="warn">Deadline passed</Badge>}</div>
            </button></li>
          ))}</ul>
        )}</Async>
      </div>
      <div className="card">
        <SectionHeader>Submissions</SectionHeader>
        {!open ? <Empty text="Select an assignment" /> : <Async state={subs}>{(rows) => rows.length === 0 ? <Empty text="No students in view for this semester" /> : (
          <ul className="divide-y max-h-[28rem] overflow-auto">{rows.map((s) => (
            <li key={s.studentId} className="px-3 py-1.5 flex items-center gap-2">
              <span className="flex-1">{s.name} <span className="text-xs text-gray-500">{s.usn}</span></span>
              <Badge tone={s.submitted ? 'good' : 'neutral'}>{s.submitted ? 'Submitted' : 'Not submitted'}</Badge>
              <button className="btn btn-ghost" onClick={() => toggle(s.studentId, !s.submitted)}>{s.submitted ? 'Mark not submitted' : 'Mark submitted'}</button>
            </li>
          ))}</ul>
        )}</Async>}
      </div>
    </div>
  )
}

const IA_LABEL: Record<string, string> = { ATTENDED: 'Attended', ABSENT: 'Absent', NOT_CONDUCTED: 'Not conducted', NOT_RECORDED: 'Not recorded' }
const IA_TONE: Record<string, string> = { ATTENDED: 'good', ABSENT: 'bad', NOT_CONDUCTED: 'neutral', NOT_RECORDED: 'warn' }

function Ia() {
  const [sem, setSem] = useState(5)
  const st = useLoad<any>(() => api.get(`/api/records/ia?semester=${sem}`).then((r) => r.data), [sem])
  const [error, setError] = useState('')
  const exam = async (ia: number, examDate: string | null, conducted: boolean) => {
    setError('')
    try { await api.put('/api/records/ia/exam', { semester: sem, iaNumber: ia, examDate, conducted }); st.reload() } catch (err) { setError(errMsg(err)) }
  }
  const mark = async (ia: number, studentId: number, status: string) => {
    setError('')
    try { await api.put('/api/records/ia/attendance', { iaNumber: ia, studentId, status: status || null }); st.reload() } catch (err) { setError(errMsg(err)) }
  }
  return (
    <div className="card">
      <SectionHeader>IA examination attendance (separate from IA marks)</SectionHeader>
      <div className="p-3 flex flex-wrap items-center gap-3 border-b">
        <label className="text-sm">Semester <select className="input ml-1" value={sem} onChange={(e) => setSem(Number(e.target.value))}>{[1, 2, 3, 4, 5, 6, 7, 8].map((s) => <option key={s}>{s}</option>)}</select></label>
        <span className="text-xs text-gray-500">An examination counts only after it is marked as conducted. Not-conducted exams are never treated as absences.</span>
      </div>
      {error && <p role="alert" className="px-3 pt-2 text-red-600 text-sm">{error}</p>}
      <Async state={st}>{(d) => (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-gray-500 border-b"><th className="p-2">Student</th>{d.exams.map((e: any) => (
              <th key={e.ia} className="p-2 align-top">IA {e.ia}
                <div className="flex items-center gap-1 mt-1 font-normal">
                  <input aria-label={`IA ${e.ia} date`} type="date" className="input !py-0.5 !px-1 text-xs" value={e.examDate ?? ''} onChange={(ev) => exam(e.ia, ev.target.value || null, e.conducted)} />
                  <label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={e.conducted} onChange={(ev) => exam(e.ia, e.examDate, ev.target.checked)} />Conducted</label>
                </div>
              </th>))}</tr></thead>
            <tbody>{d.students.length === 0 ? <tr><td className="p-3 text-gray-400" colSpan={4}>No students in view for this semester</td></tr> : d.students.map((s: any) => (
              <tr key={s.studentId} className="border-b">
                <td className="p-2">{s.name} <span className="text-xs text-gray-500">{s.usn}</span></td>
                {[1, 2, 3].map((n) => { const v = s[`ia${n}`]; return (
                  <td key={n} className="p-2">{v === 'NOT_CONDUCTED' ? <Badge>{IA_LABEL[v]}</Badge> : (
                    <select aria-label={`${s.name} IA ${n} attendance`} className="input !py-0.5" value={v === 'NOT_RECORDED' ? '' : v} onChange={(e) => mark(n, s.studentId, e.target.value)}>
                      <option value="">{IA_LABEL.NOT_RECORDED}</option><option value="ATTENDED">{IA_LABEL.ATTENDED}</option><option value="ABSENT">{IA_LABEL.ABSENT}</option>
                    </select>)}{v !== 'NOT_CONDUCTED' && <span className="ml-2"><Badge tone={IA_TONE[v]}>{IA_LABEL[v]}</Badge></span>}</td>) })}
              </tr>))}</tbody>
          </table>
        </div>
      )}</Async>
    </div>
  )
}

function Calendar() {
  const st = useLoad<any[]>(() => api.get('/api/records/holidays').then((r) => r.data), [])
  const [date, setDate] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const add = async (e: FormEvent) => { e.preventDefault(); setError(''); try { await api.post('/api/records/holidays', { date, name }); setDate(''); setName(''); st.reload() } catch (err) { setError(errMsg(err)) } }
  const del = async (d: string) => { await api.delete(`/api/records/holidays/${d}`); st.reload() }
  return (
    <div className="card max-w-xl">
      <SectionHeader>Holidays and non-instructional days</SectionHeader>
      <p className="text-xs text-gray-500 p-3 pb-0">Weekends are skipped automatically. Holidays listed here never count towards consecutive-absence signals.</p>
      <form onSubmit={add} className="p-3 flex gap-2"><input aria-label="Holiday date" required type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} />
        <input aria-label="Holiday name" className="input flex-1" placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} /><button className="btn btn-primary">Add</button></form>
      {error && <p role="alert" className="px-3 text-red-600 text-sm">{error}</p>}
      <Async state={st}>{(rows) => rows.length === 0 ? <Empty text="No holidays configured" /> : <ul className="divide-y">{rows.map((h) => <li key={h.date} className="px-3 py-1.5 flex items-center"><span className="flex-1">{fmtDay(h.date)} · {h.name}</span><button className="btn btn-ghost" onClick={() => del(h.date)}>Remove</button></li>)}</ul>}</Async>
    </div>
  )
}

export default function Records() {
  const [tab, setTab] = useState('Assignments')
  return (
    <Page title="Academic Records" subtitle="Assignments, IA examination attendance and the instructional calendar feed the support signals">
      <div className="flex gap-1 border-b mb-4" role="tablist">
        {['Assignments', 'IA attendance', 'Calendar'].map((t) => <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)} className={`px-3 py-2 text-sm border-b-2 ${tab === t ? 'border-teal-600 text-teal-700 font-semibold' : 'border-transparent text-gray-500'}`}>{t}</button>)}
      </div>
      {tab === 'Assignments' ? <Assignments /> : tab === 'IA attendance' ? <Ia /> : <Calendar />}
    </Page>
  )
}
