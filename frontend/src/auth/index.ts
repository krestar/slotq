import { productApiBaseUrl } from '../config/environment'
import { createApiClient } from './apiClient'
import { createLocalAuthSession, type LocalAuthSession } from './localAuthSession'

const localFixtureKey = import.meta.env.DEV
  ? import.meta.env.VITE_LOCAL_AUTH_FIXTURE?.trim()
  : undefined

const productionSession: LocalAuthSession = {
  initialize: async () => undefined,
  accessToken: () => undefined,
  invalidate: () => undefined,
}

export const localAuthSession = import.meta.env.DEV
  ? createLocalAuthSession({
      apiBaseUrl: productApiBaseUrl,
      fixtureKey: localFixtureKey,
    })
  : productionSession

export const apiRequest = createApiClient({
  apiBaseUrl: productApiBaseUrl,
  authSession: localAuthSession,
})

export function initializeLocalAuth(): Promise<void> {
  return localAuthSession.initialize()
}
