import { apiRequest } from '../auth'

export type VenueStatus = 'ACTIVE' | 'INACTIVE'
export type ResourceStatus = 'ACTIVE' | 'INACTIVE'
export type ReservationState =
  | 'HELD' | 'CONFIRMED' | 'CHECKED_IN' | 'COMPLETED'
  | 'CANCELLED' | 'NO_SHOW' | 'EXPIRED'
export type ReservationAction = 'cancel' | 'check-in' | 'no-show' | 'complete'

export interface ManagementVenue {
  id: string
  name: string
  timezone: string
  status: VenueStatus
  currentPolicyVersion: number
  configurationWritable: boolean
}

export interface Policy {
  version: number
  slotDurationMinutes: number
  holdDurationMinutes: number
  cancellationCutoffMinutes: number
  noShowGraceMinutes: number
  createdAt?: string
}

export type PolicyInput = Pick<Policy,
  'slotDurationMinutes' | 'holdDurationMinutes' |
  'cancellationCutoffMinutes' | 'noShowGraceMinutes'>

export interface ManagementResource {
  id: string
  type: 'TABLE'
  name: string
  seatingCapacity: number
  status: ResourceStatus
}

export interface SlotInventory {
  id: string
  resourceId: string
  startsAt: string
  endsAt: string
  capacity: number
  appliedPolicyVersion: number
}

export interface ManagementReservation {
  id: string
  resourceId: string
  slotInventoryId: string
  state: ReservationState
  partySize: number
  startsAt: string
  endsAt: string
  expiresAt: string
  customerReference: string
  allowedActions: ReservationAction[]
}

export interface ReservationCommandRepresentation {
  id: string
  state: ReservationState
  startsAt: string
  endsAt: string
  expiresAt: string
  partySize: number
}

export interface ReservationDetails extends ReservationCommandRepresentation {
  venueId: string
  resourceId: string
  slotInventoryId: string
  allocationQuantity: number
  cancelAllowedUntil: string
  noShowEligibleAt: string
  appliedPolicyVersion: number
}

export type ManagementErrorCode =
  | 'VALIDATION_FAILED' | 'AUTHENTICATION_REQUIRED' | 'ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND' | 'INTERNAL_ERROR' | 'SLOT_INVENTORY_CONFLICT'
  | 'SLOT_INVENTORY_NOT_ALLOWED' | 'HOLD_EXPIRED'
  | 'CANCELLATION_WINDOW_CLOSED' | 'RESERVATION_TRANSITION_NOT_ALLOWED'
export type ManagementClientErrorCode = ManagementErrorCode | 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'

export class ManagementApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ManagementClientErrorCode,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(code)
    this.name = 'ManagementApiError'
  }
}

export class ManagementMutationResultUnknownError extends Error {
  constructor(readonly code: 'NETWORK_ERROR' | 'INTERNAL_ERROR') {
    super('MANAGEMENT_MUTATION_RESULT_UNKNOWN')
    this.name = 'ManagementMutationResultUnknownError'
  }
}

type ApiRequest = typeof apiRequest

export interface ManagementApi {
  listVenues(): Promise<ManagementVenue[]>
  getVenue(venueId: string): Promise<ManagementVenue>
  patchVenue(venueId: string, patch: { name?: string; status?: VenueStatus }): Promise<ManagementVenue>
  getPolicy(venueId: string): Promise<Policy>
  putPolicy(venueId: string, policy: PolicyInput): Promise<Policy>
  listResources(venueId: string): Promise<ManagementResource[]>
  createResource(venueId: string, input: { name: string; seatingCapacity: number }): Promise<ManagementResource>
  patchResource(venueId: string, resourceId: string, patch: {
    name?: string
    seatingCapacity?: number
    status?: ResourceStatus
  }): Promise<ManagementResource>
  listSlots(venueId: string, date: string): Promise<SlotInventory[]>
  createSlot(venueId: string, input: { resourceId: string; startsAt: string }): Promise<SlotInventory>
  listReservations(venueId: string, date: string, status?: ReservationState): Promise<ManagementReservation[]>
  getReservation(venueId: string, reservationId: string): Promise<ReservationDetails>
  commandReservation(
    venueId: string,
    reservationId: string,
    action: ReservationAction,
  ): Promise<ReservationCommandRepresentation>
}

const errorCodes = new Set<ManagementErrorCode>([
  'VALIDATION_FAILED', 'AUTHENTICATION_REQUIRED', 'ACCESS_DENIED', 'RESOURCE_NOT_FOUND',
  'INTERNAL_ERROR', 'SLOT_INVENTORY_CONFLICT', 'SLOT_INVENTORY_NOT_ALLOWED',
  'HOLD_EXPIRED', 'CANCELLATION_WINDOW_CLOSED', 'RESERVATION_TRANSITION_NOT_ALLOWED',
])

function matchesStatus(status: number, code: ManagementErrorCode): boolean {
  if (code === 'VALIDATION_FAILED') return status === 400
  if (code === 'AUTHENTICATION_REQUIRED') return status === 401
  if (code === 'ACCESS_DENIED') return status === 403
  if (code === 'RESOURCE_NOT_FOUND') return status === 404
  if (code === 'INTERNAL_ERROR') return status >= 500
  return status === 409
}

async function problem(response: Response): Promise<{
  code?: ManagementErrorCode
  fieldErrors: Record<string, string>
}> {
  try {
    const payload = await response.json() as { code?: unknown; fieldErrors?: unknown }
    const code = typeof payload.code === 'string' && errorCodes.has(payload.code as ManagementErrorCode)
      ? payload.code as ManagementErrorCode : undefined
    const fieldErrors = payload.fieldErrors && typeof payload.fieldErrors === 'object'
      && !Array.isArray(payload.fieldErrors)
      ? Object.fromEntries(Object.entries(payload.fieldErrors)
          .filter((entry): entry is [string, string] => typeof entry[1] === 'string'))
      : {}
    return { code, fieldErrors }
  } catch {
    return { fieldErrors: {} }
  }
}

async function requestJson<T>(
  request: ApiRequest,
  path: string,
  options: Parameters<ApiRequest>[1],
  mutation = false,
): Promise<T> {
  let response: Response
  try {
    response = await request(path, options)
  } catch {
    if (mutation) throw new ManagementMutationResultUnknownError('NETWORK_ERROR')
    throw new ManagementApiError(0, 'NETWORK_ERROR')
  }
  if (!response.ok) {
    const payload = await problem(response)
    if (mutation && response.status >= 500) {
      throw new ManagementMutationResultUnknownError('INTERNAL_ERROR')
    }
    const code = payload.code && matchesStatus(response.status, payload.code)
      ? payload.code
      : response.status >= 500 ? 'INTERNAL_ERROR' : 'UNEXPECTED_RESPONSE'
    throw new ManagementApiError(response.status, code, payload.fieldErrors)
  }
  try {
    return await response.json() as T
  } catch {
    if (mutation) throw new ManagementMutationResultUnknownError('NETWORK_ERROR')
    throw new ManagementApiError(response.status, 'UNEXPECTED_RESPONSE')
  }
}

const json = (body: unknown) => ({
  access: 'protected' as const,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export function createManagementApi(request: ApiRequest): ManagementApi {
  const venuePath = (venueId: string) =>
    `/api/v1/management/venues/${encodeURIComponent(venueId)}`
  return {
    listVenues: () => requestJson(request, '/api/v1/management/venues', {
      access: 'protected', cache: 'no-store',
    }),
    getVenue: (venueId) => requestJson(request, venuePath(venueId), {
      access: 'protected', cache: 'no-store',
    }),
    patchVenue: (venueId, patch) => requestJson(request, venuePath(venueId), {
      ...json(patch), method: 'PATCH',
    }, true),
    getPolicy: (venueId) => requestJson(request, `${venuePath(venueId)}/policy`, {
      access: 'protected', cache: 'no-store',
    }),
    putPolicy: (venueId, policy) => requestJson(request, `${venuePath(venueId)}/policy`, {
      ...json(policy), method: 'PUT',
    }, true),
    listResources: (venueId) => requestJson(request, `${venuePath(venueId)}/resources`, {
      access: 'protected', cache: 'no-store',
    }),
    createResource: (venueId, input) => requestJson(request, `${venuePath(venueId)}/resources`, {
      ...json(input), method: 'POST',
    }, true),
    patchResource: (venueId, resourceId, patch) => requestJson(
      request,
      `${venuePath(venueId)}/resources/${encodeURIComponent(resourceId)}`,
      { ...json(patch), method: 'PATCH' },
      true,
    ),
    listSlots: (venueId, date) => requestJson(
      request,
      `${venuePath(venueId)}/slot-inventories?${new URLSearchParams({ date })}`,
      { access: 'protected', cache: 'no-store' },
    ),
    createSlot: (venueId, input) => requestJson(request, `${venuePath(venueId)}/slot-inventories`, {
      ...json(input), method: 'POST',
    }, true),
    listReservations: (venueId, date, status) => {
      const query = new URLSearchParams({ date })
      if (status) query.set('status', status)
      return requestJson(request, `${venuePath(venueId)}/reservations?${query}`, {
        access: 'protected', cache: 'no-store',
      })
    },
    getReservation: (venueId, reservationId) => requestJson(
      request,
      `/api/v1/venues/${encodeURIComponent(venueId)}/reservations/${encodeURIComponent(reservationId)}`,
      { access: 'protected', cache: 'no-store' },
    ),
    commandReservation: (venueId, reservationId, action) => requestJson(
      request,
      `/api/v1/venues/${encodeURIComponent(venueId)}/reservations/${encodeURIComponent(reservationId)}/${action}`,
      { access: 'protected', method: 'POST' },
      true,
    ),
  }
}

export const managementApi = createManagementApi(apiRequest)
