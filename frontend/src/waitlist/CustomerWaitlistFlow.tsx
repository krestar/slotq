import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { Button, FormField, StatusBadge } from '../components'
import { customerReservationApi, type CustomerReservationApi, type Availability, type VenueSummary } from '../customer/customerReservationApi'
import { WaitlistApiError, WaitlistMutationUnknown, waitlistApi, type Entry, type EntryPage,
  type Offer, type RegistrationReceipt, type WaitlistApi, type WaitlistCode } from './waitlistApi'
import type { ActionCommand, RegistrationCommand, WaitlistSession } from './waitlistSession'

export interface WaitlistSelection {
  venueId?: string; date?: string; slotInventoryId?: string; partySize?: number
  entryId?: string; offerId?: string
}
export interface CustomerWaitlistFlowProps {
  api?: WaitlistApi
  reservationApi?: CustomerReservationApi
  session: WaitlistSession
  navigation?: ReactNode
  selection?: WaitlistSelection
  onSelectionChange?: (selection: WaitlistSelection) => void
  onNavigationLockChange?: (locked: boolean) => void
  onOpenReservation?: (venueId: string, reservationId: string) => void
}
type Load = 'idle' | 'loading' | 'success' | 'error'
const REFRESH_MS = 15_000
const copy: Record<WaitlistCode, string> = {
  VALIDATION_FAILED: '입력값을 확인해 주세요.', AUTHENTICATION_REQUIRED: '인증이 필요합니다. 다시 조회해 주세요.',
  ACCESS_DENIED: '현재 사용자에게 허용되지 않은 작업입니다.', RESOURCE_NOT_FOUND: '대상을 찾을 수 없습니다.',
  IDEMPOTENCY_KEY_REUSED: '등록 키가 다른 요청에 사용되었습니다. 원래 요청 상태를 확인해 주세요.',
  WAITLIST_DEMAND_NOT_ALLOWED: '현재 수요를 등록할 수 없습니다. 최신 시간대를 확인해 주세요.',
  WAITLIST_TRANSITION_NOT_ALLOWED: '현재 Entry 상태에서 처리할 수 없습니다.',
  OFFER_EXPIRED: '서버에서 Offer 기한이 지났습니다. 원래 Offer를 다시 조회합니다.',
  OFFER_TRANSITION_NOT_ALLOWED: '현재 Offer 상태에서 처리할 수 없습니다.',
  CAPACITY_UNAVAILABLE: '현재 capacity를 확보할 수 없습니다. 원래 Offer를 다시 조회합니다.',
  INTERNAL_ERROR: '서버 결과를 확인할 수 없습니다.', NETWORK_ERROR: '서버에 연결할 수 없습니다.',
  UNEXPECTED_RESPONSE: '서버 응답을 확인할 수 없습니다.',
}
const normalize = (error: unknown) => error instanceof WaitlistApiError
  ? error : new WaitlistApiError(0, 'UNEXPECTED_RESPONSE')
function format(value: string, timezone: string) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return value
  return `${new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short', timeZone: timezone }).format(date)} (${timezone})`
}
function Deadline({ value, timezone, onElapsed }: { value: string; timezone: string; onElapsed: () => void }) {
  const [now, setNow] = useState(Date.now)
  const elapsed = useRef(false)
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])
  const remaining = Math.max(0, Math.ceil((Date.parse(value) - now) / 1000))
  useEffect(() => {
    if (remaining === 0 && !elapsed.current) { elapsed.current = true; onElapsed() }
  }, [remaining, onElapsed])
  return <p>서버 기한 <time dateTime={value}>{format(value, timezone)}</time> · {remaining > 0
    ? `표시용 ${Math.floor(remaining / 60)}분 ${String(remaining % 60).padStart(2, '0')}초`
    : '표시 시간이 지났습니다. 서버 상태 확인 중'}</p>
}

export function CustomerWaitlistFlow({ api = waitlistApi, reservationApi = customerReservationApi,
  session, navigation, selection, onSelectionChange, onNavigationLockChange, onOpenReservation,
}: CustomerWaitlistFlowProps) {
  const sessionState = useSyncExternalStore(session.subscribe, session.getSnapshot)
  const [venues, setVenues] = useState<VenueSummary[]>([])
  const [venueState, setVenueState] = useState<Load>('loading')
  const [venueId, setVenueId] = useState(selection?.venueId ?? '')
  const [date, setDate] = useState(selection?.date ?? '')
  const [partySize, setPartySize] = useState(String(selection?.partySize ?? 2))
  const [slotInventoryId, setSlotInventoryId] = useState(selection?.slotInventoryId ?? '')
  const [availability, setAvailability] = useState<Availability>()
  const [availabilityState, setAvailabilityState] = useState<Load>('idle')
  const [page, setPage] = useState<EntryPage>()
  const [pageState, setPageState] = useState<Load>('idle')
  const [selectedEntryId, setSelectedEntryId] = useState(selection?.entryId ?? '')
  const [entry, setEntry] = useState<Entry>()
  const [offer, setOffer] = useState<Offer>()
  const [exactState, setExactState] = useState<Load>('idle')
  const [notice, setNotice] = useState('')
  const [error, setError] = useState<WaitlistApiError>()
  const [readError, setReadError] = useState<WaitlistApiError>()
  const [operation, setOperation] = useState(false)
  const pageGeneration = useRef(0)
  const exactGeneration = useRef(0)
  const availabilityGeneration = useRef(0)
  const pollInFlight = useRef(false)
  const operationInFlight = useRef(false)
  const mounted = useRef(true)
  const authEpoch = sessionState.epoch
  const attempt = sessionState.registration
  const action = sessionState.action
  const pending = attempt?.status === 'pending' || action?.status === 'pending' || operation
  const unresolved = attempt?.status === 'unknown' || attempt?.status === 'known'
    || action?.status === 'unknown' || action?.status === 'known'
  const locked = pending || unresolved

  function invalidateReads() {
    pageGeneration.current += 1
    exactGeneration.current += 1
    availabilityGeneration.current += 1
  }
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; invalidateReads() }
  }, [])
  useEffect(() => { invalidateReads(); setVenues([]); setPage(undefined); setEntry(undefined); setOffer(undefined) }, [authEpoch])
  useLayoutEffect(() => {
    onNavigationLockChange?.(pending)
    return () => onNavigationLockChange?.(false)
  }, [pending, onNavigationLockChange])
  useEffect(() => {
    const active = action?.command ?? attempt?.command
    if (active && venueId !== active.venueId) setVenueId(active.venueId)
    if (action && selectedEntryId !== action.command.targetId && action.command.kind === 'cancel') {
      setSelectedEntryId(action.command.targetId)
    }
  }, [action, attempt, selectedEntryId, venueId])
  useEffect(() => {
    onSelectionChange?.({ venueId: venueId || undefined, date: date || undefined,
      entryId: selectedEntryId || undefined,
      offerId: offer?.id ?? (selection?.entryId === selectedEntryId ? selection?.offerId : undefined) })
  }, [venueId, date, selectedEntryId, offer?.id])

  async function loadVenues() {
    const readEpoch = session.getSnapshot().epoch
    setVenueState('loading')
    try {
      const result = await reservationApi.listVenues()
      if (mounted.current && readEpoch === session.getSnapshot().epoch) {
        setVenues(result); setVenueState('success')
      }
    } catch (cause) {
      if (mounted.current && readEpoch === session.getSnapshot().epoch) {
        setVenueState('error'); setReadError(normalize(cause))
      }
    }
  }
  useEffect(() => { void loadVenues() }, [reservationApi, authEpoch])

  async function loadAvailability() {
    const size = Number(partySize)
    if (!venueId || !date || !Number.isInteger(size) || size < 1 || locked) {
      setError(new WaitlistApiError(400, 'VALIDATION_FAILED')); return
    }
    const generation = ++availabilityGeneration.current
    const readEpoch = session.getSnapshot().epoch
    setAvailabilityState('loading'); setAvailability(undefined); setSlotInventoryId('')
    try {
      const result = await reservationApi.getAvailability(venueId, date, size)
      if (generation !== availabilityGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
      setAvailability(result); setAvailabilityState('success'); setReadError(undefined)
    } catch (cause) {
      if (generation !== availabilityGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
      setAvailabilityState('error'); setReadError(normalize(cause))
    }
  }
  async function loadEntries(cursor?: string) {
    if (!venueId || !date) return
    const target = { venueId, date }
    const generation = ++pageGeneration.current
    const readEpoch = session.getSnapshot().epoch
    setPageState('loading')
    try {
      const result = await api.entries(target.venueId, target.date, cursor)
      if (generation !== pageGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
      setPage((old) => cursor && old ? { ...result, items: [...old.items, ...result.items] } : result)
      setPageState('success'); setReadError(undefined)
    } catch (cause) {
      if (generation !== pageGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
      setPageState('error'); setReadError(normalize(cause))
    }
  }
  useEffect(() => {
    pageGeneration.current += 1; setPage(undefined); setPageState('idle')
    if (venueId && date) void loadEntries()
    return () => { pageGeneration.current += 1 }
  }, [venueId, date, api, authEpoch])

  async function readExact(targetEntryId: string, targetOfferId?: string,
    targetVenue = venueId): Promise<Entry | undefined> {
    if (!targetVenue || !targetEntryId) return
    const generation = ++exactGeneration.current
    const readEpoch = session.getSnapshot().epoch
    setExactState('loading'); setEntry(undefined); setOffer(undefined); setReadError(undefined)
    try {
      const nextEntry = await api.entry(targetVenue, targetEntryId)
      if (generation !== exactGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
      if (targetOfferId && nextEntry.offerId !== targetOfferId) throw new Error('Offer/Entry mismatch')
      setEntry(nextEntry)
      if (nextEntry.offerId) {
        const nextOffer = await api.offer(targetVenue, nextEntry.offerId)
        if (generation !== exactGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
        if (nextOffer.entryId !== nextEntry.id) throw new Error('Offer/Entry mismatch')
        setOffer(nextOffer)
        if (action?.status === 'unknown' && action.command.targetId === nextOffer.id
          && nextOffer.state !== 'PENDING') {
          session.markAction(action.command, 'ready')
          setNotice('원래 대상의 현재 상태를 확인했습니다.')
        }
      } else if (action?.status === 'unknown' && action.command.kind === 'cancel'
        && action.command.targetId === nextEntry.id && nextEntry.state !== 'WAITING') {
        session.markAction(action.command, 'ready')
        setNotice('원래 대상의 현재 상태를 확인했습니다.')
      }
      setExactState('success')
      setNotice((current) => current === '원래 대상의 현재 상태를 확인할 수 없습니다.' ? '' : current)
      const currentRegistration = session.getSnapshot().registration
      if (currentRegistration?.status === 'known' && currentRegistration.receipt?.entryId === nextEntry.id) {
        session.markRegistration(currentRegistration.command, 'ready')
      }
      return nextEntry
    } catch (cause) {
      if (generation !== exactGeneration.current || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
      setExactState('error'); setReadError(normalize(cause)); setNotice('원래 대상의 현재 상태를 확인할 수 없습니다.')
    }
  }
  useEffect(() => {
    if (operation) return
    exactGeneration.current += 1; setEntry(undefined); setOffer(undefined)
    if (venueId && selectedEntryId) void readExact(selectedEntryId, selection?.offerId)
    return () => { exactGeneration.current += 1 }
  }, [venueId, selectedEntryId, api, authEpoch, operation])
  useEffect(() => {
    if (!venueId || selectedEntryId || !selection?.offerId) return
    const offerId = selection.offerId
    const readEpoch = session.getSnapshot().epoch
    void api.offer(venueId, offerId).then((result) => {
      if (readEpoch === session.getSnapshot().epoch && mounted.current) setSelectedEntryId(result.entryId)
    }).catch((cause) => {
      if (readEpoch === session.getSnapshot().epoch && mounted.current) setReadError(normalize(cause))
    })
  }, [venueId, selectedEntryId, selection?.offerId, api, authEpoch])

  async function sendRegistration(command: RegistrationCommand) {
    if (operationInFlight.current) return
    operationInFlight.current = true; setOperation(true); invalidateReads(); setError(undefined); setNotice('등록 결과를 기다리는 중입니다.')
    try {
      const result = await api.register(command.venueId, command.slotInventoryId, command.partySize, command.idempotencyKey)
      if (!session.registrationCurrent(command) || !mounted.current) return
      session.markRegistration(command, 'known', result)
      setSelectedEntryId(result.entryId)
      setNotice(result.originalStatus === 200 ? '기존 활성 Entry가 반환되었습니다. 새 순서를 받은 것은 아닙니다.' : '등록 결과를 확인했습니다.')
      void loadEntries()
    } catch (cause) {
      if (!session.registrationCurrent(command) || !mounted.current) return
      if (cause instanceof WaitlistMutationUnknown) {
        session.markRegistration(command, 'unknown')
        setNotice('처리 결과 확인 필요. 등록 키 조회 후 같은 요청만 명시적으로 재시도할 수 있습니다.')
      } else {
        const known = normalize(cause)
        setError(known)
        session.markRegistration(command, known.code === 'IDEMPOTENCY_KEY_REUSED' ? 'unknown' : 'rejected')
        setNotice('서버 응답을 확인했습니다. 현재 Entry 목록은 별도로 조회하세요.')
      }
    } finally { operationInFlight.current = false; if (mounted.current) setOperation(false) }
  }
  function register() {
    const size = Number(partySize)
    if (!venueId || !slotInventoryId || !Number.isInteger(size) || size < 1 || locked || attempt || action) {
      setError(new WaitlistApiError(400, 'VALIDATION_FAILED')); return
    }
    const command = session.beginRegistration({ venueId, slotInventoryId, partySize: size })
    if (command) void sendRegistration(command)
  }
  async function lookupRegistration() {
    if (!attempt || operationInFlight.current) return
    const command = attempt.command
    operationInFlight.current = true; setOperation(true); setError(undefined)
    try {
      const receipt: RegistrationReceipt = await api.registration(command.venueId, command.idempotencyKey)
      if (!session.registrationCurrent(command) || !mounted.current) return
      session.markRegistration(command, 'known', receipt)
      setSelectedEntryId(receipt.entryId)
      void loadEntries()
      setNotice('등록 키의 commit 결과를 확인했습니다.')
    } catch (cause) {
      if (!session.registrationCurrent(command) || !mounted.current) return
      const known = normalize(cause)
      setError(known)
      setNotice(known.code === 'RESOURCE_NOT_FOUND'
        ? '아직 commit 결과가 관측되지 않았습니다. 원 요청의 실패 증거는 아닙니다.'
        : '등록 키 결과를 아직 확인할 수 없습니다.')
    } finally { operationInFlight.current = false; if (mounted.current) setOperation(false) }
  }
  async function reconcileAction(command: ActionCommand) {
    let target = command.targetId
    if (command.kind !== 'cancel') {
      const readEpoch = session.getSnapshot().epoch
      setExactState('loading'); setEntry(undefined); setOffer(undefined); setReadError(undefined)
      try {
        const originalOffer = await api.offer(command.venueId, command.targetId)
        if (!session.actionCurrent(command) || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
        target = originalOffer.entryId
      } catch (cause) {
        if (!session.actionCurrent(command) || readEpoch !== session.getSnapshot().epoch || !mounted.current) return
        setExactState('error'); setReadError(normalize(cause))
        setNotice('원래 Offer의 현재 상태를 확인할 수 없습니다.')
        return
      }
    }
    const observed = await readExact(target, command.kind === 'cancel' ? undefined : command.targetId, command.venueId)
    if (observed) {
      const current = session.getSnapshot().action
      if (current?.command === command && current.status === 'known') session.markAction(command, 'ready')
      setNotice('원래 대상의 현재 상태를 확인했습니다.')
      void loadEntries()
    }
  }
  async function sendAction(command: ActionCommand) {
    if (operationInFlight.current) return
    operationInFlight.current = true; setOperation(true); invalidateReads(); setEntry(undefined); setOffer(undefined)
    setNotice('원래 대상의 처리 결과를 확인하는 중입니다.'); setError(undefined)
    try {
      if (command.kind === 'cancel') await api.cancel(command.venueId, command.targetId)
      else await api.offerAction(command.venueId, command.targetId, command.kind)
      if (!session.actionCurrent(command) || !mounted.current) return
      session.markAction(command, 'known')
      await reconcileAction(command)
    } catch (cause) {
      if (!session.actionCurrent(command) || !mounted.current) return
      if (cause instanceof WaitlistMutationUnknown) {
        session.markAction(command, 'unknown')
        setNotice('처리 결과 확인 필요. 원래 대상을 exact 조회해 주세요.')
      } else {
        setError(normalize(cause)); session.markAction(command, 'known')
        await reconcileAction(command)
      }
    } finally { operationInFlight.current = false; if (mounted.current) setOperation(false) }
  }
  function startAction(kind: ActionCommand['kind'], targetId: string) {
    if (!venueId || operationInFlight.current || attempt?.status === 'pending' || action) return
    const command = session.beginAction({ venueId, targetId, kind })
    if (command) void sendAction(command)
  }
  async function refresh() {
    if (!venueId || !date || pending) return
    if (selectedEntryId) await readExact(selectedEntryId)
    await loadEntries()
  }
  useEffect(() => {
    if (!venueId || !date || pending) return
    const poll = () => {
      if (document.visibilityState === 'hidden' || pollInFlight.current) return
      if (entry?.state !== 'WAITING' && offer?.state !== 'PENDING' && !page?.items.some((x) => x.state === 'WAITING' || x.state === 'OFFERED')) return
      pollInFlight.current = true
      void refresh().finally(() => { pollInFlight.current = false })
    }
    const focus = () => { if (document.visibilityState === 'visible' && !pollInFlight.current) {
      pollInFlight.current = true; void refresh().finally(() => { pollInFlight.current = false })
    } }
    const timer = window.setInterval(poll, REFRESH_MS)
    window.addEventListener('focus', focus)
    document.addEventListener('visibilitychange', focus)
    return () => { window.clearInterval(timer); window.removeEventListener('focus', focus)
      document.removeEventListener('visibilitychange', focus) }
  }, [venueId, date, entry?.state, offer?.state, page, pending, selectedEntryId, api])

  const actionTargetOffer = action && action.command.kind !== 'cancel' && offer?.id === action.command.targetId
  const actionTargetEntry = action && action.command.kind === 'cancel' && entry?.id === action.command.targetId
  return <>
    <a className="skip-link" href="#main-content">본문으로 바로가기</a>
    <header className="site-header"><span className="brand" aria-label="SlotQ">SlotQ</span>{navigation}</header>
    <main id="main-content" className="app-shell" tabIndex={-1}>
      <section className="intro" aria-labelledby="waitlist-title">
        <p className="eyebrow">Customer waitlist</p><h1 id="waitlist-title">대기와 Offer</h1>
        <p>이 시간대에 적합한 Table을 기다립니다. 등록은 Offer나 대기시간을 보장하지 않습니다.</p>
        <p>새로고침하면 미해결 key와 재시도 의도가 사라집니다. 재인증 후 자신의 목록과 알려진 ID를 서버에서 다시 확인하세요.</p>
      </section>
      {notice ? <p className="notice notice--state" role="status" aria-live="polite">{notice}</p> : null}
      {error ? <p className="notice notice--error" role="alert">{copy[error.code]} <code>{error.code}</code></p> : null}
      {readError ? <p className="notice notice--error" role="status">현재값 확인 실패: {copy[readError.code]} <code>{readError.code}</code></p> : null}
      <section className="management-section" aria-labelledby="waitlist-register-title">
        <h2 id="waitlist-register-title">시간대 선택과 등록</h2>
        {venueState === 'loading' ? <p role="status">Venue 조회 중…</p> : null}
        {venueState === 'error' ? <Button onClick={() => void loadVenues()}>Venue 다시 조회</Button> : null}
        <div className="filter-row">
          <FormField id="waitlist-venue" label="Venue">
            <select value={venueId} disabled={locked} onChange={(event) => {
              invalidateReads(); setVenueId(event.target.value); setPage(undefined); setEntry(undefined); setOffer(undefined)
              setSelectedEntryId(''); setAvailability(undefined); setSlotInventoryId('')
            }}><option value="">Venue 선택</option>{venues.map((item) =>
              <option key={item.id} value={item.id}>{item.name} · {item.timezone}</option>)}</select>
          </FormField>
          <FormField id="waitlist-date" label="Venue-local 날짜">
            <input type="date" value={date} disabled={locked} onChange={(event) => {
              invalidateReads(); setDate(event.target.value); setPage(undefined); setAvailability(undefined); setSlotInventoryId('')
            }} />
          </FormField>
          <FormField id="waitlist-party" label="인원" description="1명 이상의 정수">
            <input type="number" min="1" step="1" value={partySize} disabled={locked}
              onChange={(event) => { setPartySize(event.target.value); setAvailability(undefined); setSlotInventoryId('') }} />
          </FormField>
          <Button variant="secondary" disabled={!venueId || !date || locked || availabilityState === 'loading'} onClick={() => void loadAvailability()}>시간대 조회</Button>
        </div>
        {availabilityState === 'loading' ? <p role="status">시간대 조회 중…</p> : null}
        {availability && availability.items.length === 0 ? <p className="notice notice--empty">선택한 날짜의 Slot이 없습니다.</p> : null}
        {availability ? <ul className="availability-list" aria-label="대기 등록 시간대">
          {availability.items.map((item) => <li key={item.slotInventoryId} className="availability-card">
            <div><strong>{item.resourceName}</strong><p><time dateTime={item.startsAt}>{format(item.startsAt, availability.timezone)}</time></p>
              <p>현재 가능 {item.available} · 인원 수용 {item.seatingCapacity}</p></div>
            <Button variant={slotInventoryId === item.slotInventoryId ? 'primary' : 'secondary'}
              disabled={locked} onClick={() => setSlotInventoryId(item.slotInventoryId)}>
              {slotInventoryId === item.slotInventoryId ? '선택됨' : '이 시간대 선택'}</Button>
          </li>)}</ul> : null}
        <Button disabled={!slotInventoryId || locked || !!attempt || !!action} onClick={register}>이 시간대의 적합한 Table 대기</Button>
        {attempt ? <div className="notice notice--unknown" aria-label="등록 요청 상태">
          <strong>등록 요청: {attempt.status}</strong><p>Venue {attempt.command.venueId} · Slot {attempt.command.slotInventoryId} · {attempt.command.partySize}명</p>
          {attempt.receipt ? <p>확인된 Entry {attempt.receipt.entryId} · 최초 HTTP {attempt.receipt.originalStatus}</p> : null}
          {(attempt.status === 'unknown' || attempt.status === 'known') ? <>
            <Button variant="secondary" disabled={operation} onClick={() => void lookupRegistration()}>등록 키 결과 조회</Button>
            {attempt.status === 'known' && attempt.receipt ? <Button variant="secondary" disabled={operation}
              onClick={() => void readExact(attempt.receipt!.entryId)}>확인된 Entry 다시 조회</Button> : null}
            {attempt.status === 'unknown' ? <Button disabled={operation} onClick={() => {
              const command = session.retryRegistration(); if (command) void sendRegistration(command)
            }}>같은 key·body로 명시적 재시도</Button> : null}
          </> : null}
          {(attempt.status === 'ready' || attempt.status === 'rejected') ? <Button variant="secondary"
            onClick={() => session.clearRegistration()}>새 등록 intent 시작</Button> : null}
        </div> : null}
      </section>
      <section className="management-section" aria-labelledby="waitlist-entries-title">
        <h2 id="waitlist-entries-title">내 Entry</h2>
        <p>서버의 joinedAt·ID 순서를 그대로 표시합니다. 목록은 현재 상태의 공통 snapshot이 아닙니다.</p>
        <Button variant="secondary" disabled={!venueId || !date || pending} onClick={() => void refresh()}>현재 상태 다시 조회</Button>
        {pageState === 'loading' ? <p role="status">Entry 목록 조회 중…</p> : null}
        {pageState === 'error' && page ? <p className="notice notice--error" role="status">
          아래 목록은 이전 조회 결과입니다. 최신 상태를 확인할 수 없습니다.
        </p> : null}
        {pageState === 'success' && page?.items.length === 0 ? <p className="notice notice--empty" role="status">이 날짜의 Entry가 없습니다.</p> : null}
        {page ? <><ul className="reservation-list" aria-label="내 Entry 목록">
          {page.items.map((item) => <li key={item.id} className="reservation-row">
            <div className="reservation-scan"><time dateTime={item.startsAt}>{format(item.startsAt, item.venueTimezone)}</time>
              <StatusBadge status={item.state} /><span>{item.partySize}명</span><span>Entry {item.id}</span></div>
            <Button variant="secondary" disabled={pending} onClick={() => {
              if (selectedEntryId === item.id) void readExact(item.id)
              else setSelectedEntryId(item.id)
            }}>상세 조회</Button>
          </li>)}</ul>
          {page.nextCursor ? <Button variant="secondary" disabled={pageState === 'loading' || pending}
            onClick={() => void loadEntries(page.nextCursor!)}>다음 페이지</Button> : null}</> : null}
        {selectedEntryId ? <p>선택한 Entry {selectedEntryId}</p> : null}
        {exactState === 'loading' ? <p role="status">원래 Entry와 Offer 현재 상태 조회 중…</p> : null}
        {exactState === 'error' ? <Button variant="secondary" disabled={pending}
          onClick={() => void readExact(selectedEntryId)}>원래 Entry 다시 조회</Button> : null}
        {entry && exactState === 'success' ? <article className="reservation-card" aria-label="Entry 현재 상태">
          <div className="reservation-summary"><div><span className="field-label">Entry</span><StatusBadge status={entry.state} /></div>
            <div><span className="field-label">가입 시각</span><time dateTime={entry.joinedAt}>{format(entry.joinedAt, entry.venueTimezone)}</time></div></div>
          <p>{entry.partySize}명 · <time dateTime={entry.startsAt}>{format(entry.startsAt, entry.venueTimezone)}</time></p>
          <p>서버 관측 <time dateTime={entry.observedAt}>{format(entry.observedAt, entry.venueTimezone)}</time></p>
          {entry.allowedActions.includes('CANCEL') && !action ? <Button variant="destructive" disabled={pending || exactState !== 'success'}
            onClick={() => startAction('cancel', entry.id)}>대기 취소</Button> : null}
          {entry.offerId && !offer ? <p>Offer 현재 상태를 확인하는 중입니다.</p> : null}
        </article> : null}
        {offer && exactState === 'success' ? <article className="reservation-card" aria-label="Offer 현재 상태">
          <div className="reservation-summary"><div><span className="field-label">Offer</span><StatusBadge status={offer.state} /></div>
            <div><span className="field-label">Resource / Slot</span><strong>{offer.resourceId} / {offer.slotInventoryId}</strong></div></div>
          <p>Offer {offer.id} · Reservation {offer.reservation.id} · 현재 Reservation {offer.reservation.state}</p>
          {offer.state === 'PENDING' ? <Deadline value={offer.expiresAt} timezone={offer.venueTimezone}
            onElapsed={() => { if (!pending) void readExact(entry!.id) }} /> : null}
          <p>서버 관측 <time dateTime={offer.observedAt}>{format(offer.observedAt, offer.venueTimezone)}</time></p>
          {offer.allowedActions.includes('ACCEPT') && !action ? <Button disabled={pending}
            onClick={() => startAction('accept', offer.id)}>Offer 수락</Button> : null}
          {offer.allowedActions.includes('REJECT') && !action ? <Button variant="destructive" disabled={pending}
            onClick={() => startAction('reject', offer.id)}>Offer 거절</Button> : null}
          {offer.state === 'ACCEPTED' ? <Button variant="secondary" onClick={() => onOpenReservation?.(offer.venueId, offer.reservation.id)}>
            같은 Reservation 상세 확인</Button> : null}
        </article> : null}
        {action ? <div className="notice notice--unknown" aria-label="원래 작업 상태">
          <strong>{action.command.kind} · {action.status}</strong><p>원래 대상 {action.command.targetId}</p>
          {(action.status === 'unknown' || action.status === 'known') ? <Button variant="secondary" disabled={operation}
            onClick={() => void reconcileAction(action.command)}>원래 대상 exact 조회</Button> : null}
          {action.status === 'unknown' && ((actionTargetOffer && offer?.state === 'PENDING'
            && offer.allowedActions.includes(action.command.kind === 'accept' ? 'ACCEPT' : 'REJECT'))
            || (actionTargetEntry && entry?.state === 'WAITING' && entry.allowedActions.includes('CANCEL'))) ?
            <Button disabled={operation} onClick={() => { const command = session.retryAction(); if (command) void sendAction(command) }}>
              같은 대상·작업 명시적 재시도</Button> : null}
          {action.status === 'ready' ? <Button variant="secondary" onClick={() => session.clearAction()}>확인된 작업 닫기</Button> : null}
        </div> : null}
      </section>
    </main><footer className="site-footer">SlotQ Product</footer>
  </>
}
