import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Button } from './Button'
import { FormField } from './FormField'
import {
  reservationStatusTone,
  formatStatus,
  type ReservationStatus,
  type WaitlistStatus,
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
        label="테이블 이름"
        description="운영 화면에 표시되는 이름입니다."
        error="테이블 이름을 입력해 주세요."
      >
        <input type="text" />
      </FormField>,
    )

    const control = screen.getByRole('textbox', { name: '테이블 이름' })
    expect(control).toHaveAttribute('aria-invalid', 'true')
    expect(control).toHaveAttribute(
      'aria-describedby',
      'resource-name-description resource-name-error',
    )
    expect(screen.getByText('테이블 이름을 입력해 주세요.')).toHaveAttribute(
      'id',
      'resource-name-error',
    )
  })

  it('keeps native select semantics and compact density', () => {
    render(
      <FormField id="resource" label="테이블" density="compact">
        <select defaultValue="room-a">
          <option value="room-a">Room A</option>
        </select>
      </FormField>,
    )

    expect(screen.getByRole('combobox', { name: '테이블' })).toHaveClass(
      'form-control',
      'control--compact',
    )
  })
})

describe('StatusBadge', () => {
  it('매장·테이블의 활성 상태도 한국어로 표시한다', () => {
    expect(formatStatus('ACTIVE')).toBe('활성')
    expect(formatStatus('INACTIVE')).toBe('비활성')
  })
  it.each<[ReservationStatus | WaitlistStatus, string, string]>([
    ['HELD', '예약 보류', 'attention'], ['CONFIRMED', '예약 확정', 'active'],
    ['CHECKED_IN', '체크인 완료', 'active'], ['COMPLETED', '이용 완료', 'success'],
    ['CANCELLED', '취소됨', 'inactive'], ['NO_SHOW', '미방문', 'destructive'],
    ['EXPIRED', '만료됨', 'inactive'], ['WAITING', '대기 중', 'attention'],
    ['OFFERED', '예약 제안 도착', 'active'], ['FULFILLED', '제안 수락으로 대기 종료', 'success'],
    ['DECLINED', '제안 거절', 'inactive'], ['PENDING', '수락 대기', 'attention'],
    ['ACCEPTED', '제안 수락 완료', 'success'],
  ])('%s를 %s로 표시하며 색상 의미를 유지한다', (status, label, tone) => {
    render(<StatusBadge status={status} />)
    expect(reservationStatusTone[status]).toBe(tone)
    expect(screen.getByText(label)).toHaveClass(`status-badge--${tone}`)
    expect(screen.queryByText(status)).not.toBeInTheDocument()
  })
})
