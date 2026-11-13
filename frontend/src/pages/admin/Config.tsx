import { useState } from 'react'
import { api, errMsg } from '../../lib/api'
import { Async, Page, useLoad } from '../../components/ui'

function SettingRow({ s, onSaved }: { s: any; onSaved: () => void }) {
  const [v, setV] = useState(s.value)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const isBool = s.key === 'attendance.leave_counts_as_present'
  const save = async () => {
    try { await api.put(`/api/admin/settings/${s.key}`, { value: v }); setMsg({ ok: true, text: 'Saved' }); onSaved() } catch (e) { setMsg({ ok: false, text: errMsg(e) }) }
  }
  return (
    <tr>
      <td className="text-left"><div className="font-semibold">{s.description}</div><div className="text-xs text-gray-400">{s.key}</div></td>
      <td>{isBool ? <select className="input" aria-label={s.key} value={v} onChange={(e) => setV(e.target.value)}><option>true</option><option>false</option></select>
        : <input className="input w-28" aria-label={s.key} type="number" min={0} step="any" value={v} onChange={(e) => setV(e.target.value)} />}</td>
      <td><button className="btn btn-primary" disabled={v === s.value} onClick={save}>Save</button>{msg && <span role="status" className={`ml-2 text-xs ${msg.ok ? 'text-green-700' : 'text-red-600'}`}>{msg.text}</span>}</td>
    </tr>
  )
}

function SettingsTable({ prefixes, note }: { prefixes: string[]; note?: string }) {
  const st = useLoad<any[]>(() => api.get('/api/admin/settings').then((r) => r.data), [])
  return (
    <Async state={st}>{(list) => (
      <>
        {note && <p className="text-sm text-gray-600 mb-3">{note}</p>}
        <table className="erp-table max-w-3xl"><thead><tr><th className="text-left">Setting</th><th>Value</th><th></th></tr></thead>
          <tbody>{list.filter((s) => prefixes.some((p) => s.key.startsWith(p))).map((s) => <SettingRow key={s.key + s.value} s={s} onSaved={st.reload} />)}</tbody></table>
      </>
    )}</Async>
  )
}

export function AssessmentConfig() {
  return (
    <Page title="Assessment Configuration" subtitle="Internal assessment structure used by every marks calculation">
      <SettingsTable prefixes={['ia.', 'activity.']} note="Default (as in the college ERP): best 2 of 3 IA tests (each out of 25) form a 50-mark bucket converted to 25; three activities form a bucket converted to 25. Changes apply immediately to all students." />
    </Page>
  )
}

export function SettingsPage() {
  return (
    <Page title="Settings" subtitle="Attendance and support-signal thresholds, notification priorities, performance criteria and messaging limits">
      <SettingsTable prefixes={['attendance.', 'signals.', 'priority.', 'perf.', 'moderation.']} />
    </Page>
  )
}
