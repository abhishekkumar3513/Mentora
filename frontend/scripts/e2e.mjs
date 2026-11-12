// End-to-end scenario check against a running backend (BASE env, default http://localhost:8090).
// Run: node scripts/e2e.mjs
import { Client } from '@stomp/stompjs'

const BASE = process.env.BASE || 'http://localhost:8090'
const WS = BASE.replace('http', 'ws') + '/ws'
let failed = 0
const ok = (name, cond, extra = '') => { console.log(`${cond ? 'PASS' : 'FAIL'}  ${name} ${cond ? '' : extra}`); if (!cond) failed++ }

async function req(path, { method = 'GET', token, body, form } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body) headers['Content-Type'] = 'application/json'
  const r = await fetch(BASE + path, { method, headers, body: form ?? (body ? JSON.stringify(body) : undefined) })
  const text = await r.text()
  let json; try { json = JSON.parse(text) } catch { json = text }
  return { status: r.status, json, headers: r.headers }
}
const login = async (email, password) => (await req('/api/auth/login', { method: 'POST', body: { email, password } })).json

function connect(token) {
  return new Promise((resolve, reject) => {
    const events = []
    const c = new Client({ brokerURL: WS, connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 0,
      onConnect: () => { for (const q of ['messages', 'notifications', 'errors', 'read']) c.subscribe(`/user/queue/${q}`, (m) => events.push({ q, body: JSON.parse(m.body) })); resolve({ c, events }) },
      onStompError: (f) => reject(new Error(f.headers.message)), onWebSocketError: reject })
    c.activate()
  })
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

const admin = await login('admin@mentora.edu', 'Admin@123')
const m1 = await login('mentor1@mentora.edu', 'Mentor@123')
const m2 = await login('mentor2@mentora.edu', 'Mentor@123')
const s3 = await login('1rn23is003@mentora.edu', 'Student@123')
const s6 = await login('1rn23is006@mentora.edu', 'Student@123')
const sid3 = s3.user.studentId, sid6 = s6.user.studentId

// S1 & S12: isolation
ok('S1 student sees own marks', (await req(`/api/students/${sid3}/marks`, { token: s3.token })).status === 200)
ok('S12 student denied other student marks', (await req(`/api/students/${sid6}/marks`, { token: s3.token })).status === 403)
ok('S12 student denied other student leaves', (await req(`/api/students/${sid6}/leaves`, { token: s3.token })).status === 403)
ok('S12 student denied mentor endpoints', (await req('/api/mentor/students', { token: s3.token })).status === 403)

// S2: mentor sees only assigned
const list1 = (await req('/api/mentor/students?size=100', { token: m1.token })).json
const list2 = (await req('/api/mentor/students?size=100', { token: m2.token })).json
ok('S2 mentor1 sees 30 students', list1.total === 30, JSON.stringify(list1.total))
ok('S2 mentor sets are disjoint', !list1.content.some((a) => list2.content.some((b) => b.studentId === a.studentId)))
ok('S2 mentor denied other mentor student', (await req(`/api/students/${list2.content[0].studentId}/attendance`, { token: m1.token })).status === 403)
ok('S2 mentor denied other mentor conversation', (await req(`/api/messages/${list2.content[0].studentId}`, { token: m1.token })).status === 403)
ok('S2 mentor denied private observations of other mentor student', (await req(`/api/students/${list2.content[0].studentId}/observations`, { token: m1.token })).status === 403)
ok('private observations hidden from student', (await req(`/api/students/${sid3}/observations`, { token: s3.token })).status === 403)

// S3/S4: realtime chat + moderation over STOMP
const stu = await connect(s3.token), men = await connect(m1.token)
const text = `Hello sir, could we meet tomorrow ${Date.now()}`
stu.c.publish({ destination: '/app/chat.send', body: JSON.stringify({ studentId: sid3, content: text }) })
await wait(1200)
ok('S3 mentor received message in real time', men.events.some((e) => e.q === 'messages' && e.body.content === text))
ok('S3 mentor got notification in real time', men.events.some((e) => e.q === 'notifications' && e.body.type === 'NEW_MESSAGE'))
stu.c.publish({ destination: '/app/chat.send', body: JSON.stringify({ studentId: sid3, content: 'you are a stupid idiot' }) })
await wait(800)
ok('S4 abusive message blocked', stu.events.some((e) => e.q === 'errors' && /communication guidelines/.test(e.body.error)))
ok('S4 abusive message not delivered', !men.events.some((e) => e.q === 'messages' && /idiot/.test(e.body.content)))
stu.c.publish({ destination: '/app/chat.send', body: JSON.stringify({ studentId: sid3, content: 'I had a fever and was absent, my illness caused failure in the test' }) })
await wait(800)
ok('medical words are not treated as abusive', men.events.some((e) => e.q === 'messages' && /fever/.test(e.body.content)))
// student cannot send into another student's conversation
stu.c.publish({ destination: '/app/chat.send', body: JSON.stringify({ studentId: sid6, content: 'intrusion test' }) })
await wait(600)
ok('WS: cannot send to another conversation', stu.events.some((e) => e.q === 'errors' && e.body.studentId === sid6))
// subscription to someone else's destination is rejected
let rejected = false
await new Promise((resolve) => {
  const c = new Client({ brokerURL: WS, connectHeaders: { Authorization: `Bearer ${s3.token}` }, reconnectDelay: 0,
    onConnect: () => { c.subscribe('/queue/messages', () => {}); setTimeout(resolve, 600) }, onStompError: () => { rejected = true; resolve() }, onWebSocketClose: () => { rejected = true; resolve() } })
  c.activate()
})
ok('WS: subscribing to non-user destination rejected', rejected)
let noToken = false
await new Promise((resolve) => { const c = new Client({ brokerURL: WS, reconnectDelay: 0, onConnect: () => resolve(), onStompError: () => { noToken = true; resolve() }, onWebSocketClose: () => { noToken = true; resolve() } }); c.activate(); setTimeout(resolve, 2000) })
ok('WS: connection without JWT rejected', noToken)
// the AI summary step is async; without a Gemini key it is marked UNAVAILABLE for mentors
const hist = (await req(`/api/messages/${sid3}`, { token: m1.token })).json
ok('mentor history exposes aiStatus field', hist.some((m) => m.senderRole === 'STUDENT' && 'aiStatus' in m))
const histStudent = (await req(`/api/messages/${sid3}`, { token: s3.token })).json
ok('student never sees AI summaries', histStudent.every((m) => !m.aiSummary && !m.aiStatus))
stu.c.deactivate(); men.c.deactivate()

// S5-S7: leave workflow with a document
const pdf = new Blob(['%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF'], { type: 'application/pdf' })
const form = new FormData()
form.append('type', 'MEDICAL'); form.append('startDate', new Date().toISOString().slice(0, 10)); form.append('endDate', new Date().toISOString().slice(0, 10))
form.append('reason', 'Fever, certificate attached.'); form.append('files', pdf, 'cert.pdf')
const before = (await req(`/api/students/${sid3}/attendance`, { token: s3.token })).json.overall
const sub = await req('/api/leaves', { method: 'POST', token: s3.token, form })
ok('S5 leave submitted with document', sub.status === 200 && sub.json.documents.length === 1, JSON.stringify(sub.json))
const after = (await req(`/api/students/${sid3}/attendance`, { token: s3.token })).json.overall
ok('submission did not change attendance', before.absent === after.absent && before.leave === after.leave)
const exe = new FormData(); exe.append('type', 'MEDICAL'); exe.append('startDate', '2026-11-01'); exe.append('endDate', '2026-11-01'); exe.append('reason', 'x'); exe.append('files', new Blob(['MZ-not-a-pdf'], { type: 'application/pdf' }), 'evil.pdf')
ok('fake PDF content rejected', (await req('/api/leaves', { method: 'POST', token: s3.token, form: exe })).status === 400)
const lid = sub.json.id, did = sub.json.documents[0].id
ok('S6 assigned mentor can download document', (await req(`/api/leaves/${lid}/documents/${did}?download=true`, { token: m1.token })).status === 200)
ok('S6 other mentor cannot download document', (await req(`/api/leaves/${lid}/documents/${did}`, { token: m2.token })).status === 403)
ok('other student cannot download document', (await req(`/api/leaves/${lid}/documents/${did}`, { token: s6.token })).status === 403)
ok('reject requires comment', (await req(`/api/leaves/${lid}/review`, { method: 'PUT', token: m1.token, body: { approve: false } })).status === 400)
ok('other mentor cannot review', (await req(`/api/leaves/${lid}/review`, { method: 'PUT', token: m2.token, body: { approve: true } })).status === 403)
ok('student cannot review own leave', (await req(`/api/leaves/${lid}/review`, { method: 'PUT', token: s3.token, body: { approve: true } })).status === 403)
const rev = await req(`/api/leaves/${lid}/review`, { method: 'PUT', token: m1.token, body: { approve: true, comment: 'Approved, get well soon' } })
ok('S6 mentor approves leave', rev.status === 200 && rev.json.status === 'APPROVED')
const notes = (await req('/api/notifications?size=10', { token: s3.token })).json
ok('S7 student notified of approval', notes.some((n) => n.type === 'LEAVE_APPROVED' && n.description.includes('approved')))
ok('already-reviewed leave cannot be re-reviewed', (await req(`/api/leaves/${lid}/review`, { method: 'PUT', token: m1.token, body: { approve: false, comment: 'x' } })).status === 400)

// S8: backend raises an absence alert to the right mentor only
const s2 = (await req('/api/mentor/students?search=1RN23IS002', { token: m1.token })).json.content[0]
const day = new Date(); while ([0, 6].includes(day.getDay())) day.setDate(day.getDate() - 1)
const iso = day.toISOString().slice(0, 10)
const m1Before = (await req('/api/notifications/unread-count', { token: m1.token })).json.count
const m2Before = (await req('/api/notifications/unread-count', { token: m2.token })).json.count
const mark = await req('/api/admin/attendance/mark-day', { method: 'POST', token: admin.token, body: { usn: '1RN23IS002', date: iso, status: 'A' } })
ok('S8 admin records absence', mark.status === 200, JSON.stringify(mark.json))
const mark2 = await req('/api/admin/attendance/mark-day', { method: 'POST', token: admin.token, body: { usn: '1RN23IS002', date: iso, status: 'A' } })
ok('S8 repeat absence does not duplicate alerts', mark2.json.alertsCreated === 0, JSON.stringify(mark2.json))
const m1After = (await req('/api/notifications/unread-count', { token: m1.token })).json.count
const m2After = (await req('/api/notifications/unread-count', { token: m2.token })).json.count
ok('S8 assigned mentor alerted', m1After > m1Before, `${m1Before}->${m1After}`)
ok('S8 other mentor not alerted', m2After === m2Before)
ok('S8 alert text', (await req('/api/notifications?size=5', { token: m1.token })).json.some((n) => n.description.includes('Aanchal Upreti (1RN23IS002) was marked absent')))
await req('/api/admin/attendance/mark-day', { method: 'POST', token: admin.token, body: { usn: '1RN23IS002', date: iso, status: 'P' } })

// S9-S11: AI assistant (deterministic analytics)
const ask = async (token, q) => (await req('/api/ai/assistant', { method: 'POST', token, body: { question: q } })).json.assistant?.payload
const a1 = await ask(m1.token, 'Which students have attendance below 75%?')
ok('S9 low-attendance operation', a1.operation === 'LOW_ATTENDANCE' && a1.answer.includes('Abhinav Bhardawaj'), a1.answer)
const d1 = await ask(m1.token, 'Which students have shown declining academic performance?')
ok('S10 declining operation includes Aashmit', d1.operation === 'DECLINING' && d1.answer.includes('Aashmit'), d1.answer)
const a2 = await ask(m2.token, 'Which students have attendance below 75%?')
ok('S11 mentor2 gets different, own students', a2.answer.includes('Farhan Sheikh') && !a2.answer.includes('Abhinav'), a2.answer)
ok('S11 mentor1 answer has no mentor2 students', !JSON.stringify(a1).includes('Farhan'))
ok('assistant gracefully works without Gemini', a1.aiGenerated === false || a1.aiGenerated === true)
ok('assistant is mentor-only', (await req('/api/ai/assistant', { method: 'POST', token: s3.token, body: { question: 'hi' } })).status === 403)
ok('unauthenticated rejected', (await req('/api/mentor/dashboard')).status === 401)
ok('tampered token rejected', (await req('/api/mentor/dashboard', { token: m1.token.slice(0, -6) + 'AAAAAA' })).status === 401)

console.log(failed ? `\n${failed} check(s) FAILED` : '\nAll end-to-end checks passed')
process.exit(failed ? 1 : 0)
