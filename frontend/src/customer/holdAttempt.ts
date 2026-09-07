import { localAuthSession } from '../auth'

// Less than the server's 24h completedAt retention; measured from first submit.
export const HOLD_RETRY_WINDOW_MS = 23 * 60 * 60 * 1000

export interface HoldCommand {
  readonly idempotencyKey: string
  readonly venueId: string
  readonly slotInventoryId: string
  readonly partySize: number
}

type HoldAttempt =
  | { readonly status: 'idle' | 'abandoned' | 'unavailable' }
  | { readonly status: 'pending' | 'unknown'; readonly command: HoldCommand }

// One App runtime owns this HOLD-only memory. Nothing is written to browser storage.
export function createHoldAttemptMemory() {
  let snapshot: HoldAttempt = { status: 'idle' }
  let deadline = 0
  let wallDeadline = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  const listeners = new Set<() => void>()
  function update(next: HoldAttempt) {
    snapshot = next
    listeners.forEach((listener) => listener())
  }
  function clear(status: 'idle' | 'abandoned' | 'unavailable') {
    clearTimeout(timer)
    update({ status })
  }
  function current(command: HoldCommand) {
    return 'command' in snapshot && snapshot.command === command
  }
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    connect() {
      const disconnect = localAuthSession.onInvalidate(() => {
        if ('command' in snapshot) clear('unavailable')
      })
      return () => {
        disconnect()
        clearTimeout(timer)
      }
    },
    begin(input: Omit<HoldCommand, 'idempotencyKey'>): HoldCommand | undefined {
      if ('command' in snapshot || snapshot.status === 'unavailable') return undefined
      const command = Object.freeze({ ...input, idempotencyKey: crypto.randomUUID() })
      deadline = performance.now() + HOLD_RETRY_WINDOW_MS
      wallDeadline = Date.now() + HOLD_RETRY_WINDOW_MS
      timer = setTimeout(() => clear('unavailable'), HOLD_RETRY_WINDOW_MS)
      update({ status: 'pending', command })
      return command
    },
    retry(): HoldCommand | undefined {
      if (snapshot.status !== 'unknown') return undefined
      // Some browsers pause performance.now() during OS sleep. Keep both limits:
      // wall time accounts for sleep, monotonic time resists wall-clock rollback.
      if (Date.now() >= wallDeadline || performance.now() >= deadline) {
        clear('unavailable')
        return undefined
      }
      const command = snapshot.command
      update({ status: 'pending', command })
      return command
    },
    unknown(command: HoldCommand) {
      if (current(command)) update({ status: 'unknown', command })
    },
    current,
    finish(command: HoldCommand, definitive = false) {
      // A 401 invalidates auth before the command response reaches the UI.
      // The in-flight UI guard still owns this command until its response settles.
      if (current(command) || (definitive && snapshot.status === 'unavailable')) clear('idle')
    },
    abandon() {
      if (snapshot.status !== 'pending') clear('abandoned')
    },
  }
}

export type HoldAttemptMemory = ReturnType<typeof createHoldAttemptMemory>
