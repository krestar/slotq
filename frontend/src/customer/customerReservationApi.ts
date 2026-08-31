import { apiRequest } from '../auth'
import type { ReservationStatus } from '../components'

export interface VenueSummary {
  id: string
  name: string
  timezone: string
}

export interface AvailabilityItem {
  slotInventoryId: string
  resourceId: string
  resourceName: string
  startsAt: string
  endsAt: string
  seatingCapacity: number
  capacity: number
  occupied: number
  available: number
}

export interface Availability {
  venueId: string
  timezone: string
  date: string
  items: AvailabilityItem[]
}

export interface Reservation {
  id: string
  venueId: string
  resourceId: string
  slotInventoryId: string
  state: ReservationStatus
  partySize: number
  allocationQuantity: number
  startsAt: string
  endsAt: string
  expiresAt: string
  cancelAllowedUntil: string
  noShowEligibleAt: string
  appliedPolicyVersion: number
}

export type ProductErrorCode =
  | 'VALIDATION_FAILED'
  | 'AUTHENTICATION_REQUIRED'
  | 'ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND'
  | 'CAPACITY_UNAVAILABLE'
  | 'PARTY_SIZE_NOT_SUPPORTED'
  | 'BOOKING_NOT_ALLOWED'
  | 'HOLD_EXPIRED'
  | 'CANCELLATION_WINDOW_CLOSED'
  | 'RESERVATION_TRANSITION_NOT_ALLOWED'
  | 'INTERNAL_ERROR'

export type ClientErrorCode = ProductErrorCode | 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'

interface ProblemPayload {
  status?: unknown
  code?: unknown
  fieldErrors?: unknown
}

export class CustomerApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ClientErrorCode,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(code)
    this.name = 'CustomerApiError'
  }
}

export class MutationResultUnknownError extends Error {
  constructor(readonly code: ProductErrorCode | 'NETWORK_ERROR') {
    super('MUTATION_RESULT_UNKNOWN')
    this.name = 'MutationResultUnknownError'
  }
}

type ApiRequest = typeof apiRequest

export interface CustomerReservationApi {
  listVenues(): Promise<VenueSummary[]>
  getAvailability(venueId: string, date: string, partySize: number): Promise<Availability>
  createHold(venueId: string, slotInventoryId: string, partySize: number): Promise<Reservation>
  getReservation(venueId: string, reservationId: string): Promise<Reservation>
  confirmReservation(venueId: string, reservationId: string): Promise<Reservation>
  cancelReservation(venueId: string, reservationId: string): Promise<Reservation>
}

const productErrorCodes = new Set<ProductErrorCode>([
  'VALIDATION_FAILED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'RESOURCE_NOT_FOUND',
  'CAPACITY_UNAVAILABLE',
  'PARTY_SIZE_NOT_SUPPORTED',
  'BOOKING_NOT_ALLOWED',
  'HOLD_EXPIRED',
  'CANCELLATION_WINDOW_CLOSED',
  'RESERVATION_TRANSITION_NOT_ALLOWED',
  'INTERNAL_ERROR',
])

function productErrorCode(value: unknown): ProductErrorCode | undefined {
  return typeof value === 'string' && productErrorCodes.has(value as ProductErrorCode)
    ? value as ProductErrorCode
    : undefined
}

function codeMatchesStatus(status: number, code: ProductErrorCode): boolean {
  if (code === 'VALIDATION_FAILED') return status === 400
  if (code === 'AUTHENTICATION_REQUIRED') return status === 401
  if (code === 'ACCESS_DENIED') return status === 403
  if (code === 'RESOURCE_NOT_FOUND') return status === 404
  if (code === 'INTERNAL_ERROR') return status >= 500
  return status === 409
}

function fieldErrors(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {}
  }
  return Object.fromEntries(
    Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
  )
}

async function parseProblem(response: Response): Promise<{
  code: ProductErrorCode | undefined
  fieldErrors: Record<string, string>
}> {
  try {
    const payload = await response.json() as ProblemPayload
    return {
      code: productErrorCode(payload.code),
      fieldErrors: fieldErrors(payload.fieldErrors),
    }
  } catch {
    return { code: undefined, fieldErrors: {} }
  }
}

async function requestJson<T>(
  request: ApiRequest,
  path: string,
  options: Parameters<ApiRequest>[1],
  mutation: boolean,
): Promise<T> {
  let response: Response
  try {
    response = await request(path, options)
  } catch (cause) {
    if (mutation) {
      throw new MutationResultUnknownError('NETWORK_ERROR')
    }
    throw new CustomerApiError(0, 'NETWORK_ERROR')
  }

  if (!response.ok) {
    const problem = await parseProblem(response)
    if (mutation && response.status >= 500) {
      throw new MutationResultUnknownError(problem.code ?? 'INTERNAL_ERROR')
    }
    const matchedCode = problem.code && codeMatchesStatus(response.status, problem.code)
      ? problem.code
      : undefined
    throw new CustomerApiError(
      response.status,
      matchedCode ?? (response.status >= 500 ? 'INTERNAL_ERROR' : 'UNEXPECTED_RESPONSE'),
      problem.fieldErrors,
    )
  }

  try {
    return await response.json() as T
  } catch (cause) {
    if (mutation) {
      throw new MutationResultUnknownError('NETWORK_ERROR')
    }
    throw new CustomerApiError(response.status, 'UNEXPECTED_RESPONSE')
  }
}

export function createCustomerReservationApi(request: ApiRequest): CustomerReservationApi {
  return {
    listVenues() {
      return requestJson<VenueSummary[]>(request, '/api/v1/venues', { access: 'public' }, false)
    },
    getAvailability(venueId, date, partySize) {
      const query = new URLSearchParams({ date, partySize: String(partySize) })
      return requestJson<Availability>(
        request,
        `/api/v1/venues/${encodeURIComponent(venueId)}/availability?${query}`,
        { access: 'public' },
        false,
      )
    },
    createHold(venueId, slotInventoryId, partySize) {
      return requestJson<Reservation>(
        request,
        `/api/v1/venues/${encodeURIComponent(venueId)}/reservations/holds`,
        {
          access: 'protected',
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ slotInventoryId, partySize }),
        },
        true,
      )
    },
    getReservation(venueId, reservationId) {
      return requestJson<Reservation>(
        request,
        `/api/v1/venues/${encodeURIComponent(venueId)}/reservations/${encodeURIComponent(reservationId)}`,
        { access: 'protected', cache: 'no-store' },
        false,
      )
    },
    confirmReservation(venueId, reservationId) {
      return requestJson<Reservation>(
        request,
        `/api/v1/venues/${encodeURIComponent(venueId)}/reservations/${encodeURIComponent(reservationId)}/confirm`,
        { access: 'protected', method: 'POST' },
        true,
      )
    },
    cancelReservation(venueId, reservationId) {
      return requestJson<Reservation>(
        request,
        `/api/v1/venues/${encodeURIComponent(venueId)}/reservations/${encodeURIComponent(reservationId)}/cancel`,
        { access: 'protected', method: 'POST' },
        true,
      )
    },
  }
}

export const customerReservationApi = createCustomerReservationApi(apiRequest)
