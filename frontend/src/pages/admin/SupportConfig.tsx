import { useState } from 'react'
import { api, errMsg } from '../../lib/api'
import { Async, Badge, Page, SectionHeader, useLoad } from '../../components/ui'

const OWNERS = ['FACULTY_MENTOR', 'DEPARTMENT_HOD', 'ACADEMIC_SUPPORT_TEAM', 'CAREER_GUIDANCE_TEAM', 'COUNSELLOR', 'STUDENT_AFFAIRS']
const label = (r: string) => r.split('_').map((w) => w[0] + w.slice(1).toLowerCase()).join(' ')

/** Support categories (default action owner, active flag) and counsellor authorisation. Thresholds are under Settings. */
export default function SupportConfig() {
  const cats = useLoad<any[]>(() => api.get('/api/admin/support-categories').then((r) => r.data), [])
  const people = useLoad<any[]>(() => api.get('/api/admin/counsellors').then((r) => r.data), [])
  const [msg, setMsg] = useState('')
  const act = async (fn: () => Promise<any>, reload: () => void) => {
    setMsg('')
    try { await fn(); setMsg('Saved'); reload() } catch (e) { setMsg(errMsg(e)) }
  }
  return (
    <Page title="Support Configuration" subtitle="Categories, default action owners and counsellor authorisation. Alert thresholds and priorities are under Settings.">
      {msg && <p role="status" className="mb-3 text-sm text-teal-700">{msg}</p>}
      <div className="grid lg:grid-cols-2 gap-4">
        <div className="card">
          <SectionHeader>Support categories</SectionHeader>
          <Async state={cats}>{(rows) => (
            <table className="w-full text-sm"><thead><tr className="text-left text-xs text-gray-500 border-b"><th className="p-2">Category</th><th className="p-2">Default action owner</th><th className="p-2">Active</th></tr></thead>
              <tbody>{rows.map((c) => (
                <tr key={c.code} className="border-b">
                  <td className="p-2">{c.name} {c.confidential && <Badge tone="warn">Confidential</Badge>}</td>
                  <td className="p-2"><select aria-label={`Default owner for ${c.name}`} className="input" value={c.defaultOwner} onChange={(e) => act(() => api.put(`/api/admin/support-categories/${c.code}`, { defaultOwner: e.target.value }), cats.reload)}>{OWNERS.map((o) => <option key={o} value={o}>{label(o)}</option>)}</select></td>
                  <td className="p-2"><input type="checkbox" aria-label={`${c.name} active`} checked={c.active} onChange={(e) => act(() => api.put(`/api/admin/support-categories/${c.code}`, { active: e.target.checked }), cats.reload)} /></td>
                </tr>))}</tbody></table>
          )}</Async>
        </div>
        <div className="card">
          <SectionHeader>Authorised counsellors</SectionHeader>
          <p className="text-xs text-gray-500 p-3 pb-0">Only counsellors and administrators can read confidential personal-counselling referrals. Ordinary mentors see that a referral exists, nothing more.</p>
          <Async state={people}>{(rows) => (
            <ul className="divide-y">{rows.map((p) => (
              <li key={p.facultyId} className="px-3 py-2 flex items-center gap-2"><span className="flex-1">{p.name}</span>
                <label className="text-sm flex items-center gap-1"><input type="checkbox" checked={p.counsellor} onChange={(e) => act(() => api.put(`/api/admin/counsellors/${p.facultyId}`, { counsellor: e.target.checked }), people.reload)} />Counsellor</label></li>
            ))}</ul>
          )}</Async>
        </div>
      </div>
    </Page>
  )
}
