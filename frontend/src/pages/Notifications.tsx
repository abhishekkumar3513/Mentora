import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { CheckCheck } from 'lucide-react'
import { api } from '../lib/api'
import { useBus } from '../lib/bus'
import { Async, Empty, Page, fmtDate, useLoad } from '../components/ui'

interface N { id: number; type: string; title: string; description: string; link?: string; read: boolean; createdAt: string }

export default function Notifications() {
  const nav = useNavigate()
  const st = useLoad<N[]>(() => api.get('/api/notifications?size=100').then((r) => r.data), [])
  const reload = st.reload
  useBus('notifications', useCallback(() => reload(), [reload]))
  const changed = () => window.dispatchEvent(new Event('mentora:notifications-changed'))

  const open = async (n: N) => {
    if (!n.read) { await api.post(`/api/notifications/${n.id}/read`).catch(() => {}); changed() }
    if (n.link) nav(n.link)
    else reload()
  }
  const markAll = async () => { await api.post('/api/notifications/read-all'); changed(); reload() }

  return (
    <Page title="Notifications" subtitle="Alerts, replies, approvals and announcements" actions={<button className="btn btn-ghost" onClick={markAll}><CheckCheck size={16} />Mark all read</button>}>
      <Async state={st}>
        {(list) => list.length === 0 ? <Empty text="No notifications yet" /> : (
          <ul className="card divide-y">
            {list.map((n) => (
              <li key={n.id}>
                <button onClick={() => open(n)} className={`w-full text-left px-4 py-3 flex gap-3 hover:bg-gray-50 ${n.read ? '' : 'bg-teal-50'}`}>
                  <span className={`mt-1.5 h-2 w-2 rounded-full shrink-0 ${n.read ? 'bg-gray-300' : 'bg-teal-600'}`} />
                  <span className="flex-1 min-w-0">
                    <span className="block font-semibold">{n.title}</span>
                    <span className="block text-gray-600">{n.description}</span>
                  </span>
                  <span className="text-xs text-gray-400 whitespace-nowrap">{fmtDate(n.createdAt)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Async>
    </Page>
  )
}
