import type { LocalAuthSession } from './localAuthSession'

export type ApiAccess = 'public' | 'protected'

export interface ApiRequestOptions extends RequestInit {
  access: ApiAccess
}

interface ApiClientOptions {
  apiBaseUrl: string
  authSession: LocalAuthSession
  fetchImplementation?: typeof fetch
}

export function createApiClient({
  apiBaseUrl,
  authSession,
  fetchImplementation = fetch,
}: ApiClientOptions) {
  return async function apiRequest(path: string, options: ApiRequestOptions): Promise<Response> {
    if (!path.startsWith('/') || path.startsWith('//')) {
      throw new Error('Product API request path must be origin-relative.')
    }
    const requestUrl = new URL(path, apiBaseUrl)
    if (requestUrl.origin !== new URL(apiBaseUrl).origin) {
      throw new Error('Product API request must stay on the configured API origin.')
    }
    const { access, ...requestInit } = options
    const headers = new Headers(requestInit.headers)
    headers.delete('Authorization')

    if (access === 'protected') {
      await authSession.initialize()
      const accessToken = authSession.accessToken()
      if (!accessToken) {
        throw new Error('Protected API request requires an authenticated runtime session.')
      }
      headers.set('Authorization', `Bearer ${accessToken}`)
    }

    return fetchImplementation(requestUrl, {
      ...requestInit,
      headers,
    })
  }
}
