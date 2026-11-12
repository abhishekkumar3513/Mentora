import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import Chat from '../../components/Chat'
import { Async, Avatar, ErrorBox, Page, SectionHeader, fmtDate, useLoad } from '../../components/ui'

export default function StudentMessages() {
  const { user } = useAuth()
  const prof = useLoad<any>(() => api.get(`/api/students/${user!.studentId}/profile`).then((r) => r.data), [])
  const fb = useLoad<any[]>(() => api.get(`/api/students/${user!.studentId}/feedback`).then((r) => r.data), [])
  return (
    <Page title="Messages" subtitle="Chat with your assigned mentor in real time">
      <Async state={prof}>
        {(p) => !p.mentorName ? <ErrorBox error="No mentor has been assigned to you yet. Please contact the administrator." /> : (
          <div className="grid lg:grid-cols-3 gap-4">
            <div className="lg:col-span-2 card flex flex-col" style={{ height: '70vh' }}>
              <div className="flex items-center gap-2 px-3 py-2 border-b"><Avatar name={p.mentorName} /><div><div className="font-semibold">{p.mentorName}</div><div className="text-xs text-gray-500">Your mentor</div></div></div>
              <div className="flex-1 min-h-0"><Chat studentId={user!.studentId!} /></div>
            </div>
            <div className="card self-start">
              <SectionHeader>Mentor feedback</SectionHeader>
              <Async state={fb}>{(list) => list.length === 0 ? <p className="p-3 text-gray-400">No feedback yet</p> : (
                <ul className="divide-y">{list.map((f) => <li key={f.id} className="p-3"><p>{f.content}</p><p className="text-xs text-gray-400 mt-1">{f.author} · {fmtDate(f.createdAt)}</p></li>)}</ul>
              )}</Async>
            </div>
          </div>
        )}
      </Async>
    </Page>
  )
}
