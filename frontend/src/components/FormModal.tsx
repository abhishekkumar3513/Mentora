import { useState, type FormEvent } from 'react'
import { errMsg } from '../lib/api'
import { Modal } from './ui'

export interface Field {
  key: string
  label: string
  type?: 'text' | 'number' | 'password' | 'email' | 'select' | 'checkbox'
  options?: { value: string | number; label: string }[]
  required?: boolean
  min?: number
  max?: number
}

/** Generic validated form dialog used by the admin CRUD pages. */
export default function FormModal({ title, fields, initial, onSubmit, onClose }: {
  title: string
  fields: Field[]
  initial: Record<string, any>
  onSubmit: (values: Record<string, any>) => Promise<void>
  onClose: () => void
}) {
  const [v, setV] = useState<Record<string, any>>(initial)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const out: Record<string, any> = { ...v }
      fields.forEach((f) => { if (f.type === 'number' && out[f.key] !== '' && out[f.key] != null) out[f.key] = Number(out[f.key]) })
      await onSubmit(out)
      onClose()
    } catch (err) {
      setError(errMsg(err))
    }
    setBusy(false)
  }
  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {fields.map((f) => (
          <div key={f.key}>
            {f.type === 'checkbox' ? (
              <label className="flex items-center gap-2"><input type="checkbox" checked={!!v[f.key]} onChange={(e) => setV({ ...v, [f.key]: e.target.checked })} />{f.label}</label>
            ) : (
              <>
                <label htmlFor={`f-${f.key}`} className="text-xs font-semibold text-gray-600">{f.label}{f.required && ' *'}</label>
                {f.type === 'select' ? (
                  <select id={`f-${f.key}`} className="input w-full" required={f.required} value={v[f.key] ?? ''} onChange={(e) => setV({ ...v, [f.key]: e.target.value })}>
                    <option value="">Select…</option>
                    {f.options?.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                ) : (
                  <input id={`f-${f.key}`} className="input w-full" type={f.type || 'text'} required={f.required} min={f.min} max={f.max} value={v[f.key] ?? ''} onChange={(e) => setV({ ...v, [f.key]: e.target.value })} autoComplete="off" />
                )}
              </>
            )}
          </div>
        ))}
        {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={busy}>Save</button>
        </div>
      </form>
    </Modal>
  )
}
