import { ACCEPTED_IMAGE_TYPES, LIMITS } from './contribution'

/**
 * On-device image compression before upload (FR-5.6). Phone photos are often several MB;
 * uploading them on a slow connection is expensive, and the server refuses anything over
 * 500 KB. An accepted image that is already small enough is sent untouched; anything else is
 * scaled down and re-encoded until it fits. The server re-encodes again to about 80 KB, so
 * this only has to get under the upload cap, cheaply.
 */

/** Longest side after resizing. The server goes down to 1280 anyway. */
const MAX_SIDE = 1600
const QUALITIES = [0.85, 0.75, 0.65, 0.5]
const SHRINK = 0.75
const MIN_SIDE = 400

export type PreparedImage = { blob: Blob; name: string; previewUrl: string }

export class ImageError extends Error {}

export async function prepareImage(file: File): Promise<PreparedImage> {
  const accepted = (ACCEPTED_IMAGE_TYPES as readonly string[]).includes(file.type)
  if (accepted && file.size <= LIMITS.maxImageBytes) {
    return { blob: file, name: file.name, previewUrl: URL.createObjectURL(file) }
  }
  if (!file.type.startsWith('image/')) {
    throw new ImageError('Images must be PNG, JPG, or WebP.')
  }

  let bitmap: ImageBitmap
  try {
    // imageOrientation honours EXIF rotation, so phone photos are not sideways.
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new ImageError('This image could not be read. Try a PNG, JPG, or WebP file.')
  }
  try {
    const blob = await shrink(bitmap)
    const name = file.name.replace(/\.[^.]+$/, '') + (blob.type === 'image/webp' ? '.webp' : '.jpg')
    return { blob, name, previewUrl: URL.createObjectURL(blob) }
  } finally {
    bitmap.close()
  }
}

async function shrink(bitmap: ImageBitmap): Promise<Blob> {
  const longSide = Math.max(bitmap.width, bitmap.height)
  let side = Math.min(longSide, MAX_SIDE)
  let smallest: Blob | null = null
  for (;;) {
    const canvas = draw(bitmap, side)
    for (const quality of QUALITIES) {
      const blob = await encode(canvas, quality)
      if (!smallest || blob.size < smallest.size) smallest = blob
      if (blob.size <= LIMITS.maxImageBytes) return blob
    }
    const next = Math.round(side * SHRINK)
    if (next < MIN_SIDE) break
    side = next
  }
  if (smallest && smallest.size <= LIMITS.maxImageBytes) return smallest
  throw new ImageError('This image is too detailed to make smaller than 500 KB. Try another image.')
}

function draw(bitmap: ImageBitmap, side: number): HTMLCanvasElement {
  const ratio = side / Math.max(bitmap.width, bitmap.height)
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bitmap.width * ratio))
  canvas.height = Math.max(1, Math.round(bitmap.height * ratio))
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new ImageError('This device cannot prepare images. Try a smaller file.')
  // White behind transparency, in case the browser falls back to JPEG.
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  return canvas
}

/** WebP where the browser can encode it (it returns PNG when it cannot), else JPEG. */
async function encode(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  const webp = await toBlob(canvas, 'image/webp', quality)
  if (webp && webp.type === 'image/webp') return webp
  const jpeg = await toBlob(canvas, 'image/jpeg', quality)
  if (!jpeg) throw new ImageError('This device cannot prepare images. Try a smaller file.')
  return jpeg
}

function toBlob(canvas: HTMLCanvasElement, type: string, quality: number) {
  return new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, quality))
}
