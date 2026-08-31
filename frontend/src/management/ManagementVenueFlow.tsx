import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from 'react'
import { Button, FormField, StatusBadge } from '../components'
import {
  ManagementApiError,
  ManagementMutationResultUnknownError,
  managementApi,
  type ManagementApi,
  type ManagementClientErrorCode,
  type ManagementReservation,
  type ManagementResource,
  type ManagementVenue,
  type Policy,
  type ReservationAction,
  type ReservationState,
  type SlotInventory,
} from './managementApi'

export interface ManagementVenueFlowProps {
  api?: ManagementApi
  navigation?: ReactNode
}

type LoadState = 'idle' | 'loading' | 'success' | 'error'
type ConfigurationMutation = 'venue' | 'policy' | 'resource-create' | 'resource-patch' | 'slot-create'
type UnknownMutation = ConfigurationMutation | 'reservation-command'

const reservationStates: ReservationState[] = [
  'HELD', 'CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED',
]

const errorCopy: Record<ManagementClientErrorCode, string> = {
  VALIDATION_FAILED: '입력값을 확인해 주세요.',
  AUTHENTICATION_REQUIRED: '인증 세션을 확인한 뒤 다시 시도해 주세요.',
  ACCESS_DENIED: '현재 사용자에게 이 작업을 수행할 권한이 없습니다.',
  RESOURCE_NOT_FOUND: '대상을 찾을 수 없습니다. 다른 범위의 데이터는 표시하지 않습니다.',
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 최신 정보를 다시 조회해 주세요.',
  SLOT_INVENTORY_CONFLICT: '기존 Slot과 겹칩니다. Slot 목록을 다시 확인해 주세요.',
  SLOT_INVENTORY_NOT_ALLOWED: '선택한 Resource와 시간에는 Slot을 만들 수 없습니다.',
  HOLD_EXPIRED: 'HOLD가 만료되었습니다. 최신 Reservation 목록을 확인해 주세요.',
  CANCELLATION_WINDOW_CLOSED: '서버의 취소 가능 시간이 지났습니다.',
  RESERVATION_TRANSITION_NOT_ALLOWED: '현재 서버 상태에서는 이 작업을 수행할 수 없습니다.',
  NETWORK_ERROR: '서버에 연결할 수 없습니다. 자동 재시도하지 않았습니다.',
  UNEXPECTED_RESPONSE: '서버 응답을 확인할 수 없습니다.',
}

function normalizeError(error: unknown): ManagementApiError {
  return error instanceof ManagementApiError
    ? error
    : new ManagementApiError(0, 'UNEXPECTED_RESPONSE')
}

function ErrorNotice({ error }: { error: ManagementApiError }) {
  const kind = error.code === 'ACCESS_DENIED' ? 'forbidden'
    : error.code === 'RESOURCE_NOT_FOUND' ? 'not-found'
      : error.status === 409 ? 'conflict' : 'error'
  return (
    <div className={`notice notice--error notice--${kind}`} role="alert">
      <strong>{errorCopy[error.code]}</strong>
      <code>{error.code}</code>
    </div>
  )
}

function Loading({ children }: { children: string }) {
  return <p className="notice notice--loading" role="status" aria-live="polite">{children}</p>
}

function venueToday(timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

function formatTime(value: string, timezone: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: timezone, month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

const actionLabel: Record<ReservationAction, string> = {
  cancel: '취소', 'check-in': '체크인', 'no-show': '노쇼', complete: '완료',
}

export function ManagementVenueFlow({
  api = managementApi,
  navigation,
}: ManagementVenueFlowProps) {
  const [venues, setVenues] = useState<ManagementVenue[]>([])
  const [venueListState, setVenueListState] = useState<LoadState>('loading')
  const [venueListError, setVenueListError] = useState<ManagementApiError>()
  const [venueId, setVenueId] = useState('')
  const [date, setDate] = useState('')
  const [status, setStatus] = useState<ReservationState | ''>('')
  const [detail, setDetail] = useState<ManagementVenue>()
  const [policy, setPolicy] = useState<Policy>()
  const [resources, setResources] = useState<ManagementResource[]>([])
  const [slots, setSlots] = useState<SlotInventory[]>([])
  const [reservations, setReservations] = useState<ManagementReservation[]>([])
  const [configurationState, setConfigurationState] = useState<LoadState>('idle')
  const [slotState, setSlotState] = useState<LoadState>('idle')
  const [reservationState, setReservationState] = useState<LoadState>('idle')
  const [configurationError, setConfigurationError] = useState<ManagementApiError>()
  const [slotError, setSlotError] = useState<ManagementApiError>()
  const [reservationError, setReservationError] = useState<ManagementApiError>()
  const [mutation, setMutation] = useState<UnknownMutation>()
  const [resultUnknown, setResultUnknown] = useState<UnknownMutation>()
  const [mutationError, setMutationError] = useState<ManagementApiError>()
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [fieldErrorScope, setFieldErrorScope] = useState('')
  const venueGeneration = useRef(0)
  const slotGeneration = useRef(0)
  const reservationGeneration = useRef(0)
  const mutationInFlight = useRef(false)

  async function loadVenues() {
    setVenueListState('loading')
    setVenueListError(undefined)
    try {
      const result = await api.listVenues()
      setVenues(result)
      setVenueListState('success')
    } catch (error) {
      setVenueListError(normalizeError(error))
      setVenueListState('error')
    }
  }

  useEffect(() => { void loadVenues() }, [api])

  async function loadConfiguration(targetVenueId: string, generation: number) {
    setConfigurationState('loading')
    setConfigurationError(undefined)
    try {
      const [nextDetail, nextPolicy, nextResources] = await Promise.all([
        api.getVenue(targetVenueId), api.getPolicy(targetVenueId), api.listResources(targetVenueId),
      ])
      if (generation !== venueGeneration.current) return
      setDetail(nextDetail)
      setPolicy(nextPolicy)
      setResources(nextResources)
      setConfigurationState('success')
    } catch (error) {
      if (generation !== venueGeneration.current) return
      setConfigurationError(normalizeError(error))
      setConfigurationState('error')
    }
  }

  async function loadSlots(targetVenueId: string, targetDate: string) {
    const generation = slotGeneration.current + 1
    slotGeneration.current = generation
    setSlotState('loading')
    setSlotError(undefined)
    try {
      const result = await api.listSlots(targetVenueId, targetDate)
      if (generation !== slotGeneration.current) return
      setSlots(result)
      setSlotState('success')
    } catch (error) {
      if (generation !== slotGeneration.current) return
      setSlotError(normalizeError(error))
      setSlotState('error')
    }
  }

  async function loadReservations(
    targetVenueId: string,
    targetDate: string,
    targetStatus: ReservationState | '',
  ) {
    const generation = reservationGeneration.current + 1
    reservationGeneration.current = generation
    setReservationState('loading')
    setReservationError(undefined)
    try {
      const result = await api.listReservations(
        targetVenueId, targetDate, targetStatus || undefined,
      )
      if (generation !== reservationGeneration.current) return
      setReservations(result)
      setReservationState('success')
    } catch (error) {
      if (generation !== reservationGeneration.current) return
      setReservationError(normalizeError(error))
      setReservationState('error')
    }
  }

  function selectVenue(nextVenueId: string) {
    if (mutationInFlight.current) return
    venueGeneration.current += 1
    slotGeneration.current += 1
    reservationGeneration.current += 1
    setVenueId(nextVenueId)
    setDetail(undefined)
    setPolicy(undefined)
    setResources([])
    setSlots([])
    setReservations([])
    setConfigurationState(nextVenueId ? 'loading' : 'idle')
    setSlotState('idle')
    setReservationState('idle')
    setConfigurationError(undefined)
    setSlotError(undefined)
    setReservationError(undefined)
    setMutationError(undefined)
    setResultUnknown(undefined)
    const selected = venues.find((venue) => venue.id === nextVenueId)
    const nextDate = selected ? venueToday(selected.timezone) : ''
    setDate(nextDate)
    setStatus('')
    if (!selected) return
    const generation = venueGeneration.current
    void loadConfiguration(nextVenueId, generation)
    void loadSlots(nextVenueId, nextDate)
    void loadReservations(nextVenueId, nextDate, '')
  }

  function changeDate(nextDate: string) {
    if (mutationInFlight.current) return
    slotGeneration.current += 1
    reservationGeneration.current += 1
    setDate(nextDate)
    setSlots([])
    setReservations([])
    setSlotState(nextDate ? 'loading' : 'idle')
    setReservationState(nextDate ? 'loading' : 'idle')
    if (venueId && nextDate) {
      void loadSlots(venueId, nextDate)
      void loadReservations(venueId, nextDate, status)
    }
  }

  function changeStatus(nextStatus: ReservationState | '') {
    if (mutationInFlight.current) return
    reservationGeneration.current += 1
    setStatus(nextStatus)
    setReservations([])
    if (venueId && date) void loadReservations(venueId, date, nextStatus)
  }

  async function runMutation<T>(
    kind: UnknownMutation,
    command: () => Promise<T>,
    apply: (result: T) => void,
    errorScope: string = kind,
  ): Promise<T | undefined> {
    if (mutationInFlight.current) return
    mutationInFlight.current = true
    setMutation(kind)
    setMutationError(undefined)
    setResultUnknown(undefined)
    setFieldErrors({})
    setFieldErrorScope('')
    try {
      const result = await command()
      apply(result)
      return result
    } catch (error) {
      if (error instanceof ManagementMutationResultUnknownError) setResultUnknown(kind)
      else {
        const normalized = normalizeError(error)
        setMutationError(normalized)
        setFieldErrors(normalized.fieldErrors)
        setFieldErrorScope(errorScope)
      }
    } finally {
      mutationInFlight.current = false
      setMutation(undefined)
    }
  }

  function submitVenue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail) return
    const data = new FormData(event.currentTarget)
    const name = String(data.get('name') ?? '').trim()
    const nextStatus = String(data.get('status')) as ManagementVenue['status']
    if (!name) {
      setFieldErrors({ name: 'Venue 이름을 입력해 주세요.' })
      return
    }
    const context = { venueId: detail.id }
    void runMutation('venue', () => api.patchVenue(context.venueId, { name, status: nextStatus }), (updated) => {
      setDetail(updated)
      setVenues((current) => current.map((item) => item.id === updated.id ? updated : item))
    })
  }

  function submitPolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail) return
    const data = new FormData(event.currentTarget)
    const input = {
      slotDurationMinutes: Number(data.get('slotDurationMinutes')),
      holdDurationMinutes: Number(data.get('holdDurationMinutes')),
      cancellationCutoffMinutes: Number(data.get('cancellationCutoffMinutes')),
      noShowGraceMinutes: Number(data.get('noShowGraceMinutes')),
    }
    const context = { venueId: detail.id }
    void runMutation('policy', () => api.putPolicy(context.venueId, input), setPolicy)
  }

  function submitResource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail) return
    const form = event.currentTarget
    const data = new FormData(form)
    const input = { name: String(data.get('name') ?? '').trim(), seatingCapacity: Number(data.get('seatingCapacity')) }
    const context = { venueId: detail.id }
    void runMutation('resource-create', () => api.createResource(context.venueId, input), (created) => {
      setResources((current) => [...current, created])
      form.reset()
    })
  }

  function submitResourcePatch(event: FormEvent<HTMLFormElement>, resource: ManagementResource) {
    event.preventDefault()
    if (!detail) return
    const data = new FormData(event.currentTarget)
    const patch = {
      name: String(data.get('name') ?? '').trim(),
      seatingCapacity: Number(data.get('seatingCapacity')),
      status: String(data.get('status')) as ManagementResource['status'],
    }
    const context = { venueId: detail.id, resourceId: resource.id }
    void runMutation('resource-patch', () => api.patchResource(
      context.venueId, context.resourceId, patch,
    ), (updated) => setResources((current) => current.map((item) =>
      item.id === updated.id ? updated : item)), `resource-patch:${resource.id}`)
  }

  function submitSlot(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail) return
    const form = event.currentTarget
    const data = new FormData(form)
    const input = {
      resourceId: String(data.get('resourceId') ?? ''),
      startsAt: String(data.get('startsAt') ?? '').trim(),
    }
    const context = { venueId: detail.id, date }
    void runMutation('slot-create', () => api.createSlot(context.venueId, input), (created) => {
      if (context.venueId === venueId && context.date === date) setSlots((current) => [...current, created])
      form.reset()
    })
  }

  async function commandReservation(reservation: ManagementReservation, action: ReservationAction) {
    if (!detail) return
    const context = { venueId: detail.id, date, status, reservationId: reservation.id }
    reservationGeneration.current += 1
    const result = await runMutation('reservation-command', () => api.commandReservation(
      context.venueId, context.reservationId, action,
    ), (updated) => setReservations((current) => current.map((item) =>
      item.id === updated.id ? { ...item, ...updated, allowedActions: [] } : item)))
    if (!result) return
    if (context.venueId === venueId && context.date === date && context.status === status) {
      await loadReservations(context.venueId, context.date, context.status)
    }
  }

  const selectedVenue = detail ?? venues.find((venue) => venue.id === venueId)
  const resourceNames = new Map(resources.map((resource) => [resource.id, resource.name]))
  const writable = selectedVenue?.configurationWritable === true
  const busy = Boolean(mutation)
  const errorFor = (scope: string, field: string) =>
    fieldErrorScope === scope ? fieldErrors[field] : undefined

  function reconcileUnknown() {
    if (!venueId) return
    if (resultUnknown === 'slot-create') {
      if (date) void loadSlots(venueId, date)
      return
    }
    if (resultUnknown === 'reservation-command') {
      if (date) void loadReservations(venueId, date, status)
      return
    }
    void loadConfiguration(venueId, venueGeneration.current)
  }

  return (
    <>
      <a className="skip-link" href="#main-content">본문으로 바로가기</a>
      <header className="site-header">
        <span className="brand" aria-label="SlotQ">SlotQ</span>
        {navigation}
      </header>
      <main id="main-content" className="app-shell management-shell" tabIndex={-1}>
        <section className="intro" aria-labelledby="management-title">
          <p className="eyebrow">Venue management</p>
          <h1 id="management-title">Venue 운영</h1>
          <p className="intro-copy">Venue를 선택하고 당일 Reservation과 서버가 허용한 작업을 먼저 확인하세요.</p>
        </section>

        <section className="management-section venue-selector" aria-labelledby="venue-selector-title">
          <h2 id="venue-selector-title">1. Venue 선택</h2>
          {venueListState === 'loading' ? <Loading>접근 가능한 Venue를 불러오는 중입니다.</Loading> : null}
          {venueListError ? <><ErrorNotice error={venueListError} /><Button density="compact" variant="secondary" onClick={() => void loadVenues()}>Venue 다시 조회</Button></> : null}
          {venueListState === 'success' && venues.length === 0 ? <p className="notice notice--empty" role="status">할당된 Venue가 없습니다.</p> : null}
          <FormField id="management-venue" label="Venue" density="compact">
            <select value={venueId} onChange={(event) => selectVenue(event.target.value)} disabled={busy || venues.length === 0}>
              <option value="">Venue 선택</option>
              {venues.map((venue) => <option key={venue.id} value={venue.id}>{venue.name} · {venue.timezone}</option>)}
            </select>
          </FormField>
          {selectedVenue ? <p className="venue-timezone">기준 timezone: <strong>{selectedVenue.timezone}</strong></p> : null}
        </section>

        {selectedVenue ? (
          <>
            <section className="management-section reservation-operations" aria-labelledby="reservation-list-title">
              <div className="section-heading"><div><h2 id="reservation-list-title">2. Reservation 운영</h2><p>상태와 작업 가능 여부는 서버 응답을 그대로 표시합니다.</p></div></div>
              <div className="filter-row">
                <FormField id="management-date" label="Venue-local 날짜" density="compact">
                  <input type="date" value={date} onChange={(event) => changeDate(event.target.value)} disabled={busy} />
                </FormField>
                <FormField id="reservation-status" label="상태" density="compact">
                  <select value={status} onChange={(event) => changeStatus(event.target.value as ReservationState | '')} disabled={busy}>
                    <option value="">전체 상태</option>
                    {reservationStates.map((value) => <option key={value} value={value}>{value}</option>)}
                  </select>
                </FormField>
                <Button density="compact" variant="secondary" disabled={busy || !date || reservationState === 'loading'} onClick={() => void loadReservations(venueId, date, status)}>목록 새로고침</Button>
              </div>
              {reservationState === 'loading' ? <Loading>Reservation 목록을 불러오는 중입니다.</Loading> : null}
              {reservationError ? <ErrorNotice error={reservationError} /> : null}
              {reservationState === 'success' && reservations.length === 0 ? <p className="notice notice--empty" role="status">선택한 날짜와 상태의 Reservation이 없습니다.</p> : null}
              {reservations.length > 0 ? (
                <ul className="reservation-list" aria-label="Reservation 목록">
                  {reservations.map((reservation) => (
                    <li key={reservation.id} className="reservation-row">
                      <div className="reservation-scan">
                        <strong><time dateTime={reservation.startsAt}>{formatTime(reservation.startsAt, selectedVenue.timezone)}</time></strong>
                        <StatusBadge status={reservation.state} />
                        <span>Resource {resourceNames.get(reservation.resourceId) ?? reservation.resourceId}</span>
                        <span>{reservation.partySize}명</span>
                        <span>Customer {reservation.customerReference}</span>
                        {reservation.state === 'HELD' ? <span>HOLD 만료 <time dateTime={reservation.expiresAt}>{formatTime(reservation.expiresAt, selectedVenue.timezone)}</time></span> : null}
                      </div>
                      <div className="reservation-actions" aria-label={`${reservation.id} 허용 작업`}>
                        {reservation.allowedActions.length === 0 ? <span className="muted">허용된 작업 없음</span> : null}
                        {reservation.allowedActions.map((action) => (
                          <Button key={action} density="compact" variant={action === 'cancel' || action === 'no-show' ? 'destructive' : 'secondary'} disabled={busy} onClick={() => void commandReservation(reservation, action)}>{actionLabel[action]}</Button>
                        ))}
                      </div>
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>

            <section className="management-section configuration-section" aria-labelledby="configuration-title">
              <h2 id="configuration-title">3. Venue configuration</h2>
              {configurationState === 'loading' ? <Loading>Venue 설정을 불러오는 중입니다.</Loading> : null}
              {configurationError ? <ErrorNotice error={configurationError} /> : null}
              {!writable ? <p className="notice notice--state" role="status">이 Venue의 설정은 읽기 전용입니다. Reservation 운영만 사용할 수 있습니다.</p> : null}
              {detail && policy ? (
                <div className="configuration-grid">
                  <article className="configuration-panel">
                    <h3>Venue</h3>
                    {writable ? (
                      <form className="compact-form" onSubmit={submitVenue} noValidate>
                        <FormField id="venue-name" label="이름" density="compact" error={errorFor('venue', 'name')}><input name="name" defaultValue={detail.name} disabled={busy} /></FormField>
                        <FormField id="venue-status" label="상태" density="compact" error={errorFor('venue', 'status')}><select name="status" defaultValue={detail.status} disabled={busy}><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option></select></FormField>
                        <Button density="compact" type="submit" disabled={busy}>{mutation === 'venue' ? '저장 중…' : 'Venue 저장'}</Button>
                      </form>
                    ) : <dl className="read-only-details"><div><dt>이름</dt><dd>{detail.name}</dd></div><div><dt>상태</dt><dd>{detail.status}</dd></div></dl>}
                  </article>
                  <article className="configuration-panel">
                    <h3>Policy v{policy.version}</h3>
                    {writable ? (
                      <form className="compact-form policy-form" onSubmit={submitPolicy} noValidate>
                        {(['slotDurationMinutes', 'holdDurationMinutes', 'cancellationCutoffMinutes', 'noShowGraceMinutes'] as const).map((field) => <FormField key={field} id={`policy-${field}`} label={field} density="compact" error={errorFor('policy', field)}><input name={field} type="number" min={field.includes('Duration') ? 1 : 0} defaultValue={policy[field]} disabled={busy} /></FormField>)}
                        <Button density="compact" type="submit" disabled={busy}>{mutation === 'policy' ? '저장 중…' : 'Policy 전체 저장'}</Button>
                      </form>
                    ) : <dl className="read-only-details"><div><dt>Slot</dt><dd>{policy.slotDurationMinutes}분</dd></div><div><dt>HOLD</dt><dd>{policy.holdDurationMinutes}분</dd></div><div><dt>취소 cutoff</dt><dd>{policy.cancellationCutoffMinutes}분</dd></div><div><dt>No-show grace</dt><dd>{policy.noShowGraceMinutes}분</dd></div></dl>}
                  </article>
                </div>
              ) : null}

              {configurationState === 'success' ? (
                <article className="configuration-panel resource-panel">
                  <h3>TABLE Resources</h3>
                  {resources.length === 0 ? <p className="notice notice--empty" role="status">Resource가 없습니다.</p> : null}
                  <ul className="resource-list">
                    {resources.map((resource) => <li key={resource.id}>
                      {writable ? <form className="resource-form" onSubmit={(event) => submitResourcePatch(event, resource)}>
                        <FormField id={`resource-name-${resource.id}`} label="이름" density="compact" error={errorFor(`resource-patch:${resource.id}`, 'name')}><input name="name" defaultValue={resource.name} disabled={busy} /></FormField>
                        <FormField id={`resource-seats-${resource.id}`} label="좌석 수" density="compact" error={errorFor(`resource-patch:${resource.id}`, 'seatingCapacity')}><input name="seatingCapacity" type="number" min="1" defaultValue={resource.seatingCapacity} disabled={busy} /></FormField>
                        <FormField id={`resource-status-${resource.id}`} label="상태" density="compact" error={errorFor(`resource-patch:${resource.id}`, 'status')}><select name="status" defaultValue={resource.status} disabled={busy}><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option></select></FormField>
                        <Button density="compact" variant="secondary" type="submit" disabled={busy}>Resource 저장</Button>
                      </form> : <span>{resource.name} · {resource.seatingCapacity}석 · {resource.status}</span>}
                    </li>)}
                  </ul>
                  {writable ? <form className="resource-form create-form" onSubmit={submitResource}>
                    <FormField id="new-resource-name" label="새 TABLE 이름" density="compact" error={errorFor('resource-create', 'name')}><input name="name" disabled={busy} /></FormField>
                    <FormField id="new-resource-seats" label="좌석 수" density="compact" error={errorFor('resource-create', 'seatingCapacity')}><input name="seatingCapacity" type="number" min="1" disabled={busy} /></FormField>
                    <Button density="compact" type="submit" disabled={busy}>TABLE Resource 추가</Button>
                  </form> : null}
                </article>
              ) : null}

              <article className="configuration-panel slot-panel">
                <div className="section-heading"><div><h3>Slot inventory</h3><p>TABLE capacity는 서버 representation 그대로 표시합니다.</p></div><Button density="compact" variant="secondary" onClick={() => void loadSlots(venueId, date)} disabled={busy || !date || slotState === 'loading'}>Slot 목록 다시 조회</Button></div>
                {slotState === 'loading' ? <Loading>Slot 목록을 불러오는 중입니다.</Loading> : null}
                {slotError ? <ErrorNotice error={slotError} /> : null}
                {slotState === 'success' && slots.length === 0 ? <p className="notice notice--empty" role="status">선택한 날짜의 Slot이 없습니다.</p> : null}
                {slots.length > 0 ? <ul className="slot-list">{slots.map((slot) => <li key={slot.id}><time dateTime={slot.startsAt}>{formatTime(slot.startsAt, selectedVenue.timezone)}</time> · {resourceNames.get(slot.resourceId) ?? slot.resourceId} · capacity {slot.capacity} · policy v{slot.appliedPolicyVersion}</li>)}</ul> : null}
                {writable ? <form className="slot-create-form" onSubmit={submitSlot}>
                  <FormField id="slot-resource" label="Resource" density="compact" error={errorFor('slot-create', 'resourceId')}><select name="resourceId" disabled={busy}><option value="">Resource 선택</option>{resources.filter((resource) => resource.status === 'ACTIVE').map((resource) => <option key={resource.id} value={resource.id}>{resource.name}</option>)}</select></FormField>
                  <FormField id="slot-start" label="startsAt (RFC3339 offset)" density="compact" description={`${selectedVenue.timezone} 예: 2026-08-31T18:00:00+09:00`} error={errorFor('slot-create', 'startsAt')}><input name="startsAt" placeholder="2026-08-31T18:00:00+09:00" disabled={busy} /></FormField>
                  <Button density="compact" type="submit" disabled={busy}>Slot 생성</Button>
                </form> : null}
              </article>
            </section>
          </>
        ) : <p className="step-placeholder">서버가 반환한 Venue 중 하나를 선택해 주세요.</p>}

        {mutationError ? <div className="management-mutation-notice"><ErrorNotice error={mutationError} />{venueId && date ? <Button density="compact" variant="secondary" onClick={() => { void loadConfiguration(venueId, venueGeneration.current); void loadSlots(venueId, date); void loadReservations(venueId, date, status) }}>관련 데이터 다시 조회</Button> : null}</div> : null}
        {resultUnknown ? <div className="notice notice--unknown" role="alert"><strong>작업 결과를 확인할 수 없습니다</strong><span>성공이나 실패를 추정하지 않고 자동 재전송하지 않았습니다. 관련 목록을 다시 조회해 확인해 주세요.</span><code>RESULT_UNKNOWN</code><Button density="compact" variant="secondary" onClick={reconcileUnknown}>관련 데이터 다시 조회</Button></div> : null}
      </main>
      <footer className="site-footer"><small>Server scope and allowedActions are the source of truth.</small></footer>
    </>
  )
}
