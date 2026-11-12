import { useCallback, useEffect, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import {
  Bell, BarChart3, BookOpen, Bot, CalendarCheck, ClipboardList, FileText, GraduationCap, LayoutDashboard, LogOut, Menu, MessageSquare,
  Settings, SlidersHorizontal, Upload, UserCircle, Users, UserCog, Building2, Library, CalendarRange, Link2, Award, X, Wifi, WifiOff,
} from 'lucide-react'
import { useAuth } from '../lib/auth'
import { api } from '../lib/api'
import { useBus } from '../lib/bus'
import { Avatar } from './ui'

type Item = { to: string; label: string; icon: any }
const NAV: Record<string, Item[]> = {
  STUDENT: [
    { to: '/student/dashboard', label: 'Dashboard', icon: LayoutDashboard },
    { to: '/student/attendance', label: 'My Attendance', icon: CalendarCheck },
    { to: '/student/marks', label: 'Internal Marks', icon: ClipboardList },
    { to: '/student/results', label: 'Results', icon: Award },
    { to: '/student/analytics', label: 'Performance Analytics', icon: BarChart3 },
    { to: '/student/messages', label: 'Messages', icon: MessageSquare },
    { to: '/student/leaves', label: 'Leave Applications', icon: FileText },
    { to: '/notifications', label: 'Notifications', icon: Bell },
    { to: '/profile', label: 'Profile', icon: UserCircle },
  ],
  MENTOR: [
    { to: '/mentor/dashboard', label: 'Dashboard', icon: LayoutDashboard },
    { to: '/mentor/students', label: 'My Students', icon: Users },
    { to: '/mentor/attendance', label: 'Attendance Monitoring', icon: CalendarCheck },
    { to: '/mentor/performance', label: 'Academic Performance', icon: ClipboardList },
    { to: '/mentor/leaves', label: 'Leave Requests', icon: FileText },
    { to: '/mentor/messages', label: 'Messages', icon: MessageSquare },
    { to: '/mentor/assistant', label: 'AI Mentor Assistant', icon: Bot },
    { to: '/notifications', label: 'Notifications', icon: Bell },
    { to: '/profile', label: 'Profile', icon: UserCircle },
  ],
  ADMIN: [
    { to: '/admin/dashboard', label: 'Dashboard', icon: LayoutDashboard },
    { to: '/admin/students', label: 'Students', icon: GraduationCap },
    { to: '/admin/mentors', label: 'Mentors', icon: UserCog },
    { to: '/admin/departments', label: 'Departments', icon: Building2 },
    { to: '/admin/subjects', label: 'Subjects', icon: Library },
    { to: '/admin/semesters', label: 'Semesters', icon: CalendarRange },
    { to: '/admin/assignments', label: 'Mentor Assignments', icon: Link2 },
    { to: '/admin/import', label: 'Academic Data Import', icon: Upload },
    { to: '/admin/assessment', label: 'Assessment Configuration', icon: SlidersHorizontal },
    { to: '/admin/settings', label: 'Settings', icon: Settings },
    { to: '/profile', label: 'Profile', icon: UserCircle },
  ],
}

export default function Layout() {
  const { user, logout, connected } = useAuth()
  const nav = useNavigate()
  const [open, setOpen] = useState(false)
  const [unread, setUnread] = useState(0)
  const items = NAV[user!.role]

  const loadUnread = useCallback(() => {
    api.get('/api/notifications/unread-count').then((r) => setUnread(r.data.count)).catch(() => {})
  }, [])
  useEffect(() => { loadUnread() }, [loadUnread])
  useBus('notifications', () => loadUnread())
  useEffect(() => {
    const h = () => loadUnread()
    window.addEventListener('mentora:notifications-changed', h)
    return () => window.removeEventListener('mentora:notifications-changed', h)
  }, [loadUnread])

  return (
    <div className="min-h-screen flex bg-white">
      {open && <div className="fixed inset-0 bg-black/30 z-30 lg:hidden" onClick={() => setOpen(false)} />}
      <aside className={`fixed lg:sticky top-0 z-40 h-screen w-60 shrink-0 bg-gray-800 text-gray-200 flex flex-col transition-transform ${open ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}`}>
        <div className="flex items-center justify-between px-4 h-14 border-b border-gray-700">
          <div className="flex items-center gap-2 font-bold text-white text-lg"><BookOpen size={20} className="text-teal-300" />Mentora</div>
          <button className="lg:hidden" aria-label="Close menu" onClick={() => setOpen(false)}><X size={18} /></button>
        </div>
        <nav className="flex-1 overflow-y-auto py-2" aria-label="Main navigation">
          {items.map((it) => (
            <NavLink key={it.to} to={it.to} onClick={() => setOpen(false)}
              className={({ isActive }) => `flex items-center gap-3 px-4 py-2.5 text-sm ${isActive ? 'bg-teal-600 text-white font-semibold' : 'hover:bg-gray-700'}`}>
              <it.icon size={17} />
              {it.label}
              {it.to === '/notifications' && unread > 0 && <span className="ml-auto bg-red-500 text-white text-xs rounded-full px-1.5">{unread}</span>}
            </NavLink>
          ))}
        </nav>
        <button onClick={logout} className="flex items-center gap-3 px-4 py-3 border-t border-gray-700 hover:bg-gray-700 text-sm"><LogOut size={17} />Logout</button>
      </aside>
      <div className="flex-1 min-w-0 flex flex-col">
        <header className="h-14 border-b bg-white flex items-center justify-between px-4 sticky top-0 z-20">
          <button className="lg:hidden" aria-label="Open menu" onClick={() => setOpen(true)}><Menu size={20} /></button>
          <div className="hidden lg:block text-xs text-gray-400 items-center">{connected ? <span className="flex items-center gap-1 text-green-600"><Wifi size={14} />Live</span> : <span className="flex items-center gap-1"><WifiOff size={14} />Connecting…</span>}</div>
          <div className="flex items-center gap-4">
            <button className="relative" aria-label={`Notifications (${unread} unread)`} onClick={() => nav('/notifications')}>
              <Bell size={20} className="text-gray-600" />
              {unread > 0 && <span className="absolute -top-1.5 -right-2 bg-red-500 text-white text-[10px] rounded-full px-1.5 min-w-4 text-center">{unread}</span>}
            </button>
            <button className="flex items-center gap-2" onClick={() => nav('/profile')}>
              <Avatar name={user!.fullName} size={32} />
              <div className="text-left hidden sm:block"><div className="text-sm font-semibold leading-tight">{user!.fullName}</div><div className="text-[11px] text-gray-500">{user!.role}{user!.usn ? ` · ${user!.usn}` : ''}</div></div>
            </button>
          </div>
        </header>
        <main className="p-4 lg:p-6 flex-1 overflow-x-hidden"><Outlet /></main>
      </div>
    </div>
  )
}
