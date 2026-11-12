import type { PackageType, ResourceType } from './api/packages'

/**
 * Download size in decimal units, as phones and the designs show it ("850 KB", "1.2 MB").
 * One decimal below 10 MB, none above; KB is always whole.
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1000) return `${Math.max(0, Math.round(bytes))} B`
  if (bytes < 1_000_000) return `${Math.min(999, Math.round(bytes / 1000))} KB`
  const mb = bytes / 1_000_000
  return `${mb < 10 ? Number(mb.toFixed(1)) : Math.round(mb)} MB`
}

export const PACKAGE_TYPE_LABEL: Record<PackageType, string> = {
  text_only: 'Text only',
  with_visual: 'With visual',
}

export const RESOURCE_TYPE_LABEL: Record<ResourceType, string> = {
  text: 'Text',
  video: 'Video',
}
