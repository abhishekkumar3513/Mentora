import { useAuth } from '../../lib/auth'
import { Page } from '../../components/ui'
import { AttendanceView } from '../../components/StudentViews'

export default function StudentAttendance() {
  const { user } = useAuth()
  return <Page title="My Attendance" subtitle="Calculated from your recorded classes"><AttendanceView studentId={user!.studentId!} /></Page>
}
