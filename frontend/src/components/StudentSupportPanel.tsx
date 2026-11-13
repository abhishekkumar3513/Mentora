import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { SIGNAL_LABEL, STATUS_LABEL, priorityLabel, priorityTone, statusToneOf } from '../lib/support'
import { Async, Badge, Empty, SectionHeader, fmtDate, fmtDay, useLoad } from './ui'
import RequestDetail from './RequestDetail'
import SupportActionModal from './SupportActionModal'

const IA_LABEL: Record<string, string> = { ATTENDED: 'Attended', ABSENT: 'Absent', NOT_CONDUCTED: 'Not conducted', NOT_RECORDED: 'Not recorded' }
const IA_TONE: Record<string, string> = { ATTENDED: 'good', ABSENT: 'bad', NOT_CONDUCTED: 'neutral', NOT_RECORDED: 'warn' }

/** Support signals, IA examination attendance (apart from marks) and assignments of one student. forStudent hides all staff-only content. */
export default function StudentSupportPanel({ studentId, forStudent }: { studentId: number; forStudent?: boolean }) {
  const st = useLoad<any>(() => api.get(`/api/students/${studentId}/support`).then((r) => r.data), [studentId])
  const [creating, setCreating] = useState<{ signalId?: number; text?: string } | null>(null)
  const [open, setOpen] = useState<number | null>(null)

  return (
    <Async state={st}>
      {(d) => (
        <div className="space-y-4">
          <div className="card">
            <SectionHeader>{forStudent ? 'Why support may help you' : 'Active support signals'}</SectionHeader>
            {forStudent && <p className="text-xs text-gray-500 px-3 pt-2">These are prompts that help your mentor offer support. They are not a judgement and carry no penalty.</p>}
            {d.activeSignals.length === 0 ? <Empty text="No current support signal" /> : (
              <ul className="divide-y">
                {d.activeSignals.map((s: any) => (
                  <li key={s.id} className="px-3 py-2 flex flex-wrap gap-2 items-start">
                    <div className="flex-1 min-w-[220px]">
                      <div className="font-semibold flex items-center gap-2 flex-wrap">{forStudent ? SIGNAL_LABEL[s.type] : s.title}{!forStudent && <Badge tone={priorityTone(s.priority)}>{priorityLabel(s.priority)}</Badge>}</div>
                      <div className="text-sm text-gray-600">{s.summary}</div>
                      <div className="text-[11px] text-gray-400">Since {fmtDate(s.firstDetectedAt)}</div>
                      {!forStudent && <div className="text-xs mt-1 flex gap-3">
                        {s.type === 'ATTENDANCE' && <Link className="text-blue-700 hover:underline" to="/mentor/attendance">Attendance records</Link>}
                        {s.type === 'ASSIGNMENT' && <Link className="text-blue-700 hover:underline" to="/records">Assignment records</Link>}
                        {s.type === 'IA_ATTENDANCE' && <Link className="text-blue-700 hover:underline" to="/records">IA attendance records</Link>}
                      </div>}
                    </div>
                    {!forStudent && <button className="btn btn-ghost" onClick={() => setCreating({ signalId: s.id, text: `Reviewed: ${s.summary}` })}>Create Support Action</button>}
                  </li>
                ))}
              </ul>
            )}
            {forStudent && d.activeSignals.length > 0 && <div className="p-3 border-t text-sm">Suggested next step: <Link className="text-blue-700 underline" to="/student/support">ask for support in the Student Support Navigator</Link>.</div>}
          </div>

          <div className="grid md:grid-cols-2 gap-4">
            <div className="card">
              <SectionHeader>Internal assessments</SectionHeader>
              <table className="w-full text-sm"><thead><tr className="text-left text-xs text-gray-500 border-b"><th className="p-2">Exam</th><th className="p-2">Attendance</th><th className="p-2">Marks</th></tr></thead>
                <tbody>{d.ia.map((r: any) => <tr key={r.ia} className="border-b"><td className="p-2">IA {r.ia}</td><td className="p-2"><Badge tone={IA_TONE[r.attendance]}>{IA_LABEL[r.attendance]}</Badge></td><td className="p-2">{r.marks ?? '—'}</td></tr>)}</tbody></table>
              <p className="text-[11px] text-gray-400 p-2">Attendance at an examination and the marks scored are recorded separately.</p>
            </div>
            <div className="card">
              <SectionHeader>Assignments</SectionHeader>
              {d.assignments.length === 0 ? <Empty text="No assignments" /> : (
                <ul className="divide-y text-sm">{d.assignments.map((a: any) => (
                  <li key={a.id} className="px-3 py-1.5 flex items-center gap-2"><span className="flex-1">{a.title} <span className="text-xs text-gray-500">· {a.subject} · due {fmtDay(a.deadline)}</span></span>
                    <Badge tone={a.state === 'SUBMITTED' ? 'good' : a.state === 'OVERDUE' ? 'bad' : 'neutral'}>{a.state === 'OVERDUE' ? 'Overdue' : a.state === 'PENDING' ? 'Pending' : 'Submitted'}</Badge></li>
                ))}</ul>
              )}
            </div>
          </div>

          {!forStudent && (
            <>
              <div className="card">
                <SectionHeader>Support actions and requests</SectionHeader>
                <div className="p-3"><button className="btn btn-primary" onClick={() => setCreating({})}>Create Support Action</button></div>
                {(d.requests ?? []).length === 0 ? <Empty text="No support actions yet" /> : (
                  <ul className="divide-y">{d.requests.map((r: any) => (
                    <li key={r.id}><button className="w-full text-left px-3 py-2 hover:bg-gray-50" onClick={() => setOpen(r.id)}>
                      <span className="font-semibold">{r.categoryName}</span> <Badge tone={statusToneOf(r.status)}>{STATUS_LABEL[r.status]}</Badge>
                      <div className="text-xs text-gray-500 truncate">{r.concern}</div></button></li>
                  ))}</ul>
                )}
              </div>
              {d.signalHistory?.length > 0 && (
                <div className="card">
                  <SectionHeader>Signal history</SectionHeader>
                  <ul className="divide-y text-sm">{d.signalHistory.map((s: any) => <li key={s.id} className="px-3 py-1.5"><b>{SIGNAL_LABEL[s.type]}</b> · {fmtDate(s.firstDetectedAt)} → resolved {fmtDate(s.resolvedAt)}<div className="text-xs text-gray-500">{s.summary}</div></li>)}</ul>
                </div>
              )}
            </>
          )}
          {creating && <SupportActionModal studentId={studentId} signalId={creating.signalId} initialObservation={creating.text} onClose={() => setCreating(null)} onDone={(c) => { st.reload(); setOpen(c.id) }} />}
          {open && <RequestDetail id={open} onClose={() => setOpen(null)} onChanged={st.reload} />}
        </div>
      )}
    </Async>
  )
}
