import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { Client } from '@stomp/stompjs'
import { api, type User } from './api'
import { emit } from './bus'

interface AuthCtx {
  user: User | null
  loading: boolean
  login: (e: string, p: string) => Promise<User>
  logout: () => void
  refresh: () => Promise<void>
  connected: boolean
}
const Ctx = createContext<AuthCtx>(null as any)
export const useAuth = () => useContext(Ctx)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [connected, setConnected] = useState(false)
  const client = useRef<Client | null>(null)

  const refresh = useCallback(async () => {
    if (!localStorage.getItem('mentora_token')) {
      setUser(null)
      setLoading(false)
      return
    }
    try {
      setUser((await api.get('/api/auth/me')).data)
    } catch {
      localStorage.removeItem('mentora_token')
      setUser(null)
    }
    setLoading(false)
  }, [])
  useEffect(() => {
    refresh()
  }, [refresh])

  // real-time channel (STOMP over WebSocket), authenticated with the JWT
  useEffect(() => {
    if (!user) return
    const token = localStorage.getItem('mentora_token')
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const c = new Client({
      brokerURL: `${proto}://${location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 4000,
      onConnect: () => {
        setConnected(true)
        for (const q of ['messages', 'notifications', 'read', 'errors', 'ai-summary'])
          c.subscribe(`/user/queue/${q}`, (m) => emit(q, JSON.parse(m.body)))
      },
      onWebSocketClose: () => setConnected(false),
    })
    c.activate()
    client.current = c
    ;(window as any).__mentoraSend = (dest: string, body: any) => c.publish({ destination: dest, body: JSON.stringify(body) })
    return () => {
      c.deactivate()
      client.current = null
      setConnected(false)
    }
  }, [user])

  const login = async (email: string, password: string) => {
    const { data } = await api.post('/api/auth/login', { email, password })
    localStorage.setItem('mentora_token', data.token)
    setUser(data.user)
    return data.user as User
  }
  const logout = () => {
    api.post('/api/auth/logout').catch(() => {})
    localStorage.removeItem('mentora_token')
    setUser(null)
    location.href = '/login'
  }
  return <Ctx.Provider value={{ user, loading, login, logout, refresh, connected }}>{children}</Ctx.Provider>
}

export function sendSocket(dest: string, body: any): boolean {
  const f = (window as any).__mentoraSend
  if (!f) return false
  try {
    f(dest, body)
    return true
  } catch {
    return false
  }
}
