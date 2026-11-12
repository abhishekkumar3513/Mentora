import { useAuth } from '../../lib/auth'
import { Page } from '../../components/ui'
import { ResultsView } from '../../components/StudentViews'

export default function StudentResults() {
  const { user } = useAuth()
  return <Page title="Semester Results" subtitle="Grades, SGPA and CGPA"><ResultsView studentId={user!.studentId!} /></Page>
}
