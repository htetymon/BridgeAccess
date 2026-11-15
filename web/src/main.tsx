import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { router } from './router'
import { getDeviceId } from './lib/deviceId'
import { startSync } from './lib/sync'
import './index.css'

// Create (or load) the anonymous device ID on launch, before any request needs it.
getDeviceId()
// Send anything queued while offline, and keep sending as changes are made (FR-8.2).
startSync()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
