import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'
import {
  CustomerApiError,
  MutationResultUnknownError,
  type Availability,
  type CustomerReservationApi,
  type ProductErrorCode,
  type Reservation,
} from './customer/customerReservationApi'
import type { ManagementApi } from './management/managementApi'

const venue = { id: 'venue-a', name: '서울 다이닝', timezone: 'Asia/Seoul' }
const anotherVenue = { id: 'venue-b', name: '부산 다이닝', timezone: 'Asia/Seoul' }
const slot = {
  slotInventoryId: 'slot-a',
  resourceId: 'resource-a',
  resourceName: 'Table 1',
  startsAt: '2099-09-01T09:00:00Z',
  endsAt: '2099-09-01T09:30:00Z',
  seatingCapacity: 4,
  capacity: 1,
  occupied: 0,
  available: 1,
}
const availability: Availability = {
  venueId: venue.id,
  timezone: venue.timezone,
  date: '2099-09-01',
  items: [slot],
}
const held: Reservation = {
  id: 'reservation-a',
  venueId: venue.id,
  resourceId: slot.resourceId,
  slotInventoryId: slot.slotInventoryId,
  state: 'HELD',
  partySize: 2,
  allocationQuantity: 1,
  startsAt: slot.startsAt,
  endsAt: slot.endsAt,
  expiresAt: '2099-09-01T08:55:00Z',
  cancelAllowedUntil: '2099-09-01T08:30:00Z',
  noShowEligibleAt: '2099-09-01T09:15:00Z',
  appliedPolicyVersion: 3,
}

function makeApi(overrides: Partial<CustomerReservationApi> = {}): CustomerReservationApi {
  return {
    listVenues: vi.fn().mockResolvedValue([venue]),
    getAvailability: vi.fn().mockResolvedValue(availability),
    createHold: vi.fn().mockResolvedValue(held),
    getReservation: vi.fn().mockResolvedValue(held),
    confirmReservation: vi.fn().mockResolvedValue({ ...held, state: 'CONFIRMED' }),
    cancelReservation: vi.fn().mockResolvedValue({ ...held, state: 'CANCELLED' }),
    ...overrides,
  }
}

async function searchAvailability(api: CustomerReservationApi) {
  render(<App api={api} />)
  await screen.findByRole('option', { name: /서울 다이닝/ })
  fireEvent.change(screen.getByRole('combobox', { name: 'Venue' }), {
    target: { value: venue.id },
  })
  fireEvent.change(screen.getByLabelText('날짜'), { target: { value: '2099-09-01' } })
  fireEvent.change(screen.getByLabelText('인원'), { target: { value: '2' } })
  fireEvent.click(screen.getByRole('button', { name: '예약 가능 시간 조회' }))
}

async function createHeldReservation(api: CustomerReservationApi) {
  await searchAvailability(api)
  fireEvent.click(await screen.findByRole('button', { name: '이 시간 선택' }))
  fireEvent.click(screen.getByRole('button', { name: '이 시간 HOLD' }))
  await screen.findByRole('article', { name: '현재 예약' })
}

describe('Customer reservation guided flow', () => {
  it('keeps the accessibility shell and loads active Venue choices from the API', async () => {
    let resolveVenues: ((value: typeof venue[]) => void) | undefined
    const api = makeApi({
      listVenues: vi.fn().mockReturnValue(new Promise((resolve) => { resolveVenues = resolve })),
    })
    render(<App api={api} />)

    expect(screen.getByRole('heading', { level: 1, name: '예약하기' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
    expect(screen.getByRole('link', { name: '본문으로 바로가기' })).toHaveAttribute('href', '#main-content')
    expect(screen.getByRole('status', { name: '' })).toHaveTextContent('Venue를 불러오는 중')

    resolveVenues?.([venue])
    expect(await screen.findByRole('option', { name: /서울 다이닝/ })).toBeInTheDocument()
    expect(api.listVenues).toHaveBeenCalledOnce()
  })

  it('distinguishes client validation without calling Availability', async () => {
    const api = makeApi()
    render(<App api={api} />)
    await screen.findByRole('option', { name: /서울 다이닝/ })

    fireEvent.change(screen.getByLabelText('인원'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: '예약 가능 시간 조회' }))

    expect(screen.getByText('Venue를 선택해 주세요.')).toHaveAttribute('id', 'venue-error')
    expect(screen.getByText('날짜를 선택해 주세요.')).toHaveAttribute('id', 'date-error')
    expect(screen.getByText('인원은 1명 이상의 정수로 입력해 주세요.')).toHaveAttribute('id', 'party-size-error')
    expect(screen.getByLabelText('인원')).toHaveAttribute('aria-invalid', 'true')
    expect(api.getAvailability).not.toHaveBeenCalled()
  })

  it('distinguishes a normal result, unavailable item, and empty result', async () => {
    const occupied = { ...slot, slotInventoryId: 'slot-b', available: 0, occupied: 1 }
    const api = makeApi({
      getAvailability: vi.fn()
        .mockResolvedValueOnce({ ...availability, items: [slot, occupied] })
        .mockResolvedValueOnce({ ...availability, items: [] }),
    })
    await searchAvailability(api)

    expect(await screen.findByRole('list', { name: '예약 가능한 시간' })).toBeInTheDocument()
    expect(screen.getByText('예약 불가')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '이 시간 선택' })).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: '예약 가능 시간 조회' }))
    expect(await screen.findByText('선택한 조건에 예약 가능한 시간이 없습니다.')).toHaveAttribute(
      'role',
      'status',
    )
  })

  it('distinguishes an Availability API failure and offers an explicit read retry', async () => {
    const api = makeApi({
      getAvailability: vi.fn().mockRejectedValue(new CustomerApiError(500, 'INTERNAL_ERROR')),
    })
    await searchAvailability(api)

    expect(await screen.findByRole('alert')).toHaveTextContent('INTERNAL_ERROR')
    expect(screen.getByRole('button', { name: '같은 조건으로 다시 조회' })).toBeInTheDocument()
    expect(api.getAvailability).toHaveBeenCalledOnce()
  })

  it.each([
    {
      condition: 'Venue',
      control: () => screen.getByRole('combobox', { name: 'Venue' }),
      value: anotherVenue.id,
    },
    {
      condition: 'date',
      control: () => screen.getByLabelText('날짜'),
      value: '2099-09-02',
    },
    {
      condition: 'partySize',
      control: () => screen.getByLabelText('인원'),
      value: '4',
    },
  ])('does not restore stale Availability after $condition changes', async ({ control, value }) => {
    let resolveAvailability: ((value: Availability) => void) | undefined
    const api = makeApi({
      listVenues: vi.fn().mockResolvedValue([venue, anotherVenue]),
      getAvailability: vi.fn().mockReturnValue(
        new Promise<Availability>((resolve) => { resolveAvailability = resolve }),
      ),
    })
    await searchAvailability(api)

    const input = control() as HTMLInputElement | HTMLSelectElement
    fireEvent.change(input, { target: { value } })
    expect(input.value).toBe(value)

    await act(async () => { resolveAvailability?.(availability) })

    expect(screen.queryByRole('list', { name: '예약 가능한 시간' })).not.toBeInTheDocument()
    expect(screen.getByText('먼저 예약 가능한 시간을 조회해 주세요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이 시간 HOLD' })).not.toBeInTheDocument()
  })

  it('shows server HELD state and expiresAt, then uses command representations for confirm and cancel', async () => {
    const api = makeApi()
    await createHeldReservation(api)

    expect(screen.getByText('HELD')).toBeInTheDocument()
    expect(screen.getByText(/표시용 남은 시간/)).toBeInTheDocument()
    expect(screen.getByText('서버 HOLD deadline')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '예약 확정' }))
    expect(await screen.findByText('CONFIRMED')).toBeInTheDocument()
    expect(api.confirmReservation).toHaveBeenCalledWith(venue.id, held.id)

    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }))
    expect(await screen.findByText('CANCELLED')).toBeInTheDocument()
    expect(api.cancelReservation).toHaveBeenCalledWith(venue.id, held.id)
  })

  it('changes to EXPIRED only after the Reservation GET returns effective EXPIRED', async () => {
    const api = makeApi({
      getReservation: vi.fn().mockResolvedValue({ ...held, state: 'EXPIRED' }),
    })
    await createHeldReservation(api)

    fireEvent.click(screen.getByRole('button', { name: '최신 Reservation 상태 조회' }))

    expect(await screen.findByText('EXPIRED')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('서버가 이 예약을 EXPIRED 상태로 반환')
    expect(screen.queryByRole('button', { name: '예약 확정' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '예약 취소' })).not.toBeInTheDocument()
  })

  it('does not restore a Reservation GET response after its search context is reset', async () => {
    let resolveReservation: ((value: Reservation) => void) | undefined
    const api = makeApi({
      getReservation: vi.fn().mockReturnValue(
        new Promise<Reservation>((resolve) => { resolveReservation = resolve }),
      ),
    })
    await createHeldReservation(api)

    fireEvent.click(screen.getByRole('button', { name: '최신 Reservation 상태 조회' }))
    expect(screen.getByText('최신 예약 상태를 조회하는 중입니다.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('인원'), { target: { value: '4' } })
    expect(screen.queryByRole('article', { name: '현재 예약' })).not.toBeInTheDocument()

    await act(async () => { resolveReservation?.({ ...held, state: 'CONFIRMED' }) })

    expect(screen.queryByRole('article', { name: '현재 예약' })).not.toBeInTheDocument()
    expect(screen.getByText('HOLD를 생성하면 서버가 반환한 예약 상태가 여기에 표시됩니다.'))
      .toBeInTheDocument()
  })

  it.each<ProductErrorCode>([
    'VALIDATION_FAILED',
    'CAPACITY_UNAVAILABLE',
    'PARTY_SIZE_NOT_SUPPORTED',
    'BOOKING_NOT_ALLOWED',
    'AUTHENTICATION_REQUIRED',
    'ACCESS_DENIED',
    'RESOURCE_NOT_FOUND',
  ])('renders HOLD business state %s by stable code', async (code) => {
    const status = code === 'VALIDATION_FAILED' ? 400
      : code === 'AUTHENTICATION_REQUIRED' ? 401
      : code === 'ACCESS_DENIED' ? 403
        : code === 'RESOURCE_NOT_FOUND' ? 404 : 409
    const api = makeApi({
      createHold: vi.fn().mockRejectedValue(new CustomerApiError(status, code)),
    })
    await searchAvailability(api)
    fireEvent.click(await screen.findByRole('button', { name: '이 시간 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '이 시간 HOLD' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(code)
  })

  it.each<ProductErrorCode>([
    'HOLD_EXPIRED',
    'CANCELLATION_WINDOW_CLOSED',
    'RESERVATION_TRANSITION_NOT_ALLOWED',
  ])('renders command business state %s without inventing a new Reservation state', async (code) => {
    const api = makeApi({
      confirmReservation: vi.fn().mockRejectedValue(new CustomerApiError(409, code)),
    })
    await createHeldReservation(api)
    fireEvent.click(screen.getByRole('button', { name: '예약 확정' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(code)
    expect(screen.getByText('HELD')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '최신 Reservation 상태 조회' })).toBeInTheDocument()
  })

  it('does not replay a HOLD with unknown Reservation ID and offers Availability refresh', async () => {
    const api = makeApi({
      createHold: vi.fn().mockRejectedValue(new MutationResultUnknownError('NETWORK_ERROR')),
    })
    await searchAvailability(api)
    fireEvent.click(await screen.findByRole('button', { name: '이 시간 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '이 시간 HOLD' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Reservation ID를 받지 못했으므로 같은 요청을 자동 재전송하지 않습니다')
    expect(screen.getByRole('button', { name: 'Availability 새로고침' })).toBeInTheDocument()
    expect(api.createHold).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: 'Availability 새로고침' }))
    await waitFor(() => expect(api.getAvailability).toHaveBeenCalledTimes(2))
    expect(api.createHold).toHaveBeenCalledOnce()
  })

  it('marks a timeout/5xx command result unknown and reconciles with explicit Reservation GET', async () => {
    const api = makeApi({
      confirmReservation: vi.fn().mockRejectedValue(new MutationResultUnknownError('INTERNAL_ERROR')),
      getReservation: vi.fn().mockResolvedValue({ ...held, state: 'CONFIRMED' }),
    })
    await createHeldReservation(api)
    fireEvent.click(screen.getByRole('button', { name: '예약 확정' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('성공이나 실패를 추정하지 않습니다')
    expect(screen.getByText('HELD')).toBeInTheDocument()
    expect(api.confirmReservation).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('button', { name: '최신 Reservation 상태 조회' }))
    expect(await screen.findByText('CONFIRMED')).toBeInTheDocument()
    expect(screen.queryByText('성공이나 실패를 추정하지 않습니다')).not.toBeInTheDocument()
    expect(api.getReservation).toHaveBeenCalledOnce()
  })

  it('blocks duplicate submit while a mutation is in flight and never retries automatically', async () => {
    let resolveConfirm: ((value: Reservation) => void) | undefined
    const confirmReservation = vi.fn().mockReturnValue(
      new Promise<Reservation>((resolve) => { resolveConfirm = resolve }),
    )
    const api = makeApi({ confirmReservation })
    await createHeldReservation(api)

    const confirm = screen.getByRole('button', { name: '예약 확정' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    expect(confirm).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Venue' })).toBeDisabled()
    expect(screen.getByLabelText('날짜')).toBeDisabled()
    expect(screen.getByLabelText('인원')).toBeDisabled()
    fireEvent.change(screen.getByLabelText('인원'), { target: { value: '4' } })
    expect(confirmReservation).toHaveBeenCalledOnce()
    resolveConfirm?.({ ...held, state: 'CONFIRMED' })
    await waitFor(() => expect(screen.getByText('CONFIRMED')).toBeInTheDocument())
    expect(screen.getByLabelText('인원')).toHaveValue(2)
  })

  it.each([
    ['예약 확정', 'confirmReservation', 'CONFIRMED'],
    ['예약 취소', 'cancelReservation', 'CANCELLED'],
  ] as const)(
    'blocks App surface navigation while %s is in flight',
    async (buttonName, method, resolvedState) => {
      let resolveCommand: ((value: Reservation) => void) | undefined
      const command = vi.fn().mockReturnValue(
        new Promise<Reservation>((resolve) => { resolveCommand = resolve }),
      )
      const api = makeApi({ [method]: command })
      await createHeldReservation(api)

      fireEvent.click(screen.getByRole('button', { name: buttonName }))
      const managementNavigation = screen.getByRole('button', { name: 'Venue 운영' })
      expect(managementNavigation).toBeDisabled()
      fireEvent.click(managementNavigation)
      expect(screen.getByRole('heading', { level: 1, name: '예약하기' })).toBeInTheDocument()
      expect(command).toHaveBeenCalledOnce()

      resolveCommand?.({ ...held, state: resolvedState })
      await waitFor(() => expect(managementNavigation).toBeEnabled())
    },
  )

  it('preserves known result-unknown identity until exact GET, then restores navigation', async () => {
    const api = makeApi({
      listVenues: vi.fn().mockResolvedValue([venue, anotherVenue]),
      confirmReservation: vi.fn().mockRejectedValue(
        new MutationResultUnknownError('INTERNAL_ERROR'),
      ),
      getReservation: vi.fn().mockResolvedValue({ ...held, state: 'CONFIRMED' }),
    })
    const managementApi = {
      listVenues: vi.fn().mockResolvedValue([]),
    } as unknown as ManagementApi
    render(<App api={api} managementApi={managementApi} />)
    await screen.findByRole('option', { name: /서울 다이닝/ })
    fireEvent.change(screen.getByRole('combobox', { name: 'Venue' }), {
      target: { value: venue.id },
    })
    fireEvent.change(screen.getByLabelText('날짜'), { target: { value: '2099-09-01' } })
    fireEvent.change(screen.getByLabelText('인원'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: '예약 가능 시간 조회' }))
    fireEvent.click(await screen.findByRole('button', { name: '이 시간 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '이 시간 HOLD' }))
    await screen.findByRole('article', { name: '현재 예약' })
    fireEvent.click(screen.getByRole('button', { name: '예약 확정' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('성공이나 실패를 추정하지 않습니다')
    const venueSelect = screen.getByRole('combobox', { name: 'Venue' })
    const dateInput = screen.getByLabelText('날짜')
    const partySizeInput = screen.getByLabelText('인원')
    const managementNavigation = screen.getByRole('button', { name: 'Venue 운영' })
    expect(venueSelect).toBeDisabled()
    expect(dateInput).toBeDisabled()
    expect(partySizeInput).toBeDisabled()
    expect(managementNavigation).toBeDisabled()
    fireEvent.change(venueSelect, { target: { value: anotherVenue.id } })
    fireEvent.change(dateInput, { target: { value: '2099-09-02' } })
    fireEvent.change(partySizeInput, { target: { value: '4' } })
    expect(venueSelect).toHaveValue(venue.id)
    expect(dateInput).toHaveValue('2099-09-01')
    expect(partySizeInput).toHaveValue(2)

    fireEvent.click(screen.getByRole('button', { name: '최신 Reservation 상태 조회' }))
    expect(await screen.findByText('CONFIRMED')).toBeInTheDocument()
    expect(api.getReservation).toHaveBeenCalledWith(venue.id, held.id)
    await waitFor(() => expect(managementNavigation).toBeEnabled())
    fireEvent.click(managementNavigation)
    expect(await screen.findByRole('heading', { level: 1, name: 'Venue 운영' })).toBeInTheDocument()
  })

  it('keeps the reservation context immutable while cancel is in flight', async () => {
    let resolveCancel: ((value: Reservation) => void) | undefined
    const cancelReservation = vi.fn().mockReturnValue(
      new Promise<Reservation>((resolve) => { resolveCancel = resolve }),
    )
    const api = makeApi({ cancelReservation })
    await createHeldReservation(api)

    const cancel = screen.getByRole('button', { name: '예약 취소' })
    fireEvent.click(cancel)
    fireEvent.click(cancel)

    expect(cancel).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Venue' })).toBeDisabled()
    fireEvent.change(screen.getByRole('combobox', { name: 'Venue' }), {
      target: { value: anotherVenue.id },
    })
    expect(cancelReservation).toHaveBeenCalledOnce()

    resolveCancel?.({ ...held, state: 'CANCELLED' })
    expect(await screen.findByText('CANCELLED')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Venue' })).toHaveValue(venue.id)
  })

  it('blocks duplicate HOLD submit while creation is in flight', async () => {
    let resolveHold: ((value: Reservation) => void) | undefined
    const createHold = vi.fn().mockReturnValue(
      new Promise<Reservation>((resolve) => { resolveHold = resolve }),
    )
    const api = makeApi({ createHold })
    await searchAvailability(api)
    fireEvent.click(await screen.findByRole('button', { name: '이 시간 선택' }))

    const holdButton = screen.getByRole('button', { name: '이 시간 HOLD' })
    fireEvent.click(holdButton)
    fireEvent.click(holdButton)

    expect(holdButton).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Venue' })).toBeDisabled()
    expect(screen.getByLabelText('날짜')).toBeDisabled()
    expect(screen.getByLabelText('인원')).toBeDisabled()
    expect(screen.getByRole('button', { name: '예약 가능 시간 조회' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('인원'), { target: { value: '4' } })
    expect(createHold).toHaveBeenCalledOnce()
    resolveHold?.(held)
    expect(await screen.findByRole('article', { name: '현재 예약' })).toBeInTheDocument()
    expect(createHold).toHaveBeenCalledWith(venue.id, slot.slotInventoryId, 2)
    expect(screen.getByLabelText('인원')).toHaveValue(2)
  })
})
