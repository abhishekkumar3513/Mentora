import axios from 'axios'

export const api = axios.create({ baseURL: '' })

api.interceptors.request.use((cfg) => {
  const t = localStorage.getItem('mentora_token')
  if (t) cfg.headers.Authorization = `Bearer ${t}`
  return cfg
})

api.interceptors.response.use(
  (r) => r,
  (e) => {
    if (e.response?.status === 401 && !String(e.config?.url).includes('/auth/login')) {
      localStorage.removeItem('mentora_token')
      if (location.pathname !== '/login') location.href = '/login'
    }
    return Promise.reject(e)
  },
)

export function errMsg(e: any): string {
  return e?.response?.data?.error || e?.message || 'Something went wrong'
}

export type Role = 'ADMIN' | 'MENTOR' | 'STUDENT'
export interface User {
  id: number
  email: string
  fullName: string
  role: Role
  phone?: string
  studentId?: number
  usn?: string
  facultyId?: number
}

export async function fetchBlobUrl(url: string): Promise<string> {
  const r = await api.get(url, { responseType: 'blob' })
  return URL.createObjectURL(r.data)
}
