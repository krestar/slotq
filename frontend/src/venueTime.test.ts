import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatVenueTime, venueLocalTimestamp, venueToday } from './venueTime'

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('매장 시간대 표시와 입력', () => {
  it.each([
    ['Asia/Seoul', '2026-10-11T18:00', '2026-10-11T18:00:00+09:00'],
    ['UTC', '2026-10-11T18:00', '2026-10-11T18:00:00+00:00'],
    ['America/New_York', '2026-07-11T18:00', '2026-07-11T18:00:00-04:00'],
    ['America/New_York', '2026-01-11T18:00', '2026-01-11T18:00:00-05:00'],
    ['Asia/Kathmandu', '2026-10-11T18:00', '2026-10-11T18:00:00+05:45'],
  ])('%s 현지 입력을 현지 시각과 offset로 변환한다', (timezone, local, instant) => {
    expect(venueLocalTimestamp(local, timezone)).toBe(instant)
  })

  it.each([
    ['America/New_York', '2026-03-08T02:30', '존재하지 않는'],
    ['America/New_York', '2026-11-01T01:30', '중복 시각'],
    ['Australia/Lord_Howe', '2026-10-04T02:15', '존재하지 않는'],
    ['Australia/Lord_Howe', '2026-04-05T01:45', '중복 시각'],
    ['Pacific/Apia', '2011-12-30T12:00', '존재하지 않는'],
  ])('%s 시간대 전환의 gap/overlap을 보정하지 않는다', (timezone, local, message) => {
    expect(() => venueLocalTimestamp(local, timezone)).toThrow(message)
  })

  it.each(['', '2026-02-30T18:00', '2026-10-11T25:00', '2026-10-11T18:60',
    '2026-10-11T18:00+09:00'])('잘못된 현지 입력 %s를 거절한다', (local) => {
    expect(() => venueLocalTimestamp(local, 'Asia/Seoul')).toThrow()
  })

  it('매장 기준 날짜 경계와 한국어 시각을 사용한다', () => {
    vi.useFakeTimers().setSystemTime(new Date('2026-10-11T00:30:00Z'))
    expect(venueToday('Asia/Seoul')).toBe('2026-10-11')
    expect(venueToday('UTC')).toBe('2026-10-11')
    expect(venueToday('America/New_York')).toBe('2026-10-10')
    expect(formatVenueTime('2026-10-11T00:30:00Z', 'Asia/Seoul')).toContain('오전 9:30 (KST · Asia/Seoul)')
    expect(formatVenueTime('2026-10-11T00:30:00Z', 'UTC')).toContain('오전 12:30 (UTC)')
    expect(formatVenueTime('2026-10-11T00:30:00Z', 'America/New_York')).toContain('오후 8:30 (America/New_York)')
    expect(formatVenueTime('2026-10-11T00:30:00Z')).toBe('매장 시간대 확인 후 표시')
  })

  it('Intl이 AM·PM을 반환하는 환경에서도 오전·오후로 표시한다', () => {
    const formatToParts = Intl.DateTimeFormat.prototype.formatToParts
    vi.spyOn(Intl.DateTimeFormat.prototype, 'formatToParts').mockImplementation(function (this: Intl.DateTimeFormat, date) {
      return formatToParts.call(this, date).map((part) => {
        if (part.type !== 'dayPeriod') return part
        return { ...part, value: part.value === '오전' ? 'AM' : part.value === '오후' ? 'PM' : part.value }
      })
    })
    expect(formatVenueTime('2026-10-11T00:30:00Z', 'Asia/Seoul')).toContain('오전 9:30 (KST · Asia/Seoul)')
    expect(formatVenueTime('2026-10-11T00:30:00Z', 'America/New_York')).toContain('오후 8:30 (America/New_York)')
  })
})
