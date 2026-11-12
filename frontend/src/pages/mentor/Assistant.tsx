import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Bot, Loader2, MessageSquarePlus, RefreshCw, Send, Sparkles } from 'lucide-react'
import { api, errMsg } from '../../lib/api'
import { Page, Stat } from '../../components/ui'

interface Reply { answer: string; aiGenerated: boolean; operation: string; cards: { label: string; value: string; tone: string }[]; table?: { columns: string[]; rows: string[][] } | null; notice?: string | null }
interface ChatMsg { id: string; role: 'USER' | 'ASSISTANT'; text: string; reply?: Reply; error?: boolean; question?: string }

const SUGGESTED = [
  'Analyze my students\' attendance.',
  'Identify academically weak students.',
  'Compare internal assessment performance.',
  'Summarize pending leave applications.',
  'Which students have attendance below 75%?',
  'Which students have shown declining academic performance?',
  'Who improved the most between IA 1 and IA 3?',
  'Give me a summary of today\'s attendance.',
]
const OPENING = 'Hello! I\'m your AI Mentor Assistant. Ask me anything about your assigned students\' attendance, academic performance or pending requests.'

export default function Assistant() {
  const [msgs, setMsgs] = useState<ChatMsg[]>([{ id: 'open', role: 'ASSISTANT', text: OPENING }])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [convId, setConvId] = useState<number | null>(null)
  const [history, setHistory] = useState<{ id: number; title: string }[]>([])
  const end = useRef<HTMLDivElement>(null)

  const loadHistory = () => api.get('/api/ai/conversations').then((r) => setHistory(r.data)).catch(() => {})
  useEffect(() => { loadHistory() }, [])
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }) }, [msgs.length, busy])

  const ask = async (q: string, conversation: number | null = convId, replaceId?: string) => {
    if (!q.trim() || busy) return
    setBusy(true)
    if (!replaceId) setMsgs((m) => [...m, { id: 'u' + Date.now(), role: 'USER', text: q }])
    else setMsgs((m) => m.filter((x) => x.id !== replaceId))
    try {
      const { data } = await api.post('/api/ai/assistant', { conversationId: conversation, question: q })
      setConvId(data.conversationId)
      const reply: Reply = data.assistant.payload
      setMsgs((m) => [...m, { id: 'a' + data.assistant.id, role: 'ASSISTANT', text: reply.answer, reply }])
      loadHistory()
    } catch (e) {
      setMsgs((m) => [...m, { id: 'e' + Date.now(), role: 'ASSISTANT', text: errMsg(e), error: true, question: q }])
    }
    setBusy(false)
  }
  const submit = (e: FormEvent) => { e.preventDefault(); const q = input; setInput(''); ask(q) }

  const openConv = async (id: number) => {
    const { data } = await api.get(`/api/ai/conversations/${id}`)
    setConvId(id)
    setMsgs([{ id: 'open', role: 'ASSISTANT', text: OPENING }, ...data.map((m: any) => ({ id: String(m.id), role: m.role, text: m.content, reply: m.payload || undefined }))])
  }
  const reset = () => { setConvId(null); setMsgs([{ id: 'open', role: 'ASSISTANT', text: OPENING }]) }

  return (
    <Page title="AI Mentor Assistant" subtitle="Answers come from your own students' verified records; the AI only explains them">
      <div className="grid lg:grid-cols-[1fr_240px] gap-4">
        <div className="card flex flex-col" style={{ height: '74vh' }}>
          <div className="flex-1 overflow-y-auto p-4 space-y-4 bg-gray-50" aria-live="polite">
            {msgs.map((m) => (
              <div key={m.id} className={`flex ${m.role === 'USER' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[92%] rounded-lg px-3 py-2 ${m.role === 'USER' ? 'bg-teal-600 text-white' : m.error ? 'bg-red-50 border border-red-200 text-red-700' : 'bg-white border'}`}>
                  {m.role === 'ASSISTANT' && <div className="text-[11px] font-bold text-violet-700 flex items-center gap-1 mb-1"><Bot size={12} />Assistant{m.reply && (m.reply.aiGenerated ? <span className="flex items-center gap-0.5 font-normal"><Sparkles size={10} />AI-generated explanation</span> : <span className="font-normal text-gray-500">· computed from your data</span>)}</div>}
                  <p className="whitespace-pre-wrap">{m.text}</p>
                  {m.reply?.notice && <p className="text-xs text-amber-700 mt-1">{m.reply.notice}</p>}
                  {m.reply && m.reply.cards.length > 0 && <div className="grid grid-cols-2 md:grid-cols-4 gap-2 mt-2">{m.reply.cards.map((c) => <Stat key={c.label} label={c.label} value={<span className="text-lg">{c.value}</span>} tone={c.tone} />)}</div>}
                  {m.reply?.table && m.reply.table.rows.length > 0 && (
                    <div className="overflow-x-auto mt-2"><table className="erp-table text-xs"><thead><tr>{m.reply.table.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
                      <tbody>{m.reply.table.rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} className={j === 0 ? 'text-left font-semibold' : ''}>{c}</td>)}</tr>)}</tbody></table></div>
                  )}
                  {m.error && m.question && <button className="btn btn-ghost mt-2" onClick={() => ask(m.question!, convId, m.id)}><RefreshCw size={14} />Retry</button>}
                </div>
              </div>
            ))}
            {busy && <div className="flex items-center gap-2 text-gray-500"><Loader2 className="animate-spin" size={16} />Analyzing your students' data…</div>}
            <div ref={end} />
          </div>
          <div className="px-3 pt-2 flex flex-wrap gap-1.5 border-t">
            {SUGGESTED.map((s) => <button key={s} className="text-xs bg-violet-50 hover:bg-violet-100 text-violet-800 rounded-full px-2.5 py-1" disabled={busy} onClick={() => ask(s)}>{s}</button>)}
          </div>
          <form onSubmit={submit} className="p-3 flex gap-2">
            <input className="input flex-1" aria-label="Ask the assistant" placeholder="Ask about attendance, marks, leave requests…" maxLength={500} value={input} onChange={(e) => setInput(e.target.value)} />
            <button className="btn btn-primary" disabled={busy || !input.trim()}><Send size={16} />Ask</button>
          </form>
        </div>
        <div className="card self-start">
          <div className="p-2 border-b flex items-center justify-between"><span className="font-semibold text-xs uppercase text-gray-500">Conversations</span><button className="text-teal-700" aria-label="New conversation" title="New conversation" onClick={reset}><MessageSquarePlus size={16} /></button></div>
          <ul className="divide-y max-h-[60vh] overflow-auto">{history.length === 0 && <li className="p-3 text-xs text-gray-400">No history yet</li>}
            {history.map((h) => <li key={h.id}><button onClick={() => openConv(h.id)} className={`w-full text-left p-2 text-xs hover:bg-gray-50 ${h.id === convId ? 'bg-teal-50' : ''}`}>{h.title}</button></li>)}</ul>
          <div className="p-2 text-[11px] text-gray-400 border-t">Looking for a student? <Link className="text-blue-600" to="/mentor/students">My students</Link></div>
        </div>
      </div>
    </Page>
  )
}
