import { describe, expect, it, vi } from 'vitest'
import type { ApiRequestOptions } from '../auth/apiClient'
import {
  CustomerApiError,
  MutationResultUnknownError,
  createCustomerReservationApi,
} from './customerReservationApi'

function response(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

describe('customer reservation API', () => {
  it('uses public #12 reads and sends only the contracted HOLD fields', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(response([{ id: 'venue-a', name: 'Venue A', timezone: 'UTC' }]))
      .mockResolvedValueOnce(response({ venueId: 'venue-a', timezone: 'UTC', date: '2026-09-01', items: [] }))
      .mockResolvedValueOnce(response({ id: 'reservation-a', state: 'HELD' }, 201))
    const api = createCustomerReservationApi(request)

    await api.listVenues()
    await api.getAvailability('venue-a', '2026-09-01', 2)
    await api.createHold('venue-a', 'slot-a', 2)

    expect(request.mock.calls[0]).toEqual(['/api/v1/venues', { access: 'public' }])
    expect(request.mock.calls[1]).toEqual([
      '/api/v1/venues/venue-a/availability?date=2026-09-01&partySize=2',
      { access: 'public' },
    ])
    const [holdPath, holdOptions] = request.mock.calls[2] as [string, ApiRequestOptions]
    expect(holdPath).toBe('/api/v1/venues/venue-a/reservations/holds')
    expect(holdOptions.access).toBe('protected')
    expect(holdOptions.method).toBe('POST')
    expect(JSON.parse(String(holdOptions.body))).toEqual({ slotInventoryId: 'slot-a', partySize: 2 })
    expect(new Headers(holdOptions.headers).has('Idempotency-Key')).toBe(false)
    expect(String(holdOptions.body)).not.toMatch(/tenantId|customerId|resourceId|expiresAt|quantity|role/i)
  })

  it('uses protected #13/#14 endpoints without mutation retry metadata', async () => {
    const request = vi.fn().mockResolvedValue(response({ id: 'reservation-a', state: 'CONFIRMED' }))
    const api = createCustomerReservationApi(request)

    await api.getReservation('venue-a', 'reservation-a')
    await api.confirmReservation('venue-a', 'reservation-a')
    await api.cancelReservation('venue-a', 'reservation-a')

    expect(request.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/venues/venue-a/reservations/reservation-a',
      '/api/v1/venues/venue-a/reservations/reservation-a/confirm',
      '/api/v1/venues/venue-a/reservations/reservation-a/cancel',
    ])
    for (const [, options] of request.mock.calls.slice(1) as [string, ApiRequestOptions][]) {
      expect(options).toEqual({ access: 'protected', method: 'POST' })
    }
  })

  it('branches on HTTP status and stable code without parsing detail text', async () => {
    const request = vi.fn().mockResolvedValue(response({
      status: 409,
      code: 'CAPACITY_UNAVAILABLE',
      detail: 'arbitrary localized text that must not drive behavior',
    }, 409))
    const api = createCustomerReservationApi(request)

    await expect(api.createHold('venue-a', 'slot-a', 2)).rejects.toMatchObject({
      status: 409,
      code: 'CAPACITY_UNAVAILABLE',
    })
  })

  it('preserves validation field errors for accessible form feedback', async () => {
    const request = vi.fn().mockResolvedValue(response({
      code: 'VALIDATION_FAILED',
      fieldErrors: { partySize: 'must be greater than or equal to 1' },
    }, 400))
    const api = createCustomerReservationApi(request)

    await expect(api.getAvailability('venue-a', '2026-09-01', 0)).rejects.toEqual(
      new CustomerApiError(400, 'VALIDATION_FAILED', {
        partySize: 'must be greater than or equal to 1',
      }),
    )
  })

  it.each([
    ['confirm timeout', () => Promise.reject(new TypeError('network timeout'))],
    ['confirm 5xx', () => Promise.resolve(response({ code: 'INTERNAL_ERROR' }, 500))],
  ])('treats %s as result-unknown and never retries automatically', async (_name, result) => {
    const request = vi.fn(result)
    const api = createCustomerReservationApi(request)

    await expect(api.confirmReservation('venue-a', 'reservation-a'))
      .rejects.toBeInstanceOf(MutationResultUnknownError)
    expect(request).toHaveBeenCalledOnce()
  })

  it('keeps a failed read retryable without classifying it as a mutation result', async () => {
    const request = vi.fn().mockRejectedValue(new TypeError('offline'))
    const api = createCustomerReservationApi(request)

    await expect(api.getReservation('venue-a', 'reservation-a')).rejects.toMatchObject({
      status: 0,
      code: 'NETWORK_ERROR',
    })
    expect(request).toHaveBeenCalledOnce()
  })
})
