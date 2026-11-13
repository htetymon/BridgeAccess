/**
 * Reads an image's real type, size and EXIF orientation from its first bytes, without
 * decoding it. Used before compression to:
 * - judge the type the way the server does (by content, not by file name or the browser's
 *   guess), so a renamed file is caught on the phone rather than after uploading;
 * - refuse images with absurd pixel counts before decoding them (a 500 KB file can declare
 *   enough pixels to exhaust a low-end phone's memory);
 * - decode large photos directly at a smaller size;
 * - spot JPEGs whose EXIF says "rotate me", which must be re-encoded upright because the
 *   server ignores EXIF orientation.
 */

export type ImageHeader = {
  type: 'image/png' | 'image/jpeg' | 'image/webp'
  /** Stored (not rotated) pixel size. */
  width: number
  height: number
  /** EXIF orientation 1 to 8; 1 means upright. */
  orientation: number
}

/** How much of the file to read: enough for JPEG EXIF (up to 64 KB) and the frame header. */
export const HEADER_BYTES = 256 * 1024

export function readImageHeader(bytes: Uint8Array): ImageHeader | null {
  if (isPng(bytes)) return readPng(bytes)
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return readJpeg(bytes)
  if (ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 4) === 'WEBP') return readWebp(bytes)
  return null
}

/** Size as displayed, after applying the EXIF rotation (orientations 5 to 8 swap sides). */
export function orientedSize(h: ImageHeader): { width: number; height: number } {
  return h.orientation >= 5 ? { width: h.height, height: h.width } : { width: h.width, height: h.height }
}

// -------------------------------------------------------------------- PNG

function isPng(b: Uint8Array) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  return sig.every((v, i) => b[i] === v)
}

function readPng(b: Uint8Array): ImageHeader | null {
  // The first chunk must be IHDR: width and height as 32-bit big-endian at 16 and 20.
  if (b.length < 24 || ascii(b, 12, 4) !== 'IHDR') return null
  return sized('image/png', u32be(b, 16), u32be(b, 20), 1)
}

// ------------------------------------------------------------------- JPEG

function readJpeg(b: Uint8Array): ImageHeader | null {
  let orientation = 1
  let i = 2
  while (i + 4 <= b.length) {
    if (b[i] !== 0xff) return null // lost sync: not a JPEG we can read
    const marker = b[i + 1]
    if (marker === 0xff) {
      i++ // fill byte
      continue
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      i += 2 // markers without a length
      continue
    }
    if (marker === 0xd9 || marker === 0xda) return null // end of image / scan before any frame
    const length = u16be(b, i + 2)
    if (length < 2) return null
    const start = i + 4
    if (marker === 0xe1 && ascii(b, start, 4) === 'Exif') {
      orientation = exifOrientation(b, start + 6, Math.min(b.length, i + 2 + length)) ?? orientation
    }
    // SOF0..SOF15 carry the frame size, except DHT (C4), JPG (C8) and DAC (CC).
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      if (start + 5 > b.length) return null
      return sized('image/jpeg', u16be(b, start + 3), u16be(b, start + 1), orientation)
    }
    i += 2 + length
  }
  return null
}

/** Orientation tag (0x0112) from IFD0 of the TIFF block that starts at `tiff`. */
function exifOrientation(b: Uint8Array, tiff: number, end: number): number | null {
  if (tiff + 8 > end) return null
  const order = ascii(b, tiff, 2)
  if (order !== 'II' && order !== 'MM') return null
  const little = order === 'II'
  const u16 = (o: number) => (little ? b[o] | (b[o + 1] << 8) : (b[o] << 8) | b[o + 1])
  const u32 = (o: number) => (little ? u32le(b, o) : u32be(b, o))
  if (u16(tiff + 2) !== 42) return null
  const ifd = tiff + u32(tiff + 4)
  if (ifd + 2 > end) return null
  const count = u16(ifd)
  for (let n = 0; n < count; n++) {
    const entry = ifd + 2 + n * 12
    if (entry + 12 > end) return null
    if (u16(entry) === 0x0112) {
      const value = u16(entry + 8)
      return value >= 1 && value <= 8 ? value : null
    }
  }
  return null
}

// ------------------------------------------------------------------- WebP

function readWebp(b: Uint8Array): ImageHeader | null {
  const chunk = ascii(b, 12, 4)
  const data = 20
  if (chunk === 'VP8X' && b.length >= data + 10) {
    // Extended: canvas width-1 and height-1 as 24-bit little-endian.
    return sized('image/webp', u24le(b, data + 4) + 1, u24le(b, data + 7) + 1, 1)
  }
  if (chunk === 'VP8 ' && b.length >= data + 10) {
    // Lossy: frame tag (3 bytes), start code 9d 01 2a, then 14-bit width and height.
    if (b[data + 3] !== 0x9d || b[data + 4] !== 0x01 || b[data + 5] !== 0x2a) return null
    return sized('image/webp', u16le(b, data + 6) & 0x3fff, u16le(b, data + 8) & 0x3fff, 1)
  }
  if (chunk === 'VP8L' && b.length >= data + 5) {
    // Lossless: signature 0x2f, then 14-bit width-1 and height-1 packed little-endian.
    if (b[data] !== 0x2f) return null
    const bits = u32le(b, data + 1)
    return sized('image/webp', (bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1, 1)
  }
  return null
}

// ---------------------------------------------------------------- helpers

function sized(type: ImageHeader['type'], width: number, height: number, orientation: number): ImageHeader | null {
  return width > 0 && height > 0 ? { type, width, height, orientation } : null
}

function ascii(b: Uint8Array, at: number, n: number) {
  if (at + n > b.length) return ''
  return String.fromCharCode(...b.subarray(at, at + n))
}

const u16be = (b: Uint8Array, o: number) => (b[o] << 8) | b[o + 1]
const u16le = (b: Uint8Array, o: number) => b[o] | (b[o + 1] << 8)
const u24le = (b: Uint8Array, o: number) => b[o] | (b[o + 1] << 8) | (b[o + 2] << 16)
const u32be = (b: Uint8Array, o: number) => ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0
const u32le = (b: Uint8Array, o: number) => (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0
