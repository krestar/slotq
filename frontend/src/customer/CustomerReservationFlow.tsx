import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Button, FormField, StatusBadge } from '../components'
import {
  CustomerApiError,
  MutationResultUnknownError,
  customerReservationApi,
  type Availability,
  type AvailabilityItem,
  type ClientErrorCode,
  type CustomerReservationApi,
  type Reservation,
  type VenueSummary,
} from './customerReservationApi'

export interface CustomerReservationFlowProps {
  api?: CustomerReservationApi
  navigation?: ReactNode
}

interface SearchInput {
  venueId: string
  date: string
  partySize: number
}

type LoadState = 'idle' | 'loading' | 'success' | 'error'
type ReservationAction = 'confirm' | 'cancel'

const errorCopy: Record<ClientErrorCode, { title: string; message: string }> = {
  VALIDATION_FAILED: {
    title: '입력값을 확인해 주세요',
    message: '서버가 요청 값을 검증하지 못했습니다. 표시된 값을 확인해 주세요.',
  },
  AUTHENTICATION_REQUIRED: {
    title: '인증이 필요합니다',
    message: '현재 인증 세션을 확인한 뒤 다시 시도해 주세요.',
  },
  ACCESS_DENIED: {
    title: '접근할 수 없습니다',
    message: '현재 사용자에게 이 작업을 수행할 권한이 없습니다.',
  },
  RESOURCE_NOT_FOUND: {
    title: '대상을 찾을 수 없습니다',
    message: '요청한 Venue, 시간 또는 예약을 찾을 수 없습니다.',
  },
  CAPACITY_UNAVAILABLE: {
    title: '선택한 시간이 마감되었습니다',
    message: '최신 예약 가능 시간을 다시 조회해 주세요.',
  },
  PARTY_SIZE_NOT_SUPPORTED: {
    title: '인원을 수용할 수 없습니다',
    message: '선택한 Resource가 요청 인원을 수용하지 못합니다.',
  },
  BOOKING_NOT_ALLOWED: {
    title: '현재 예약할 수 없습니다',
    message: 'Venue 또는 선택한 시간의 최신 예약 가능 상태를 확인해 주세요.',
  },
  HOLD_EXPIRED: {
    title: '예약 보류가 만료되었습니다',
    message: '서버에서 최신 예약 상태를 다시 확인해 주세요.',
  },
  CANCELLATION_WINDOW_CLOSED: {
    title: '취소 가능 시간이 지났습니다',
    message: '서버의 취소 마감 규칙에 따라 예약을 취소할 수 없습니다.',
  },
  RESERVATION_TRANSITION_NOT_ALLOWED: {
    title: '현재 상태에서 처리할 수 없습니다',
    message: '서버에서 최신 예약 상태를 다시 확인해 주세요.',
  },
  INTERNAL_ERROR: {
    title: '서버 오류가 발생했습니다',
    message: '잠시 후 최신 정보를 다시 조회해 주세요.',
  },
  NETWORK_ERROR: {
    title: '서버에 연결할 수 없습니다',
    message: '연결 상태를 확인하고 명시적으로 다시 조회해 주세요.',
  },
  UNEXPECTED_RESPONSE: {
    title: '응답을 확인할 수 없습니다',
    message: '서버 응답 형식을 확인하지 못했습니다. 다시 조회해 주세요.',
  },
}

function normalizeError(error: unknown): CustomerApiError {
  return error instanceof CustomerApiError
    ? error
    : new CustomerApiError(0, 'UNEXPECTED_RESPONSE')
}

function ErrorNotice({ error }: { error: CustomerApiError }) {
  const copy = errorCopy[error.code]
  return (
    <div className={`notice notice--error notice--${error.code.toLowerCase()}`} role="alert">
      <strong>{copy.title}</strong>
      <span>{copy.message}</span>
      <code>{error.code}</code>
    </div>
  )
}

function LoadingNotice({ children }: { children: string }) {
  return <p className="notice notice--loading" role="status" aria-live="polite">{children}</p>
}

function formatTimestamp(value: string, timezone?: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: timezone,
  }).format(date)
}

function HoldCountdown({ expiresAt }: { expiresAt: string }) {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  const remainingSeconds = Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now) / 1000))
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = remainingSeconds % 60

  return (
    <p className="countdown">
      {remainingSeconds > 0
        ? `표시용 남은 시간 ${minutes}분 ${String(seconds).padStart(2, '0')}초`
        : '표시 시간이 지났습니다. 서버에서 최신 상태를 다시 확인해 주세요.'}
    </p>
  )
}

function AvailabilityCard({
  item,
  timezone,
  selected,
  disabled,
  onSelect,
}: {
  item: AvailabilityItem
  timezone: string
  selected: boolean
  disabled: boolean
  onSelect: () => void
}) {
  return (
    <li className={`availability-card${selected ? ' availability-card--selected' : ''}`}>
      <div>
        <strong>{item.resourceName}</strong>
        <p>
          <time dateTime={item.startsAt}>{formatTimestamp(item.startsAt, timezone)}</time>
          {' – '}
          <time dateTime={item.endsAt}>{formatTimestamp(item.endsAt, timezone)}</time>
        </p>
        <p>수용 인원 {item.seatingCapacity}명 · 현재 가능 {item.available}</p>
      </div>
      {item.available > 0 ? (
        <Button variant={selected ? 'primary' : 'secondary'} onClick={onSelect} disabled={disabled}>
          {selected ? '선택됨' : '이 시간 선택'}
        </Button>
      ) : <span className="availability-unavailable">예약 불가</span>}
    </li>
  )
}

export function CustomerReservationFlow({
  api = customerReservationApi,
  navigation,
}: CustomerReservationFlowProps) {
  const [venues, setVenues] = useState<VenueSummary[]>([])
  const [venueState, setVenueState] = useState<LoadState>('loading')
  const [venueError, setVenueError] = useState<CustomerApiError>()
  const [venueId, setVenueId] = useState('')
  const [date, setDate] = useState('')
  const [partySize, setPartySize] = useState('2')
  const [formErrors, setFormErrors] = useState<Record<string, string>>({})
  const [availability, setAvailability] = useState<Availability>()
  const [availabilityState, setAvailabilityState] = useState<LoadState>('idle')
  const [availabilityError, setAvailabilityError] = useState<CustomerApiError>()
  const [lastSearch, setLastSearch] = useState<SearchInput>()
  const [selectedSlotId, setSelectedSlotId] = useState('')
  const [reservation, setReservation] = useState<Reservation>()
  const [holdState, setHoldState] = useState<LoadState>('idle')
  const [actionState, setActionState] = useState<LoadState>('idle')
  const [actionError, setActionError] = useState<CustomerApiError>()
  const [reservationReadState, setReservationReadState] = useState<LoadState>('idle')
  const [reservationReadError, setReservationReadError] = useState<CustomerApiError>()
  const [resultUnknown, setResultUnknown] = useState<'hold' | 'transition'>()
  const availabilityRequestGeneration = useRef(0)
  const reservationReadGeneration = useRef(0)
  const reservationReadInFlight = useRef(false)
  const holdInFlight = useRef(false)
  const actionInFlight = useRef(false)

  async function loadVenues() {
    setVenueState('loading')
    setVenueError(undefined)
    try {
      setVenues(await api.listVenues())
      setVenueState('success')
    } catch (error) {
      setVenueError(normalizeError(error))
      setVenueState('error')
    }
  }

  useEffect(() => {
    void loadVenues()
  }, [api])

  function resetDownstream() {
    availabilityRequestGeneration.current += 1
    reservationReadGeneration.current += 1
    reservationReadInFlight.current = false
    setAvailability(undefined)
    setAvailabilityState('idle')
    setAvailabilityError(undefined)
    setLastSearch(undefined)
    setSelectedSlotId('')
    setReservation(undefined)
    setHoldState('idle')
    setActionState('idle')
    setActionError(undefined)
    setReservationReadState('idle')
    setReservationReadError(undefined)
    setResultUnknown(undefined)
  }

  async function loadAvailability(search: SearchInput) {
    const requestGeneration = availabilityRequestGeneration.current + 1
    availabilityRequestGeneration.current = requestGeneration
    setAvailabilityState('loading')
    setAvailabilityError(undefined)
    setSelectedSlotId('')
    setResultUnknown(undefined)
    try {
      const result = await api.getAvailability(search.venueId, search.date, search.partySize)
      if (requestGeneration !== availabilityRequestGeneration.current) return
      setAvailability(result)
      setAvailabilityState('success')
      setLastSearch(search)
    } catch (error) {
      if (requestGeneration !== availabilityRequestGeneration.current) return
      const normalized = normalizeError(error)
      setAvailability(undefined)
      setAvailabilityError(normalized)
      setAvailabilityState('error')
      setLastSearch(search)
      if (normalized.code === 'VALIDATION_FAILED') {
        setFormErrors((current) => ({
          ...current,
          ...(normalized.fieldErrors.date ? { date: normalized.fieldErrors.date } : {}),
          ...(normalized.fieldErrors.partySize
            ? { partySize: normalized.fieldErrors.partySize }
            : {}),
        }))
      }
    }
  }

  function submitAvailability(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (holdInFlight.current || actionInFlight.current) return
    const nextErrors: Record<string, string> = {}
    const parsedPartySize = Number(partySize)
    if (!venueId) nextErrors.venue = 'Venue를 선택해 주세요.'
    if (!date) nextErrors.date = '날짜를 선택해 주세요.'
    if (!Number.isInteger(parsedPartySize) || parsedPartySize < 1) {
      nextErrors.partySize = '인원은 1명 이상의 정수로 입력해 주세요.'
    }
    setFormErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return
    resetDownstream()
    void loadAvailability({ venueId, date, partySize: parsedPartySize })
  }

  async function createHold() {
    if (
      !lastSearch
      || !selectedSlotId
      || holdInFlight.current
      || actionInFlight.current
      || reservationReadInFlight.current
    ) return
    const command = {
      venueId: lastSearch.venueId,
      slotInventoryId: selectedSlotId,
      partySize: lastSearch.partySize,
    }
    holdInFlight.current = true
    setHoldState('loading')
    setActionError(undefined)
    setResultUnknown(undefined)
    try {
      setReservation(await api.createHold(
        command.venueId,
        command.slotInventoryId,
        command.partySize,
      ))
      setHoldState('success')
    } catch (error) {
      setHoldState('error')
      if (error instanceof MutationResultUnknownError) setResultUnknown('hold')
      else setActionError(normalizeError(error))
    } finally {
      holdInFlight.current = false
    }
  }

  async function transition(action: ReservationAction) {
    if (
      !reservation
      || holdInFlight.current
      || actionInFlight.current
      || reservationReadInFlight.current
    ) return
    const command = { venueId: reservation.venueId, reservationId: reservation.id }
    actionInFlight.current = true
    setActionState('loading')
    setActionError(undefined)
    setReservationReadError(undefined)
    setResultUnknown(undefined)
    try {
      const result = action === 'confirm'
        ? await api.confirmReservation(command.venueId, command.reservationId)
        : await api.cancelReservation(command.venueId, command.reservationId)
      setReservation(result)
      setActionState('success')
    } catch (error) {
      setActionState('error')
      if (error instanceof MutationResultUnknownError) setResultUnknown('transition')
      else setActionError(normalizeError(error))
    } finally {
      actionInFlight.current = false
    }
  }

  async function refreshReservation() {
    if (
      !reservation
      || holdInFlight.current
      || actionInFlight.current
      || reservationReadInFlight.current
    ) return
    const command = { venueId: reservation.venueId, reservationId: reservation.id }
    const requestGeneration = reservationReadGeneration.current + 1
    reservationReadGeneration.current = requestGeneration
    reservationReadInFlight.current = true
    setReservationReadState('loading')
    setReservationReadError(undefined)
    try {
      const result = await api.getReservation(command.venueId, command.reservationId)
      if (requestGeneration !== reservationReadGeneration.current) return
      setReservation(result)
      setReservationReadState('success')
      setResultUnknown(undefined)
      setActionError(undefined)
    } catch (error) {
      if (requestGeneration !== reservationReadGeneration.current) return
      setReservationReadState('error')
      setReservationReadError(normalizeError(error))
    } finally {
      if (requestGeneration === reservationReadGeneration.current) {
        reservationReadInFlight.current = false
      }
    }
  }

  const selectedVenue = venues.find((venue) => venue.id === venueId)
  const selectedItem = availability?.items.find((item) => item.slotInventoryId === selectedSlotId)
  const canConfirm = reservation?.state === 'HELD'
  const canCancel = reservation?.state === 'HELD' || reservation?.state === 'CONFIRMED'
  const mutationInFlight = holdState === 'loading' || actionState === 'loading'

  return (
    <>
      <a className="skip-link" href="#main-content">본문으로 바로가기</a>
      <header className="site-header">
        <span className="brand" aria-label="SlotQ">SlotQ</span>
        {navigation}
      </header>

      <main id="main-content" className="app-shell" tabIndex={-1}>
        <section className="intro" aria-labelledby="page-title">
          <p className="eyebrow">Customer reservation</p>
          <h1 id="page-title">예약하기</h1>
          <p className="intro-copy">
            Venue와 날짜를 선택하고, 서버가 알려 주는 최신 예약 상태를 단계별로 확인하세요.
          </p>
        </section>

        <ol className="guided-flow">
          <li className="flow-step">
            <section aria-labelledby="availability-title">
              <div className="step-heading">
                <span aria-hidden="true">1</span>
                <div><h2 id="availability-title">예약 가능한 시간 찾기</h2><p>Venue, 날짜와 인원을 선택합니다.</p></div>
              </div>

              {venueState === 'loading' ? <LoadingNotice>Venue를 불러오는 중입니다.</LoadingNotice> : null}
              {venueError ? (
                <div className="stack">
                  <ErrorNotice error={venueError} />
                  <Button variant="secondary" onClick={() => void loadVenues()}>Venue 다시 조회</Button>
                </div>
              ) : null}
              {venueState === 'success' && venues.length === 0 ? (
                <p className="notice notice--empty" role="status">현재 선택할 수 있는 active Venue가 없습니다.</p>
              ) : null}

              <form className="search-form" onSubmit={submitAvailability} noValidate>
                <FormField id="venue" label="Venue" error={formErrors.venue}>
                  <select
                    value={venueId}
                    onChange={(event) => {
                      if (holdInFlight.current || actionInFlight.current) return
                      setVenueId(event.target.value)
                      setFormErrors((current) => ({ ...current, venue: '' }))
                      resetDownstream()
                    }}
                    disabled={venueState !== 'success' || venues.length === 0 || mutationInFlight}
                  >
                    <option value="">Venue 선택</option>
                    {venues.map((venue) => (
                      <option key={venue.id} value={venue.id}>{venue.name} · {venue.timezone}</option>
                    ))}
                  </select>
                </FormField>
                <FormField id="date" label="날짜" error={formErrors.date}>
                  <input
                    type="date"
                    value={date}
                    onChange={(event) => {
                      if (holdInFlight.current || actionInFlight.current) return
                      setDate(event.target.value)
                      setFormErrors((current) => ({ ...current, date: '' }))
                      resetDownstream()
                    }}
                    disabled={mutationInFlight}
                  />
                </FormField>
                <FormField id="party-size" label="인원" error={formErrors.partySize}>
                  <input
                    type="number"
                    min="1"
                    step="1"
                    inputMode="numeric"
                    value={partySize}
                    onChange={(event) => {
                      if (holdInFlight.current || actionInFlight.current) return
                      setPartySize(event.target.value)
                      setFormErrors((current) => ({ ...current, partySize: '' }))
                      resetDownstream()
                    }}
                    disabled={mutationInFlight}
                  />
                </FormField>
                <Button type="submit" disabled={availabilityState === 'loading' || mutationInFlight}>
                  {availabilityState === 'loading' ? '조회 중…' : '예약 가능 시간 조회'}
                </Button>
              </form>

              {availabilityState === 'loading' ? <LoadingNotice>예약 가능한 시간을 조회하는 중입니다.</LoadingNotice> : null}
              {availabilityError ? (
                <div className="stack">
                  <ErrorNotice error={availabilityError} />
                  {lastSearch ? (
                    <Button
                      variant="secondary"
                      onClick={() => void loadAvailability(lastSearch)}
                      disabled={mutationInFlight}
                    >
                      같은 조건으로 다시 조회
                    </Button>
                  ) : null}
                </div>
              ) : null}
              {availabilityState === 'success' && availability?.items.length === 0 ? (
                <p className="notice notice--empty" role="status">선택한 조건에 예약 가능한 시간이 없습니다.</p>
              ) : null}
            </section>
          </li>

          <li className="flow-step">
            <section aria-labelledby="slot-title">
              <div className="step-heading">
                <span aria-hidden="true">2</span>
                <div><h2 id="slot-title">시간 선택 및 보류</h2><p>조회 결과에서 한 시간을 선택한 뒤 HOLD를 생성합니다.</p></div>
              </div>

              {availability && availability.items.length > 0 ? (
                <ul className="availability-list" aria-label="예약 가능한 시간">
                  {availability.items.map((item) => (
                    <AvailabilityCard
                      key={item.slotInventoryId}
                      item={item}
                      timezone={availability.timezone}
                      selected={selectedSlotId === item.slotInventoryId}
                      disabled={mutationInFlight}
                      onSelect={() => {
                        if (holdInFlight.current || actionInFlight.current) return
                        setSelectedSlotId(item.slotInventoryId)
                      }}
                    />
                  ))}
                </ul>
              ) : <p className="step-placeholder">먼저 예약 가능한 시간을 조회해 주세요.</p>}

              {selectedItem && lastSearch ? (
                <div className="hold-action">
                  <p><strong>{selectedItem.resourceName}</strong> · {lastSearch.partySize}명</p>
                  <Button
                    onClick={() => void createHold()}
                    disabled={mutationInFlight || reservationReadState === 'loading'}
                  >
                    {holdState === 'loading' ? 'HOLD 생성 중…' : '이 시간 HOLD'}
                  </Button>
                </div>
              ) : null}
              {holdState === 'loading' ? <LoadingNotice>서버에서 예약 보류를 생성하는 중입니다.</LoadingNotice> : null}
              {actionError && !reservation ? <ErrorNotice error={actionError} /> : null}
              {resultUnknown === 'hold' ? (
                <div className="notice notice--unknown" role="alert">
                  <strong>HOLD 생성 결과를 확인할 수 없습니다</strong>
                  <span>Reservation ID를 받지 못했으므로 같은 요청을 자동 재전송하지 않습니다.</span>
                  {lastSearch ? (
                    <Button
                      variant="secondary"
                      onClick={() => void loadAvailability(lastSearch)}
                      disabled={mutationInFlight}
                    >
                      Availability 새로고침
                    </Button>
                  ) : null}
                </div>
              ) : null}
            </section>
          </li>

          <li className="flow-step">
            <section aria-labelledby="reservation-title">
              <div className="step-heading">
                <span aria-hidden="true">3</span>
                <div><h2 id="reservation-title">예약 상태 확인</h2><p>서버가 반환한 effective 상태와 deadline을 확인하고 다음 작업을 선택합니다.</p></div>
              </div>

              {reservation ? (
                <article className="reservation-card" aria-label="현재 예약">
                  <div className="reservation-summary">
                    <div><span className="field-label">현재 상태</span><StatusBadge status={reservation.state} /></div>
                    <div>
                      <span className="field-label">예약 시간</span>
                      <strong>{formatTimestamp(reservation.startsAt, selectedVenue?.timezone)}</strong>
                    </div>
                  </div>
                  <dl className="reservation-details">
                    <div><dt>Reservation ID</dt><dd>{reservation.id}</dd></div>
                    <div><dt>인원</dt><dd>{reservation.partySize}명</dd></div>
                    <div>
                      <dt>서버 HOLD deadline</dt>
                      <dd><time dateTime={reservation.expiresAt}>{formatTimestamp(reservation.expiresAt, selectedVenue?.timezone)}</time></dd>
                    </div>
                  </dl>
                  {reservation.state === 'HELD' ? <HoldCountdown expiresAt={reservation.expiresAt} /> : null}

                  {(canConfirm || canCancel) ? (
                    <div className="reservation-actions" aria-label="예약 작업">
                      {canConfirm ? (
                        <Button
                          onClick={() => void transition('confirm')}
                          disabled={mutationInFlight || reservationReadState === 'loading'}
                        >예약 확정</Button>
                      ) : null}
                      {canCancel ? (
                        <Button
                          variant="destructive"
                          onClick={() => void transition('cancel')}
                          disabled={mutationInFlight || reservationReadState === 'loading'}
                        >예약 취소</Button>
                      ) : null}
                    </div>
                  ) : (
                    <p className="notice notice--state" role="status">
                      {reservation.state === 'EXPIRED'
                        ? '서버가 이 예약을 EXPIRED 상태로 반환했습니다.'
                        : `서버가 반환한 ${reservation.state} 상태에는 Customer 작업이 없습니다.`}
                    </p>
                  )}
                  {actionState === 'loading' ? <LoadingNotice>예약 작업 결과를 기다리는 중입니다.</LoadingNotice> : null}
                  {actionError ? <ErrorNotice error={actionError} /> : null}
                  {resultUnknown === 'transition' ? (
                    <div className="notice notice--unknown" role="alert">
                      <strong>예약 작업 결과를 확인할 수 없습니다</strong>
                      <span>성공이나 실패를 추정하지 않습니다. 최신 Reservation을 조회해 확인해 주세요.</span>
                    </div>
                  ) : null}
                  {reservationReadState === 'loading' ? <LoadingNotice>최신 예약 상태를 조회하는 중입니다.</LoadingNotice> : null}
                  {reservationReadError ? <ErrorNotice error={reservationReadError} /> : null}
                  <Button
                    variant="secondary"
                    onClick={() => void refreshReservation()}
                    disabled={reservationReadState === 'loading' || mutationInFlight}
                  >
                    최신 Reservation 상태 조회
                  </Button>
                </article>
              ) : <p className="step-placeholder">HOLD를 생성하면 서버가 반환한 예약 상태가 여기에 표시됩니다.</p>}
            </section>
          </li>
        </ol>
      </main>

      <footer className="site-footer"><small>Server state is the source of truth.</small></footer>
    </>
  )
}
