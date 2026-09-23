import { localAuthSession } from '../auth'
import type { RegistrationReceipt } from './waitlistApi'

export interface RegistrationCommand {
  readonly venueId: string
  readonly slotInventoryId: string
  readonly partySize: number
  readonly idempotencyKey: string
}
export interface ActionCommand {
  readonly venueId: string
  readonly targetId: string
  readonly kind: 'cancel' | 'accept' | 'reject'
}
type Registration = { status: 'pending' | 'unknown' | 'known' | 'ready' | 'rejected'; command: RegistrationCommand; receipt?: RegistrationReceipt }
type Action = { status: 'pending' | 'unknown' | 'known' | 'ready'; command: ActionCommand }
interface Snapshot { registration?: Registration; action?: Action; epoch: number }

// One App mount owns this state. A reload or auth invalidation discards all retry material.
export function createWaitlistSession() {
  let snapshot: Snapshot = { epoch: 0 }
  const listeners = new Set<() => void>()
  const publish = (next: Snapshot) => {
    snapshot = next
    listeners.forEach((listener) => listener())
  }
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
    connect() {
      return localAuthSession.onInvalidate(() => publish({ epoch: snapshot.epoch + 1 }))
    },
    beginRegistration(input: Omit<RegistrationCommand, 'idempotencyKey'>) {
      if (snapshot.registration || snapshot.action) return undefined
      const command = Object.freeze({ ...input, idempotencyKey: crypto.randomUUID() })
      publish({ ...snapshot, registration: { status: 'pending', command } })
      return command
    },
    retryRegistration() {
      const registration = snapshot.registration
      if (registration?.status !== 'unknown') return undefined
      publish({ ...snapshot, registration: { ...registration, status: 'pending' } })
      return registration.command
    },
    registrationCurrent(command: RegistrationCommand) { return snapshot.registration?.command === command },
    markRegistration(command: RegistrationCommand, status: Registration['status'], receipt?: RegistrationReceipt) {
      if (snapshot.registration?.command !== command) return
      publish({ ...snapshot, registration: { ...snapshot.registration, status,
        receipt: receipt ?? snapshot.registration.receipt } })
    },
    clearRegistration() {
      if (snapshot.registration?.status === 'ready' || snapshot.registration?.status === 'rejected') {
        publish({ ...snapshot, registration: undefined })
      }
    },
    beginAction(input: ActionCommand) {
      if (snapshot.action || snapshot.registration?.status === 'pending') return undefined
      const command = Object.freeze({ ...input })
      publish({ ...snapshot, action: { status: 'pending', command } })
      return command
    },
    retryAction() {
      const action = snapshot.action
      if (action?.status !== 'unknown') return undefined
      publish({ ...snapshot, action: { ...action, status: 'pending' } })
      return action.command
    },
    actionCurrent(command: ActionCommand) { return snapshot.action?.command === command },
    markAction(command: ActionCommand, status: Action['status']) {
      if (snapshot.action?.command === command) publish({ ...snapshot, action: { ...snapshot.action, status } })
    },
    clearAction() {
      if (snapshot.action?.status === 'ready') publish({ ...snapshot, action: undefined })
    },
  }
}
export type WaitlistSession = ReturnType<typeof createWaitlistSession>
