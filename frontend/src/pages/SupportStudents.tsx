import { Link, useSearchParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Page } from '../components/ui'
import SupportStudentList from '../components/SupportStudentList'

/** Filtered student list opened from the Department Overview cards, bars and metrics (?category=...). */
export default function SupportStudents() {
  const [params, setParams] = useSearchParams()
  const category = params.get('category') || 'ANY'
  return (
    <Page title="Students Requiring Support" subtitle="Support signals are prompts for human review, not judgements about students"
      actions={<Link className="btn btn-ghost" to="/overview"><ArrowLeft size={14} />Department Overview</Link>}>
      <div className="card"><SupportStudentList category={category} onCategory={(c) => setParams({ category: c })} /></div>
    </Page>
  )
}
