import { formatVenueTime as format } from '../venueTime'
import { useEffect, useRef, useState } from 'react'
import { localAuthSession } from '../auth'
import { Button, FormField, StatusBadge, formatStatus } from '../components'
import type { SlotInventory } from '../management/managementApi'
import { WaitlistApiError, waitlistApi, type ManagementPage, type Offer, type WaitlistApi } from './waitlistApi'

const REFRESH_MS = 15_000
export function ManagementWaitlistView({ venueId, date, timezone, slots, api = waitlistApi }: {
  venueId: string; date: string; timezone: string; slots: SlotInventory[]; api?: WaitlistApi
}) {
  const [slotId, setSlotId] = useState('')
  const [page, setPage] = useState<ManagementPage>()
  const [offer, setOffer] = useState<Offer>()
  const [loading, setLoading] = useState(false)
  const [offerLoading, setOfferLoading] = useState(false)
  const [error, setError] = useState<WaitlistApiError>()
  const [offerError, setOfferError] = useState<WaitlistApiError>()
  const [authEpoch, setAuthEpoch] = useState(0)
  const generation = useRef(0)
  const offerGeneration = useRef(0)
  const pollInFlight = useRef(false)
  useEffect(() => localAuthSession.onInvalidate(() => {
    generation.current += 1; offerGeneration.current += 1
    setPage(undefined); setOffer(undefined); setError(undefined); setOfferError(undefined)
    setAuthEpoch((current) => current + 1)
  }), [])

  async function load(cursor?: string) {
    if (!venueId || !date) return
    const current = ++generation.current
    setLoading(true); setError(undefined)
    try {
      const result = await api.managementEntries(venueId, date, slotId || undefined, cursor)
      if (current !== generation.current) return
      setPage((old) => cursor && old ? { ...result, items: [...old.items, ...result.items] } : result)
    } catch (cause) {
      if (current !== generation.current) return
      setError(cause instanceof WaitlistApiError ? cause : new WaitlistApiError(0, 'UNEXPECTED_RESPONSE'))
      if (!cursor) setPage(undefined)
    } finally { if (current === generation.current) setLoading(false) }
  }
  async function loadOffer(offerId: string) {
    const current = ++offerGeneration.current
    setOfferLoading(true); setOffer(undefined); setOfferError(undefined)
    try {
      const result = await api.managementOffer(venueId, offerId)
      if (current === offerGeneration.current) setOffer(result)
    } catch (cause) {
      if (current === offerGeneration.current) setOfferError(cause instanceof WaitlistApiError
        ? cause : new WaitlistApiError(0, 'UNEXPECTED_RESPONSE'))
    } finally { if (current === offerGeneration.current) setOfferLoading(false) }
  }
  useEffect(() => {
    generation.current += 1; offerGeneration.current += 1
    setPage(undefined); setOffer(undefined); setError(undefined); setOfferError(undefined)
    if (venueId && date) void load()
    return () => { generation.current += 1; offerGeneration.current += 1 }
  }, [venueId, date, slotId, api, authEpoch])
  useEffect(() => {
    if (!venueId || !date) return
    const refresh = () => {
      if (document.visibilityState === 'hidden' || pollInFlight.current) return
      if (!page?.items.some((item) => item.state === 'WAITING' || item.offerState === 'PENDING')
        && offer?.state !== 'PENDING') return
      pollInFlight.current = true
      void Promise.all([load(), offer ? loadOffer(offer.id) : Promise.resolve()]).finally(() => { pollInFlight.current = false })
    }
    const focus = () => {
      if (document.visibilityState !== 'visible' || pollInFlight.current) return
      pollInFlight.current = true
      void Promise.all([load(), offer ? loadOffer(offer.id) : Promise.resolve()]).finally(() => { pollInFlight.current = false })
    }
    const timer = window.setInterval(refresh, REFRESH_MS)
    window.addEventListener('focus', focus); document.addEventListener('visibilitychange', focus)
    return () => { window.clearInterval(timer); window.removeEventListener('focus', focus)
      document.removeEventListener('visibilitychange', focus) }
  }, [venueId, date, slotId, page, offer, api, authEpoch])

  return <section className="management-section" aria-labelledby="management-waitlist-title">
    <div className="section-heading"><div><h2 id="management-waitlist-title">대기 업무 조회</h2>
      <p>서버가 발견한 매장의 순서와 상태를 표시합니다. 먼저 등록한 대기 요청이 이 시간대에 맞지 않으면 뒤에 등록한 요청이 먼저 처리될 수 있습니다.</p></div></div>
    <div className="filter-row">
      <FormField id="waitlist-slot-filter" label="예약 시간대 필터" density="compact">
        <select value={slotId} onChange={(event) => { setSlotId(event.target.value); setOffer(undefined) }}>
          <option value="">전체 시간대</option>
          {slots.map((slot) => <option key={slot.id} value={slot.id}>{format(slot.startsAt, timezone)} · {slot.id}</option>)}
        </select>
      </FormField>
      <Button density="compact" variant="secondary" disabled={!date || loading} onClick={() => void load()}>대기 목록 새로고침</Button>
    </div>
    {!date ? <p className="notice notice--empty">매장 기준 날짜를 먼저 선택해 주세요.</p> : null}
    {loading ? <p className="notice notice--loading" role="status">대기 목록 조회 중…</p> : null}
    {error ? <p className="notice notice--error" role="status">대기 현재 상태 확인 실패: {error.code}
      {error.code === 'ACCESS_DENIED' || error.code === 'RESOURCE_NOT_FOUND' ? ' · 이 매장의 대기 조회 권한을 확인해 주세요.' : ''}</p> : null}
    {page && page.items.length === 0 ? <p className="notice notice--empty" role="status">이 날짜의 대기 등록이 없습니다.</p> : null}
    {page ? <><p>서버 관측 <time dateTime={page.observedAt}>{format(page.observedAt, page.venueTimezone)}</time></p>
      <ol className="reservation-list" aria-label="대기 등록 목록">
        {page.items.map((item) => <li key={item.id} className="reservation-row">
          <div className="reservation-scan">
            <time dateTime={item.joinedAt}>{format(item.joinedAt, item.venueTimezone)}</time>
            <StatusBadge status={item.state} /><span>{item.partySize}명</span>
            <span>대기 등록 {item.id}</span>
            {slotId && item.eligibleForSlot !== undefined ? <span>{item.eligibleForSlot ? '선택 예약 시간대에 적합' : '선택 예약 시간대에 부적합'}</span> : null}
            {item.offerState ? <span>예약 제안 {formatStatus(item.offerState)}</span> : null}
            {item.offerExpiresAt ? <span>기한 <time dateTime={item.offerExpiresAt}>{format(item.offerExpiresAt, item.venueTimezone)}</time></span> : null}
            {item.reservationId ? <span>예약 {item.reservationId}</span> : null}
          </div>
          {item.offerId ? <Button density="compact" variant="secondary" onClick={() => void loadOffer(item.offerId!)}>예약 제안 업무 상세 조회</Button> : null}
        </li>)}
      </ol>
      {page.nextCursor ? <Button variant="secondary" disabled={loading} onClick={() => void load(page.nextCursor!)}>다음 페이지</Button> : null}
    </> : null}
    {offerLoading ? <p role="status">예약 제안 업무 상세 조회 중…</p> : null}
    {offerError ? <p className="notice notice--error" role="status">예약 제안 현재 상태 확인 실패: {offerError.code}</p> : null}
    {offer ? <article className="reservation-card" aria-label="예약 제안 업무 상세">
      <div className="reservation-summary"><div><span className="field-label">예약 제안</span><StatusBadge status={offer.state} /></div>
        <div><span className="field-label">기한</span><time dateTime={offer.expiresAt}>{format(offer.expiresAt, offer.venueTimezone)}</time></div></div>
      <p>대기 등록 {offer.entryId} · 테이블 {offer.resourceId} · 예약 시간대 {offer.slotInventoryId}</p>
      <p>예약 {offer.reservation.id} · 현재 상태 {formatStatus(offer.reservation.state)}</p>
      <p>서버 관측 <time dateTime={offer.observedAt}>{format(offer.observedAt, offer.venueTimezone)}</time></p>
      <Button variant="secondary" onClick={() => void loadOffer(offer.id)}>예약 제안 다시 조회</Button>
    </article> : null}
  </section>
}
