import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Loader2, AlertCircle, Inbox, X } from 'lucide-react'
import { errMsg } from '../lib/api'

export function Loading({ text = 'Loading...' }: { text?: string }) {
  return (
    <div className="flex items-center gap-2 text-gray-500 p-6">
      <Loader2 className="animate-spin" size={18} />
      {text}
    </div>
  )
}

export function ErrorBox({ error, onRetry }: { error: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex items-center gap-3 bg-red-50 border border-red-200 text-red-700 rounded p-3">
      <AlertCircle size={18} />
      <span className="flex-1">{error}</span>
      {onRetry && (
        <button className="btn btn-ghost" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  )
}

export function Empty({ text }: { text: string }) {
  return (
    <div className="flex flex-col items-center text-gray-400 py-10">
      <Inbox size={30} />
      <p className="mt-2">{text}</p>
    </div>
  )
}

/** Loads data with loading/error states. */
export function useLoad<T>(fn: () => Promise<T>, deps: any[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let live = true
    setLoading(true)
    setError('')
    fn()
      .then((d) => live && setData(d))
      .catch((e) => live && setError(errMsg(e)))
      .finally(() => live && setLoading(false))
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  // stable identity: callers put reload in effect/callback dependency lists
  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, error, loading, reload, setData }
}

/** Renders loading/error around loaded data. */
export function Async<T>({ state, children }: { state: ReturnType<typeof useLoad<T>>; children: (d: T) => ReactNode }) {
  if (state.loading && !state.data) return <Loading />
  if (state.error) return <ErrorBox error={state.error} onRetry={state.reload} />
  if (!state.data) return null
  return <>{children(state.data)}</>
}

export function Page({ title, subtitle, actions, children }: { title: string; subtitle?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-2 mb-4">
        <div>
          <h1 className="text-xl font-bold text-gray-800">{title}</h1>
          {subtitle && <p className="text-gray-500 text-xs mt-0.5">{subtitle}</p>}
        </div>
        <div className="flex gap-2">{actions}</div>
      </div>
      {children}
    </div>
  )
}

const tones: Record<string, string> = {
  neutral: 'border-gray-200 text-gray-800',
  good: 'border-green-300 bg-green-50 text-green-800',
  bad: 'border-red-300 bg-red-50 text-red-700',
  warn: 'border-amber-300 bg-amber-50 text-amber-800',
}
export function Stat({ label, value, tone = 'neutral', hint, icon }: { label: string; value: ReactNode; tone?: string; hint?: string; icon?: ReactNode }) {
  return (
    <div className={`border rounded-md p-3 bg-white ${tones[tone] || tones.neutral}`}>
      <div className="flex items-center justify-between text-xs text-gray-500 font-semibold uppercase tracking-wide">
        {label}
        {icon}
      </div>
      <div className="text-2xl font-bold mt-1">{value}</div>
      {hint && <div className="text-xs text-gray-500 mt-0.5">{hint}</div>}
    </div>
  )
}

export function SectionHeader({ children }: { children: ReactNode }) {
  return (
    <div className="text-white font-semibold px-3 py-1.5 text-sm rounded-t-md" style={{ background: '#6fc3c3' }}>
      {children}
    </div>
  )
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: string }) {
  const m: Record<string, string> = {
    good: 'bg-green-100 text-green-800',
    bad: 'bg-red-100 text-red-700',
    warn: 'bg-amber-100 text-amber-800',
    neutral: 'bg-gray-100 text-gray-700',
    info: 'bg-blue-100 text-blue-800',
  }
  return <span className={`inline-block rounded px-2 py-0.5 text-xs font-semibold ${m[tone] || m.neutral}`}>{children}</span>
}
export const statusTone = (s: string) =>
  s === 'EXCELLENT' || s === 'APPROVED' ? 'good' : s === 'IMPROVING' ? 'info' : s === 'NEEDS ATTENTION' || s === 'REJECTED' ? 'bad' : s === 'PENDING' ? 'warn' : 'neutral'

export function AttCell({ s }: { s: string }) {
  const cls: Record<string, string> = {
    P: 'text-gray-800',
    A: 'text-red-600 font-bold',
    NC: 'text-red-500 font-semibold',
    OD: 'text-blue-600 font-semibold',
    L: 'text-amber-600 font-semibold',
  }
  return <span className={cls[s] || 'text-gray-300'}>{s || '·'}</span>
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-label={title}>
      <div className={`bg-white rounded-lg shadow-xl w-full ${wide ? 'max-w-3xl' : 'max-w-md'} max-h-[90vh] overflow-auto`}>
        <div className="flex items-center justify-between px-4 py-3 border-b">
          <h2 className="font-bold">{title}</h2>
          <button aria-label="Close" onClick={onClose}>
            <X size={18} />
          </button>
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  )
}

export function Confirm({ title, message, confirmLabel = 'Confirm', danger, onConfirm, onClose }: { title: string; message: string; confirmLabel?: string; danger?: boolean; onConfirm: () => void | Promise<void>; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  return (
    <Modal title={title} onClose={onClose}>
      <p className="mb-4 text-gray-600">{message}</p>
      <div className="flex justify-end gap-2">
        <button className="btn btn-ghost" onClick={onClose}>
          Cancel
        </button>
        <button
          className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`}
          disabled={busy}
          onClick={async () => {
            setBusy(true)
            try {
              await onConfirm()
            } finally {
              setBusy(false)
              onClose()
            }
          }}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  )
}

export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <div className="flex items-center justify-end gap-2 mt-3">
      <button className="btn btn-ghost" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Prev
      </button>
      <span className="text-xs text-gray-500">
        Page {page + 1} of {totalPages}
      </span>
      <button className="btn btn-ghost" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </button>
    </div>
  )
}

export function Avatar({ name, size = 36 }: { name: string; size?: number }) {
  const initials = name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((s) => s[0])
    .join('')
    .toUpperCase()
  return (
    <div style={{ width: size, height: size }} className="rounded bg-gradient-to-br from-amber-100 to-amber-200 border border-amber-300 text-amber-800 font-bold flex items-center justify-center text-xs shrink-0">
      {initials}
    </div>
  )
}

export const fmtDate = (s?: string | null) => (s ? new Date(s).toLocaleString([], { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '')
export const fmtDay = (s?: string | null) =>
  s ? new Date(s + (s.length === 10 ? 'T00:00:00' : '')).toLocaleDateString([], { day: '2-digit', month: 'short', year: 'numeric' }) : ''
