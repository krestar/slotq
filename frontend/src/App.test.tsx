import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('renders the accessible SlotQ application shell', () => {
    render(<App />)

    expect(screen.getByRole('heading', { level: 1, name: 'SlotQ' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
    expect(screen.getByRole('link', { name: '본문으로 바로가기' })).toHaveAttribute(
      'href',
      '#main-content',
    )
  })
})
