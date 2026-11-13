import { LIMITS } from './contribution'
import { HEADER_BYTES, orientedSize, readImageHeader, type ImageHeader } from './imageHeader'

/**
 * On-device image preparation before upload (FR-5.4, FR-5.6). Phone photos are often
 * several MB; uploading them on a slow connection is expensive, and the server refuses
 * anything over 500 KB. A real PNG, JPG or WebP that is small enough and upright is sent
 * untouched; anything else is decoded at a reduced size, drawn upright, and re-encoded until
 * it fits. The server re-encodes again to about 80 KB, so this only has to get under the
 * upload cap, cheaply and without exhausting a low-end phone's memory.
 */

/** Longest side after resizing. The server goes down to 1280 anyway. */
export const MAX_SIDE = 1600
/** Refuse before decoding: this many pixels decode to about 200 MB. */
export const MAX_PIXELS = 50_000_000
const QUALITIES = [0.85, 0.75, 0.65, 0.5]
const SHRINK = 0.75
const MIN_SIDE = 400

export type PreparedImage = {
  blob: Blob
  name: string
  previewUrl: string
  /** Size of the file the contributor picked, to show what compression saved. */
  originalBytes: number
  /** False when the picked file is uploaded as it is. */
  reencoded: boolean
}

export class ImageError extends Error {}

export type ImagePlan =
  | { action: 'keep' }
  | { action: 'reencode'; reason: 'too-large' | 'rotated' | 'other-format'; decodeWidth?: number }
  | { action: 'reject'; message: string }

/**
 * What to do with a picked file, from its header (null when the format is not PNG, JPG or
 * WebP, e.g. HEIC or GIF: the browser may still decode it, and it is then converted).
 */
export function planImage(header: ImageHeader | null, size: number): ImagePlan {
  if (header) {
    if (header.width * header.height > MAX_PIXELS) {
      return {
        action: 'reject',
        message: `This image is too large (${header.width} × ${header.height} pixels). Try a smaller image.`,
      }
    }
    const upright = header.orientation === 1
    if (size <= LIMITS.maxImageBytes && upright) return { action: 'keep' }
    const { width, height } = orientedSize(header)
    const longSide = Math.max(width, height)
    // Decode straight to the working size when the photo is bigger: far less memory.
    const decodeWidth = longSide > MAX_SIDE ? Math.round((width * MAX_SIDE) / longSide) : undefined
    return { action: 'reencode', reason: upright ? 'too-large' : 'rotated', decodeWidth }
  }
  return { action: 'reencode', reason: 'other-format' }
}

export async function prepareImage(file: File): Promise<PreparedImage> {
  const header = readImageHeader(new Uint8Array(await file.slice(0, HEADER_BYTES).arrayBuffer()))
  if (!header && !file.type.startsWith('image/')) {
    throw new ImageError('Choose a PNG, JPG, or WebP image.')
  }
  const plan = planImage(header, file.size)
  if (plan.action === 'reject') throw new ImageError(plan.message)
  if (plan.action === 'keep') {
    // Labelled with the real type, whatever the file name or browser guessed.
    const blob = file.type === header!.type ? file : new Blob([file], { type: header!.type })
    return { blob, name: file.name, previewUrl: URL.createObjectURL(blob), originalBytes: file.size, reencoded: false }
  }

  const source = await decode(file, plan.decodeWidth)
  try {
    const blob = await shrink(source.image, source.width, source.height)
    const name = file.name.replace(/\.[^.]+$/, '') + (blob.type === 'image/webp' ? '.webp' : '.jpg')
    return { blob, name, previewUrl: URL.createObjectURL(blob), originalBytes: file.size, reencoded: true }
  } finally {
    source.release()
  }
}

type Decoded = { image: CanvasImageSource; width: number; height: number; release: () => void }

/**
 * Decodes upright (EXIF applied). createImageBitmap can decode straight at `decodeWidth`
 * (only the width is given, so the aspect ratio is always kept); browsers without it, or
 * that cannot decode this file with it, fall back to an <img> element.
 */
async function decode(file: File, decodeWidth?: number): Promise<Decoded> {
  if (typeof createImageBitmap === 'function') {
    try {
      const options: ImageBitmapOptions = { imageOrientation: 'from-image' }
      if (decodeWidth) Object.assign(options, { resizeWidth: decodeWidth, resizeQuality: 'high' })
      const bitmap = await createImageBitmap(file, options)
      return { image: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() }
    } catch {
      // fall through to <img>
    }
  }
  const url = URL.createObjectURL(file)
  try {
    const img = new Image()
    img.decoding = 'async'
    img.src = url
    await img.decode()
    return { image: img, width: img.naturalWidth, height: img.naturalHeight, release: () => URL.revokeObjectURL(url) }
  } catch {
    URL.revokeObjectURL(url)
    throw new ImageError('This image could not be read. Choose a PNG, JPG, or WebP image.')
  }
}

async function shrink(image: CanvasImageSource, width: number, height: number): Promise<Blob> {
  let side = Math.min(Math.max(width, height), MAX_SIDE)
  let smallest: Blob | null = null
  for (;;) {
    const canvas = draw(image, width, height, side)
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

function draw(image: CanvasImageSource, width: number, height: number, side: number): HTMLCanvasElement {
  const ratio = Math.min(1, side / Math.max(width, height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(width * ratio))
  canvas.height = Math.max(1, Math.round(height * ratio))
  const ctx = context(canvas)
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(image, 0, 0, canvas.width, canvas.height)
  return canvas
}

/**
 * WebP where the browser can encode it (it silently returns PNG when it cannot), keeping
 * transparency. Otherwise JPEG, with transparent areas painted white instead of black.
 */
async function encode(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  const webp = await toBlob(canvas, 'image/webp', quality)
  if (webp && webp.type === 'image/webp') return webp
  const flat = document.createElement('canvas')
  flat.width = canvas.width
  flat.height = canvas.height
  const ctx = context(flat)
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, flat.width, flat.height)
  ctx.drawImage(canvas, 0, 0)
  const jpeg = await toBlob(flat, 'image/jpeg', quality)
  if (!jpeg) throw new ImageError('This phone could not prepare the image. Try a smaller one.')
  return jpeg
}

function context(canvas: HTMLCanvasElement) {
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new ImageError('This phone could not prepare the image. Try a smaller one.')
  return ctx
}

function toBlob(canvas: HTMLCanvasElement, type: string, quality: number) {
  return new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, quality))
}

/** Same file picked twice (same name, size and date): ignore the second copy. */
export function fileKey(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}
