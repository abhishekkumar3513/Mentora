import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { CheckCheck, PencilLine } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import { Async, AttCell, Badge, Empty, Loading, Modal, Page, useLoad } from '../../components/ui'

const STATUSES = ['P', 'A', 'OD', 'L', 'NC']
const NAMES: Record<string, string> = { P: 'Present', A: 'Absent', OD: 'On duty', L: 'Leave', NC: 'Not conducted' }
const isoDay = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10)

/** Lets the mentor set a day's attendance for their own students (applies to all of the student's subjects that day). */
function MarkDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const today = isoDay(new Date())
  const min = isoDay(new Date(Date.now() - 60 * 86400000))
  const [date, setDate] = useState(today)
  const [values, setValues] = useState<Record<number, string>>({})
  const [rows, setRows] = useState<any[] | null>(null)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState('')

  useEffect(() => {
    let live = true
    setRows(null); setError('')
    api.get('/api/mentor/attendance/day', { params: { date } })
      .then((r) => { if (!live) return; setRows(r.data); setValues(Object.fromEntries(r.data.map((x: any) => [x.studentId, x.status]))) })
      .catch((e) => live && setError(errMsg(e)))
    return () => { live = false }
  }, [date])

  const setAll = (s: string) => setValues(Object.fromEntries((rows || []).map((r) => [r.studentId, s])))
  const changed = (rows || []).filter((r) => values[r.studentId] && values[r.studentId] !== r.status)

  const save = async () => {
    setSaving(true); setError(''); setResult('')
    try {
      const entries = changed.map((r) => ({ studentId: r.studentId, status: values[r.studentId] }))
      const { data } = await api.post('/api/mentor/attendance/mark', { date, entries })
      setResult(`Saved ${data.updated} student(s). Alerts raised: ${data.alertsCreated}.`)
      onSaved()
      setRows((cur) => (cur || []).map((r) => ({ ...r, status: values[r.studentId] || r.status })))
    } catch (e) { setError(errMsg(e)) }
    setSaving(false)
  }

  return (
    <Modal title="Mark attendance" wide onClose={onClose}>
      <div className="flex flex-wrap items-end gap-3 mb-3">
        <div><label htmlFor="mdate" className="block text-xs font-semibold text-gray-600">Date</label>
          <input id="mdate" type="date" className="input" min={min} max={today} value={date} onChange={(e) => e.target.value && setDate(e.target.value)} /></div>
        <button className="btn btn-ghost" onClick={() => setAll('P')} disabled={!rows}><CheckCheck size={14} />All present</button>
        <button className="btn btn-ghost" onClick={() => setAll('A')} disabled={!rows}>All absent</button>
        <span className="text-xs text-gray-500 ml-auto">Applies to every subject of the day. Editable up to 60 days back.</span>
      </div>
      {!rows && !error && <Loading />}
      {rows && rows.length === 0 && <Empty text="No assigned students" />}
      {rows && rows.length > 0 && (
        <div className="max-h-[50vh] overflow-auto">
          <table className="erp-table"><thead><tr><th className="text-left">Student</th><th>USN</th><th>Status</th></tr></thead>
            <tbody>{rows.map((r) => (
              <tr key={r.studentId}><td className="text-left">{r.name}</td><td>{r.usn}</td>
                <td><div className="inline-flex gap-1" role="group" aria-label={`Attendance for ${r.name}`}>
                  {STATUSES.map((s) => (
                    <button key={s} type="button" title={NAMES[s]} aria-pressed={values[r.studentId] === s} onClick={() => setValues((v) => ({ ...v, [r.studentId]: s }))}
                      className={`w-9 py-1 rounded border text-xs font-bold ${values[r.studentId] === s ? (s === 'A' ? 'bg-red-500 text-white border-red-600' : s === 'P' ? 'bg-green-600 text-white border-green-700' : 'bg-teal-600 text-white border-teal-700') : 'bg-white text-gray-600 hover:bg-gray-100'}`}>{s}</button>
                  ))}
                </div></td></tr>
            ))}</tbody></table>
        </div>
      )}
      {error && <p role="alert" className="text-red-600 mt-2">{error}</p>}
      {result && <p role="status" className="text-green-700 mt-2">{result}</p>}
      <div className="flex items-center justify-between mt-3">
        <span className="text-xs text-gray-500">{changed.length} change(s) pending</span>
        <div className="flex gap-2"><button className="btn btn-ghost" onClick={onClose}>Close</button>
          <button className="btn btn-primary" disabled={saving || changed.length === 0} onClick={save}>Save attendance</button></div>
      </div>
    </Modal>
  )
}

/** ERP-style consolidated attendance grid: fixed name/USN columns, scrollable date columns. */
export default function MentorAttendance() {
  const [sort, setSort] = useState('usn')
  const [days, setDays] = useState(20)
  const [marking, setMarking] = useState(false)
  const st = useLoad<any>(() => api.get('/api/mentor/attendance-grid', { params: { sort, days } }).then((r) => r.data), [sort, days])
  return (
    <Page title="Attendance Monitoring" subtitle="Daily status of your assigned students"
      actions={<button className="btn btn-primary" onClick={() => setMarking(true)}><PencilLine size={16} />Mark attendance</button>}>
      <div className="flex flex-wrap gap-3 items-center mb-3 text-sm">
        <span className="font-semibold">Sort by:</span>
        {['name', 'usn'].map((k) => <label key={k} className="flex items-center gap-1"><input type="radio" name="sort" checked={sort === k} onChange={() => setSort(k)} />{k === 'name' ? 'Name' : 'USN'}</label>)}
        <label className="ml-4 flex items-center gap-1">Days <select className="input" value={days} onChange={(e) => setDays(Number(e.target.value))}>{[10, 20, 30, 45].map((n) => <option key={n}>{n}</option>)}</select></label>
        <span className="ml-auto text-xs text-gray-500">P present · <span className="text-red-600 font-bold">A</span> absent · <span className="text-red-500">NC</span> not conducted · OD on duty · L approved leave</span>
      </div>
      <Async state={st}>
        {(d) => d.rows.length === 0 ? <Empty text="No students assigned" /> : (
          <div className="card overflow-auto max-h-[75vh]">
            <table className="erp-table" style={{ borderSpacing: '0 3px' }}>
              <thead>
                <tr>
                  <th className="text-left sticky left-0 z-30 min-w-[190px]">Student Name</th><th className="sticky left-[190px] z-30">USN</th><th>Total</th><th>Present</th><th>Attendance</th>
                  {d.dates.map((x: string) => { const dt = new Date(x + 'T00:00:00'); return <th key={x} style={{ background: '#f87171', color: '#fff' }} className="min-w-[46px]"><div className="text-[10px]">{dt.toLocaleString([], { month: 'short' })}</div><div>{dt.getDate()}</div></th> })}
                </tr>
              </thead>
              <tbody>
                {d.rows.map((r: any) => (
                  <tr key={r.studentId}>
                    <td className="text-left sticky left-0 bg-inherit z-10"><Link className="text-blue-600 hover:underline" to={`/mentor/students/${r.studentId}`}>{r.name}</Link></td>
                    <td className="sticky left-[190px] bg-inherit z-10">{r.usn}</td><td>{r.total}</td><td>{r.present}</td>
                    <td><Badge tone={r.percent < d.threshold ? 'bad' : 'good'}>{r.percent}%</Badge></td>
                    {d.dates.map((x: string) => <td key={x}><AttCell s={r.days[x]} /></td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Async>
      {marking && <MarkDialog onClose={() => setMarking(false)} onSaved={st.reload} />}
    </Page>
  )
}
