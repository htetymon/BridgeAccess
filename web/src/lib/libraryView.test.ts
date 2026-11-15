import { describe, expect, it } from 'vitest'
import type { LocalPackage } from './db'
import { applyLibraryView, readLibraryView, writeLibraryView, type LibraryItem, type LibraryView } from './libraryView'

function item(id: string, extra: Partial<LocalPackage> = {}, lastOpenedAt?: string): LibraryItem {
  return {
    pkg: {
      id,
      title: id,
      subject: 'Science',
      resourceType: 'text',
      packageType: 'text_only',
      sizeBytes: 1,
      publishedAt: '2026-11-01T00:00:00.000Z',
      downloadStatus: 'downloaded',
      downloadedAt: '2026-11-10T00:00:00.000Z',
      ...extra,
    },
    lastOpenedAt,
    learnt: false,
    percent: 0,
  }
}

const view = (v: Partial<LibraryView> = {}): LibraryView => ({ q: '', sort: 'latest_downloaded', ...v })
const ids = (items: LibraryItem[]) => items.map((i) => i.pkg.id)

describe('URL view', () => {
  it('round-trips, leaving out defaults', () => {
    const v: LibraryView = { q: 'water', subject: 'Health', resourceType: 'video', packageType: 'with_visual', sort: 'latest_opened' }
    expect(readLibraryView(writeLibraryView(v, 'latest_downloaded'), 'latest_downloaded')).toEqual(v)
    expect(writeLibraryView(view(), 'latest_downloaded').toString()).toBe('')
  })

  it('ignores unknown values from a hand-edited link', () => {
    const v = readLibraryView(new URLSearchParams('subject=Magic&sort=random&resourceType=audio'), 'latest_opened')
    expect(v).toEqual({ q: '', subject: undefined, resourceType: undefined, packageType: undefined, sort: 'latest_opened' })
  })
})

describe('applyLibraryView', () => {
  const items = [
    item('The Water Cycle', { subject: 'Science', downloadedAt: '2026-11-12T00:00:00.000Z' }, '2026-11-13T00:00:00.000Z'),
    item('Écosystèmes et biodiversité', { subject: 'Science', packageType: 'with_visual', downloadedAt: '2026-11-11T00:00:00.000Z' }),
    item('Healthy Living Habits', { subject: 'Health', resourceType: 'video', downloadedAt: '2026-11-10T00:00:00.000Z' }, '2026-11-14T00:00:00.000Z'),
  ]

  it('searches title and subject, every word, ignoring case and accents', () => {
    expect(ids(applyLibraryView(items, view({ q: 'ECOSYSTEMES' })))).toEqual(['Écosystèmes et biodiversité'])
    expect(ids(applyLibraryView(items, view({ q: 'water science' })))).toEqual(['The Water Cycle'])
    expect(ids(applyLibraryView(items, view({ q: 'health' })))).toEqual(['Healthy Living Habits'])
    expect(applyLibraryView(items, view({ q: 'water health' }))).toEqual([])
  })

  it('applies every filter', () => {
    expect(ids(applyLibraryView(items, view({ subject: 'Science', packageType: 'with_visual' })))).toEqual([
      'Écosystèmes et biodiversité',
    ])
    expect(ids(applyLibraryView(items, view({ resourceType: 'video' })))).toEqual(['Healthy Living Habits'])
  })

  it('sorts by latest downloaded', () => {
    expect(ids(applyLibraryView(items, view()))).toEqual([
      'The Water Cycle',
      'Écosystèmes et biodiversité',
      'Healthy Living Habits',
    ])
  })

  it('sorts by latest opened, never-opened last', () => {
    expect(ids(applyLibraryView(items, view({ sort: 'latest_opened' })))).toEqual([
      'Healthy Living Habits',
      'The Water Cycle',
      'Écosystèmes et biodiversité',
    ])
  })
})
