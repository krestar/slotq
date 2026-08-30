import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { initializeLocalAuth } from './auth'
import 'pretendard/dist/web/variable/pretendardvariable.css'
import './styles.css'

const rootElement = document.getElementById('root')

if (!rootElement) {
  throw new Error('SlotQ frontend root element was not found.')
}

void initializeLocalAuth().catch(() => undefined)

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
