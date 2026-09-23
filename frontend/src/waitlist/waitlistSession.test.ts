import { describe, expect, it } from 'vitest'
import { localAuthSession } from '../auth'
import { createWaitlistSession } from './waitlistSession'

describe('Waitlist App-session intent', () => {
  it('keeps the same immutable registration key/body across explicit retry without a HOLD retention clock', () => {
    const session = createWaitlistSession()
    const command = session.beginRegistration({ venueId: 'venue', slotInventoryId: 'slot', partySize: 2 })!
    expect(Object.isFrozen(command)).toBe(true)
    expect(session.beginRegistration({ venueId: 'venue', slotInventoryId: 'other', partySize: 3 })).toBeUndefined()
    session.markRegistration(command, 'unknown')
    expect(session.retryRegistration()).toBe(command)
    expect(session.getSnapshot().registration?.command).toBe(command)
    session.markRegistration(command, 'known', { entryId: 'entry', entryLocation: '/entry', originalStatus: 200 })
    session.markRegistration(command, 'ready')
    session.clearRegistration()
    expect(session.beginRegistration({ venueId: 'venue', slotInventoryId: 'other', partySize: 3 })?.idempotencyKey)
      .not.toBe(command.idempotencyKey)
  })
  it('drops registration and action retry context when auth invalidates', () => {
    const session = createWaitlistSession()
    const disconnect = session.connect()
    const command = session.beginRegistration({ venueId: 'venue', slotInventoryId: 'slot', partySize: 2 })!
    session.markRegistration(command, 'unknown')
    const epoch = session.getSnapshot().epoch
    localAuthSession.invalidate()
    expect(session.getSnapshot()).toEqual({ epoch: epoch + 1 })
    expect(session.retryRegistration()).toBeUndefined()
    expect(session.registrationCurrent(command)).toBe(false)
    const action = session.beginAction({ venueId: 'venue', targetId: 'offer', kind: 'accept' })!
    session.markAction(action, 'unknown')
    localAuthSession.invalidate()
    expect(session.retryAction()).toBeUndefined()
    disconnect()
  })
  it('blocks competing Offer actions while one target has an unresolved result', () => {
    const session = createWaitlistSession()
    const command = session.beginAction({ venueId: 'venue', targetId: 'offer', kind: 'accept' })!
    session.markAction(command, 'unknown')
    expect(session.beginAction({ venueId: 'venue', targetId: 'offer', kind: 'reject' })).toBeUndefined()
    expect(session.retryAction()).toBe(command)
    session.markAction(command, 'ready')
    session.clearAction()
    expect(session.beginAction({ venueId: 'venue', targetId: 'offer', kind: 'reject' })).toBeDefined()
  })
})
