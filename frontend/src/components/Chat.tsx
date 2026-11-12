import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Check, CheckCheck, Paperclip, Send, Sparkles, X, FileText } from 'lucide-react'
import { api, errMsg, fetchBlobUrl } from '../lib/api'
import { sendSocket, useAuth } from '../lib/auth'
import { useBus } from '../lib/bus'
import { Loading, fmtDate } from './ui'

interface Msg {
  id: number
  studentId: number
  senderId: number
  senderRole: string
  senderName: string
  content: string
  attachmentName?: string
  hasAttachment: boolean
  createdAt: string
  readAt?: string | null
  status: string
  aiSummary?: any
  aiStatus?: string
}

function AiSummary({ m, onRegenerate }: { m: Msg; onRegenerate: () => void }) {
  const [busy, setBusy] = useState(false)
  if (m.aiSummary) {
    const s = { ...m.aiSummary }
    // "Unspecified" means the student did not state it; show that clearly instead of a bare placeholder
    for (const k of ['reason', 'date', 'document', 'request']) if (!s[k] || /^unspecified$/i.test(s[k])) s[k] = 'Not stated in the message'
    return (
      <div className="mt-1 border border-violet-200 bg-violet-50 rounded p-2 text-xs text-gray-700 max-w-md">
        <div className="flex items-center gap-1 font-bold text-violet-700 mb-1"><Sparkles size={12} />AI-generated summary <span className="font-normal text-violet-500">(verify with the original message)</span></div>
        <p className="mb-1">{s.summary}</p>
        <ul className="space-y-0.5">
          <li><b>Reason:</b> {s.reason}</li><li><b>Date:</b> {s.date}</li><li><b>Document:</b> {s.document}</li><li><b>Request:</b> {s.request}</li>
          <li><b>Category:</b> {s.category} · <b>Urgency:</b> {s.urgency}</li>
        </ul>
        <p className="mt-1 text-violet-800"><b>Suggested mentor action:</b> {s.suggestedAction}</p>
      </div>
    )
  }
  if (m.aiStatus === 'PENDING') return <div className="mt-1 text-xs text-violet-600 flex items-center gap-1"><Sparkles size={12} />Generating AI summary…</div>
  return (
    <button disabled={busy} className="mt-1 text-xs text-violet-700 flex items-center gap-1 hover:underline" onClick={async () => { setBusy(true); try { await api.post(`/api/ai/messages/${m.id}/summary`) } catch { /* ignore */ } setBusy(false); onRegenerate() }}>
      <Sparkles size={12} />{m.aiStatus === 'UNAVAILABLE' ? 'AI summary unavailable – try again' : m.aiStatus === 'FAILED' ? 'AI summary failed – retry' : 'Generate AI summary'}
    </button>
  )
}

export default function Chat({ studentId, onActivity }: { studentId: number; onActivity?: () => void }) {
  const { user, connected } = useAuth()
  const [msgs, setMsgs] = useState<Msg[]>([])
  const [loading, setLoading] = useState(true)
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const bottom = useRef<HTMLDivElement>(null)
  const isMentor = user!.role === 'MENTOR'

  // keep the latest callback in a ref so it never retriggers loading (it used to cause a reload loop = flicker)
  const activity = useRef(onActivity)
  useEffect(() => { activity.current = onActivity })
  const notifyActivity = useCallback(() => activity.current?.(), [])

  const load = useCallback(async () => {
    try {
      const { data } = await api.get(`/api/messages/${studentId}`, { params: { size: 100 } })
      setMsgs(data)
      api.post(`/api/messages/${studentId}/read`).then(notifyActivity).catch(() => {})
    } catch (e) { setError(errMsg(e)) }
    setLoading(false)
  }, [studentId, notifyActivity])
  useEffect(() => { setLoading(true); setMsgs([]); setError(''); load() }, [load])
  useEffect(() => { bottom.current?.scrollIntoView({ behavior: 'smooth' }) }, [msgs.length])

  useBus('messages', useCallback((m: Msg) => {
    if (m.studentId !== studentId) return
    setMsgs((cur) => (cur.some((x) => x.id === m.id) ? cur : [...cur, m]))
    if (m.senderId !== user!.id) api.post(`/api/messages/${studentId}/read`).then(notifyActivity).catch(() => {})
    else notifyActivity()
  }, [studentId, user, notifyActivity]))
  useBus('read', useCallback((r: any) => {
    if (r.studentId === studentId) setMsgs((cur) => cur.map((m) => (m.senderId === user!.id && !m.readAt ? { ...m, readAt: r.readAt, status: 'READ' } : m)))
  }, [studentId, user]))
  useBus('errors', useCallback((e: any) => { if (!e.studentId || e.studentId === studentId) setError(e.error) }, [studentId]))
  useBus('ai-summary', useCallback((e: any) => {
    if (e.studentId === studentId) setMsgs((cur) => cur.map((m) => (m.id === e.messageId ? { ...m, aiSummary: e.aiSummary, aiStatus: e.aiStatus } : m)))
  }, [studentId]))

  const send = async (e: FormEvent) => {
    e.preventDefault()
    const content = text.trim()
    if (!content && !file) return
    setError('')
    setSending(true)
    try {
      if (!file && connected && sendSocket('/app/chat.send', { studentId, content })) {
        setText('')
      } else {
        const fd = new FormData()
        if (content) fd.append('content', content)
        if (file) fd.append('file', file)
        const { data } = await api.post(`/api/messages/${studentId}`, fd)
        setMsgs((cur) => (cur.some((x) => x.id === data.id) ? cur : [...cur, data]))
        setText(''); setFile(null)
      }
    } catch (err) { setError(errMsg(err)) }
    setSending(false)
  }

  const open = async (m: Msg) => { window.open(await fetchBlobUrl(`/api/messages/attachments/${m.id}`), '_blank') }

  if (loading) return <Loading text="Loading conversation..." />
  return (
    <div className="flex flex-col h-full min-h-[420px]">
      <div className="flex-1 overflow-y-auto space-y-3 p-3 bg-gray-50 border rounded-t" aria-live="polite">
        {msgs.length === 0 && <p className="text-center text-gray-400 py-10">No messages yet. Say hello!</p>}
        {msgs.map((m) => {
          const mine = m.senderId === user!.id
          return (
            <div key={m.id} className={`flex flex-col ${mine ? 'items-end' : 'items-start'}`}>
              <div className={`max-w-[80%] rounded-lg px-3 py-2 shadow-sm ${mine ? 'bg-teal-600 text-white' : 'bg-white border'}`}>
                {!mine && <div className="text-[11px] font-semibold text-teal-700">{m.senderName}</div>}
                <p className="whitespace-pre-wrap break-words">{m.content}</p>
                {m.hasAttachment && <button onClick={() => open(m)} className={`mt-1 flex items-center gap-1 text-xs underline ${mine ? 'text-teal-100' : 'text-blue-600'}`}><FileText size={13} />{m.attachmentName}</button>}
                <div className={`text-[10px] mt-1 flex items-center gap-1 justify-end ${mine ? 'text-teal-100' : 'text-gray-400'}`}>
                  {fmtDate(m.createdAt)}
                  {mine && (m.readAt ? <CheckCheck size={13} className="text-sky-200" aria-label="Read" /> : <Check size={13} aria-label="Delivered" />)}
                </div>
              </div>
              {isMentor && m.senderRole === 'STUDENT' && <AiSummary m={m} onRegenerate={load} />}
            </div>
          )
        })}
        <div ref={bottom} />
      </div>
      {error && <div role="alert" className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 text-sm flex justify-between"><span>{error}</span><button aria-label="Dismiss" onClick={() => setError('')}><X size={14} /></button></div>}
      <form onSubmit={send} className="flex items-center gap-2 border border-t-0 rounded-b p-2 bg-white">
        <label className="cursor-pointer text-gray-500 hover:text-teal-700" title="Attach PDF, JPG or PNG (max 5 MB)">
          <Paperclip size={18} /><input type="file" accept="application/pdf,image/jpeg,image/png" className="hidden" aria-label="Attach file" onChange={(e) => setFile(e.target.files?.[0] || null)} />
        </label>
        {file && <span className="text-xs bg-gray-100 rounded px-2 py-1 flex items-center gap-1">{file.name}<button type="button" aria-label="Remove attachment" onClick={() => setFile(null)}><X size={12} /></button></span>}
        <input className="input flex-1" placeholder="Type a message" aria-label="Message" value={text} maxLength={1000} onChange={(e) => setText(e.target.value)} />
        <button className="btn btn-primary" disabled={sending || (!text.trim() && !file)} aria-label="Send"><Send size={16} />Send</button>
      </form>
    </div>
  )
}
