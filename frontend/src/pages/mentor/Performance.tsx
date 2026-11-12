import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../lib/api'
import { Async, Empty, Page, useLoad } from '../../components/ui'

const f = (v: any) => (v === null || v === undefined ? '—' : v)

/** ERP-style consolidated internal-assessment grid for one subject across assigned students. */
export default function MentorPerformance() {
  const [subjectId, setSubjectId] = useState<string>('')
  const [sort, setSort] = useState('usn')
  const st = useLoad<any>(() => api.get('/api/mentor/marks-grid', { params: { subjectId: subjectId || undefined, sort } }).then((r) => r.data), [subjectId, sort])
  return (
    <Page title="Academic Performance" subtitle="Internal assessment marks of your assigned students">
      <Async state={st}>
        {(d) => d.subjects.length === 0 ? <Empty text="No subjects or students available" /> : (
          <>
            <div className="flex flex-wrap gap-3 items-end mb-3">
              <div><label htmlFor="subj" className="block text-xs font-semibold text-gray-600">Subject</label>
                <select id="subj" className="input" value={d.selected.id} onChange={(e) => setSubjectId(e.target.value)}>{d.subjects.map((s: any) => <option key={s.id} value={s.id}>{s.code} – {s.name}</option>)}</select></div>
              <div className="text-sm flex gap-3 items-center"><b>Sort by:</b>{['name', 'usn'].map((k) => <label key={k} className="flex items-center gap-1"><input type="radio" name="s2" checked={sort === k} onChange={() => setSort(k)} />{k === 'name' ? 'Name' : 'USN'}</label>)}</div>
            </div>
            <div className="card overflow-auto">
              <div className="px-3 py-2 text-xs text-gray-500 border-b">Bucket: best {d.rules.iaBestCount} IA tests ({d.rules.iaBucketMax}) → {d.rules.iaConvertedMax}; activities ({d.rules.actBucketMax}) → {d.rules.actConvertedMax}. Rules are configured by the administrator.</div>
              <table className="erp-table min-w-[1100px]">
                <thead>
                  <tr>
                    <th colSpan={3} style={{ background: '#a3a3a3', color: '#fff' }}>Student Details</th>
                    <th colSpan={5} style={{ background: '#7fcfcf', color: '#fff' }}>Internal Assessment, Bucket Marks: {d.rules.iaBucketMax} IA Marks: {d.rules.iaConvertedMax}</th>
                    <th colSpan={5} style={{ background: '#7fcfcf', color: '#fff' }}>Activity, Bucket Marks: {d.rules.actBucketMax} Marks: {d.rules.actConvertedMax}</th>
                    <th rowSpan={2} style={{ background: 'linear-gradient(#f87171,#fecaca)' }}>Total</th>
                  </tr>
                  <tr><th>SL</th><th className="text-left sticky left-0 z-20 min-w-[180px]">Student Name</th><th>USN</th><th>T-IA1</th><th>T-IA2</th><th>T-IA3</th><th>Bucket</th><th>IA Marks</th><th>Act1</th><th>Act2</th><th>Act3</th><th>Bucket</th><th>Act Marks</th></tr>
                </thead>
                <tbody>
                  {d.rows.map((r: any, i: number) => (
                    <tr key={r.studentId}>
                      <td>{i + 1}</td>
                      <td className="text-left sticky left-0 bg-inherit"><Link to={`/mentor/students/${r.studentId}`} className="text-blue-600 hover:underline">{r.name}</Link></td>
                      <td>{r.usn}</td>
                      <td className="text-blue-700 font-semibold">{f(r.marks?.ia1)}</td><td className="text-blue-700 font-semibold">{f(r.marks?.ia2)}</td><td className="text-blue-700 font-semibold">{f(r.marks?.ia3)}</td>
                      <td className="text-xs text-gray-600">{r.marks ? `Bucket Marks: ${r.marks.iaBucket}` : '—'}</td><td className="font-semibold">{f(r.marks?.iaConverted)}</td>
                      <td>{f(r.marks?.act1)}</td><td>{f(r.marks?.act2)}</td><td>{f(r.marks?.act3)}</td>
                      <td className="text-xs text-gray-600">{r.marks ? `Bucket Marks: ${r.marks.actBucket}` : '—'}</td><td className="font-semibold">{f(r.marks?.actConverted)}</td>
                      <td className="font-bold text-red-700">{f(r.marks?.total)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Async>
    </Page>
  )
}
