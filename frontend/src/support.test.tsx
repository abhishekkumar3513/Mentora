import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./lib/api', async () => {
  const actual = await vi.importActual<any>('./lib/api')
  return { ...actual, api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }, fetchBlobUrl: vi.fn() }
})
let role: any = { id: 1, role: 'MENTOR', fullName: 'Dr. Sunitha K', email: 'm@x', facultyId: 1 }
vi.mock('./lib/auth', async () => {
  const actual = await vi.importActual<any>('./lib/auth')
  return { ...actual, useAuth: () => ({ user: role, loading: false, connected: true, logout: vi.fn() }) }
})
import { api } from './lib/api'
import Overview from './pages/Overview'
import StudentSupport from './pages/student/Support'
import Notifications from './pages/Notifications'

const mocked = api as unknown as { get: any; post: any }

const overview = {
  scope: 'ASSIGNED_STUDENTS', studentsInView: 33, averageAttendance: 81.4, studentsRequiringSupport: 7, openInterventions: 4,
  distribution: { none: 26, review: 5, multiple: 2 },
  metrics: { belowAttendance: 3, overdueAssignments: 2, iaLow: 2, consecutiveAbsence: 1 }, attentionTotal: 7,
  attention: [],
}
const student = {
  studentId: 9, name: 'Rhea Menon', usn: '1RN23IS061', semester: 5, department: 'ISE', attendancePct: 68, iaAttended: 1, overdueAssignments: 3, consecutiveAbsences: 3,
  signalTypes: ['ATTENDANCE'], reasons: ['Attendance below threshold'], priority: 'HIGH', category: 'MULTIPLE', label: 'Multiple Support Signals',
}

beforeEach(() => {
  vi.clearAllMocks()
  Element.prototype.scrollIntoView = vi.fn()
  role = { id: 1, role: 'MENTOR', fullName: 'Dr. Sunitha K', email: 'm@x', facultyId: 1 }
  mocked.get.mockImplementation((url: string) => {
    if (url === '/api/overview') return Promise.resolve({ data: overview })
    if (url.startsWith('/api/overview/students')) return Promise.resolve({ data: [student] })
    return Promise.resolve({ data: [] })
  })
})

describe('Department Overview', () => {
  it('shows backend numbers, support wording and the non-diagnostic note', async () => {
    render(<MemoryRouter><Overview /></MemoryRouter>)
    expect(await screen.findByText('Students Requiring Support')).toBeInTheDocument()
    expect(screen.queryByText(/high-risk/i)).not.toBeInTheDocument()
    expect(screen.getByText('33')).toBeInTheDocument()
    expect(screen.getByText('81.4%')).toBeInTheDocument()
    expect(screen.getByText('Student Support Distribution')).toBeInTheDocument()
    expect(screen.getByLabelText(/Multiple Support Signals: 2 students/)).toHaveAttribute('href', '/support-students?category=MULTIPLE')
    expect(screen.getByText(/not automated decisions/)).toBeInTheDocument()
  })

  it('lists attention students with their reasons and a details link', async () => {
    render(<MemoryRouter><Overview /></MemoryRouter>)
    expect(await screen.findByText('Rhea Menon')).toBeInTheDocument()
    expect(screen.getByText('IA Attendance:', { exact: false })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View Details' })).toHaveAttribute('href', '/mentor/students/9')
    expect(screen.getAllByText('Multiple Support Signals').length).toBeGreaterThan(1) // distribution bar, filter option and the row badge
  })

  it('sends the search term to the backend', async () => {
    render(<MemoryRouter><Overview /></MemoryRouter>)
    await screen.findByText('Rhea Menon')
    await userEvent.type(screen.getByLabelText('Search by name or USN'), 'rhea')
    await waitFor(() => expect(mocked.get).toHaveBeenCalledWith(expect.stringContaining('q=rhea')))
  })
})

describe('Student Support Navigator', () => {
  it('offers the seven categories and never sends a student id', async () => {
    role = { id: 5, role: 'STUDENT', fullName: 'Rhea Menon', usn: '1RN23IS061', email: 's@x', studentId: 9 }
    const names = ['Academic Counselling', 'Attendance Improvement', 'Assignment Support', 'Technical Skill Development', 'Career Guidance', 'Parent Interaction', 'Personal Counselling Referral']
    mocked.get.mockImplementation((url: string) => Promise.resolve({ data: url === '/api/support/categories' ? names.map((n, i) => ({ code: `C${i}`, name: n, confidential: i === 6 })) : [] }))
    mocked.post.mockResolvedValue({ data: {} })
    render(<MemoryRouter><StudentSupport /></MemoryRouter>)
    const select = await screen.findByLabelText(/Support Category/)
    await waitFor(() => expect(screen.getAllByRole('option').length).toBe(8))
    for (const n of names) expect(screen.getByRole('option', { name: n })).toBeInTheDocument()
    expect(screen.getByLabelText('Student')).toBeDisabled()
    await userEvent.selectOptions(select, 'C4')
    await userEvent.type(screen.getByLabelText(/Describe your concern/), 'Need resume help')
    await userEvent.click(screen.getByRole('button', { name: /submit request/i }))
    await waitFor(() => expect(mocked.post).toHaveBeenCalledWith('/api/support/requests', { category: 'C4', concern: 'Need resume help', helpNeeded: '' }))
    expect(JSON.stringify(mocked.post.mock.calls[0])).not.toMatch(/studentId/)
  })
})

describe('Notifications centre', () => {
  it('shows priority, student and category and filters on the server', async () => {
    mocked.get.mockImplementation(() => Promise.resolve({ data: [{ id: 1, type: 'IA_ATTENDANCE', title: 'Internal Assessment Attendance Alert', description: 'IA 1: Attended', read: false, createdAt: new Date().toISOString(), priority: 'HIGH', studentName: 'Ishaan Mehta', usn: '1RN23IS063', studentId: 3, link: '/mentor/students/3' }] }))
    render(<MemoryRouter><Notifications /></MemoryRouter>)
    expect(await screen.findByText('Ishaan Mehta · 1RN23IS063')).toBeInTheDocument()
    expect(screen.getByText('High priority')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Filter by category'), 'IA_ATTENDANCE')
    await waitFor(() => expect(mocked.get).toHaveBeenCalledWith(expect.stringContaining('type=IA_ATTENDANCE')))
    await userEvent.click(screen.getByRole('button', { name: /mark all as read/i }))
    await waitFor(() => expect(mocked.post).toHaveBeenCalledWith('/api/notifications/read-all'))
  })
})
