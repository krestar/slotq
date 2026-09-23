import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { localAuthSession } from '../auth'
import { WaitlistMutationUnknown, type Entry, type Offer, type WaitlistApi } from './waitlistApi'
import type { CustomerReservationApi } from '../customer/customerReservationApi'
import type { ManagementApi } from '../management/managementApi'

const venue = '10000000-0000-4000-8000-000000000001'
const slot = '20000000-0000-4000-8000-000000000002'
const entryId = '30000000-0000-4000-8000-000000000003'
const otherEntryId = '30000000-0000-4000-8000-000000000005'
const offerId = '40000000-0000-4000-8000-000000000004'
const instant = '2099-09-01T09:00:00Z'
const entry: Entry = { id: entryId, venueId: venue, startsAt: instant, endsAt: instant, partySize: 2,
  joinedAt: instant, state: 'WAITING', observedAt: instant, venueTimezone: 'Asia/Seoul',
  allowedActions: ['CANCEL'], offerId: null }
const offer: Offer = { id: offerId, entryId, venueId: venue, resourceId: slot, slotInventoryId: slot,
  state: 'PENDING', terminalReason: null, expiresAt: instant, observedAt: instant,
  venueTimezone: 'Asia/Seoul', allowedActions: ['ACCEPT', 'REJECT'],
  reservation: { id: slot, state: 'HELD', startsAt: instant, endsAt: instant,
    expiresAt: instant, partySize: 2, allocationQuantity: 1, appliedPolicyVersion: 1 } }
const reservationApi = { listVenues: vi.fn().mockResolvedValue([{ id: venue, name: 'Venue', timezone: 'Asia/Seoul' }]),
  getAvailability: vi.fn().mockResolvedValue({ venueId: venue, timezone: 'Asia/Seoul', date: '2099-09-01',
    items: [{ slotInventoryId: slot, resourceId: slot, resourceName: 'Table', startsAt: instant,
      endsAt: instant, seatingCapacity: 4, capacity: 1, occupied: 1, available: 0 }] }) } as unknown as CustomerReservationApi
const makeWaitlist = (overrides: Partial<WaitlistApi> = {}) => ({
  entries: vi.fn().mockResolvedValue({ items: [], nextCursor: null, observedAt: instant, venueTimezone: 'Asia/Seoul' }),
  entry: vi.fn().mockResolvedValue(entry), offer: vi.fn(), registration: vi.fn(), register: vi.fn(),
  cancel: vi.fn(), offerAction: vi.fn(), managementEntries: vi.fn(), managementOffer: vi.fn(),
  ...overrides,
}) as WaitlistApi
afterEach(() => window.history.replaceState(null, '', '/'))

describe('Waitlist App navigation and auth', () => {
  it('drops the previous Offer when selecting another Entry from the list', async () => {
    window.history.replaceState(null, '', `/?view=waitlist&venueId=${venue}&date=2099-09-01&entryId=${entryId}&offerId=${offerId}`)
    const other = { ...entry, id: otherEntryId, state: 'WAITING' as const }
    const waitlistApi = makeWaitlist({
      entries: vi.fn().mockResolvedValue({ items: [{ ...entry, state: 'OFFERED', offerId }, other],
        nextCursor: null, observedAt: instant, venueTimezone: 'Asia/Seoul' }),
      entry: vi.fn().mockImplementation((_venue, id) => Promise.resolve(id === entryId
        ? { ...entry, state: 'OFFERED', offerId, allowedActions: [] } : other)),
      offer: vi.fn().mockResolvedValue(offer),
    })
    render(<App api={reservationApi} waitlistApi={waitlistApi} />)
    await screen.findByRole('article', { name: 'Offer 현재 상태' })
    const buttons = await screen.findAllByRole('button', { name: '상세 조회' })
    fireEvent.click(buttons[1])
    await waitFor(() => expect(screen.getByRole('article', { name: 'Entry 현재 상태' })).toHaveTextContent('WAITING'))
    expect(screen.getByText(`선택한 Entry ${otherEntryId}`)).toBeInTheDocument()
    expect(window.location.search).toContain(`entryId=${otherEntryId}`)
    expect(window.location.search).not.toContain(`offerId=${offerId}`)
    expect(waitlistApi.entry).toHaveBeenCalledWith(venue, otherEntryId)
  })

  it('restores Management after Customer to Management to Waitlist to Back', async () => {
    const managementApi = { listVenues: vi.fn().mockResolvedValue([]) } as unknown as ManagementApi
    render(<App api={reservationApi} managementApi={managementApi} waitlistApi={makeWaitlist()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Venue 운영' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Venue 운영' })).toBeInTheDocument()
    expect(new URLSearchParams(window.location.search).get('view')).toBe('management')
    fireEvent.click(screen.getByRole('button', { name: 'Customer 대기' }))
    expect(await screen.findByRole('heading', { level: 1, name: '대기와 Offer' })).toBeInTheDocument()
    await act(async () => { window.history.back() })
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Venue 운영' })).toBeInTheDocument())
  })

  it('recovers URL selection through exact server read and keeps URL free of key/body', async () => {
    window.history.replaceState(null, '', `/?view=waitlist&venueId=${venue}&date=2099-09-01&entryId=${entryId}`)
    const waitlistApi = makeWaitlist()
    render(<App api={reservationApi} waitlistApi={waitlistApi} />)
    expect(await screen.findByRole('article', { name: 'Entry 현재 상태' })).toHaveTextContent('WAITING')
    expect(waitlistApi.entry).toHaveBeenCalledWith(venue, entryId)
    expect(window.location.search).toContain(`entryId=${entryId}`)
    expect(window.location.search).not.toMatch(/key|partySize|slotInventoryId|token/i)
  })
  it('guards pending navigation, preserves unknown attempt on surface return, and drops it on auth invalidation', async () => {
    window.history.replaceState(null, '', `/?view=waitlist&venueId=${venue}&date=2099-09-01`)
    let reject!: (cause: unknown) => void
    const register = vi.fn().mockImplementation(() => new Promise((_resolve, rejectPromise) => { reject = rejectPromise }))
    const waitlistApi = makeWaitlist({ register })
    render(<App api={reservationApi} waitlistApi={waitlistApi} />)
    fireEvent.click(screen.getByRole('button', { name: '시간대 조회' }))
    fireEvent.click(await screen.findByRole('button', { name: '이 시간대 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '이 시간대의 적합한 Table 대기' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Customer 예약' })).toBeDisabled())
    reject(new WaitlistMutationUnknown('NETWORK_ERROR'))
    await screen.findByRole('button', { name: '같은 key·body로 명시적 재시도' })
    expect(screen.getByRole('button', { name: 'Customer 예약' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Customer 예약' }))
    fireEvent.click(screen.getByRole('button', { name: 'Customer 대기' }))
    expect(screen.getByRole('button', { name: '같은 key·body로 명시적 재시도' })).toBeInTheDocument()
    expect(register).toHaveBeenCalledOnce()
    expect(window.location.search).not.toMatch(/key|partySize|slotInventoryId|token/i)
    localAuthSession.invalidate()
    await waitFor(() => expect(screen.queryByRole('button', { name: '같은 key·body로 명시적 재시도' })).not.toBeInTheDocument())
    expect(register).toHaveBeenCalledOnce()
  })
})
