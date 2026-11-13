import { useSyncExternalStore } from 'react'

/** Whether the browser thinks it is online; re-renders when that changes (FR-5.9). */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, () => true)
}

function subscribe(onChange: () => void) {
  window.addEventListener('online', onChange)
  window.addEventListener('offline', onChange)
  return () => {
    window.removeEventListener('online', onChange)
    window.removeEventListener('offline', onChange)
  }
}

function getSnapshot() {
  return navigator.onLine !== false
}
