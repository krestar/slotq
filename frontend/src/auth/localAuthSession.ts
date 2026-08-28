export interface LocalAuthSession {
  initialize(): Promise<void>
  accessToken(): string | undefined
  invalidate(): void
}

interface LocalAuthSessionOptions {
  apiBaseUrl: string
  fixtureKey?: string
  fetchImplementation?: typeof fetch
}

interface BootstrapResponse {
  accessToken: string
  tokenType: 'Bearer'
}

const fixtureKeyPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export function createLocalAuthSession({
  apiBaseUrl,
  fixtureKey,
  fetchImplementation = fetch,
}: LocalAuthSessionOptions): LocalAuthSession {
  const selectedFixture = fixtureKey?.trim()
  let inMemoryAccessToken: string | undefined
  let initialization: Promise<void> | undefined
  let sessionGeneration = 0

  async function bootstrap(generation: number): Promise<void> {
    if (!selectedFixture) {
      return
    }
    if (!fixtureKeyPattern.test(selectedFixture)) {
      throw new Error('Local auth fixtureKey is invalid.')
    }

    const response = await fetchImplementation(`${apiBaseUrl}/__dev/auth/session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      credentials: 'omit',
      body: JSON.stringify({ fixtureKey: selectedFixture }),
    })
    if (!response.ok) {
      throw new Error('Local auth session bootstrap failed.')
    }
    const payload = (await response.json()) as Partial<BootstrapResponse>
    if (
      payload.tokenType !== 'Bearer' ||
      typeof payload.accessToken !== 'string' ||
      payload.accessToken.length < 32
    ) {
      throw new Error('Local auth session response is invalid.')
    }
    if (generation === sessionGeneration) {
      inMemoryAccessToken = payload.accessToken
    }
  }

  return {
    initialize() {
      if (!initialization) {
        const generation = sessionGeneration
        const bootstrapAttempt = bootstrap(generation)
        initialization = bootstrapAttempt
        void bootstrapAttempt.catch(() => {
          if (initialization === bootstrapAttempt) {
            initialization = undefined
          }
          if (generation === sessionGeneration) {
            inMemoryAccessToken = undefined
          }
        })
      }
      return initialization
    },
    accessToken() {
      return inMemoryAccessToken
    },
    invalidate() {
      sessionGeneration += 1
      initialization = undefined
      inMemoryAccessToken = undefined
    },
  }
}
