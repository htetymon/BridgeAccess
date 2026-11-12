import { describe, expect, it } from 'vitest'
import { formatBytes } from './format'

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [999, '999 B'],
    [1_000, '1 KB'],
    [30_400, '30 KB'],
    [850_000, '850 KB'],
    [999_600, '999 KB'],
    [1_000_000, '1 MB'],
    [1_240_000, '1.2 MB'],
    [4_800_000, '4.8 MB'],
    [12_600_000, '13 MB'],
  ])('%i bytes is %s', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected)
  })
})
