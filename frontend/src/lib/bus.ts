import { useEffect } from 'react'

/** Tiny event bus: the socket provider emits server pushes, pages subscribe. */
const target = new EventTarget()
export function emit(name: string, detail: any) {
  target.dispatchEvent(new CustomEvent(name, { detail }))
}
export function useBus(name: string, handler: (d: any) => void) {
  useEffect(() => {
    const fn = (e: Event) => handler((e as CustomEvent).detail)
    target.addEventListener(name, fn)
    return () => target.removeEventListener(name, fn)
  }, [name, handler])
}
