import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ManagementWaitlistView } from './ManagementWaitlistView'
import { WaitlistApiError, type ManagementEntry, type Offer, type WaitlistApi } from './waitlistApi'
import { localAuthSession } from '../auth'

const venue = '10000000-0000-4000-8000-000000000001'
const slot = '20000000-0000-4000-8000-000000000002'
const instant = '2099-09-01T09:00:00Z'
const first: ManagementEntry = { id: '30000000-0000-4000-8000-000000000003', venueId: venue,
  startsAt: instant, endsAt: instant, partySize: 5, joinedAt: instant, state: 'WAITING',
  observedAt: instant, venueTimezone: 'Asia/Seoul', allowedActions: [], offerId: null, eligibleForSlot: false }
const second: ManagementEntry = { ...first, id: '40000000-0000-4000-8000-000000000004', partySize: 2,
  state: 'OFFERED', offerId: '50000000-0000-4000-8000-000000000005', eligibleForSlot: true,
  offerState: 'PENDING', offerExpiresAt: instant, reservationId: slot }
const offer: Offer = { id: second.offerId!, entryId: second.id, venueId: venue,
  resourceId: slot, slotInventoryId: slot, state: 'PENDING', terminalReason: null,
  expiresAt: instant, observedAt: instant, venueTimezone: 'Asia/Seoul', allowedActions: [],
  reservation: { id: slot, state: 'HELD', startsAt: instant, endsAt: instant, expiresAt: instant,
    partySize: 2, allocationQuantity: 1, appliedPolicyVersion: 1 } }
const api = { managementEntries: vi.fn().mockResolvedValue({ items: [first, second], nextCursor: null,
  observedAt: instant, venueTimezone: 'Asia/Seoul' }), managementOffer: vi.fn().mockResolvedValue(offer) } as unknown as WaitlistApi

describe('management Waitlist read', () => {
  it('uses server order, slot eligibility, and management exact Offer without command controls', async () => {
    render(<ManagementWaitlistView venueId={venue} date="2099-09-01" slots={[{
      id: slot, resourceId: slot, startsAt: instant, endsAt: instant, capacity: 1, appliedPolicyVersion: 1,
    }]} api={api} />)
    await screen.findByText(/선행 수요가 이 Slot에 부적합/)
    fireEvent.change(screen.getByRole('combobox', { name: 'Slot 필터' }), { target: { value: slot } })
    await waitFor(() => expect(api.managementEntries).toHaveBeenLastCalledWith(venue, '2099-09-01', slot, undefined))
    const rows = await screen.findAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('부적합')
    expect(rows[1]).toHaveTextContent('적합')
    fireEvent.click(screen.getByRole('button', { name: 'Offer 업무 상세 조회' }))
    expect(await screen.findByRole('article', { name: 'Offer 업무 상세' })).toHaveTextContent(slot)
    expect(screen.queryByRole('button', { name: /수락|거절|강제/ })).not.toBeInTheDocument()
  })
  it('does not turn scoped 403 into empty state', async () => {
    const denied = { ...api, managementEntries: vi.fn().mockRejectedValue(new WaitlistApiError(403, 'ACCESS_DENIED')) }
    render(<ManagementWaitlistView venueId={venue} date="2099-09-01" slots={[]} api={denied} />)
    expect(await screen.findByText(/ACCESS_DENIED/)).toBeInTheDocument()
    expect(screen.queryByText('이 날짜의 Waitlist Entry가 없습니다.')).not.toBeInTheDocument()
  })
  it('drops the previous scoped response when authentication changes', async () => {
    let resolveFirst!: (value: { items: ManagementEntry[]; nextCursor: null; observedAt: string; venueTimezone: string }) => void
    const pending = new Promise<{
      items: ManagementEntry[]; nextCursor: null; observedAt: string; venueTimezone: string
    }>((resolve) => { resolveFirst = resolve })
    const request = vi.fn().mockReturnValueOnce(pending).mockResolvedValue({
      items: [], nextCursor: null, observedAt: instant, venueTimezone: 'Asia/Seoul',
    })
    render(<ManagementWaitlistView venueId={venue} date="2099-09-01" slots={[]} api={{
      ...api, managementEntries: request,
    }} />)
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    localAuthSession.invalidate()
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
    resolveFirst({ items: [first], nextCursor: null, observedAt: instant, venueTimezone: 'Asia/Seoul' })
    await screen.findByText('이 날짜의 Waitlist Entry가 없습니다.')
    expect(screen.queryByText(`Entry ${first.id}`)).not.toBeInTheDocument()
  })
})
