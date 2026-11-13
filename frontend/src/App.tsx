import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import type { ReactElement } from 'react'
import { AuthProvider, useAuth } from './lib/auth'
import type { Role } from './lib/api'
import Layout from './components/Layout'
import { Loading } from './components/ui'
import Login from './pages/Login'
import Notifications from './pages/Notifications'
import Profile from './pages/Profile'
import StudentDashboard from './pages/student/Dashboard'
import StudentAttendance from './pages/student/Attendance'
import StudentMarks from './pages/student/Marks'
import StudentResults from './pages/student/Results'
import StudentAnalytics from './pages/student/Analytics'
import StudentMessages from './pages/student/Messages'
import StudentLeaves from './pages/student/Leaves'
import MentorDashboard from './pages/mentor/Dashboard'
import MentorStudents from './pages/mentor/Students'
import StudentProfile from './pages/mentor/StudentProfile'
import MentorAttendance from './pages/mentor/Attendance'
import MentorPerformance from './pages/mentor/Performance'
import MentorLeaves from './pages/mentor/Leaves'
import MentorMessages from './pages/mentor/Messages'
import Assistant from './pages/mentor/Assistant'
import AdminDashboard from './pages/admin/Dashboard'
import AdminStudents from './pages/admin/Students'
import AdminMentors from './pages/admin/Mentors'
import { Departments, Semesters, Subjects } from './pages/admin/Academic'
import Assignments from './pages/admin/Assignments'
import ImportData from './pages/admin/ImportData'
import { AssessmentConfig, SettingsPage } from './pages/admin/Config'
import Overview from './pages/Overview'
import SupportStudents from './pages/SupportStudents'
import Interventions from './pages/Interventions'
import Records from './pages/Records'
import StudentSupport from './pages/student/Support'
import SupportConfig from './pages/admin/SupportConfig'

export const homeFor = (r: Role) => (r === 'ADMIN' ? '/admin/dashboard' : r === 'MENTOR' ? '/mentor/dashboard' : '/student/dashboard')

/** Frontend route guard (the backend enforces the real authorization). */
function Guard({ role, children }: { role?: Role; children: ReactElement }) {
  const { user, loading } = useAuth()
  if (loading) return <Loading />
  if (!user) return <Navigate to="/login" replace />
  if (role && user.role !== role) return <Navigate to={homeFor(user.role)} replace />
  return children
}

/** Pages shared by mentors and administrators (the backend scopes the data). */
function StaffGuard({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth()
  if (loading) return <Loading />
  if (!user) return <Navigate to="/login" replace />
  if (user.role === 'STUDENT') return <Navigate to={homeFor(user.role)} replace />
  return children
}

function Root() {
  const { user, loading } = useAuth()
  if (loading) return <Loading />
  return <Navigate to={user ? homeFor(user.role) : '/login'} replace />
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<Root />} />
          <Route element={<Guard><Layout /></Guard>}>
            <Route path="/notifications" element={<Notifications />} />
            <Route path="/profile" element={<Profile />} />
            <Route path="/student/dashboard" element={<Guard role="STUDENT"><StudentDashboard /></Guard>} />
            <Route path="/student/attendance" element={<Guard role="STUDENT"><StudentAttendance /></Guard>} />
            <Route path="/student/marks" element={<Guard role="STUDENT"><StudentMarks /></Guard>} />
            <Route path="/student/results" element={<Guard role="STUDENT"><StudentResults /></Guard>} />
            <Route path="/student/analytics" element={<Guard role="STUDENT"><StudentAnalytics /></Guard>} />
            <Route path="/student/messages" element={<Guard role="STUDENT"><StudentMessages /></Guard>} />
            <Route path="/student/support" element={<Guard role="STUDENT"><StudentSupport /></Guard>} />
            <Route path="/overview" element={<StaffGuard><Overview /></StaffGuard>} />
            <Route path="/support-students" element={<StaffGuard><SupportStudents /></StaffGuard>} />
            <Route path="/support" element={<StaffGuard><Interventions /></StaffGuard>} />
            <Route path="/records" element={<StaffGuard><Records /></StaffGuard>} />
            <Route path="/admin/students/:id" element={<Guard role="ADMIN"><StudentProfile /></Guard>} />
            <Route path="/admin/support-config" element={<Guard role="ADMIN"><SupportConfig /></Guard>} />
            <Route path="/student/leaves" element={<Guard role="STUDENT"><StudentLeaves /></Guard>} />
            <Route path="/mentor/dashboard" element={<Guard role="MENTOR"><MentorDashboard /></Guard>} />
            <Route path="/mentor/students" element={<Guard role="MENTOR"><MentorStudents /></Guard>} />
            <Route path="/mentor/students/:id" element={<Guard role="MENTOR"><StudentProfile /></Guard>} />
            <Route path="/mentor/attendance" element={<Guard role="MENTOR"><MentorAttendance /></Guard>} />
            <Route path="/mentor/performance" element={<Guard role="MENTOR"><MentorPerformance /></Guard>} />
            <Route path="/mentor/leaves" element={<Guard role="MENTOR"><MentorLeaves /></Guard>} />
            <Route path="/mentor/messages" element={<Guard role="MENTOR"><MentorMessages /></Guard>} />
            <Route path="/mentor/assistant" element={<Guard role="MENTOR"><Assistant /></Guard>} />
            <Route path="/admin/dashboard" element={<Guard role="ADMIN"><AdminDashboard /></Guard>} />
            <Route path="/admin/students" element={<Guard role="ADMIN"><AdminStudents /></Guard>} />
            <Route path="/admin/mentors" element={<Guard role="ADMIN"><AdminMentors /></Guard>} />
            <Route path="/admin/departments" element={<Guard role="ADMIN"><Departments /></Guard>} />
            <Route path="/admin/subjects" element={<Guard role="ADMIN"><Subjects /></Guard>} />
            <Route path="/admin/semesters" element={<Guard role="ADMIN"><Semesters /></Guard>} />
            <Route path="/admin/assignments" element={<Guard role="ADMIN"><Assignments /></Guard>} />
            <Route path="/admin/import" element={<Guard role="ADMIN"><ImportData /></Guard>} />
            <Route path="/admin/assessment" element={<Guard role="ADMIN"><AssessmentConfig /></Guard>} />
            <Route path="/admin/settings" element={<Guard role="ADMIN"><SettingsPage /></Guard>} />
          </Route>
          <Route path="*" element={<Root />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
