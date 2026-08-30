export type ReservationStatus =
  | 'HELD'
  | 'CONFIRMED'
  | 'CHECKED_IN'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW'
  | 'EXPIRED'

export type StatusTone = 'active' | 'success' | 'attention' | 'destructive' | 'inactive'

export const reservationStatusTone: Record<ReservationStatus, StatusTone> = {
  HELD: 'attention',
  CONFIRMED: 'active',
  CHECKED_IN: 'active',
  COMPLETED: 'success',
  CANCELLED: 'inactive',
  NO_SHOW: 'destructive',
  EXPIRED: 'inactive',
}

export interface StatusBadgeProps {
  status: ReservationStatus
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const tone = reservationStatusTone[status]

  return <span className={`status-badge status-badge--${tone}`}>{status}</span>
}
