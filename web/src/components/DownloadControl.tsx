import { useState } from 'react'
import { formatBytes } from '../lib/format'
import {
  cancelDownload,
  dataSaverOn,
  dismissFailure,
  needsConfirmation,
  startDownload,
  useDownload,
} from '../lib/downloadManager'
import { useOnline } from '../lib/useOnline'
import { CheckIcon, DownloadIcon, XIcon } from './Icons'

type Props = { packageId: string; title: string; sizeBytes: number }

/**
 * Download button, confirmation, progress and result for one package (FR-4.3, FR-4.6).
 * Sits inside a package card, above its stretched title link, so it stays tappable.
 *
 * A download over 1 MB, or any download while the browser's Data Saver is on, asks first,
 * inline (the same pattern as deleting a contribution). Resuming an interrupted download
 * does not ask again: the learner already agreed to it.
 */
export default function DownloadControl({ packageId, title, sizeBytes }: Props) {
  const state = useDownload(packageId)
  const online = useOnline()
  const [confirming, setConfirming] = useState(false)

  function begin() {
    dismissFailure(packageId)
    setConfirming(false)
    startDownload(packageId)
  }

  if (state.status === 'downloaded') {
    return (
      <p className="download download--done">
        <CheckIcon size={16} /> Downloaded
      </p>
    )
  }

  if (state.status === 'downloading' || state.status === 'retrying') {
    const { receivedBytes, totalBytes } = state
    const percent = totalBytes > 0 ? Math.min(100, Math.floor((receivedBytes * 100) / totalBytes)) : 0
    const label =
      state.status === 'retrying'
        ? state.offline
          ? 'Waiting for a connection…'
          : 'Connection problem. Retrying…'
        : totalBytes > 0
          ? `${percent}% · ${formatBytes(receivedBytes)} of ${formatBytes(totalBytes)}`
          : 'Starting…'
    return (
      <div className="download download--active">
        <div className="download__progress">
          <progress
            max={100}
            value={totalBytes > 0 ? percent : undefined}
            aria-label={`Downloading ${title}`}
            aria-valuetext={label}
          />
          <span className="download__label">{label}</span>
        </div>
        <button
          type="button"
          className="icon-button icon-button--small download__cancel"
          onClick={() => cancelDownload(packageId)}
          aria-label={`Cancel downloading ${title}`}
        >
          <XIcon size={18} />
        </button>
        <span className="visually-hidden" role="status">
          {state.status === 'retrying' ? label : `Downloading ${title}`}
        </span>
      </div>
    )
  }

  if (confirming) {
    return (
      <div className="download download--confirm" role="group" aria-label={`Download ${title}?`}>
        <p>
          {dataSaverOn()
            ? `Data Saver is on. Download this package (${formatBytes(sizeBytes)}) anyway?`
            : `This package is ${formatBytes(sizeBytes)}. Download it now?`}
        </p>
        <div className="download__actions">
          <button type="button" className="button button--primary button--small" onClick={begin}>
            Download
          </button>
          <button type="button" className="button button--secondary button--small" onClick={() => setConfirming(false)}>
            Cancel
          </button>
        </div>
      </div>
    )
  }

  const failed = state.status === 'failed'
  const paused = state.status === 'paused'
  return (
    <div className="download">
      {failed && (
        <p className="download__error" role="alert">
          {state.message}
        </p>
      )}
      <button
        type="button"
        className="button button--secondary button--small download__start"
        disabled={!online}
        onClick={() => (paused || failed || !needsConfirmation(sizeBytes) ? begin() : setConfirming(true))}
        aria-label={`${failed ? 'Try downloading' : paused ? 'Resume downloading' : 'Download'} ${title}`}
      >
        <DownloadIcon size={18} />
        {failed ? 'Try again' : paused ? 'Resume' : 'Download'}
      </button>
      {!online && <span className="download__hint">Connect to the internet to download.</span>}
    </div>
  )
}
