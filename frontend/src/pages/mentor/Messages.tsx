import { useCallback, useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../../lib/api'
import { useBus } from '../../lib/bus'
import Chat from '../../components/Chat'
import { Async, Avatar, Empty, Page, fmtDate, useLoad } from '../../components/ui'

export default function MentorMessages() {
  const [params, setParams] = useSearchParams()
  const selected = params.get('student') ? Number(params.get('student')) : null
  const conv = useLoad<any[]>(() => api.get('/api/mentor/conversations').then((r) => r.data), [])
  const reload = conv.reload
  useBus('messages', useCallback(() => reload(), [reload]))
  useEffect(() => { if (!selected && conv.data?.length) setParams({ student: String(conv.data[0].studentId) }, { replace: true }) }, [selected, conv.data, setParams])
  const current = conv.data?.find((c) => c.studentId === selected)

  return (
    <Page title="Messages" subtitle="Real-time conversations with your assigned students">
      <Async state={conv}>
        {(list) => list.length === 0 ? <Empty text="No assigned students" /> : (
          <div className="grid lg:grid-cols-[280px_1fr] gap-4">
            <div className="card overflow-y-auto" style={{ maxHeight: '72vh' }}>
              <ul className="divide-y">{list.map((c) => (
                <li key={c.studentId}><button onClick={() => setParams({ student: String(c.studentId) })} className={`w-full text-left px-3 py-2.5 flex gap-2 hover:bg-gray-50 ${c.studentId === selected ? 'bg-teal-50' : ''}`}>
                  <Avatar name={c.name} size={34} />
                  <div className="flex-1 min-w-0"><div className="flex justify-between"><span className="font-semibold truncate">{c.name}</span>{c.unread > 0 && <span className="bg-red-500 text-white text-[10px] rounded-full px-1.5">{c.unread}</span>}</div>
                    <div className="text-xs text-gray-500 truncate">{c.lastMessage || 'No messages yet'}</div><div className="text-[10px] text-gray-400">{c.lastAt ? fmtDate(c.lastAt) : c.usn}</div></div>
                </button></li>
              ))}</ul>
            </div>
            {selected && current ? (
              <div className="card flex flex-col" style={{ height: '72vh' }}>
                <div className="flex items-center gap-2 px-3 py-2 border-b"><Avatar name={current.name} /><div className="flex-1"><div className="font-semibold">{current.name}</div><div className="text-xs text-gray-500">{current.usn}</div></div>
                  <Link className="btn btn-ghost" to={`/mentor/students/${selected}`}>View profile</Link></div>
                <div className="flex-1 min-h-0"><Chat key={selected} studentId={selected} onActivity={reload} /></div>
              </div>
            ) : <Empty text="Select a conversation" />}
          </div>
        )}
      </Async>
    </Page>
  )
}
