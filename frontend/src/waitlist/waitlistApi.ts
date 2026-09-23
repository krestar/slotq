import { apiRequest } from '../auth'

export type EntryState = 'WAITING' | 'OFFERED' | 'FULFILLED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED'
export type OfferState = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED'
export type EntryAction = 'CANCEL'
export type OfferAction = 'ACCEPT' | 'REJECT'
export type WaitlistCode = 'VALIDATION_FAILED' | 'AUTHENTICATION_REQUIRED' | 'ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND' | 'IDEMPOTENCY_KEY_REUSED' | 'WAITLIST_DEMAND_NOT_ALLOWED'
  | 'WAITLIST_TRANSITION_NOT_ALLOWED' | 'OFFER_EXPIRED' | 'OFFER_TRANSITION_NOT_ALLOWED'
  | 'CAPACITY_UNAVAILABLE' | 'INTERNAL_ERROR' | 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'

export interface Entry {
  id: string
  venueId: string
  startsAt: string
  endsAt: string
  partySize: number
  joinedAt: string
  state: EntryState
  observedAt: string
  venueTimezone: string
  allowedActions: EntryAction[]
  offerId: string | null
}
export interface EntryPage { items: Entry[]; nextCursor: string | null; observedAt: string; venueTimezone: string }
export interface RegistrationReceipt { entryId: string; entryLocation: string; originalStatus: 200 | 201 }
export interface RegistrationResult extends RegistrationReceipt { entry: Entry }
export interface OfferReservation {
  id: string; state: string; startsAt: string; endsAt: string; expiresAt: string
  partySize: number; allocationQuantity: number; appliedPolicyVersion: number
}
export interface Offer {
  id: string; entryId: string; venueId: string; resourceId: string; slotInventoryId: string
  state: OfferState; terminalReason: string | null; expiresAt: string; observedAt: string
  venueTimezone: string; allowedActions: OfferAction[]; reservation: OfferReservation
}
export interface ManagementEntry extends Entry {
  eligibleForSlot?: boolean
  offerState?: OfferState
  offerExpiresAt?: string
  reservationId?: string
}
export interface ManagementPage extends Omit<EntryPage, 'items'> { items: ManagementEntry[] }

export class WaitlistApiError extends Error {
  constructor(readonly status: number, readonly code: WaitlistCode,
    readonly fieldErrors: Record<string, string> = {}) { super(code); this.name = 'WaitlistApiError' }
}
export class WaitlistMutationUnknown extends Error {
  constructor(readonly code: WaitlistCode) { super('WAITLIST_MUTATION_UNKNOWN'); this.name = 'WaitlistMutationUnknown' }
}

type Request = typeof apiRequest
const entryStates = new Set<EntryState>(['WAITING', 'OFFERED', 'FULFILLED', 'DECLINED', 'EXPIRED', 'CANCELLED'])
const offerStates = new Set<OfferState>(['PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED'])
const codes = new Set<WaitlistCode>([
  'VALIDATION_FAILED', 'AUTHENTICATION_REQUIRED', 'ACCESS_DENIED', 'RESOURCE_NOT_FOUND',
  'IDEMPOTENCY_KEY_REUSED', 'WAITLIST_DEMAND_NOT_ALLOWED', 'WAITLIST_TRANSITION_NOT_ALLOWED',
  'OFFER_EXPIRED', 'OFFER_TRANSITION_NOT_ALLOWED', 'CAPACITY_UNAVAILABLE', 'INTERNAL_ERROR',
])
const object = (value: unknown): Record<string, unknown> | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : undefined
const string = (value: unknown): value is string => typeof value === 'string' && value.length > 0
const instant = (value: unknown): value is string => string(value) && Number.isFinite(Date.parse(value))
const uuid = (value: unknown): value is string => string(value) && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0
const oneOf = <T extends string>(value: unknown, choices: Set<T>): value is T => choices.has(value as T)
const actions = <T extends string>(value: unknown, choices: readonly T[]): value is T[] =>
  Array.isArray(value) && value.every((item) => choices.includes(item))
const nullableUuid = (value: unknown): value is string | null => value === null || uuid(value)
const optional = (value: unknown, check: (value: unknown) => boolean) => value === undefined || check(value)

export function entryPath(venueId: string, entryId: string) {
  return `/api/v1/venues/${encodeURIComponent(venueId)}/waitlist-entries/${encodeURIComponent(entryId)}`
}
function root(venueId: string) { return `/api/v1/venues/${encodeURIComponent(venueId)}` }
function checkEntry(value: unknown, venueId: string): Entry {
  const x = object(value)
  if (!x || !uuid(x.id) || x.venueId !== venueId || !instant(x.startsAt) || !instant(x.endsAt)
    || !integer(x.partySize) || x.partySize < 1 || !instant(x.joinedAt)
    || !oneOf(x.state, entryStates) || !instant(x.observedAt) || !string(x.venueTimezone)
    || !actions(x.allowedActions, ['CANCEL']) || !nullableUuid(x.offerId)) throw new Error('Invalid Entry')
  return x as unknown as Entry
}
function checkPage(value: unknown, venueId: string): EntryPage {
  const x = object(value)
  if (!x || !Array.isArray(x.items) || !(x.nextCursor === null || string(x.nextCursor))
    || !instant(x.observedAt) || !string(x.venueTimezone)) throw new Error('Invalid Entry page')
  return { items: x.items.map((item) => checkEntry(item, venueId)), nextCursor: x.nextCursor,
    observedAt: x.observedAt, venueTimezone: x.venueTimezone }
}
function checkReceipt(value: unknown, venueId: string): RegistrationReceipt {
  const x = object(value)
  if (!x || !uuid(x.entryId) || x.entryLocation !== entryPath(venueId, x.entryId)
    || (x.originalStatus !== 200 && x.originalStatus !== 201)) throw new Error('Invalid registration receipt')
  return x as unknown as RegistrationReceipt
}
function checkOffer(value: unknown, venueId: string, offerId: string): Offer {
  const x = object(value)
  const r = object(x?.reservation)
  if (!x || x.id !== offerId || x.venueId !== venueId || !uuid(x.entryId) || !uuid(x.resourceId)
    || !uuid(x.slotInventoryId) || !oneOf(x.state, offerStates)
    || !(x.terminalReason === null || string(x.terminalReason)) || !instant(x.expiresAt)
    || !instant(x.observedAt) || !string(x.venueTimezone)
    || !actions(x.allowedActions, ['ACCEPT', 'REJECT']) || !r || !uuid(r.id)
    || !string(r.state) || !instant(r.startsAt) || !instant(r.endsAt) || !instant(r.expiresAt)
    || !integer(r.partySize) || !integer(r.allocationQuantity) || !integer(r.appliedPolicyVersion)) {
    throw new Error('Invalid Offer')
  }
  return x as unknown as Offer
}
function checkManagementPage(value: unknown, venueId: string): ManagementPage {
  const raw = object(value)
  if (!raw || !Array.isArray(raw.items)) throw new Error('Invalid management Entry page')
  // Management JSON omits nullable fields through NON_NULL serialization.
  const page = checkPage({ ...raw, items: raw.items.map((item) => ({ offerId: null, ...object(item) })) }, venueId)
  const items = page.items.map((entry) => {
    const x = entry as unknown as Record<string, unknown>
    if (!optional(x.eligibleForSlot, (v) => typeof v === 'boolean')
      || !optional(x.offerState, (v) => oneOf(v, offerStates))
      || !optional(x.offerExpiresAt, instant) || !optional(x.reservationId, uuid)) {
      throw new Error('Invalid management Entry')
    }
    return entry as ManagementEntry
  })
  return { ...page, items }
}
function statusMatches(status: number, code: WaitlistCode) {
  if (code === 'VALIDATION_FAILED') return status === 400
  if (code === 'AUTHENTICATION_REQUIRED') return status === 401
  if (code === 'ACCESS_DENIED') return status === 403
  if (code === 'RESOURCE_NOT_FOUND') return status === 404
  if (code === 'INTERNAL_ERROR') return status >= 500
  return status === 409
}
async function call<T>(request: Request, path: string, options: Parameters<Request>[1],
  validate: (value: unknown) => T, mutation = false): Promise<{ value: T; response: Response }> {
  let response: Response
  try { response = await request(path, options) }
  catch { throw mutation ? new WaitlistMutationUnknown('NETWORK_ERROR') : new WaitlistApiError(0, 'NETWORK_ERROR') }
  if (!response.ok) {
    let payload: Record<string, unknown> | undefined
    try { payload = object(await response.json()) } catch { /* preserve HTTP status */ }
    const candidate = payload?.code
    const code = string(candidate) && codes.has(candidate as WaitlistCode)
      && statusMatches(response.status, candidate as WaitlistCode)
      ? candidate as WaitlistCode : response.status >= 500 ? 'INTERNAL_ERROR' : 'UNEXPECTED_RESPONSE'
    if (mutation && (response.status >= 500 || code === 'UNEXPECTED_RESPONSE')) {
      throw new WaitlistMutationUnknown(code)
    }
    const errors = object(payload?.fieldErrors)
    throw new WaitlistApiError(response.status, code,
      errors ? Object.fromEntries(Object.entries(errors).filter((x): x is [string, string] => string(x[1]))) : {})
  }
  try { return { value: validate(await response.json()), response } }
  catch { throw mutation ? new WaitlistMutationUnknown('UNEXPECTED_RESPONSE')
    : new WaitlistApiError(response.status, 'UNEXPECTED_RESPONSE') }
}
const read = { access: 'protected' as const, cache: 'no-store' as const }

export interface WaitlistApi {
  register(venueId: string, slotInventoryId: string, partySize: number, key: string): Promise<RegistrationResult>
  registration(venueId: string, key: string): Promise<RegistrationReceipt>
  entries(venueId: string, date: string, cursor?: string): Promise<EntryPage>
  entry(venueId: string, entryId: string): Promise<Entry>
  cancel(venueId: string, entryId: string): Promise<Entry>
  offer(venueId: string, offerId: string): Promise<Offer>
  offerAction(venueId: string, offerId: string, action: 'accept' | 'reject'): Promise<Offer>
  managementEntries(venueId: string, date: string, slotInventoryId?: string, cursor?: string): Promise<ManagementPage>
  managementOffer(venueId: string, offerId: string): Promise<Offer>
}
export function createWaitlistApi(request: Request): WaitlistApi {
  return {
    async register(venueId, slotInventoryId, partySize, key) {
      const { value, response } = await call(request, `${root(venueId)}/waitlist-entries`, {
        access: 'protected', method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(30_000),
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify({ slotInventoryId, partySize }),
      }, (body) => checkEntry(body, venueId), true)
      const location = response.headers.get('Location')
      if ((response.status !== 200 && response.status !== 201)
        || location !== entryPath(venueId, value.id)) throw new WaitlistMutationUnknown('UNEXPECTED_RESPONSE')
      return { entry: value, entryId: value.id, entryLocation: location, originalStatus: response.status }
    },
    async registration(venueId, key) {
      return (await call(request, `${root(venueId)}/waitlist-registration-requests/${encodeURIComponent(key)}`,
        read, (body) => checkReceipt(body, venueId))).value
    },
    async entries(venueId, date, cursor) {
      const query = new URLSearchParams({ date })
      if (cursor) query.set('cursor', cursor)
      return (await call(request, `${root(venueId)}/waitlist-entries?${query}`, read,
        (body) => checkPage(body, venueId))).value
    },
    async entry(venueId, entryId) {
      return (await call(request, entryPath(venueId, entryId), read,
        (body) => { const entry = checkEntry(body, venueId); if (entry.id !== entryId) throw new Error('Entry ID mismatch'); return entry })).value
    },
    async cancel(venueId, entryId) {
      return (await call(request, `${entryPath(venueId, entryId)}/cancel`, {
        access: 'protected', method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(30_000),
      }, (body) => { const entry = checkEntry(body, venueId); if (entry.id !== entryId) throw new Error('Entry ID mismatch'); return entry }, true)).value
    },
    async offer(venueId, offerId) {
      return (await call(request, `${root(venueId)}/waitlist-offers/${encodeURIComponent(offerId)}`,
        read, (body) => checkOffer(body, venueId, offerId))).value
    },
    async offerAction(venueId, offerId, action) {
      return (await call(request, `${root(venueId)}/waitlist-offers/${encodeURIComponent(offerId)}/${action}`,
        { access: 'protected', method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(30_000) },
        (body) => checkOffer(body, venueId, offerId), true)).value
    },
    async managementEntries(venueId, date, slotInventoryId, cursor) {
      const query = new URLSearchParams({ date })
      if (slotInventoryId) query.set('slotInventoryId', slotInventoryId)
      if (cursor) query.set('cursor', cursor)
      return (await call(request, `/api/v1/management/venues/${encodeURIComponent(venueId)}/waitlist-entries?${query}`,
        read, (body) => checkManagementPage(body, venueId))).value
    },
    async managementOffer(venueId, offerId) {
      return (await call(request, `/api/v1/management/venues/${encodeURIComponent(venueId)}/waitlist-offers/${encodeURIComponent(offerId)}`,
        read, (body) => checkOffer(body, venueId, offerId))).value
    },
  }
}
export const waitlistApi = createWaitlistApi(apiRequest)
