import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CheckCheck } from 'lucide-react'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useBus } from '../lib/bus'
import { NOTIFICATION_CATEGORIES, categoryLabel, priorityLabel, priorityTone } from '../lib/support'
import { Async, Badge, Empty, Page, fmtDate, useLoad } from '../components/ui'

interface N { id: number; type: string; title: string; description: string; link?: string; read: boolean; createdAt: string; priority?: string; studentName?: string; usn?: string; studentId?: number }

export default function Notifications() {
  const nav = useNavigate()
  const { user } = useAuth()
  const [type, setType] = useState('')
  const [status, setStatus] = useState('')
  const st = useLoad<N[]>(() => {
    const p = new URLSearchParams({ size: '100' })
    if (type) p.set('type', type)
    if (status === 'unread') p.set('unread', 'true')
    return api.get(`/api/notifications?${p}`).then((r) => (status === 'read' ? r.data.filter((n: N) => n.read) : r.data))
  }, [type, status])
  const reload = st.reload
  useBus('notifications', useCallback(() => reload(), [reload]))
  const changed = () => window.dispatchEvent(new Event('mentora:notifications-changed'))

  const open = async (n: N) => {
    if (!n.read) { await api.post(`/api/notifications/${n.id}/read`).catch(() => {}); changed() }
    if (n.link) nav(n.link)
    else reload()
  }
  const markOne = async (n: N) => { await api.post(`/api/notifications/${n.id}/read`); changed(); reload() }
  const markAll = async () => { await api.post('/api/notifications/read-all'); changed(); reload() }
  const staff = user?.role !== 'STUDENT'

  return (
    <Page title="Notifications" subtitle="Alerts, replies, approvals and announcements" actions={<button className="btn btn-ghost" onClick={markAll}><CheckCheck size={16} />Mark all as read</button>}>
      <div className="flex flex-wrap gap-2 mb-3">
        <select aria-label="Filter by category" className="input" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">All categories</option>
          {NOTIFICATION_CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
        <select aria-label="Filter by read status" className="input" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Read and unread</option><option value="unread">Unread only</option><option value="read">Read only</option>
        </select>
      </div>
      <Async state={st}>
        {(list) => list.length === 0 ? <Empty text="No notifications match" /> : (
          <ul className="card divide-y">
            {list.map((n) => (
              <li key={n.id} className={`flex ${n.read ? '' : 'bg-teal-50'}`}>
                <button onClick={() => open(n)} className="flex-1 text-left px-4 py-3 flex gap-3 hover:bg-gray-50 min-w-0">
                  <span className={`mt-1.5 h-2 w-2 rounded-full shrink-0 ${n.read ? 'bg-gray-300' : 'bg-teal-600'}`} aria-label={n.read ? 'Read' : 'Unread'} />
                  <span className="flex-1 min-w-0">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="font-semibold">{n.title}</span>
                      {staff && n.priority && n.priority !== 'NORMAL' && <Badge tone={priorityTone(n.priority)}>{priorityLabel(n.priority)}</Badge>}
                      <Badge>{categoryLabel(n.type)}</Badge>
                    </span>
                    {n.studentName && <span className="block text-sm text-gray-800">{n.studentName} · {n.usn}</span>}
                    <span className="block text-gray-600 whitespace-pre-line text-sm">{n.description}</span>
                  </span>
                  <span className="text-xs text-gray-400 whitespace-nowrap">{fmtDate(n.createdAt)}</span>
                </button>
                {!n.read && <button className="px-3 text-xs text-teal-700 hover:underline" onClick={() => markOne(n)} aria-label="Mark as read">Mark read</button>}
              </li>
            ))}
          </ul>
        )}
      </Async>
    </Page>
  )
}
