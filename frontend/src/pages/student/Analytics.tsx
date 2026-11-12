import { useAuth } from '../../lib/auth'
import { Page } from '../../components/ui'
import { AnalyticsView } from '../../components/StudentViews'

export default function StudentAnalytics() {
  const { user } = useAuth()
  return <Page title="Performance Analytics" subtitle="Charts and insights computed from your own records"><AnalyticsView studentId={user!.studentId!} /></Page>
}
