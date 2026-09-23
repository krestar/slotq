import { useEffect, useRef, useState } from 'react'
import { createHoldAttemptMemory } from './customer/holdAttempt'
import { createWaitlistSession } from './waitlist/waitlistSession'
import { CustomerWaitlistFlow, type WaitlistSelection } from './waitlist/CustomerWaitlistFlow'
import type { WaitlistApi } from './waitlist/waitlistApi'
import { Button } from './components'
import {
  CustomerReservationFlow,
  type CustomerReservationFlowProps,
} from './customer/CustomerReservationFlow'
import {
  ManagementVenueFlow,
  type ManagementVenueFlowProps,
} from './management/ManagementVenueFlow'

interface AppProps {
  api?: CustomerReservationFlowProps['api']
  managementApi?: ManagementVenueFlowProps['api']
  waitlistApi?: WaitlistApi
}

type Surface = 'customer' | 'waitlist' | 'management'

function route(): { surface: Surface; selection: WaitlistSelection } {
  const query = new URLSearchParams(window.location.search)
  const surface = query.get('view') === 'waitlist' ? 'waitlist'
    : query.get('view') === 'management' ? 'management' : 'customer'
  const selection: WaitlistSelection = {}
  if (surface === 'waitlist') {
    for (const field of ['venueId', 'date', 'entryId', 'offerId'] as const) {
      const value = query.get(field)
      if (value) selection[field] = value
    }
  }
  return { surface, selection }
}

function urlFor(surface: Surface, selection: WaitlistSelection) {
  const url = new URL(window.location.href)
  for (const key of ['view', 'venueId', 'date', 'entryId', 'offerId']) url.searchParams.delete(key)
  if (surface !== 'customer') url.searchParams.set('view', surface)
  if (surface === 'waitlist') {
    for (const field of ['venueId', 'date', 'entryId', 'offerId'] as const) {
      if (selection[field]) url.searchParams.set(field, selection[field])
    }
  }
  return `${url.pathname}${url.search}${url.hash}`
}

export function App({ api, managementApi, waitlistApi }: AppProps) {
  const [initialRoute] = useState(route)
  const [surface, setSurface] = useState<Surface>(initialRoute.surface)
  const [selection, setSelection] = useState<WaitlistSelection>(initialRoute.selection)
  const [reservationTarget, setReservationTarget] = useState<{ venueId: string; reservationId: string }>()
  const [navigationLocked, setNavigationLocked] = useState(false)
  const lockRef = useRef(false)
  const [holdAttempts] = useState(createHoldAttemptMemory)
  const [waitlistSession] = useState(createWaitlistSession)
  useEffect(() => holdAttempts.connect(), [holdAttempts])
  useEffect(() => waitlistSession.connect(), [waitlistSession])
  useEffect(() => { lockRef.current = navigationLocked }, [navigationLocked])
  useEffect(() => {
    const onPopState = () => {
      if (lockRef.current) { window.history.pushState(null, '', urlFor(surface, selection)); return }
      const next = route()
      setSurface(next.surface); setSelection(next.selection); setReservationTarget(undefined)
    }
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!lockRef.current) return
      event.preventDefault(); event.returnValue = ''
    }
    window.addEventListener('popstate', onPopState)
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => { window.removeEventListener('popstate', onPopState); window.removeEventListener('beforeunload', onBeforeUnload) }
  }, [surface, selection])
  function navigate(next: Surface, nextSelection = selection) {
    if (lockRef.current) return
    setSurface(next)
    setSelection(nextSelection)
    if (next !== 'customer') setReservationTarget(undefined)
    window.history.pushState(null, '', urlFor(next, nextSelection))
  }
  function updateSelection(next: WaitlistSelection) {
    setSelection(next)
    if (surface === 'waitlist') window.history.replaceState(null, '', urlFor('waitlist', next))
  }
  const navigation = (
    <nav className="surface-navigation" aria-label="Product surface">
      <Button
        density="compact"
        variant={surface === 'customer' ? 'primary' : 'secondary'}
        aria-current={surface === 'customer' ? 'page' : undefined}
        disabled={navigationLocked}
        onClick={() => navigate('customer')}
      >Customer 예약</Button>
      <Button
        density="compact"
        variant={surface === 'waitlist' ? 'primary' : 'secondary'}
        aria-current={surface === 'waitlist' ? 'page' : undefined}
        disabled={navigationLocked}
        onClick={() => navigate('waitlist')}
      >Customer 대기</Button>
      <Button
        density="compact"
        variant={surface === 'management' ? 'primary' : 'secondary'}
        aria-current={surface === 'management' ? 'page' : undefined}
        disabled={navigationLocked}
        onClick={() => navigate('management')}
      >Venue 운영</Button>
    </nav>
  )

  return surface === 'customer'
    ? <CustomerReservationFlow api={api} holdAttempts={holdAttempts} navigation={navigation}
        onNavigationLockChange={setNavigationLocked} initialReservation={reservationTarget}
        onWaitlistSelect={(next) => navigate('waitlist', next)} />
    : surface === 'waitlist'
      ? <CustomerWaitlistFlow api={waitlistApi} session={waitlistSession} navigation={navigation}
          reservationApi={api} selection={selection} onSelectionChange={updateSelection}
          onNavigationLockChange={setNavigationLocked}
          onOpenReservation={(venueId, reservationId) => {
            if (lockRef.current) return
            setReservationTarget({ venueId, reservationId }); navigate('customer')
          }} />
      : <ManagementVenueFlow api={managementApi} waitlistApi={waitlistApi} navigation={navigation} onNavigationLockChange={setNavigationLocked} />
}
