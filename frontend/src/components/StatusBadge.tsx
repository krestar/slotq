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

export function StatusBadge({ status }: StatusBadgeProps) {
  const tone = reservationStatusTone[status]

  return <span className={`status-badge status-badge--${tone}`}>{status}</span>
}
