import { describe, expect, it } from 'vitest'
import { orientedSize, readImageHeader } from './imageHeader'
import { MAX_PIXELS, MAX_SIDE, planImage } from './images'

/** Minimal valid headers, byte by byte, as the formats define them. */
function png(width: number, height: number) {
  const b = new Uint8Array(33)
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, ...ascii('IHDR')])
  new DataView(b.buffer).setUint32(16, width)
  new DataView(b.buffer).setUint32(20, height)
  return b
}

function jpeg(width: number, height: number, opts: { orientation?: number; littleEndian?: boolean } = {}) {
  const parts: number[] = [0xff, 0xd8]
  parts.push(0xff, 0xe0, 0, 16, ...ascii('JFIF'), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0) // APP0
  if (opts.orientation) {
    const le = opts.littleEndian ?? false
    const u16 = (v: number) => (le ? [v & 0xff, v >> 8] : [v >> 8, v & 0xff])
    const u32 = (v: number) => (le ? [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, v >>> 24] : [v >>> 24, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff])
    const tiff = [
      ...ascii(le ? 'II' : 'MM'), ...u16(42), ...u32(8),
      ...u16(2), // two IFD0 entries: something else first, then orientation
      ...u16(0x010f), ...u16(2), ...u32(4), ...u32(0),
      ...u16(0x0112), ...u16(3), ...u32(1), ...u16(opts.orientation), 0, 0,
      ...u32(0),
    ]
    const body = [...ascii('Exif'), 0, 0, ...tiff]
    parts.push(0xff, 0xe1, (body.length + 2) >> 8, (body.length + 2) & 0xff, ...body)
  }
  parts.push(0xff, 0xdb, 0, 4, 0, 0) // a DQT-shaped segment to skip
  parts.push(0xff, 0xc2, 0, 11, 8, height >> 8, height & 0xff, width >> 8, width & 0xff, 1, 1, 0x11, 0) // SOF2
  return new Uint8Array(parts)
}

function webp(kind: 'VP8X' | 'VP8 ' | 'VP8L', width: number, height: number) {
  const b = new Uint8Array(40)
  b.set([...ascii('RIFF'), 32, 0, 0, 0, ...ascii('WEBP'), ...ascii(kind), 10, 0, 0, 0])
  const d = new DataView(b.buffer)
  if (kind === 'VP8X') {
    d.setUint16(24, (width - 1) & 0xffff, true)
    b[26] = (width - 1) >> 16
    d.setUint16(27, (height - 1) & 0xffff, true)
    b[29] = (height - 1) >> 16
  } else if (kind === 'VP8 ') {
    b.set([0, 0, 0, 0x9d, 0x01, 0x2a], 20)
    d.setUint16(26, width, true)
    d.setUint16(28, height, true)
  } else {
    b[20] = 0x2f
    d.setUint32(21, ((width - 1) | ((height - 1) << 14)) >>> 0, true)
  }
  return b
}

function ascii(s: string) {
  return [...s].map((c) => c.charCodeAt(0))
}

describe('readImageHeader', () => {
  it('reads PNG', () => {
    expect(readImageHeader(png(800, 600))).toEqual({ type: 'image/png', width: 800, height: 600, orientation: 1 })
  })

  it('reads JPEG size past other segments', () => {
    expect(readImageHeader(jpeg(4032, 3024))).toEqual({ type: 'image/jpeg', width: 4032, height: 3024, orientation: 1 })
  })

  it('reads EXIF orientation in both byte orders', () => {
    expect(readImageHeader(jpeg(4032, 3024, { orientation: 6 }))?.orientation).toBe(6)
    expect(readImageHeader(jpeg(4032, 3024, { orientation: 3, littleEndian: true }))?.orientation).toBe(3)
  })

  it('reads all three WebP flavours', () => {
    expect(readImageHeader(webp('VP8X', 5000, 3000))).toMatchObject({ type: 'image/webp', width: 5000, height: 3000 })
    expect(readImageHeader(webp('VP8 ', 1024, 768))).toMatchObject({ width: 1024, height: 768 })
    expect(readImageHeader(webp('VP8L', 300, 200))).toMatchObject({ width: 300, height: 200 })
  })

  it('judges by content, not by name: GIF, HEIC-like and junk are not readable', () => {
    expect(readImageHeader(new Uint8Array([...ascii('GIF89a'), 1, 0, 1, 0]))).toBeNull()
    expect(readImageHeader(new Uint8Array([0, 0, 0, 24, ...ascii('ftypheic')]))).toBeNull()
    expect(readImageHeader(new Uint8Array([1, 2, 3]))).toBeNull()
  })

  it('survives truncated headers', () => {
    expect(readImageHeader(png(10, 10).subarray(0, 20))).toBeNull()
    expect(readImageHeader(jpeg(10, 10).subarray(0, 25))).toBeNull()
    expect(readImageHeader(webp('VP8 ', 10, 10).subarray(0, 22))).toBeNull()
  })

  it('swaps sides for rotated orientations', () => {
    expect(orientedSize({ type: 'image/jpeg', width: 4032, height: 3024, orientation: 6 })).toEqual({
      width: 3024,
      height: 4032,
    })
    expect(orientedSize({ type: 'image/jpeg', width: 4032, height: 3024, orientation: 3 })).toEqual({
      width: 4032,
      height: 3024,
    })
  })
})

describe('planImage', () => {
  const header = (w: number, h: number, orientation = 1, type: 'image/png' | 'image/jpeg' = 'image/jpeg') => ({
    type,
    width: w,
    height: h,
    orientation,
  })

  it('keeps a small upright image as it is', () => {
    expect(planImage(header(800, 600), 200_000)).toEqual({ action: 'keep' })
    expect(planImage(header(800, 600), 512_000)).toEqual({ action: 'keep' })
  })

  it('re-encodes anything over 500 KB, decoding big photos straight at the working size', () => {
    expect(planImage(header(4000, 3000), 3_000_000)).toEqual({ action: 'reencode', reason: 'too-large', decodeWidth: MAX_SIDE })
    expect(planImage(header(1200, 900), 600_000)).toEqual({ action: 'reencode', reason: 'too-large', decodeWidth: undefined })
  })

  it('re-encodes a small but rotated JPEG so it arrives upright, using the rotated width', () => {
    expect(planImage(header(4000, 3000, 6), 300_000)).toEqual({ action: 'reencode', reason: 'rotated', decodeWidth: 1200 })
  })

  it('converts formats it cannot read (HEIC, GIF) when the browser can decode them', () => {
    expect(planImage(null, 100_000)).toEqual({ action: 'reencode', reason: 'other-format' })
  })

  it('refuses decompression bombs before decoding', () => {
    const plan = planImage(header(20_000, 20_000, 1, 'image/png'), 400_000)
    expect(plan.action).toBe('reject')
    expect(plan.action === 'reject' && plan.message).toMatch(/20000 × 20000/)
    expect(planImage(header(5000, 10_000), 400_000).action).toBe('keep') // exactly MAX_PIXELS
    expect(5000 * 10_000).toBe(MAX_PIXELS)
  })
})
