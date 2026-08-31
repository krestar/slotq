import { useState } from 'react'
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
}

type Surface = 'customer' | 'management'

export function App({ api, managementApi }: AppProps) {
  const [surface, setSurface] = useState<Surface>('customer')
  const navigation = (
    <nav className="surface-navigation" aria-label="Product surface">
      <Button
        density="compact"
        variant={surface === 'customer' ? 'primary' : 'secondary'}
        aria-current={surface === 'customer' ? 'page' : undefined}
        onClick={() => setSurface('customer')}
      >Customer 예약</Button>
      <Button
        density="compact"
        variant={surface === 'management' ? 'primary' : 'secondary'}
        aria-current={surface === 'management' ? 'page' : undefined}
        onClick={() => setSurface('management')}
      >Venue 운영</Button>
    </nav>
  )

  return surface === 'customer'
    ? <CustomerReservationFlow api={api} navigation={navigation} />
    : <ManagementVenueFlow api={managementApi} navigation={navigation} />
}
