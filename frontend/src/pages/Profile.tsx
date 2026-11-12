import { useState, type FormEvent } from 'react'
import { api, errMsg } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Avatar, Page } from '../components/ui'

export default function Profile() {
  const { user, refresh } = useAuth()
  const [name, setName] = useState(user!.fullName)
  const [phone, setPhone] = useState(user!.phone || '')
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [cur, setCur] = useState('')
  const [next, setNext] = useState('')
  const [pmsg, setPmsg] = useState<{ ok: boolean; text: string } | null>(null)

  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      await api.put('/api/auth/profile', { fullName: name, phone })
      await refresh()
      setMsg({ ok: true, text: 'Profile updated' })
    } catch (err) { setMsg({ ok: false, text: errMsg(err) }) }
  }
  const changePw = async (e: FormEvent) => {
    e.preventDefault()
    try {
      await api.post('/api/auth/password', { currentPassword: cur, newPassword: next })
      setCur(''); setNext('')
      setPmsg({ ok: true, text: 'Password changed' })
    } catch (err) { setPmsg({ ok: false, text: errMsg(err) }) }
  }
  const note = (m: { ok: boolean; text: string } | null) => m && <p role="status" className={`text-sm ${m.ok ? 'text-green-700' : 'text-red-600'}`}>{m.text}</p>

  return (
    <Page title="Profile" subtitle="Manage your account details">
      <div className="grid md:grid-cols-2 gap-4 max-w-4xl">
        <form onSubmit={save} className="card p-4 space-y-3">
          <div className="flex items-center gap-3"><Avatar name={user!.fullName} size={48} /><div><div className="font-bold">{user!.fullName}</div><div className="text-xs text-gray-500">{user!.email} · {user!.role}{user!.usn ? ` · ${user!.usn}` : ''}</div></div></div>
          <div><label htmlFor="pn" className="text-xs font-semibold text-gray-600">Full name</label><input id="pn" className="input w-full" required value={name} onChange={(e) => setName(e.target.value)} /></div>
          <div><label htmlFor="pp" className="text-xs font-semibold text-gray-600">Phone</label><input id="pp" className="input w-full" value={phone} maxLength={20} onChange={(e) => setPhone(e.target.value)} /></div>
          {note(msg)}
          <button className="btn btn-primary">Save changes</button>
        </form>
        <form onSubmit={changePw} className="card p-4 space-y-3">
          <h2 className="font-bold">Change password</h2>
          <div><label htmlFor="cp" className="text-xs font-semibold text-gray-600">Current password</label><input id="cp" type="password" required className="input w-full" value={cur} onChange={(e) => setCur(e.target.value)} /></div>
          <div><label htmlFor="np" className="text-xs font-semibold text-gray-600">New password (min 8 characters)</label><input id="np" type="password" required minLength={8} className="input w-full" value={next} onChange={(e) => setNext(e.target.value)} /></div>
          {note(pmsg)}
          <button className="btn btn-primary">Update password</button>
        </form>
      </div>
    </Page>
  )
}
