import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { BookOpen, Loader2 } from 'lucide-react'
import { motion } from 'framer-motion'
import { useAuth } from '../lib/auth'
import { errMsg } from '../lib/api'
import { homeFor } from '../App'

export default function Login() {
  const { user, login } = useAuth()
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  if (user) return <Navigate to={homeFor(user.role)} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const u = await login(email, password)
      nav(homeFor(u.role), { replace: true })
    } catch (err) {
      setError(errMsg(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-100 flex items-center justify-center p-4">
      <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="w-full max-w-md bg-white border rounded-lg shadow-sm">
        <div className="px-6 py-5 border-b text-center" style={{ background: '#6fc3c3' }}>
          <div className="flex items-center justify-center gap-2 text-white text-2xl font-bold"><BookOpen />Mentora</div>
          <p className="text-white/90 text-xs mt-1">AI-powered student mentorship and academic performance monitoring</p>
        </div>
        <form onSubmit={submit} className="p-6 space-y-4">
          <div>
            <label htmlFor="email" className="block text-xs font-semibold text-gray-600 mb-1">Email</label>
            <input id="email" type="email" required className="input w-full" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </div>
          <div>
            <label htmlFor="password" className="block text-xs font-semibold text-gray-600 mb-1">Password</label>
            <input id="password" type="password" required className="input w-full" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </div>
          {error && <div role="alert" className="text-red-600 text-sm bg-red-50 border border-red-200 rounded p-2">{error}</div>}
          <button className="btn btn-primary w-full justify-center" disabled={busy}>{busy && <Loader2 size={16} className="animate-spin" />}Sign in</button>
        </form>
        <p className="px-6 pb-5 -mt-2 text-center text-xs text-gray-400">Use the email and password provided by your administrator.</p>
      </motion.div>
    </div>
  )
}
