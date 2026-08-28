import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApiClient } from './apiClient'
import { createLocalAuthSession } from './localAuthSession'

const runtimeToken = 'runtime-only-token-with-at-least-thirty-two-characters'

function response(body: object, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

describe('local auth session and API client', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    document.cookie = 'before=test'
  })

  it('bootstraps with fixtureKey only and keeps the token in memory', async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(response({
      accessToken: runtimeToken,
      tokenType: 'Bearer',
    }))
    const session = createLocalAuthSession({
      apiBaseUrl: 'http://localhost:8080',
      fixtureKey: 'customer-a',
      fetchImplementation,
    })

    await session.initialize()

    expect(fetchImplementation).toHaveBeenCalledOnce()
    const [url, request] = fetchImplementation.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/__dev/auth/session')
    expect(request.body).toBe(JSON.stringify({ fixtureKey: 'customer-a' }))
    expect(request.cache).toBe('no-store')
    expect(request.credentials).toBe('omit')
    expect(session.accessToken()).toBe(runtimeToken)
    expect(url).not.toContain(runtimeToken)
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
    expect(document.cookie).toBe('before=test')
  })

  it('adds credentials only to protected requests and never to the URL', async () => {
    const bootstrapFetch = vi.fn().mockResolvedValue(response({
      accessToken: runtimeToken,
      tokenType: 'Bearer',
    }))
    const apiFetch = vi.fn().mockResolvedValue(response({}))
    const session = createLocalAuthSession({
      apiBaseUrl: 'http://localhost:8080',
      fixtureKey: 'tenant-a-owner',
      fetchImplementation: bootstrapFetch,
    })
    const request = createApiClient({
      apiBaseUrl: 'http://localhost:8080',
      authSession: session,
      fetchImplementation: apiFetch,
    })

    await request('/api/v1/venues', {
      access: 'public',
      headers: { Authorization: 'Bearer caller-forged-token' },
    })
    const [publicUrl, publicInit] = apiFetch.mock.calls[0] as [URL, RequestInit]
    expect(publicUrl.toString()).toBe('http://localhost:8080/api/v1/venues')
    expect(new Headers(publicInit.headers).has('Authorization')).toBe(false)
    expect(bootstrapFetch).not.toHaveBeenCalled()

    await request('/api/v1/management/venues', { access: 'protected' })
    const [protectedUrl, protectedInit] = apiFetch.mock.calls[1] as [URL, RequestInit]
    expect(protectedUrl.toString()).toBe('http://localhost:8080/api/v1/management/venues')
    expect(new Headers(protectedInit.headers).get('Authorization')).toBe(`Bearer ${runtimeToken}`)
    expect(protectedUrl.toString()).not.toContain(runtimeToken)
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
  })

  it('gets a fresh runtime session for a new page-process adapter', async () => {
    const fetchImplementation = vi.fn()
      .mockResolvedValueOnce(response({ accessToken: runtimeToken, tokenType: 'Bearer' }))
      .mockResolvedValueOnce(response({ accessToken: `${runtimeToken}-new`, tokenType: 'Bearer' }))

    const first = createLocalAuthSession({
      apiBaseUrl: 'http://localhost:8080',
      fixtureKey: 'customer-a',
      fetchImplementation,
    })
    const reloaded = createLocalAuthSession({
      apiBaseUrl: 'http://localhost:8080',
      fixtureKey: 'customer-a',
      fetchImplementation,
    })
    await first.initialize()
    await reloaded.initialize()

    expect(fetchImplementation).toHaveBeenCalledTimes(2)
    expect(first.accessToken()).not.toBe(reloaded.accessToken())
  })

  it('invalidates a rejected process credential without replaying the mutation', async () => {
    const restartedToken = `${runtimeToken}-after-restart`
    const bootstrapFetch = vi.fn()
      .mockResolvedValueOnce(response({ accessToken: runtimeToken, tokenType: 'Bearer' }))
      .mockResolvedValueOnce(response({ accessToken: restartedToken, tokenType: 'Bearer' }))
    const apiFetch = vi.fn()
      .mockResolvedValueOnce(response({}, 401))
      .mockResolvedValueOnce(response({}, 201))
    const session = createLocalAuthSession({
      apiBaseUrl: 'http://localhost:8080',
      fixtureKey: 'customer-a',
      fetchImplementation: bootstrapFetch,
    })
    const request = createApiClient({
      apiBaseUrl: 'http://localhost:8080',
      authSession: session,
      fetchImplementation: apiFetch,
    })
    const mutation = {
      access: 'protected' as const,
      method: 'POST',
      body: JSON.stringify({ slotInventoryId: 'slot-a', partySize: 2 }),
    }

    const rejected = await request('/api/v1/venues/venue-a/reservations/holds', mutation)

    expect(rejected.status).toBe(401)
    expect(apiFetch).toHaveBeenCalledTimes(1)
    expect(bootstrapFetch).toHaveBeenCalledTimes(1)
    expect(session.accessToken()).toBeUndefined()

    const explicitRetry = await request('/api/v1/venues/venue-a/reservations/holds', mutation)

    expect(explicitRetry.status).toBe(201)
    expect(apiFetch).toHaveBeenCalledTimes(2)
    expect(bootstrapFetch).toHaveBeenCalledTimes(2)
    const secondRequest = apiFetch.mock.calls[1]?.[1] as RequestInit
    expect(new Headers(secondRequest.headers).get('Authorization')).toBe(`Bearer ${restartedToken}`)
  })

  it('refuses absolute or protocol-relative paths before attaching a credential', async () => {
    const bootstrapFetch = vi.fn().mockResolvedValue(response({
      accessToken: runtimeToken,
      tokenType: 'Bearer',
    }))
    const apiFetch = vi.fn().mockResolvedValue(response({}))
    const session = createLocalAuthSession({
      apiBaseUrl: 'http://localhost:8080',
      fixtureKey: 'customer-a',
      fetchImplementation: bootstrapFetch,
    })
    const request = createApiClient({
      apiBaseUrl: 'http://localhost:8080',
      authSession: session,
      fetchImplementation: apiFetch,
    })

    await expect(request('https://attacker.example/collect', { access: 'protected' }))
      .rejects.toThrow('origin-relative')
    await expect(request('//attacker.example/collect', { access: 'protected' }))
      .rejects.toThrow('origin-relative')
    expect(bootstrapFetch).not.toHaveBeenCalled()
    expect(apiFetch).not.toHaveBeenCalled()
  })
})
