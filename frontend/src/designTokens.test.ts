/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

const expectedTokens = {
  '--color-primary': '#315efb',
  '--color-primary-hover': '#284edb',
  '--color-primary-active': '#203db3',
  '--color-background': '#f5f7fb',
  '--color-surface': '#ffffff',
  '--color-surface-secondary': '#eef2f7',
  '--color-text-primary': '#172033',
  '--color-text-secondary': '#536078',
  '--color-text-tertiary': '#667085',
  '--color-border': '#dfe4ef',
  '--color-focus': '#315efb',
  '--color-active-foreground': '#1d4ed8',
  '--color-active-background': '#eef4ff',
  '--color-success-foreground': '#18794e',
  '--color-success-background': '#eaf8f0',
  '--color-attention-foreground': '#8a5a00',
  '--color-attention-background': '#fff7d6',
  '--color-destructive-foreground': '#b4232c',
  '--color-destructive-background': '#fff0f0',
  '--color-inactive-foreground': '#5a6673',
  '--color-inactive-background': '#f2f4f6',
  '--font-size-xs': '12px',
  '--line-height-xs': '16px',
  '--font-size-sm': '14px',
  '--line-height-sm': '20px',
  '--font-size-md': '16px',
  '--line-height-md': '24px',
  '--font-size-lg': '20px',
  '--line-height-lg': '28px',
  '--font-size-xl': '24px',
  '--line-height-xl': '32px',
  '--font-size-2xl': '32px',
  '--line-height-2xl': '40px',
  '--space-1': '4px',
  '--space-2': '8px',
  '--space-3': '12px',
  '--space-4': '16px',
  '--space-5': '24px',
  '--space-6': '32px',
  '--space-7': '48px',
  '--control-height-default': '44px',
  '--control-height-compact': '36px',
  '--radius-default': '8px',
  '--radius-large': '12px',
  '--border-width-default': '1px',
  '--focus-ring-width': '3px',
}

const contrastPairs = [
  ['--color-primary', '--color-surface'],
  ['--color-text-primary', '--color-background'],
  ['--color-text-secondary', '--color-background'],
  ['--color-text-tertiary', '--color-surface'],
  ['--color-active-foreground', '--color-active-background'],
  ['--color-success-foreground', '--color-success-background'],
  ['--color-attention-foreground', '--color-attention-background'],
  ['--color-destructive-foreground', '--color-destructive-background'],
  ['--color-inactive-foreground', '--color-inactive-background'],
] as const

function luminance(hex: string) {
  const channels = hex
    .slice(1)
    .match(/../g)!
    .map((channel) => Number.parseInt(channel, 16) / 255)
    .map((channel) =>
      channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
    )

  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}

function contrastRatio(first: string, second: string) {
  const firstLuminance = luminance(first)
  const secondLuminance = luminance(second)

  return (
    (Math.max(firstLuminance, secondLuminance) + 0.05) /
    (Math.min(firstLuminance, secondLuminance) + 0.05)
  )
}

describe('design tokens', () => {
  it.each(Object.entries(expectedTokens))('defines %s as %s', (token, value) => {
    expect(styles).toContain(`${token}: ${value};`)
  })

  it.each(contrastPairs)('%s on %s meets WCAG AA text contrast', (foreground, background) => {
    const foregroundValue = expectedTokens[foreground]
    const backgroundValue = expectedTokens[background]

    expect(contrastRatio(foregroundValue, backgroundValue)).toBeGreaterThanOrEqual(4.5)
  })

  it('keeps text-tertiary limited to the token and placeholder treatment', () => {
    expect(styles.match(/var\(--color-text-tertiary\)/g)).toHaveLength(1)
    expect(styles).toMatch(/\.form-control::placeholder\s*{[^}]*var\(--color-text-tertiary\)/s)
  })

  it('keeps a visible token-based keyboard focus indicator', () => {
    expect(styles).toMatch(
      /:focus-visible\s*{[^}]*var\(--focus-ring-width\)[^}]*var\(--color-focus\)/s,
    )
  })
})
