import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { router } from './router'
import { getDeviceId } from './lib/deviceId'
import './index.css'

// Create (or load) the anonymous device ID on launch, before any request needs it.
getDeviceId()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
