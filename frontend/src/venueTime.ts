export function venueToday(timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

export function formatVenueTime(value: string, timezone?: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return value
  // A known reservation can arrive before the Venue list; never use browser time as a fallback.
  if (!timezone) return '매장 시간대 확인 후 표시'
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium', timeStyle: 'short', timeZone: timezone,
  })
  // 일부 ICU 버전은 ko-KR에서도 오전·오후를 AM·PM으로 반환한다.
  const display = formatter.formatToParts(date).map((part) => {
    if (part.type === 'dayPeriod') {
      if (part.value === 'AM') return '오전'
      if (part.value === 'PM') return '오후'
    }
    return part.value
  }).join('')
  return `${display} (${timezone === 'Asia/Seoul' ? 'KST · Asia/Seoul' : timezone})`
}

export function venueLocalTimestamp(value: string, timezone: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) throw new Error('시작 날짜와 시간을 선택해 주세요.')
  const [, year, month, day, hour, minute] = match.map(Number)
  const local = new Date(0)
  local.setUTCFullYear(year, month - 1, day)
  local.setUTCHours(hour, minute, 0, 0)
  if (year < 1 || local.getUTCFullYear() !== year || local.getUTCMonth() !== month - 1
    || local.getUTCDate() !== day || local.getUTCHours() !== hour || local.getUTCMinutes() !== minute) {
    throw new Error('올바른 시작 날짜와 시간을 선택해 주세요.')
  }
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  })
  const wallTime = (instant: number) => {
    const parts = formatter.formatToParts(new Date(instant))
    const part = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((x) => x.type === type)?.value)
    const wall = new Date(0)
    wall.setUTCFullYear(part('year'), part('month') - 1, part('day'))
    wall.setUTCHours(part('hour'), part('minute'), part('second'), 0)
    return wall.getTime()
  }
  // Collect the actual IANA offsets around this date, then round-trip every candidate.
  // This also covers half-hour DST and whole-day transitions without choosing an offset.
  const offsets = new Set<number>()
  const localTime = local.getTime()
  for (let hours = -48; hours <= 48; hours += 1) {
    const sample = localTime + hours * 3_600_000
    offsets.add(wallTime(sample) - sample)
  }
  const candidates = [...offsets].map((offset) => localTime - offset)
    .filter((instant) => wallTime(instant) === localTime)
  if (candidates.length === 0) {
    throw new Error('매장 시간대에서 존재하지 않는 시각입니다. 시간대 전환 구간을 피해 선택해 주세요.')
  }
  if (candidates.length !== 1) {
    throw new Error('매장 시간대에서 두 번 나타나는 중복 시각입니다. 다른 시각을 선택해 주세요.')
  }
  const offsetMinutes = (localTime - candidates[0]) / 60_000
  if (!Number.isInteger(offsetMinutes)) {
    throw new Error('분 단위로 표현할 수 없는 시간대의 시각입니다. 다른 날짜를 선택해 주세요.')
  }
  const absoluteOffset = Math.abs(offsetMinutes)
  const offsetHour = String(Math.floor(absoluteOffset / 60)).padStart(2, '0')
  const offsetMinute = String(absoluteOffset % 60).padStart(2, '0')
  // The Slot API validates the supplied local time and offset against the Venue zone.
  return `${value}:00${offsetMinutes < 0 ? '-' : '+'}${offsetHour}:${offsetMinute}`
}
