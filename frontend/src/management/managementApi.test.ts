import { describe, expect, it, vi } from 'vitest'
import {
  ManagementMutationResultUnknownError,
  createManagementApi,
} from './managementApi'

const ok = (body: unknown = {}) => new Response(JSON.stringify(body), {
  status: 200,
  headers: { 'Content-Type': 'application/json' },
})

describe('management API contract', () => {
  it('uses protected access and exact management paths, queries, and bodies', async () => {
    const request = vi.fn().mockImplementation(() => Promise.resolve(ok([])))
    const api = createManagementApi(request)

    await api.listVenues()
    await api.getVenue('venue/a')
    await api.patchVenue('venue/a', { name: 'Renamed', status: 'INACTIVE' })
    await api.getPolicy('venue/a')
    await api.putPolicy('venue/a', {
      slotDurationMinutes: 30,
      holdDurationMinutes: 5,
      cancellationCutoffMinutes: 60,
      noShowGraceMinutes: 15,
    })
    await api.listResources('venue/a')
    await api.createResource('venue/a', { name: 'Table 2', seatingCapacity: 4 })
    await api.patchResource('venue/a', 'resource/a', { status: 'INACTIVE' })
    await api.listSlots('venue/a', '2026-08-31')
    await api.createSlot('venue/a', {
      resourceId: 'resource/a', startsAt: '2026-08-31T18:00:00+09:00',
    })
    await api.listReservations('venue/a', '2026-08-31', 'CONFIRMED')
    await api.getReservation('venue/a', 'reservation/a')
    await api.commandReservation('venue/a', 'reservation/a', 'check-in')

    expect(request.mock.calls.every(([, options]) => options.access === 'protected')).toBe(true)
    expect(request.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/management/venues',
      '/api/v1/management/venues/venue%2Fa',
      '/api/v1/management/venues/venue%2Fa',
      '/api/v1/management/venues/venue%2Fa/policy',
      '/api/v1/management/venues/venue%2Fa/policy',
      '/api/v1/management/venues/venue%2Fa/resources',
      '/api/v1/management/venues/venue%2Fa/resources',
      '/api/v1/management/venues/venue%2Fa/resources/resource%2Fa',
      '/api/v1/management/venues/venue%2Fa/slot-inventories?date=2026-08-31',
      '/api/v1/management/venues/venue%2Fa/slot-inventories',
      '/api/v1/management/venues/venue%2Fa/reservations?date=2026-08-31&status=CONFIRMED',
      '/api/v1/venues/venue%2Fa/reservations/reservation%2Fa',
      '/api/v1/venues/venue%2Fa/reservations/reservation%2Fa/check-in',
    ])
    expect(request.mock.calls[11][1]).toMatchObject({
      access: 'protected', cache: 'no-store',
    })
    expect(JSON.parse(request.mock.calls[2][1].body)).toEqual({ name: 'Renamed', status: 'INACTIVE' })
    expect(JSON.parse(request.mock.calls[4][1].body)).toEqual({
      slotDurationMinutes: 30,
      holdDurationMinutes: 5,
      cancellationCutoffMinutes: 60,
      noShowGraceMinutes: 15,
    })
    expect(JSON.parse(request.mock.calls[6][1].body)).toEqual({ name: 'Table 2', seatingCapacity: 4 })
    expect(JSON.parse(request.mock.calls[9][1].body)).toEqual({
      resourceId: 'resource/a', startsAt: '2026-08-31T18:00:00+09:00',
    })
    expect(JSON.stringify(request.mock.calls))
      .not.toMatch(/tenantId|principalId|fixtureKey|venueGrant|TenantRole/i)
    expect(request.mock.calls[9][1].body).not.toContain('capacity')
  })

  it.each([
    [400, 'VALIDATION_FAILED'], [401, 'AUTHENTICATION_REQUIRED'], [403, 'ACCESS_DENIED'],
    [404, 'RESOURCE_NOT_FOUND'], [409, 'SLOT_INVENTORY_CONFLICT'],
    [409, 'SLOT_INVENTORY_NOT_ALLOWED'], [409, 'HOLD_EXPIRED'],
    [409, 'CANCELLATION_WINDOW_CLOSED'], [409, 'RESERVATION_TRANSITION_NOT_ALLOWED'],
    [500, 'INTERNAL_ERROR'],
  ] as const)('maps HTTP %s and stable code %s without parsing detail', async (status, code) => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code, detail: 'unstable prose', fieldErrors: { name: 'required' },
    }), { status, headers: { 'Content-Type': 'application/problem+json' } }))
    const api = createManagementApi(request)

    await expect(api.listVenues()).rejects.toMatchObject({
      status, code, fieldErrors: { name: 'required' },
    })
  })

  it('does not automatically retry a mutation after a network or server failure', async () => {
    const networkRequest = vi.fn().mockRejectedValue(new TypeError('timeout'))
    await expect(createManagementApi(networkRequest).createResource('venue', {
      name: 'Table', seatingCapacity: 4,
    })).rejects.toBeInstanceOf(ManagementMutationResultUnknownError)
    expect(networkRequest).toHaveBeenCalledOnce()

    const serverRequest = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'INTERNAL_ERROR',
    }), { status: 500 }))
    await expect(createManagementApi(serverRequest).createSlot('venue', {
      resourceId: 'resource', startsAt: '2026-08-31T18:00:00+09:00',
    })).rejects.toBeInstanceOf(ManagementMutationResultUnknownError)
    expect(serverRequest).toHaveBeenCalledOnce()
  })
})
