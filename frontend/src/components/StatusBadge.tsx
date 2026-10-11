export type ReservationStatus =
  | 'HELD'
  | 'CONFIRMED'
  | 'CHECKED_IN'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW'
  | 'EXPIRED'

export type WaitlistStatus = 'WAITING' | 'OFFERED' | 'FULFILLED' | 'DECLINED' | 'PENDING' | 'ACCEPTED'

export type StatusTone = 'active' | 'success' | 'attention' | 'destructive' | 'inactive'

export const reservationStatusTone: Record<ReservationStatus | WaitlistStatus, StatusTone> = {
  HELD: 'attention',
  CONFIRMED: 'active',
  CHECKED_IN: 'active',
  COMPLETED: 'success',
  CANCELLED: 'inactive',
  NO_SHOW: 'destructive',
  EXPIRED: 'inactive',
  WAITING: 'attention',
  OFFERED: 'active',
  FULFILLED: 'success',
  DECLINED: 'inactive',
  PENDING: 'attention',
  ACCEPTED: 'success',
}

export interface StatusBadgeProps {
  status: ReservationStatus | WaitlistStatus
}

export const statusLabel: Record<ReservationStatus | WaitlistStatus | 'ACTIVE' | 'INACTIVE', string> = {
  HELD: '예약 보류', CONFIRMED: '예약 확정', CHECKED_IN: '체크인 완료',
  COMPLETED: '이용 완료', CANCELLED: '취소됨', NO_SHOW: '미방문', EXPIRED: '만료됨',
  WAITING: '대기 중', OFFERED: '예약 제안 도착', FULFILLED: '제안 수락으로 대기 종료',
  DECLINED: '제안 거절', PENDING: '수락 대기', ACCEPTED: '제안 수락 완료',
  ACTIVE: '활성', INACTIVE: '비활성',
}

export function formatStatus(status: string): string {
  return statusLabel[status as keyof typeof statusLabel] ?? status
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const tone = reservationStatusTone[status]

  return <span className={`status-badge status-badge--${tone}`}>{statusLabel[status]}</span>
}
