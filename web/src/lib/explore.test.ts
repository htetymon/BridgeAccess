import { describe, expect, it } from 'vitest'
import { activeFilterCount, noResultsMessage, readView, searchTerm, toListParams, writeView } from './explore'

describe('readView / writeView', () => {
  it('round-trips a full view', () => {
    const view = { q: 'solar power', subject: 'Science' as const, resourceType: 'video' as const, packageType: 'with_visual' as const }
    expect(readView(writeView(view))).toEqual(view)
    expect(writeView(view).toString()).toBe('q=solar+power&subject=Science&resourceType=video&packageType=with_visual')
  })

  it('leaves empty values out of the URL', () => {
    expect(writeView({ q: '  ' }).toString()).toBe('')
  })

  it('ignores values the server would reject', () => {
    expect(readView(new URLSearchParams('subject=Cooking&resourceType=audio&packageType=x'))).toEqual({
      q: '',
      subject: undefined,
      resourceType: undefined,
      packageType: undefined,
    })
  })

  it('caps an over-long search from a hand-edited link', () => {
    expect(readView(new URLSearchParams(`q=${'a'.repeat(150)}`)).q).toHaveLength(100)
  })
})

describe('searchTerm', () => {
  it('sends nothing under 2 characters', () => {
    expect(searchTerm('')).toBeUndefined()
    expect(searchTerm(' a ')).toBeUndefined()
    expect(searchTerm('ab')).toBe('ab')
  })

  it('tidies spaces', () => {
    expect(searchTerm('  water   cycle ')).toBe('water cycle')
  })
})

describe('toListParams', () => {
  it('maps the view to GET /packages filters', () => {
    expect(toListParams({ q: 'x', subject: 'Health' })).toEqual({
      q: undefined,
      subject: 'Health',
      resourceType: undefined,
      packageType: undefined,
    })
  })
})

describe('messages', () => {
  it('counts active filters, not the search', () => {
    expect(activeFilterCount({ q: 'water', subject: 'Science', packageType: 'text_only' })).toBe(2)
  })

  it('says what found nothing', () => {
    expect(noResultsMessage({ q: 'water', subject: 'Science' })).toBe('No packages match “water” with these filters.')
    expect(noResultsMessage({ q: 'water' })).toBe('No packages match “water”.')
    expect(noResultsMessage({ q: '', resourceType: 'video' })).toBe('No packages match these filters.')
    expect(noResultsMessage({ q: 'w' })).toMatch(/No packages yet/)
  })
})
