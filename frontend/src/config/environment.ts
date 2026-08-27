const defaultProductApiBaseUrl = 'http://localhost:8080'
const configuredProductApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()

export const productApiBaseUrl = configuredProductApiBaseUrl || defaultProductApiBaseUrl
