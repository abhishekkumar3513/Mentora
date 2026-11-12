import { useState, type FormEvent } from 'react'
import { Upload } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import { Badge, Page, SectionHeader } from '../../components/ui'

const TYPES: Record<string, { label: string; columns: string; sample: string }> = {
  attendance: { label: 'Attendance', columns: 'usn, subjectCode, date (yyyy-MM-dd), status (P/A/OD/L/NC)', sample: 'usn,subjectCode,date,status\n1RN23IS003,23IS501,2026-11-12,A' },
  marks: { label: 'Internal marks', columns: 'usn, subjectCode, component (IA1-IA3, ACT1-ACT3), score (or AB)', sample: 'usn,subjectCode,component,score\n1RN23IS003,23IS501,IA3,18' },
  results: { label: 'Semester results', columns: 'usn, subjectCode, grade (O, A+, A, B+, B, C, P, F)', sample: 'usn,subjectCode,grade\n1RN23IS003,23IS401,A' },
}

export default function ImportData() {
  const [type, setType] = useState('attendance')
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<any>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [usn, setUsn] = useState('')
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10))
  const [status, setStatus] = useState('A')
  const [mark, setMark] = useState<{ ok: boolean; text: string } | null>(null)

  const upload = async (e: FormEvent) => {
    e.preventDefault()
    if (!file) return
    setBusy(true); setError(''); setReport(null)
    try {
      const fd = new FormData(); fd.append('file', file)
      setReport((await api.post(`/api/admin/import/${type}`, fd)).data)
    } catch (err) { setError(errMsg(err)) }
    setBusy(false)
  }
  const markDay = async (e: FormEvent) => {
    e.preventDefault()
    try {
      const { data } = await api.post('/api/admin/attendance/mark-day', { usn, date, status })
      setMark({ ok: true, text: `Recorded. Mentor alerts created: ${data.alertsCreated}` })
    } catch (err) { setMark({ ok: false, text: errMsg(err) }) }
  }
  const t = TYPES[type]
  return (
    <Page title="Academic Data Import" subtitle="Upload CSV or Excel (.xlsx) files; every row is validated and reported">
      <div className="grid lg:grid-cols-2 gap-4">
        <form onSubmit={upload} className="card">
          <SectionHeader>Bulk import</SectionHeader>
          <div className="p-3 space-y-3">
            <div><label htmlFor="it" className="text-xs font-semibold text-gray-600">Data type</label>
              <select id="it" className="input w-full" value={type} onChange={(e) => { setType(e.target.value); setReport(null) }}>{Object.entries(TYPES).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}</select></div>
            <p className="text-xs text-gray-600">Columns: <b>{t.columns}</b></p>
            <pre className="bg-gray-50 border rounded p-2 text-xs overflow-auto">{t.sample}</pre>
            <input type="file" aria-label="Import file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] || null)} />
            {error && <p role="alert" className="text-red-600">{error}</p>}
            <button className="btn btn-primary" disabled={!file || busy}><Upload size={16} />{busy ? 'Importing…' : 'Import'}</button>
          </div>
        </form>
        <form onSubmit={markDay} className="card">
          <SectionHeader>Record a student's attendance for a day</SectionHeader>
          <div className="p-3 space-y-3">
            <p className="text-xs text-gray-600">Applies the status to all subjects of the student's semester and lets the backend raise mentor alerts.</p>
            <div className="grid grid-cols-3 gap-2">
              <div><label htmlFor="mu" className="text-xs font-semibold text-gray-600">USN</label><input id="mu" required className="input w-full" value={usn} onChange={(e) => setUsn(e.target.value)} placeholder="1RN23IS003" /></div>
              <div><label htmlFor="md" className="text-xs font-semibold text-gray-600">Date</label><input id="md" type="date" required max={new Date().toISOString().slice(0, 10)} className="input w-full" value={date} onChange={(e) => setDate(e.target.value)} /></div>
              <div><label htmlFor="ms" className="text-xs font-semibold text-gray-600">Status</label><select id="ms" className="input w-full" value={status} onChange={(e) => setStatus(e.target.value)}>{['P', 'A', 'OD', 'L', 'NC'].map((s) => <option key={s}>{s}</option>)}</select></div>
            </div>
            {mark && <p role="status" className={mark.ok ? 'text-green-700' : 'text-red-600'}>{mark.text}</p>}
            <button className="btn btn-primary">Record</button>
          </div>
        </form>
      </div>
      {report && (
        <div className="card mt-4"><SectionHeader>Import report – {TYPES[report.type].label}</SectionHeader>
          <div className="p-3 space-y-3">
            <div className="flex flex-wrap gap-2"><Badge>Total {report.total}</Badge><Badge tone="good">Inserted {report.inserted}</Badge><Badge tone="info">Updated {report.updated}</Badge><Badge tone="warn">Duplicates {report.duplicates}</Badge><Badge tone="bad">Failed {report.failed}</Badge></div>
            {report.errors.length > 0 && <table className="erp-table"><thead><tr><th>Row</th><th className="text-left">Message</th></tr></thead><tbody>{report.errors.map((e: any, i: number) => <tr key={i}><td>{e.row}</td><td className="text-left text-red-700">{e.message}</td></tr>)}</tbody></table>}
          </div></div>
      )}
    </Page>
  )
}
