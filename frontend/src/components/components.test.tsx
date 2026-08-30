import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Button } from './Button'
import { FormField } from './FormField'
import {
  reservationStatusTone,
  type ReservationStatus,
  StatusBadge,
} from './StatusBadge'

describe('Button', () => {
  it('supports the shared variants, density, and native disabled state', () => {
    render(
      <Button variant="destructive" density="compact" disabled>
        예약 취소
      </Button>,
    )

    const button = screen.getByRole('button', { name: '예약 취소' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toHaveClass('button--destructive', 'control--compact')
  })
})

describe('FormField', () => {
  it('connects the label, description, and concrete error to an invalid control', () => {
    render(
      <FormField
        id="resource-name"
        label="Resource 이름"
        description="운영 화면에 표시되는 이름입니다."
        error="Resource 이름을 입력해 주세요."
      >
        <input type="text" />
      </FormField>,
    )

    const control = screen.getByRole('textbox', { name: 'Resource 이름' })
    expect(control).toHaveAttribute('aria-invalid', 'true')
    expect(control).toHaveAttribute(
      'aria-describedby',
      'resource-name-description resource-name-error',
    )
    expect(screen.getByText('Resource 이름을 입력해 주세요.')).toHaveAttribute(
      'id',
      'resource-name-error',
    )
  })

  it('keeps native select semantics and compact density', () => {
    render(
      <FormField id="resource" label="Resource" density="compact">
        <select defaultValue="room-a">
          <option value="room-a">Room A</option>
        </select>
      </FormField>,
    )

    expect(screen.getByRole('combobox', { name: 'Resource' })).toHaveClass(
      'form-control',
      'control--compact',
    )
  })
})

describe('StatusBadge', () => {
  it.each<[ReservationStatus, string]>([
    ['HELD', 'attention'],
    ['CONFIRMED', 'active'],
    ['CHECKED_IN', 'active'],
    ['COMPLETED', 'success'],
    ['CANCELLED', 'inactive'],
    ['NO_SHOW', 'destructive'],
    ['EXPIRED', 'inactive'],
  ])('maps %s to the %s semantic tone and keeps a text label', (status, tone) => {
    render(<StatusBadge status={status} />)

    expect(reservationStatusTone[status]).toBe(tone)
    expect(screen.getByText(status)).toHaveClass(`status-badge--${tone}`)
  })
})
