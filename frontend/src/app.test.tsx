import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./lib/api', async () => {
  const actual = await vi.importActual<any>('./lib/api')
  return { ...actual, api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }, fetchBlobUrl: vi.fn() }
})
vi.mock('./lib/auth', async () => {
  const actual = await vi.importActual<any>('./lib/auth')
  return { ...actual, useAuth: () => (globalThis as any).__authOverride ?? actual.useAuth() }
})
import { api } from './lib/api'
import App from './App'
import Login from './pages/Login'
import { AuthProvider } from './lib/auth'
import { MarksTable, AttendanceView } from './components/StudentViews'
import Assistant from './pages/mentor/Assistant'
import { ReviewDialog, type Leave } from './components/Leaves'

const mocked = api as unknown as { get: any; post: any; put: any }

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  Element.prototype.scrollIntoView = vi.fn()
})

describe('authentication', () => {
  it('redirects unauthenticated users to the login page', async () => {
    window.history.pushState({}, '', '/mentor/students')
    render(<App />)
    expect(await screen.findByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })

  it('logs in and shows server errors', async () => {
    mocked.post.mockRejectedValueOnce({ response: { data: { error: 'Invalid email or password' } } })
    render(<MemoryRouter><AuthProvider><Login /></AuthProvider></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Email'), 'a@b.com')
    await userEvent.type(screen.getByLabelText('Password'), 'bad')
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(mocked.post).toHaveBeenCalledWith('/api/auth/login', { email: 'a@b.com', password: 'bad' })
  })

  it('has no demo shortcut buttons: users must type their own email and password', async () => {
    render(<MemoryRouter><AuthProvider><Login /></AuthProvider></MemoryRouter>)
    expect(screen.queryByText(/Demo accounts/i)).not.toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(1)   // only "Sign in"
    expect(screen.getByLabelText('Email')).toHaveValue('')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })
})

describe('internal marks table', () => {
  const rules = { iaBestCount: 2, iaBucketMax: 50, iaConvertedMax: 25, actBucketMax: 30, actConvertedMax: 25 }
  const rows = [{ subjectId: 1, code: '23IS501', name: 'Database Management Systems', marks: { ia1: 17, ia2: null, ia3: null, iaBucket: 17, iaConverted: 9, act1: 8, act2: 9, act3: 7, actBucket: 24, actConverted: 20, total: 29 } }]
  it('renders IA, activity, bucket, converted and total columns', () => {
    render(<MarksTable rows={rows} rules={rules} />)
    expect(screen.getByText('Database Management Systems')).toBeInTheDocument()
    for (const h of ['IA 1', 'IA 2', 'IA 3', 'Act 1', 'Act 3']) expect(screen.getAllByText(h).length).toBeGreaterThan(0)
    expect(screen.getByText('29')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBe(2)   // IA2 and IA3 not conducted
    expect(screen.getByText(/Bucket 50, IA marks 25/)).toBeInTheDocument()
  })
  it('shows an empty state without marks', () => {
    render(<MarksTable rows={[]} rules={rules} />)
    expect(screen.getByText(/No internal marks/)).toBeInTheDocument()
  })
})

describe('attendance page', () => {
  const view = (percent: number) => ({
    overall: { present: 20, absent: 10, onDuty: 0, leave: 0, notConducted: 1, conducted: 30, attended: 20, percent }, filtered: {}, threshold: 75, workingDays: 6, presentDays: 4, absentDays: 2, onDutyDays: 0, leaveDays: 0,
    daily: [{ date: '2026-11-10', status: 'P' }, { date: '2026-11-11', status: 'A' }], subjectWise: [{ subjectId: 7, code: '23IS501', name: 'DBMS', present: 10, absent: 5, leave: 0, conducted: 15, percent: 66.7 }], subjectRecords: [],
  })
  it('warns about shortage and filters by subject', async () => {
    mocked.get.mockImplementation(() => Promise.resolve({ data: view(66.7) }))
    render(<AttendanceView studentId={3} />)
    expect(await screen.findByText(/Attendance shortage/)).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Subject'), '7')
    await waitFor(() => expect(mocked.get).toHaveBeenCalledWith('/api/students/3/attendance', { params: { month: undefined, subjectId: '7' } }))
  })
  it('shows no warning above the threshold', async () => {
    mocked.get.mockImplementation(() => Promise.resolve({ data: view(90) }))
    render(<AttendanceView studentId={3} />)
    await screen.findByText('90%')
    expect(screen.queryByText(/Attendance shortage/)).not.toBeInTheDocument()
  })
})

describe('AI mentor assistant', () => {
  it('sends a suggested question and renders cards and table', async () => {
    mocked.get.mockResolvedValue({ data: [] })
    mocked.post.mockResolvedValue({ data: { conversationId: 1, assistant: { id: 2, payload: { answer: '1 student is below 75%.', aiGenerated: false, operation: 'LOW_ATTENDANCE', notice: 'AI narration is unavailable', cards: [{ label: 'Below threshold', value: '1', tone: 'bad' }], table: { columns: ['Student', 'USN'], rows: [['Abhinav', '1RN23IS004']] } } } } })
    render(<MemoryRouter><Assistant /></MemoryRouter>)
    expect(screen.getByText(/I'm your AI Mentor Assistant/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Summarize pending leave applications/ }))
    expect(await screen.findByText('1 student is below 75%.')).toBeInTheDocument()
    expect(screen.getByText('1RN23IS004')).toBeInTheDocument()
    expect(screen.getByText('Below threshold')).toBeInTheDocument()
    expect(mocked.post).toHaveBeenCalledWith('/api/ai/assistant', { conversationId: null, question: 'Summarize pending leave applications.' })
  })
  it('offers retry on failure', async () => {
    mocked.get.mockResolvedValue({ data: [] })
    mocked.post.mockRejectedValueOnce({ response: { data: { error: 'Too many assistant requests' } } })
    render(<MemoryRouter><Assistant /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText('Ask the assistant'), 'hello')
    await userEvent.click(screen.getByRole('button', { name: /^Ask$/ }))
    expect(await screen.findByText('Too many assistant requests')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument()
  })
})

describe('mentor leave approval workflow', () => {
  const leave: Leave = { id: 5, studentId: 1, studentName: 'Achappa Varsha', usn: '1RN23IS006', type: 'MEDICAL', startDate: '2026-11-11', endDate: '2026-11-12', reason: 'Fever', status: 'PENDING', createdAt: '2026-11-12T10:00:00Z', documents: [], history: [] }
  it('requires a comment to reject and confirms before approving', async () => {
    const done = vi.fn()
    mocked.put.mockResolvedValue({ data: {} })
    render(<ReviewDialog leave={leave} onClose={() => {}} onDone={done} />)
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(await screen.findByText(/add a comment before rejecting/)).toBeInTheDocument()
    expect(mocked.put).not.toHaveBeenCalled()
    await userEvent.type(screen.getByLabelText(/Review comment/), 'Please share a certificate')
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' }).at(-1)!)
    await waitFor(() => expect(mocked.put).toHaveBeenCalledWith('/api/leaves/5/review', { approve: true, comment: 'Please share a certificate' }))
    await waitFor(() => expect(done).toHaveBeenCalled())
  })
})

describe('chat does not reload in a loop (regression: flicker)', () => {
  it('loads history once even when the parent passes a new onActivity every render', async () => {
    const { default: Chat } = await import('./components/Chat')
    const { useState } = await import('react')
    mocked.get.mockResolvedValue({ data: [] })
    mocked.post.mockResolvedValue({ data: {} })
    const Parent = () => {
      const [, force] = useState(0)
      return <><button onClick={() => force((n) => n + 1)}>rerender</button><Chat studentId={3} onActivity={() => {}} /></>
    }
    ;(globalThis as any).__authOverride = { user: { id: 1, role: 'MENTOR' }, connected: false }
    render(<Parent />)
    await screen.findByText(/No messages yet/)
    await userEvent.click(screen.getByText('rerender'))
    await userEvent.click(screen.getByText('rerender'))
    await new Promise((r) => setTimeout(r, 100))
    expect(mocked.get.mock.calls.filter((c: any[]) => String(c[0]).startsWith('/api/messages/3')).length).toBe(1)
  })
})
afterAll(() => { delete (globalThis as any).__authOverride })

describe('mentor attendance editor', () => {
  it('sends only the changed students for the chosen date', async () => {
    const { default: MentorAttendance } = await import('./pages/mentor/Attendance')
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('attendance-grid')) return Promise.resolve({ data: { dates: ['2026-11-12'], threshold: 75, rows: [{ studentId: 1, name: 'Asha', usn: '1RN23IS001', total: 10, present: 9, percent: 90, days: { '2026-11-12': 'P' } }] } })
      return Promise.resolve({ data: [{ studentId: 1, name: 'Asha', usn: '1RN23IS001', status: 'P' }, { studentId: 2, name: 'Bala', usn: '1RN23IS002', status: '' }] })
    })
    mocked.post.mockResolvedValue({ data: { updated: 2, alertsCreated: 1 } })
    render(<MemoryRouter><MentorAttendance /></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: /Mark attendance/ }))
    await screen.findByText('Bala')
    expect(screen.getByRole('button', { name: 'Save attendance' })).toBeDisabled()
    await userEvent.click(within(screen.getByRole('group', { name: 'Attendance for Asha' })).getByRole('button', { name: 'A' }))
    await userEvent.click(within(screen.getByRole('group', { name: 'Attendance for Bala' })).getByRole('button', { name: 'P' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save attendance' }))
    await waitFor(() => expect(mocked.post).toHaveBeenCalled())
    const [url, body] = mocked.post.mock.calls[0]
    expect(url).toBe('/api/mentor/attendance/mark')
    expect(body.entries).toEqual([{ studentId: 1, status: 'A' }, { studentId: 2, status: 'P' }])
    expect(await screen.findByText(/Saved 2 student/)).toBeInTheDocument()
  })
})
