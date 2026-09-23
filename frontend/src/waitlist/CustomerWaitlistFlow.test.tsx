import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CustomerWaitlistFlow } from './CustomerWaitlistFlow'
import { WaitlistApiError, WaitlistMutationUnknown, type Entry, type EntryPage, type Offer, type WaitlistApi } from './waitlistApi'
import { createWaitlistSession } from './waitlistSession'
import type { CustomerReservationApi } from '../customer/customerReservationApi'

const venue = '10000000-0000-4000-8000-000000000001'
const slot = '20000000-0000-4000-8000-000000000002'
const entryId = '30000000-0000-4000-8000-000000000003'
const offerId = '40000000-0000-4000-8000-000000000004'
const instant = '2099-09-01T09:00:00Z'
const entry: Entry = { id: entryId, venueId: venue, startsAt: instant, endsAt: instant,
  partySize: 2, joinedAt: instant, state: 'WAITING', observedAt: instant,
  venueTimezone: 'Asia/Seoul', allowedActions: ['CANCEL'], offerId: null }
const offer: Offer = { id: offerId, entryId, venueId: venue, resourceId: slot, slotInventoryId: slot,
  state: 'PENDING', terminalReason: null, expiresAt: instant, observedAt: instant,
  venueTimezone: 'Asia/Seoul', allowedActions: ['ACCEPT', 'REJECT'],
  reservation: { id: slot, state: 'HELD', startsAt: instant, endsAt: instant,
    expiresAt: instant, partySize: 2, allocationQuantity: 1, appliedPolicyVersion: 1 } }
const page = (items: Entry[] = []): EntryPage => ({ items, nextCursor: null, observedAt: instant, venueTimezone: 'Asia/Seoul' })
function makeApi(overrides: Partial<WaitlistApi> = {}): WaitlistApi {
  return {
    register: vi.fn().mockResolvedValue({ entry, entryId, entryLocation: `/api/v1/venues/${venue}/waitlist-entries/${entryId}`, originalStatus: 201 }),
    registration: vi.fn().mockResolvedValue({ entryId, entryLocation: `/api/v1/venues/${venue}/waitlist-entries/${entryId}`, originalStatus: 201 }),
    entries: vi.fn().mockResolvedValue(page()), entry: vi.fn().mockResolvedValue(entry),
    cancel: vi.fn().mockResolvedValue({ ...entry, state: 'CANCELLED', allowedActions: [] }),
    offer: vi.fn().mockResolvedValue(offer), offerAction: vi.fn().mockResolvedValue({ ...offer, state: 'ACCEPTED', allowedActions: [] }),
    managementEntries: vi.fn(), managementOffer: vi.fn(), ...overrides,
  }
}
const reservationApi = {
  listVenues: vi.fn().mockResolvedValue([{ id: venue, name: 'Venue', timezone: 'Asia/Seoul' }]),
  getAvailability: vi.fn().mockResolvedValue({ venueId: venue, timezone: 'Asia/Seoul', date: '2099-09-01', items: [{
    slotInventoryId: slot, resourceId: slot, resourceName: 'Table 1', startsAt: instant,
    endsAt: instant, seatingCapacity: 4, capacity: 1, occupied: 1, available: 0,
  }] }),
} as unknown as CustomerReservationApi
const selection = { venueId: venue, date: '2099-09-01', slotInventoryId: slot, partySize: 2 }

describe('Customer Waitlist flow', () => {
  it('registers a full Slot once and keeps an unknown key/body through 404 lookup and explicit retry', async () => {
    const api = makeApi({
      register: vi.fn().mockRejectedValueOnce(new WaitlistMutationUnknown('NETWORK_ERROR'))
        .mockResolvedValueOnce({ entry: { ...entry, state: 'CANCELLED', allowedActions: [] },
          entryId, entryLocation: `/api/v1/venues/${venue}/waitlist-entries/${entryId}`, originalStatus: 200 }),
      registration: vi.fn().mockRejectedValue(new WaitlistApiError(404, 'RESOURCE_NOT_FOUND')),
      entry: vi.fn().mockResolvedValue({ ...entry, state: 'CANCELLED', allowedActions: [] }),
    })
    const session = createWaitlistSession()
    render(<CustomerWaitlistFlow api={api} reservationApi={reservationApi} session={session} selection={selection} />)
    fireEvent.click(screen.getByRole('button', { name: '시간대 조회' }))
    await screen.findByText(/현재 가능 0/)
    fireEvent.click(screen.getByRole('button', { name: '이 시간대 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '이 시간대의 적합한 Table 대기' }))
    await screen.findByRole('button', { name: '같은 key·body로 명시적 재시도' })
    expect(api.register).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '등록 키 결과 조회' }))
    await screen.findByText(/아직 commit 결과가 관측되지 않았습니다/)
    expect(api.register).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '같은 key·body로 명시적 재시도' }))
    await screen.findByText(/기존 활성 Entry가 반환되었습니다/)
    expect(api.register).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.register).mock.calls[1]).toEqual(vi.mocked(api.register).mock.calls[0])
    expect(await screen.findByRole('article', { name: 'Entry 현재 상태' })).toHaveTextContent('CANCELLED')
  })
  it('reconciles an expiry conflict through exact Offer and does not expose stale opposite action', async () => {
    const current = { ...entry, state: 'OFFERED' as const, offerId, allowedActions: [] }
    const api = makeApi({ entry: vi.fn().mockResolvedValue(current),
      offer: vi.fn().mockResolvedValueOnce(offer)
        .mockResolvedValue({ ...offer, state: 'EXPIRED', allowedActions: [], terminalReason: 'HOLD_EXPIRED' }),
      offerAction: vi.fn().mockRejectedValue(new WaitlistApiError(409, 'OFFER_EXPIRED')) })
    render(<CustomerWaitlistFlow api={api} reservationApi={reservationApi} session={createWaitlistSession()}
      selection={{ ...selection, entryId }} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Offer 수락' }))
    await waitFor(() => expect(screen.getByRole('article', { name: 'Offer 현재 상태' })).toHaveTextContent('EXPIRED'))
    expect(api.offerAction).toHaveBeenCalledWith(venue, offerId, 'accept')
    expect(screen.queryByRole('button', { name: 'Offer 거절' })).not.toBeInTheDocument()
    expect(api.offer).toHaveBeenCalledTimes(3)
  })
  it('finds the original Offer after an unknown response and allows only the same action retry', async () => {
    const api = makeApi({
      entry: vi.fn().mockResolvedValue({ ...entry, state: 'OFFERED', offerId, allowedActions: [] }),
      offerAction: vi.fn().mockRejectedValueOnce(new WaitlistMutationUnknown('NETWORK_ERROR'))
        .mockResolvedValueOnce({ ...offer, state: 'ACCEPTED', allowedActions: [] }),
      offer: vi.fn().mockResolvedValue(offer),
    })
    render(<CustomerWaitlistFlow api={api} reservationApi={reservationApi} session={createWaitlistSession()}
      selection={{ ...selection, entryId }} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Offer 수락' }))
    expect(await screen.findByText(/처리 결과 확인 필요/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '원래 대상 exact 조회' }))
    expect(await screen.findByRole('button', { name: '같은 대상·작업 명시적 재시도' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Offer 거절' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '같은 대상·작업 명시적 재시도' }))
    await waitFor(() => expect(api.offerAction).toHaveBeenCalledTimes(2))
    expect(vi.mocked(api.offerAction).mock.calls).toEqual([[venue, offerId, 'accept'], [venue, offerId, 'accept']])
  })
  it('discards the old exact GET after choosing a different Entry', async () => {
    let resolveOld!: (value: Entry) => void
    const oldRead = new Promise<Entry>((resolve) => { resolveOld = resolve })
    const other = { ...entry, id: slot, state: 'CANCELLED' as const, allowedActions: [] }
    const api = makeApi({ entries: vi.fn().mockResolvedValue(page([entry, other])),
      entry: vi.fn().mockImplementation((_venue, id) => id === entryId ? oldRead : Promise.resolve(other)) })
    render(<CustomerWaitlistFlow api={api} reservationApi={reservationApi} session={createWaitlistSession()} selection={selection} />)
    const buttons = await screen.findAllByRole('button', { name: '상세 조회' })
    fireEvent.click(buttons[0]); fireEvent.click(buttons[1])
    await waitFor(() => expect(screen.getByRole('article', { name: 'Entry 현재 상태' })).toHaveTextContent('CANCELLED'))
    expect(screen.getByText(`선택한 Entry ${slot}`)).toBeInTheDocument()
    await act(async () => { resolveOld(entry) })
    expect(screen.getByRole('article', { name: 'Entry 현재 상태' })).toHaveTextContent('CANCELLED')
    expect(screen.queryByRole('button', { name: '대기 취소' })).not.toBeInTheDocument()
  })
  it('links accepted Offer to the same Reservation without creating a HOLD', async () => {
    const open = vi.fn()
    const accepted = { ...offer, state: 'ACCEPTED' as const, allowedActions: [],
      reservation: { ...offer.reservation, state: 'CANCELLED' } }
    const api = makeApi({ entry: vi.fn().mockResolvedValue({ ...entry, state: 'FULFILLED', offerId, allowedActions: [] }),
      offer: vi.fn().mockResolvedValue(accepted) })
    render(<CustomerWaitlistFlow api={api} reservationApi={reservationApi} session={createWaitlistSession()}
      selection={{ ...selection, entryId }} onOpenReservation={open} />)
    fireEvent.click(await screen.findByRole('button', { name: '같은 Reservation 상세 확인' }))
    expect(open).toHaveBeenCalledWith(venue, slot)
    expect(api.register).not.toHaveBeenCalled()
  })
})
