import { describe, expect, it, vi } from 'vitest'
import { createWaitlistApi, entryPath, WaitlistApiError, WaitlistMutationUnknown } from './waitlistApi'

const venue = '10000000-0000-4000-8000-000000000001'
const slot = '20000000-0000-4000-8000-000000000002'
const entryId = '30000000-0000-4000-8000-000000000003'
const offerId = '40000000-0000-4000-8000-000000000004'
const instant = '2099-09-01T09:00:00Z'
const entry = { id: entryId, venueId: venue, startsAt: instant, endsAt: instant,
  partySize: 2, joinedAt: instant, state: 'WAITING', observedAt: instant,
  venueTimezone: 'Asia/Seoul', allowedActions: ['CANCEL'], offerId: null }
const offer = { id: offerId, entryId, venueId: venue, resourceId: slot, slotInventoryId: slot,
  state: 'PENDING', terminalReason: null, expiresAt: instant, observedAt: instant,
  venueTimezone: 'Asia/Seoul', allowedActions: ['ACCEPT', 'REJECT'],
  reservation: { id: slot, state: 'HELD', startsAt: instant, endsAt: instant,
    expiresAt: instant, partySize: 2, allocationQuantity: 1, appliedPolicyVersion: 1 } }
const response = (body: unknown, status = 200, location = entryPath(venue, entryId)) =>
  new Response(JSON.stringify(body), { status, headers: { Location: location } })

describe('Waitlist API contract', () => {
  it.each([201, 200])('preserves %s registration status, canonical identity and immutable request', async (status) => {
    const request = vi.fn().mockResolvedValue(response(entry, status))
    const result = await createWaitlistApi(request).register(venue, slot, 2, 'same-key')
    expect(result).toMatchObject({ entryId, entryLocation: entryPath(venue, entryId), originalStatus: status })
    const [path, options] = request.mock.calls[0]
    expect(path).toBe(`/api/v1/venues/${venue}/waitlist-entries`)
    expect(options).toMatchObject({ access: 'protected', method: 'POST', cache: 'no-store' })
    expect(new Headers(options.headers).get('Idempotency-Key')).toBe('same-key')
    expect(JSON.parse(options.body)).toEqual({ slotInventoryId: slot, partySize: 2 })
  })
  it.each([
    ['wrong Location', response(entry, 201, '/wrong')],
    ['wrong venue', response({ ...entry, venueId: slot }, 201)],
    ['wrong identity', response({ ...entry, id: slot }, 201)],
    ['invalid body', response({}, 201)],
    ['unrecognized conflict', response({ code: 'UNKNOWN' }, 409)],
    ['5xx', response({ code: 'INTERNAL_ERROR' }, 500)],
  ])('keeps %s mutation outcome unknown', async (_name, result) => {
    const request = vi.fn().mockResolvedValue(result)
    await expect(createWaitlistApi(request).register(venue, slot, 2, 'key'))
      .rejects.toBeInstanceOf(WaitlistMutationUnknown)
    expect(request).toHaveBeenCalledOnce()
  })
  it.each([[400, 'VALIDATION_FAILED'], [401, 'AUTHENTICATION_REQUIRED'],
    [403, 'ACCESS_DENIED'], [404, 'RESOURCE_NOT_FOUND'],
    [409, 'WAITLIST_DEMAND_NOT_ALLOWED'], [409, 'IDEMPOTENCY_KEY_REUSED']] as const)(
    'uses stable %s %s without detail parsing', async (status, code) => {
      const request = vi.fn().mockResolvedValue(response({ code, detail: 'irrelevant' }, status))
      await expect(createWaitlistApi(request).register(venue, slot, 2, 'key'))
        .rejects.toMatchObject({ status, code })
    })
  it('validates registration receipt and reads exact Entry with no-store', async () => {
    const request = vi.fn().mockResolvedValueOnce(response({ entryId, entryLocation: entryPath(venue, entryId), originalStatus: 200 }))
      .mockResolvedValueOnce(response(entry))
    const api = createWaitlistApi(request)
    expect(await api.registration(venue, 'key')).toMatchObject({ entryId, originalStatus: 200 })
    expect(await api.entry(venue, entryId)).toMatchObject({ id: entryId })
    expect(request.mock.calls.map(([path, options]) => [path, options.cache])).toEqual([
      [`/api/v1/venues/${venue}/waitlist-registration-requests/key`, 'no-store'],
      [entryPath(venue, entryId), 'no-store'],
    ])
  })
  it('treats key lookup 404 as a read result and rejects mismatched receipt', async () => {
    const request = vi.fn().mockResolvedValueOnce(response({ code: 'RESOURCE_NOT_FOUND' }, 404))
      .mockResolvedValueOnce(response({ entryId, entryLocation: '/wrong', originalStatus: 201 }))
    const api = createWaitlistApi(request)
    await expect(api.registration(venue, 'key')).rejects.toMatchObject({ code: 'RESOURCE_NOT_FOUND' })
    await expect(api.registration(venue, 'key')).rejects.toBeInstanceOf(WaitlistApiError)
  })
  it('uses bodyless/keyless action routes and exact Offer response identity', async () => {
    const request = vi.fn().mockResolvedValueOnce(response(offer))
      .mockResolvedValueOnce(response(offer)).mockResolvedValueOnce(response(entry))
    const api = createWaitlistApi(request)
    await api.offerAction(venue, offerId, 'accept')
    await api.offerAction(venue, offerId, 'reject')
    await api.cancel(venue, entryId)
    for (const [, options] of request.mock.calls) {
      expect(options.method).toBe('POST')
      expect(options.body).toBeUndefined()
      expect(new Headers(options.headers).get('Idempotency-Key')).toBeNull()
    }
    expect(request.mock.calls[0][0]).toContain(`/waitlist-offers/${offerId}/accept`)
    expect(request.mock.calls[1][0]).toContain(`/waitlist-offers/${offerId}/reject`)
  })
  it('accepts omitted nullable management fields and preserves server order/eligibility', async () => {
    const first = { ...entry }; delete (first as Partial<typeof entry>).offerId
    const second = { ...first, id: slot, state: 'OFFERED', eligibleForSlot: false,
      offerState: 'PENDING', offerExpiresAt: instant, reservationId: slot }
    const request = vi.fn().mockResolvedValue(response({ items: [first, second], nextCursor: null,
      observedAt: instant, venueTimezone: 'Asia/Seoul' }))
    const result = await createWaitlistApi(request).managementEntries(venue, '2099-09-01', slot)
    expect(result.items.map((item) => item.id)).toEqual([entryId, slot])
    expect(result.items[1].eligibleForSlot).toBe(false)
    expect(result.items[0].offerId).toBeNull()
    expect(request.mock.calls[0][0]).toContain(`slotInventoryId=${slot}`)
  })
  it.each(['OFFER_EXPIRED', 'OFFER_TRANSITION_NOT_ALLOWED', 'CAPACITY_UNAVAILABLE'] as const)(
    'keeps stable %s conflict for exact reconciliation', async (code) => {
      const request = vi.fn().mockResolvedValue(response({ code }, 409))
      await expect(createWaitlistApi(request).offerAction(venue, offerId, 'accept'))
        .rejects.toMatchObject({ status: 409, code })
    })
})
