import { useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { AlertTriangle, Lightbulb } from 'lucide-react'
import { api } from '../lib/api'
import { Async, AttCell, Badge, Empty, SectionHeader, Stat, fmtDay, useLoad } from './ui'

const STATUS_COLOR: Record<string, string> = { P: '#bbf7d0', A: '#fecaca', OD: '#bfdbfe', L: '#fde68a', NC: '#e5e7eb' }
const STATUS_NAME: Record<string, string> = { P: 'Present', A: 'Absent', OD: 'On Duty', L: 'Approved Leave', NC: 'Not Conducted' }

export function AttendanceView({ studentId }: { studentId: number }) {
  const [month, setMonth] = useState('')
  const [subjectId, setSubjectId] = useState('')
  const [picked, setPicked] = useState<string | null>(null)
  const st = useLoad<any>(() => api.get(`/api/students/${studentId}/attendance`, { params: { month: month || undefined, subjectId: subjectId || undefined } }).then((r) => r.data), [studentId, month, subjectId])
  const all = useLoad<any>(() => api.get(`/api/students/${studentId}/attendance`).then((r) => r.data), [studentId])

  const months = useMemo(() => {
    const s = new Set<string>()
    ;(all.data?.daily || []).forEach((d: any) => s.add(d.date.slice(0, 7)))
    return [...s].sort()
  }, [all.data])

  return (
    <Async state={st}>
      {(d) => {
        const overall = d.overall
        const short = overall.conducted > 0 && overall.percent < d.threshold
        const dailyMap: Record<string, string> = {}
        d.daily.forEach((x: any) => (dailyMap[x.date] = x.status))
        const shown = month ? month : months[months.length - 1]
        const cal = shown ? buildCalendar(shown, dailyMap) : []
        const recs: any[] = d.subjectRecords || []
        return (
          <div className="space-y-4">
            {short && (
              <div role="alert" className="flex items-start gap-2 bg-red-50 border border-red-300 text-red-700 rounded p-3">
                <AlertTriangle size={18} className="mt-0.5" />
                <div><b>Attendance shortage:</b> your overall attendance is {overall.percent}%, below the required {d.threshold}%. Attend upcoming classes regularly to recover.</div>
              </div>
            )}
            <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
              <Stat label="Overall" value={`${overall.percent}%`} tone={short ? 'bad' : 'good'} hint={`Requirement ${d.threshold}%`} />
              <Stat label="Working days" value={d.workingDays} />
              <Stat label="Present days" value={d.presentDays} />
              <Stat label="Absent days" value={d.absentDays} tone={d.absentDays > 0 ? 'warn' : 'neutral'} />
              <Stat label="Approved leave" value={d.leaveDays} />
              <Stat label="On duty" value={d.onDutyDays} />
            </div>
            <div className="flex flex-wrap gap-3 items-end">
              <div><label htmlFor="att-month" className="block text-xs font-semibold text-gray-600">Month</label>
                <select id="att-month" className="input" value={month} onChange={(e) => { setMonth(e.target.value); setPicked(null) }}>
                  <option value="">Latest month (calendar) / all (list)</option>
                  {months.map((m) => <option key={m} value={m}>{m}</option>)}
                </select></div>
              <div><label htmlFor="att-subject" className="block text-xs font-semibold text-gray-600">Subject</label>
                <select id="att-subject" className="input" value={subjectId} onChange={(e) => setSubjectId(e.target.value)}>
                  <option value="">All subjects (day status)</option>
                  {d.subjectWise.map((s: any) => <option key={s.subjectId} value={s.subjectId}>{s.code} – {s.name}</option>)}
                </select></div>
              <div className="flex gap-2 text-xs ml-auto flex-wrap">
                {Object.keys(STATUS_NAME).map((k) => <span key={k} className="flex items-center gap-1"><span className="w-3 h-3 rounded-sm border" style={{ background: STATUS_COLOR[k] }} />{k} = {STATUS_NAME[k]}</span>)}
              </div>
            </div>
            <div className="grid lg:grid-cols-3 gap-4">
              <div className="card lg:col-span-2">
                <SectionHeader>Calendar {shown ? `– ${shown}` : ''}</SectionHeader>
                <div className="p-3">
                  {cal.length === 0 ? <Empty text="No attendance recorded" /> : (
                    <>
                      <div className="grid grid-cols-7 gap-1 text-center text-xs font-semibold text-gray-500 mb-1">{['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((x) => <div key={x}>{x}</div>)}</div>
                      <div className="grid grid-cols-7 gap-1">
                        {cal.map((c, i) => c ? (
                          <button key={i} onClick={() => setPicked(c.date)} aria-label={`${c.date} ${c.status ? STATUS_NAME[c.status] : 'no record'}`}
                            className={`h-12 rounded border text-xs flex flex-col items-center justify-center ${picked === c.date ? 'ring-2 ring-teal-500' : ''}`} style={{ background: c.status ? STATUS_COLOR[c.status] : '#fff' }}>
                            <span className="text-gray-500">{c.day}</span><b><AttCell s={c.status || ''} /></b>
                          </button>
                        ) : <div key={i} />)}
                      </div>
                    </>
                  )}
                  {picked && <p className="mt-3 text-sm">Selected <b>{fmtDay(picked)}</b>: {dailyMap[picked] ? STATUS_NAME[dailyMap[picked]] : 'No record'}</p>}
                </div>
              </div>
              <div className="card">
                <SectionHeader>Subject-wise attendance</SectionHeader>
                <table className="erp-table"><thead><tr><th>Subject</th><th>Present</th><th>%</th></tr></thead>
                  <tbody>{d.subjectWise.map((s: any) => (
                    <tr key={s.subjectId}><td className="text-left" title={s.name}>{s.code}</td><td>{s.present}/{s.conducted}</td><td><Badge tone={s.percent < d.threshold ? 'bad' : 'good'}>{s.percent}%</Badge></td></tr>
                  ))}</tbody></table>
              </div>
            </div>
            <div className="card">
              <SectionHeader>{subjectId ? 'Subject attendance records' : 'Daily attendance history'}</SectionHeader>
              <div className="max-h-80 overflow-auto p-2">
                <table className="erp-table"><thead><tr><th>Date</th><th>Status</th></tr></thead>
                  <tbody>
                    {(subjectId ? recs : [...d.daily].reverse()).map((x: any) => <tr key={x.date}><td>{fmtDay(x.date)}</td><td><AttCell s={x.status} /> <span className="text-xs text-gray-500 ml-1">{STATUS_NAME[x.status]}</span></td></tr>)}
                  </tbody></table>
              </div>
            </div>
          </div>
        )
      }}
    </Async>
  )
}

function buildCalendar(month: string, statuses: Record<string, string>) {
  const [y, m] = month.split('-').map(Number)
  const first = new Date(y, m - 1, 1)
  const days = new Date(y, m, 0).getDate()
  const lead = (first.getDay() + 6) % 7
  const cells: ({ date: string; day: number; status?: string } | null)[] = Array(lead).fill(null)
  for (let d = 1; d <= days; d++) {
    const date = `${month}-${String(d).padStart(2, '0')}`
    cells.push({ date, day: d, status: statuses[date] })
  }
  return cells
}

/** ERP-style internal assessment table (student view): fixed subject columns, grouped IA / Activity / Total. */
export function MarksTable({ rows, rules }: { rows: any[]; rules: any }) {
  if (!rows.length) return <Empty text="No internal marks recorded yet" />
  const f = (v: any) => (v === null || v === undefined ? '—' : v)
  return (
    <div className="card">
      <div className="text-xs text-gray-500 px-3 py-2 border-b">
        Internal Assessment – best {rules.iaBestCount} of 3 tests (bucket {rules.iaBucketMax}) converted to {rules.iaConvertedMax}; Activities (bucket {rules.actBucketMax}) converted to {rules.actConvertedMax}.
      </div>
      <div className="overflow-x-auto">
        <table className="erp-table min-w-[1000px]">
          <thead>
            <tr>
              <th rowSpan={2} className="text-left sticky left-0 z-20">Subject</th>
              <th colSpan={5} style={{ background: '#7fcfcf', color: '#fff' }}>Internal Assessment (Bucket {rules.iaBucketMax}, IA marks {rules.iaConvertedMax})</th>
              <th colSpan={5} style={{ background: '#7fcfcf', color: '#fff' }}>Activity (Bucket {rules.actBucketMax}, marks {rules.actConvertedMax})</th>
              <th rowSpan={2} style={{ background: 'linear-gradient(#f87171,#fecaca)' }}>Total ({rules.iaConvertedMax + rules.actConvertedMax})</th>
            </tr>
            <tr><th>IA 1</th><th>IA 2</th><th>IA 3</th><th>Bucket</th><th>IA Marks</th><th>Act 1</th><th>Act 2</th><th>Act 3</th><th>Bucket</th><th>Act Marks</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.subjectId}>
                <td className="text-left sticky left-0 bg-inherit"><div className="font-semibold text-blue-600">{r.name}</div><div className="text-xs text-gray-500">{r.code}</div></td>
                <td>{f(r.marks.ia1)}</td><td>{f(r.marks.ia2)}</td><td>{f(r.marks.ia3)}</td><td>{r.marks.iaBucket}</td><td className="font-semibold">{r.marks.iaConverted}</td>
                <td>{f(r.marks.act1)}</td><td>{f(r.marks.act2)}</td><td>{f(r.marks.act3)}</td><td>{r.marks.actBucket}</td><td className="font-semibold">{r.marks.actConverted}</td>
                <td className="font-bold text-red-700">{r.marks.total}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function MarksView({ studentId }: { studentId: number }) {
  const st = useLoad<any>(() => api.get(`/api/students/${studentId}/marks`).then((r) => r.data), [studentId])
  return <Async state={st}>{(d) => <MarksTable rows={d.rows} rules={d.rules} />}</Async>
}

export function ResultsView({ studentId }: { studentId: number }) {
  const st = useLoad<any>(() => api.get(`/api/students/${studentId}/results`).then((r) => r.data), [studentId])
  return (
    <Async state={st}>
      {(d) => d.semesters.length === 0 ? <Empty text="No semester results available" /> : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Stat label="CGPA" value={d.cgpa} tone="good" />
            <Stat label="Semesters completed" value={d.semesters.length} />
            <Stat label="Latest SGPA" value={d.semesters[d.semesters.length - 1].sgpa} />
            <Stat label="Backlogs" value={d.semesters.reduce((n: number, s: any) => n + s.rows.filter((r: any) => r.status === 'FAIL').length, 0)} />
          </div>
          <div className="card p-3">
            <h3 className="font-semibold mb-2">SGPA comparison across semesters</h3>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={d.semesters.map((s: any) => ({ sem: `Sem ${s.semester}`, SGPA: s.sgpa }))}><CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="sem" /><YAxis domain={[0, 10]} /><Tooltip /><Bar dataKey="SGPA" fill="#0d9488" /></BarChart>
            </ResponsiveContainer>
          </div>
          {[...d.semesters].reverse().map((s: any) => (
            <div key={s.semester} className="card">
              <SectionHeader>Semester {s.semester} — SGPA {s.sgpa} ({s.credits} credits)</SectionHeader>
              <table className="erp-table"><thead><tr><th>Code</th><th className="text-left">Subject</th><th>Credits</th><th>Grade</th><th>Grade points</th><th>Result</th></tr></thead>
                <tbody>{s.rows.map((r: any) => (
                  <tr key={r.subjectId}><td>{r.code}</td><td className="text-left">{r.name}</td><td>{r.credits}</td><td className="font-bold">{r.grade}</td><td>{r.gradePoints}</td><td><Badge tone={r.status === 'PASS' ? 'good' : 'bad'}>{r.status}</Badge></td></tr>
                ))}</tbody></table>
            </div>
          ))}
        </div>
      )}
    </Async>
  )
}

export function AnalyticsView({ studentId }: { studentId: number }) {
  const st = useLoad<any>(() => api.get(`/api/students/${studentId}/analytics`).then((r) => r.data), [studentId])
  return (
    <Async state={st}>
      {(d) => (
        <div className="space-y-4">
          <div className="card p-3 bg-teal-50 border-teal-200">
            <h3 className="font-semibold flex items-center gap-2 mb-1"><Lightbulb size={16} className="text-teal-700" />Insights from your records</h3>
            <ul className="list-disc pl-5 space-y-0.5">{d.insights.map((x: string, i: number) => <li key={i}>{x}</li>)}</ul>
          </div>
          <div className="grid lg:grid-cols-2 gap-4">
            <Chart title="IA 1 vs IA 2 vs IA 3 (marks out of 25, by subject)" empty={!d.iaComparison.length}>
              <BarChart data={d.iaComparison}><CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="subject" /><YAxis domain={[0, 25]} /><Tooltip /><Legend /><Bar dataKey="IA1" fill="#94a3b8" /><Bar dataKey="IA2" fill="#14b8a6" /><Bar dataKey="IA3" fill="#0f766e" /></BarChart>
            </Chart>
            <Chart title="Subject-wise internal total (out of 50)" empty={!d.iaComparison.length}>
              <BarChart data={d.iaComparison} layout="vertical"><CartesianGrid strokeDasharray="3 3" /><XAxis type="number" domain={[0, 50]} /><YAxis type="category" dataKey="name" width={150} tick={{ fontSize: 11 }} /><Tooltip /><Bar dataKey="total" fill="#dc2626" /></BarChart>
            </Chart>
            <Chart title="Attendance trend (weekly %)" empty={!d.attendanceTrend.length}>
              <LineChart data={d.attendanceTrend}><CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="label" /><YAxis domain={[0, 100]} /><Tooltip /><Line dataKey="percent" stroke="#0d9488" strokeWidth={2} dot /></LineChart>
            </Chart>
            <Chart title="SGPA and CGPA progression" empty={!d.sgpaProgression.length}>
              <LineChart data={d.sgpaProgression}><CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="semester" /><YAxis domain={[0, 10]} /><Tooltip /><Legend /><Line dataKey="sgpa" name="SGPA" stroke="#2563eb" strokeWidth={2} /><Line dataKey="cgpa" name="CGPA" stroke="#dc2626" strokeWidth={2} /></LineChart>
            </Chart>
          </div>
        </div>
      )}
    </Async>
  )
}

function Chart({ title, empty, children }: { title: string; empty: boolean; children: any }) {
  return (
    <div className="card p-3">
      <h3 className="font-semibold mb-2 text-sm">{title}</h3>
      {empty ? <Empty text="No data yet" /> : <ResponsiveContainer width="100%" height={260}>{children}</ResponsiveContainer>}
    </div>
  )
}
