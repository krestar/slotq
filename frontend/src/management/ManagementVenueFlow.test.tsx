import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ManagementVenueFlow } from './ManagementVenueFlow'
import {
  ManagementApiError,
  ManagementMutationResultUnknownError,
  type ManagementApi,
  type ManagementReservation,
  type ManagementVenue,
  type ReservationDetails,
} from './managementApi'

const ownerVenue: ManagementVenue = {
  id: 'venue-owner', name: 'Owner Venue', timezone: 'Asia/Seoul', status: 'ACTIVE',
  currentPolicyVersion: 3, configurationWritable: true,
}
const staffVenue: ManagementVenue = {
  id: 'venue-staff', name: 'Staff Venue', timezone: 'Asia/Seoul', status: 'ACTIVE',
  currentPolicyVersion: 3, configurationWritable: false,
}
const policy = {
  version: 3, slotDurationMinutes: 30, holdDurationMinutes: 5,
  cancellationCutoffMinutes: 60, noShowGraceMinutes: 15,
}
const resource = {
  id: 'resource-a', type: 'TABLE' as const, name: 'Table A', seatingCapacity: 4,
  status: 'ACTIVE' as const,
}
const slot = {
  id: 'slot-a', resourceId: resource.id, startsAt: '2099-08-31T09:00:00Z',
  endsAt: '2099-08-31T09:30:00Z', capacity: 1, appliedPolicyVersion: 3,
}
const reservation: ManagementReservation = {
  id: 'reservation-a', resourceId: resource.id, slotInventoryId: slot.id,
  state: 'CONFIRMED', partySize: 2, startsAt: slot.startsAt, endsAt: slot.endsAt,
  expiresAt: '2099-08-31T08:55:00Z', customerReference: 'customer-a',
  allowedActions: ['cancel', 'check-in'],
}
const reservationDetails: ReservationDetails = {
  id: reservation.id,
  venueId: ownerVenue.id,
  resourceId: reservation.resourceId,
  slotInventoryId: reservation.slotInventoryId,
  state: 'CANCELLED',
  partySize: reservation.partySize,
  allocationQuantity: 1,
  startsAt: reservation.startsAt,
  endsAt: reservation.endsAt,
  expiresAt: reservation.expiresAt,
  cancelAllowedUntil: '2099-08-31T08:30:00Z',
  noShowEligibleAt: '2099-08-31T09:15:00Z',
  appliedPolicyVersion: 3,
}

function makeApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    listVenues: vi.fn().mockResolvedValue([ownerVenue]),
    getVenue: vi.fn().mockResolvedValue(ownerVenue),
    patchVenue: vi.fn().mockResolvedValue({ ...ownerVenue, name: 'Renamed' }),
    getPolicy: vi.fn().mockResolvedValue(policy),
    putPolicy: vi.fn().mockResolvedValue({ ...policy, version: 4 }),
    listResources: vi.fn().mockResolvedValue([resource]),
    createResource: vi.fn().mockResolvedValue({ ...resource, id: 'resource-new', name: 'Table B' }),
    patchResource: vi.fn().mockResolvedValue({ ...resource, status: 'INACTIVE' }),
    listSlots: vi.fn().mockResolvedValue([slot]),
    createSlot: vi.fn().mockResolvedValue({ ...slot, id: 'slot-new' }),
    listReservations: vi.fn().mockResolvedValue([reservation]),
    getReservation: vi.fn().mockResolvedValue(reservationDetails),
    commandReservation: vi.fn().mockResolvedValue({
      id: reservation.id, state: 'CHECKED_IN', startsAt: reservation.startsAt,
      endsAt: reservation.endsAt, expiresAt: reservation.expiresAt, partySize: 2,
    }),
    ...overrides,
  }
}

async function selectVenue(api: ManagementApi, id = ownerVenue.id) {
  render(<ManagementVenueFlow api={api} />)
  await screen.findByRole('option', { name: /Owner Venue|Staff Venue/ })
  fireEvent.change(screen.getByRole('combobox', { name: '매장' }), { target: { value: id } })
  await screen.findByRole('list', { name: '예약 목록' })
}

function ManagementFlowWithNavigation({ api }: { api: ManagementApi }) {
  const [navigationLocked, setNavigationLocked] = useState(false)
  return (
    <ManagementVenueFlow
      api={api}
      onNavigationLockChange={setNavigationLocked}
      navigation={<button disabled={navigationLocked}>고객 예약</button>}
    />
  )
}

describe('Management Venue flow', () => {
  it.each([
    ['UTC', '2026-10-11T18:00', '2026-10-11T18:00:00+00:00'],
    ['America/New_York', '2026-07-11T18:00', '2026-07-11T18:00:00-04:00'],
  ])('%s 매장의 현지 시각을 기존 Slot API에 전달한다', async (timezone, local, instant) => {
    const venue = { ...ownerVenue, timezone }
    const api = makeApi({ listVenues: vi.fn().mockResolvedValue([venue]), getVenue: vi.fn().mockResolvedValue(venue) })
    await selectVenue(api)
    const input = screen.getByLabelText('시작 날짜와 시간')
    expect(input).toHaveAttribute('type', 'datetime-local')
    expect(input).toHaveAccessibleDescription(expect.stringContaining(timezone))
    fireEvent.change(screen.getByLabelText('테이블', { selector: '#slot-resource' }), { target: { value: resource.id } })
    fireEvent.change(input, { target: { value: local } })
    fireEvent.click(screen.getByRole('button', { name: '예약 시간대 생성' }))
    await waitFor(() => expect(api.createSlot).toHaveBeenCalledWith(venue.id, { resourceId: resource.id, startsAt: instant }))
  })

  it.each([
    ['2026-03-08T02:30', '존재하지 않는'], ['2026-11-01T01:30', '중복 시각'],
  ])('DST %s 입력을 안내하고 API에 전송하지 않는다', async (local, message) => {
    const venue = { ...ownerVenue, timezone: 'America/New_York' }
    const api = makeApi({ listVenues: vi.fn().mockResolvedValue([venue]), getVenue: vi.fn().mockResolvedValue(venue) })
    await selectVenue(api)
    const input = screen.getByLabelText('시작 날짜와 시간')
    fireEvent.change(input, { target: { value: local } })
    fireEvent.submit(input.closest('form')!)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription(expect.stringContaining(message))
    expect(input).toHaveFocus()
    expect(api.createSlot).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: '2026-07-11T18:00' } })
    fireEvent.submit(input.closest('form')!)
    await waitFor(() => expect(api.createSlot).toHaveBeenCalledOnce())
  })

  it('한국어 상태 필터와 정책 라벨을 표시하며 API enum을 유지한다', async () => {
    const api = makeApi()
    await selectVenue(api)
    const filter = screen.getByLabelText('상태', { selector: '#reservation-status' })
    expect(within(filter).getByRole('option', { name: '예약 확정' })).toHaveValue('CONFIRMED')
    expect(screen.getByLabelText('예약 보류 유지 시간')).toHaveAttribute('name', 'holdDurationMinutes')
    expect(screen.getByLabelText('예약 시작 전 취소 마감 시간')).toHaveValue(60)
    expect(screen.getByLabelText('예약 시작 후 미방문 처리 유예 시간')).toHaveValue(15)
    fireEvent.change(filter, { target: { value: 'EXPIRED' } })
    await waitFor(() => expect(api.listReservations).toHaveBeenLastCalledWith(ownerVenue.id, expect.any(String), 'EXPIRED'))
  })

  it('uses only server Venue choices and keeps the accessibility shell', async () => {
    let resolveVenues: ((value: ManagementVenue[]) => void) | undefined
    const api = makeApi({
      listVenues: vi.fn().mockReturnValue(new Promise((resolve) => { resolveVenues = resolve })),
    })
    render(<ManagementVenueFlow api={api} />)

    expect(screen.getByRole('heading', { level: 1, name: '매장 운영' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
    expect(screen.getByRole('link', { name: '본문으로 바로가기' })).toHaveAttribute('href', '#main-content')
    expect(screen.getByRole('status')).toHaveTextContent('접근 가능한 매장')
    resolveVenues?.([ownerVenue])
    await screen.findByRole('option', { name: /Owner Venue/ })
    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(screen.queryByLabelText(/Tenant|Role|Principal|fixture/i)).not.toBeInTheDocument()
  })

  it('shows Staff assigned Venue, server actions, and no configuration mutation controls', async () => {
    const staffReservation: ManagementReservation = { ...reservation, allowedActions: ['check-in'] }
    const api = makeApi({
      listVenues: vi.fn().mockResolvedValue([staffVenue]),
      getVenue: vi.fn().mockResolvedValue(staffVenue),
      listReservations: vi.fn().mockResolvedValue([staffReservation]),
    })
    await selectVenue(api, staffVenue.id)

    expect(screen.getByText('Staff Venue')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '체크인' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('설정은 읽기 전용')
    expect(screen.queryByRole('button', { name: /매장 저장|운영 정책 전체 저장|테이블 추가|예약 시간대 생성/ })).not.toBeInTheDocument()
    expect(screen.getByText('활성', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getByText(/Table A · 4석 · 활성/)).toBeInTheDocument()
    expect(screen.queryByText('ACTIVE')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('새 테이블 이름')).not.toBeInTheDocument()
  })

  it('exposes Owner/Manager configuration controls only from configurationWritable', async () => {
    const api = makeApi()
    await selectVenue(api)

    expect(screen.getByRole('button', { name: '매장 저장' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '운영 정책 전체 저장' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '테이블 추가' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '예약 시간대 생성' })).toBeInTheDocument()
    expect(screen.getByText(/예약 가능 수량 1/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/예약 가능 수량|예약 가능 수량 편집/)).not.toBeInTheDocument()
  })

  it('submits Venue, full Policy, Resource create/patch, and offset Slot contracts', async () => {
    const api = makeApi()
    await selectVenue(api)

    fireEvent.change(screen.getByLabelText('이름', { selector: '#venue-name' }), { target: { value: 'Renamed' } })
    fireEvent.change(screen.getByLabelText('상태', { selector: '#venue-status' }), { target: { value: 'INACTIVE' } })
    fireEvent.click(screen.getByRole('button', { name: '매장 저장' }))
    await waitFor(() => expect(api.patchVenue).toHaveBeenCalledWith(ownerVenue.id, {
      name: 'Renamed', status: 'INACTIVE',
    }))

    fireEvent.change(screen.getByLabelText('예약 시간 간격'), { target: { value: '45' } })
    fireEvent.click(screen.getByRole('button', { name: '운영 정책 전체 저장' }))
    await waitFor(() => expect(api.putPolicy).toHaveBeenCalledWith(ownerVenue.id, {
      slotDurationMinutes: 45, holdDurationMinutes: 5,
      cancellationCutoffMinutes: 60, noShowGraceMinutes: 15,
    }))

    fireEvent.change(screen.getByLabelText('새 테이블 이름'), { target: { value: 'Table B' } })
    fireEvent.change(screen.getByLabelText('좌석 수', { selector: '#new-resource-seats' }), { target: { value: '6' } })
    fireEvent.click(screen.getByRole('button', { name: '테이블 추가' }))
    await waitFor(() => expect(api.createResource).toHaveBeenCalledWith(ownerVenue.id, {
      name: 'Table B', seatingCapacity: 6,
    }))

    fireEvent.change(screen.getByLabelText('상태', { selector: '#resource-status-resource-a' }), { target: { value: 'INACTIVE' } })
    const resourceForm = screen.getByLabelText('상태', { selector: '#resource-status-resource-a' }).closest('form')
    fireEvent.click(within(resourceForm!).getByRole('button', { name: '테이블 저장' }))
    await waitFor(() => expect(api.patchResource).toHaveBeenCalledWith(ownerVenue.id, resource.id, {
      name: resource.name, seatingCapacity: 4, status: 'INACTIVE',
    }))

    fireEvent.change(screen.getByLabelText('테이블', { selector: '#slot-resource' }), { target: { value: 'resource-new' } })
    fireEvent.change(screen.getByLabelText('시작 날짜와 시간'), { target: { value: '2099-08-31T18:00' } })
    fireEvent.click(screen.getByRole('button', { name: '예약 시간대 생성' }))
    await waitFor(() => expect(api.createSlot).toHaveBeenCalledWith(ownerVenue.id, {
      resourceId: 'resource-new', startsAt: '2099-08-31T18:00:00+09:00',
    }))
  })

  it('renders only allowedActions, applies command representation, then re-fetches allowedActions', async () => {
    let resolveRefresh: ((value: ManagementReservation[]) => void) | undefined
    const listReservations = vi.fn()
      .mockResolvedValueOnce([reservation])
      .mockReturnValueOnce(new Promise((resolve) => { resolveRefresh = resolve }))
    const api = makeApi({ listReservations })
    await selectVenue(api)

    fireEvent.click(screen.getByRole('button', { name: '체크인' }))
    expect(await screen.findByText('체크인 완료', { selector: '.status-badge' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '체크인' })).not.toBeInTheDocument()
    expect(api.commandReservation).toHaveBeenCalledWith(ownerVenue.id, reservation.id, 'check-in')
    expect(listReservations).toHaveBeenCalledTimes(2)

    resolveRefresh?.([{ ...reservation, state: 'CHECKED_IN', allowedActions: ['complete'] }])
    expect(await screen.findByRole('button', { name: '완료' })).toBeInTheDocument()
  })

  it('reads the original Reservation after a business conflict and refreshes server allowedActions', async () => {
    const checkedIn = { ...reservation, state: 'CHECKED_IN' as const, allowedActions: ['complete' as const] }
    const listReservations = vi.fn()
      .mockResolvedValueOnce([reservation])
      .mockResolvedValueOnce([checkedIn])
    const api = makeApi({
      listReservations,
      getReservation: vi.fn().mockResolvedValue({ ...reservationDetails, state: 'CHECKED_IN' }),
      commandReservation: vi.fn().mockRejectedValue(
        new ManagementApiError(409, 'RESERVATION_TRANSITION_NOT_ALLOWED'),
      ),
    })
    await selectVenue(api)
    fireEvent.click(screen.getByRole('button', { name: '체크인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('RESERVATION_TRANSITION_NOT_ALLOWED')
    expect(screen.getByText('예약 확정', { selector: '.status-badge' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '원래 예약 다시 조회' }))

    expect(await screen.findByText(/예약 reservation-a 최신 상태/)).toBeInTheDocument()
    expect(api.getReservation).toHaveBeenCalledWith(ownerVenue.id, reservation.id)
    expect(await screen.findByRole('button', { name: '완료' })).toBeInTheDocument()
    expect(listReservations).toHaveBeenCalledTimes(2)
  })

  it('does not let a pending old Venue or date read overwrite the new context', async () => {
    const secondVenue = { ...ownerVenue, id: 'venue-second', name: 'Second Venue' }
    let resolveOldConfiguration: ((value: ManagementVenue) => void) | undefined
    let resolveOldReservations: ((value: ManagementReservation[]) => void) | undefined
    const api = makeApi({
      listVenues: vi.fn().mockResolvedValue([ownerVenue, secondVenue]),
      getVenue: vi.fn()
        .mockReturnValueOnce(new Promise((resolve) => { resolveOldConfiguration = resolve }))
        .mockResolvedValueOnce(secondVenue),
      listReservations: vi.fn()
        .mockReturnValueOnce(new Promise((resolve) => { resolveOldReservations = resolve }))
        .mockResolvedValue([]),
    })
    render(<ManagementVenueFlow api={api} />)
    await screen.findByRole('option', { name: /Owner Venue/ })
    fireEvent.change(screen.getByRole('combobox', { name: '매장' }), { target: { value: ownerVenue.id } })
    fireEvent.change(screen.getByRole('combobox', { name: '매장' }), { target: { value: secondVenue.id } })

    expect(await screen.findByDisplayValue('Second Venue')).toBeInTheDocument()
    await act(async () => {
      resolveOldConfiguration?.(ownerVenue)
      resolveOldReservations?.([reservation])
    })
    expect(screen.queryByText('Owner Venue', { selector: 'dd' })).not.toBeInTheDocument()
    expect(screen.queryByText('customer-a')).not.toBeInTheDocument()
  })

  it('invalidates a pending Reservation read when date changes', async () => {
    let resolveOld: ((value: ManagementReservation[]) => void) | undefined
    let resolveOldSlots: ((value: typeof slot[]) => void) | undefined
    const api = makeApi({
      listReservations: vi.fn()
        .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }))
        .mockResolvedValueOnce([]),
      listSlots: vi.fn()
        .mockReturnValueOnce(new Promise((resolve) => { resolveOldSlots = resolve }))
        .mockResolvedValueOnce([]),
    })
    render(<ManagementVenueFlow api={api} />)
    await screen.findByRole('option', { name: /Owner Venue/ })
    fireEvent.change(screen.getByRole('combobox', { name: '매장' }), { target: { value: ownerVenue.id } })
    const date = await screen.findByLabelText('매장 기준 날짜')
    fireEvent.change(date, { target: { value: '2099-09-01' } })
    await screen.findByText('선택한 날짜와 상태의 예약이 없습니다.')
    await act(async () => {
      resolveOld?.([reservation])
      resolveOldSlots?.([{ ...slot, id: 'old-slot' }])
    })
    expect(screen.queryByText('customer-a')).not.toBeInTheDocument()
    expect(screen.queryByText(/예약 가능 수량 1/)).not.toBeInTheDocument()
  })

  it('does not let an older Reservation list overwrite a command result', async () => {
    let resolveOldRead: ((value: ManagementReservation[]) => void) | undefined
    const oldCancelled = { ...reservation, state: 'CANCELLED' as const, allowedActions: [] }
    const listReservations = vi.fn()
      .mockResolvedValueOnce([reservation])
      .mockReturnValueOnce(new Promise((resolve) => { resolveOldRead = resolve }))
      .mockResolvedValueOnce([{ ...reservation, state: 'CHECKED_IN', allowedActions: ['complete'] }])
    const api = makeApi({ listReservations })
    await selectVenue(api)

    fireEvent.click(screen.getByRole('button', { name: '목록 새로고침' }))
    fireEvent.click(screen.getByRole('button', { name: '체크인' }))
    expect(await screen.findByText('체크인 완료', { selector: '.status-badge' })).toBeInTheDocument()
    await act(async () => { resolveOldRead?.([oldCancelled]) })
    expect(screen.getByText('체크인 완료', { selector: '.status-badge' })).toBeInTheDocument()
    expect(screen.queryByText('취소됨', { selector: '.status-badge' })).not.toBeInTheDocument()
  })

  it('does not let a pending same-매장 설정 read overwrite a later 운영 정책 mutation', async () => {
    let resolveOldPolicy: ((value: typeof policy) => void) | undefined
    const updatedPolicy = { ...policy, version: 4, slotDurationMinutes: 45 }
    const getPolicy = vi.fn()
      .mockResolvedValueOnce(policy)
      .mockReturnValueOnce(new Promise((resolve) => { resolveOldPolicy = resolve }))
    const putPolicy = vi.fn()
      .mockRejectedValueOnce(new ManagementMutationResultUnknownError('NETWORK_ERROR'))
      .mockResolvedValueOnce(updatedPolicy)
    const api = makeApi({ getPolicy, putPolicy })
    await selectVenue(api)

    fireEvent.click(screen.getByRole('button', { name: '운영 정책 전체 저장' }))
    expect(await screen.findByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '관련 데이터 다시 조회' }))
    await waitFor(() => expect(getPolicy).toHaveBeenCalledTimes(2))

    fireEvent.change(screen.getByLabelText('예약 시간 간격'), { target: { value: '45' } })
    fireEvent.click(screen.getByRole('button', { name: '운영 정책 전체 저장' }))
    expect(await screen.findByRole('heading', { level: 3, name: '운영 정책 v4' })).toBeInTheDocument()
    await act(async () => { resolveOldPolicy?.(policy) })
    expect(screen.getByRole('heading', { level: 3, name: '운영 정책 v4' })).toBeInTheDocument()
  })

  it('connects server validation field errors to native controls', async () => {
    const api = makeApi({
      patchVenue: vi.fn().mockRejectedValue(
        new ManagementApiError(400, 'VALIDATION_FAILED', { name: 'must not be blank' }),
      ),
    })
    await selectVenue(api)
    fireEvent.click(screen.getByRole('button', { name: '매장 저장' }))
    const input = await screen.findByLabelText('이름', { selector: '#venue-name' })
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'venue-name-error')
    expect(input).toHaveAccessibleDescription('입력값이 서버의 검증 조건에 맞지 않습니다. 값을 확인해 주세요.')
  })

  it('connects client blank Venue validation without sending a request', async () => {
    const api = makeApi()
    await selectVenue(api)
    const input = screen.getByLabelText('이름', { selector: '#venue-name' })
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: '매장 저장' }))

    expect(api.patchVenue).not.toHaveBeenCalled()
    expect(await screen.findByText('매장 이름을 입력해 주세요.')).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'venue-name-error')
  })

  it('locks mutation context, blocks duplicates, and marks an unknown create result', async () => {
    let resolvePatch: ((value: ManagementVenue) => void) | undefined
    const patchVenue = vi.fn().mockReturnValue(new Promise((resolve) => { resolvePatch = resolve }))
    const api = makeApi({ patchVenue })
    await selectVenue(api)

    const save = screen.getByRole('button', { name: '매장 저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    expect(screen.getByRole('combobox', { name: '매장' })).toBeDisabled()
    expect(screen.getByLabelText('매장 기준 날짜')).toBeDisabled()
    expect(patchVenue).toHaveBeenCalledOnce()
    resolvePatch?.(ownerVenue)
    await waitFor(() => expect(save).toBeEnabled())

    cleanup()
    const unknownApi = makeApi({
      createResource: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('NETWORK_ERROR'),
      ),
    })
    await selectVenue(unknownApi)
    fireEvent.change(screen.getAllByLabelText('새 테이블 이름').at(-1)!, { target: { value: 'Unknown' } })
    fireEvent.change(screen.getAllByLabelText('좌석 수', { selector: '#new-resource-seats' }).at(-1)!, { target: { value: '4' } })
    fireEvent.click(screen.getAllByRole('button', { name: '테이블 추가' }).at(-1)!)
    expect(await screen.findByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()
    expect(unknownApi.createResource).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '관련 데이터 다시 조회' }))
    await waitFor(() => expect(unknownApi.listResources).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('작업 결과를 확인할 수 없습니다')).not.toBeInTheDocument())
  })

  it('keeps result unknown after failed reconciliation', async () => {
    const listResources = vi.fn()
      .mockResolvedValueOnce([resource])
      .mockRejectedValueOnce(new ManagementApiError(500, 'INTERNAL_ERROR'))
    const api = makeApi({
      listResources,
      createResource: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('NETWORK_ERROR'),
      ),
    })
    await selectVenue(api)
    fireEvent.change(screen.getByLabelText('새 테이블 이름'), { target: { value: 'Unknown' } })
    fireEvent.change(screen.getByLabelText('좌석 수', { selector: '#new-resource-seats' }), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: '테이블 추가' }))
    expect(await screen.findByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '관련 데이터 다시 조회' }))
    expect(await screen.findByText('INTERNAL_ERROR')).toBeInTheDocument()
    expect(screen.getByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()
  })

  it('reconciles an unknown Venue PATCH in both selector and detail', async () => {
    const reconciledVenue = { ...ownerVenue, name: 'Reconciled Venue', status: 'INACTIVE' as const }
    const api = makeApi({
      getVenue: vi.fn().mockResolvedValueOnce(ownerVenue).mockResolvedValueOnce(reconciledVenue),
      patchVenue: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('NETWORK_ERROR'),
      ),
    })
    await selectVenue(api)
    fireEvent.change(screen.getByLabelText('이름', { selector: '#venue-name' }), {
      target: { value: 'Possibly saved' },
    })
    fireEvent.click(screen.getByRole('button', { name: '매장 저장' }))
    expect(await screen.findByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '관련 데이터 다시 조회' }))
    expect(await screen.findByRole('option', { name: /Reconciled Venue/ })).toBeInTheDocument()
    expect(screen.getByLabelText('이름', { selector: '#venue-name' })).toHaveValue('Reconciled Venue')
    expect(screen.queryByText('작업 결과를 확인할 수 없습니다')).not.toBeInTheDocument()
  })

  it('clears result unknown only after successful Slot reconciliation', async () => {
    const slotApi = makeApi({
      listSlots: vi.fn().mockResolvedValue([slot]),
      createSlot: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('NETWORK_ERROR'),
      ),
    })
    await selectVenue(slotApi)
    fireEvent.change(screen.getByLabelText('테이블', { selector: '#slot-resource' }), {
      target: { value: resource.id },
    })
    fireEvent.change(screen.getByLabelText('시작 날짜와 시간'), {
      target: { value: '2099-08-31T18:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '예약 시간대 생성' }))
    expect(await screen.findByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '관련 데이터 다시 조회' }))
    await waitFor(() => expect(slotApi.listSlots).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('작업 결과를 확인할 수 없습니다')).not.toBeInTheDocument())

  })

  it('keeps a filtered cancel result unknown until the exact target GET succeeds', async () => {
    let resolveExact: ((value: ReservationDetails) => void) | undefined
    let resolveList: ((value: ManagementReservation[]) => void) | undefined
    const listReservations = vi.fn()
      .mockResolvedValueOnce([reservation])
      .mockResolvedValueOnce([reservation])
      .mockReturnValueOnce(new Promise<ManagementReservation[]>((resolve) => {
        resolveList = resolve
      }))
    const getReservation = vi.fn().mockReturnValue(
      new Promise<ReservationDetails>((resolve) => { resolveExact = resolve }),
    )
    const api = makeApi({
      listReservations,
      getReservation,
      commandReservation: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('NETWORK_ERROR'),
      ),
    })
    render(<ManagementFlowWithNavigation api={api} />)
    await screen.findByRole('option', { name: /Owner Venue/ })
    fireEvent.change(screen.getByRole('combobox', { name: '매장' }), {
      target: { value: ownerVenue.id },
    })
    await screen.findByRole('list', { name: '예약 목록' })
    fireEvent.change(screen.getByLabelText('상태', { selector: '#reservation-status' }), {
      target: { value: 'CONFIRMED' },
    })
    await waitFor(() => expect(listReservations).toHaveBeenCalledTimes(2))

    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(await screen.findByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()
    const venueSelect = screen.getByRole('combobox', { name: '매장' })
    const dateInput = screen.getByLabelText('매장 기준 날짜')
    const statusSelect = screen.getByLabelText('상태', { selector: '#reservation-status' })
    const originalDate = (dateInput as HTMLInputElement).value
    expect(venueSelect).toBeDisabled()
    expect(dateInput).toBeDisabled()
    expect(statusSelect).toBeDisabled()
    fireEvent.change(venueSelect, { target: { value: '' } })
    fireEvent.change(dateInput, { target: { value: '2099-09-01' } })
    fireEvent.change(statusSelect, { target: { value: 'CANCELLED' } })
    expect(venueSelect).toHaveValue(ownerVenue.id)
    expect(dateInput).toHaveValue(originalDate)
    expect(statusSelect).toHaveValue('CONFIRMED')

    fireEvent.click(screen.getByRole('button', { name: '원래 예약 다시 조회' }))
    await waitFor(() => expect(getReservation).toHaveBeenCalledWith(ownerVenue.id, reservation.id))
    expect(listReservations).toHaveBeenCalledTimes(2)
    expect(screen.getByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()

    await act(async () => { resolveExact?.(reservationDetails) })
    expect(await screen.findByText(/예약 reservation-a 최신 상태/)).toBeInTheDocument()
    expect(screen.getByText('취소됨', { selector: '.notice--state .status-badge' })).toBeInTheDocument()
    await waitFor(() => expect(listReservations).toHaveBeenCalledTimes(3))
    expect(screen.queryByText('작업 결과를 확인할 수 없습니다')).not.toBeInTheDocument()
    const staleCancel = screen.queryByRole('button', { name: '취소' })
    if (staleCancel) expect(staleCancel).toBeDisabled()
    expect(venueSelect).toBeDisabled()
    expect(dateInput).toBeDisabled()
    expect(statusSelect).toBeDisabled()
    expect(screen.getByRole('button', { name: '고객 예약' })).toBeDisabled()

    await act(async () => { resolveList?.([]) })
    expect(listReservations).toHaveBeenLastCalledWith(ownerVenue.id, expect.any(String), 'CONFIRMED')
    expect(await screen.findByText('선택한 날짜와 상태의 예약이 없습니다.')).toBeInTheDocument()
    expect(venueSelect).toBeEnabled()
    expect(dateInput).toBeEnabled()
    expect(statusSelect).toBeEnabled()
    expect(screen.getByRole('button', { name: '고객 예약' })).toBeEnabled()
  })

  it('does not reactivate stale actions when the post-exact list refresh fails', async () => {
    const listReservations = vi.fn()
      .mockResolvedValueOnce([reservation])
      .mockResolvedValueOnce([reservation])
      .mockRejectedValueOnce(new ManagementApiError(500, 'INTERNAL_ERROR'))
    const api = makeApi({
      listReservations,
      getReservation: vi.fn().mockResolvedValue(reservationDetails),
      commandReservation: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('NETWORK_ERROR'),
      ),
    })
    render(<ManagementFlowWithNavigation api={api} />)
    await screen.findByRole('option', { name: /Owner Venue/ })
    fireEvent.change(screen.getByRole('combobox', { name: '매장' }), {
      target: { value: ownerVenue.id },
    })
    await screen.findByRole('list', { name: '예약 목록' })
    fireEvent.change(screen.getByLabelText('상태', { selector: '#reservation-status' }), {
      target: { value: 'CONFIRMED' },
    })
    await waitFor(() => expect(listReservations).toHaveBeenCalledTimes(2))

    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    fireEvent.click(await screen.findByRole('button', { name: '원래 예약 다시 조회' }))

    expect(await screen.findByText(/예약 reservation-a 최신 상태/)).toBeInTheDocument()
    expect(screen.getByText('취소됨', { selector: '.notice--state .status-badge' })).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('INTERNAL_ERROR')
    const staleCancel = screen.queryByRole('button', { name: '취소' })
    if (staleCancel) expect(staleCancel).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '매장' })).toBeEnabled()
    expect(screen.getByLabelText('매장 기준 날짜')).toBeEnabled()
    expect(screen.getByLabelText('상태', { selector: '#reservation-status' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '고객 예약' })).toBeEnabled()
  })

  it('keeps the original target unknown when its exact GET fails', async () => {
    const getReservation = vi.fn().mockRejectedValue(new ManagementApiError(500, 'INTERNAL_ERROR'))
    const api = makeApi({
      getReservation,
      commandReservation: vi.fn().mockRejectedValue(
        new ManagementMutationResultUnknownError('INTERNAL_ERROR'),
      ),
    })
    await selectVenue(api)
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    fireEvent.click(await screen.findByRole('button', { name: '원래 예약 다시 조회' }))

    expect(await screen.findByText('원래 예약의 결과는 아직 확정하지 않았습니다.')).toBeInTheDocument()
    expect(screen.getByText('작업 결과를 확인할 수 없습니다')).toBeInTheDocument()
    expect(getReservation).toHaveBeenCalledWith(ownerVenue.id, reservation.id)
    expect(screen.getByRole('combobox', { name: '매장' })).toBeDisabled()
  })

  it('re-fetches the selected date after Slot creation instead of appending another date', async () => {
    const otherDateSlot = {
      ...slot,
      id: 'slot-other-date',
      startsAt: '2099-09-01T18:00:00+09:00',
      endsAt: '2099-09-01T18:30:00+09:00',
    }
    const listSlots = vi.fn().mockResolvedValue([slot])
    const api = makeApi({
      listSlots,
      createSlot: vi.fn().mockResolvedValue(otherDateSlot),
    })
    await selectVenue(api)
    fireEvent.change(screen.getByLabelText('매장 기준 날짜'), { target: { value: '2099-08-31' } })
    await waitFor(() => expect(listSlots).toHaveBeenCalledTimes(2))
    fireEvent.change(screen.getByLabelText('테이블', { selector: '#slot-resource' }), {
      target: { value: resource.id },
    })
    fireEvent.change(screen.getByLabelText('시작 날짜와 시간'), {
      target: { value: '2099-09-01T18:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '예약 시간대 생성' }))

    await waitFor(() => expect(listSlots).toHaveBeenCalledTimes(3))
    expect(listSlots).toHaveBeenLastCalledWith(ownerVenue.id, '2099-08-31')
    expect(document.querySelector(`time[datetime="${otherDateSlot.startsAt}"]`)).not.toBeInTheDocument()
  })

  it.each([
    new ManagementApiError(403, 'ACCESS_DENIED'),
    new ManagementApiError(404, 'RESOURCE_NOT_FOUND'),
    new ManagementApiError(500, 'INTERNAL_ERROR'),
  ])('distinguishes forbidden, hidden not-found, and server errors', async (error) => {
    const api = makeApi({ listReservations: vi.fn().mockRejectedValue(error) })
    render(<ManagementVenueFlow api={api} />)
    await screen.findByRole('option', { name: /Owner Venue/ })
    fireEvent.change(screen.getByRole('combobox', { name: '매장' }), { target: { value: ownerVenue.id } })
    expect(await screen.findByRole('alert')).toHaveTextContent(error.code)
  })
})
