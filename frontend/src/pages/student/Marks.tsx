import { useAuth } from '../../lib/auth'
import { Page } from '../../components/ui'
import { MarksView } from '../../components/StudentViews'

export default function StudentMarks() {
  const { user } = useAuth()
  return <Page title="Internal Marks" subtitle="IA 1, IA 2, IA 3, activities, bucket and converted marks per subject"><MarksView studentId={user!.studentId!} /></Page>
}
