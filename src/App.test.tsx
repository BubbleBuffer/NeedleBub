import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from './App'

const mockNeedleBub = vi.hoisted(() => ({
  status: vi.fn(),
  requestNotificationPermission: vi.fn(),
  openNotificationAccess: vi.fn(),
  openNotificationSettings: vi.fn(),
  openMacroDroid: vi.fn(),
  setAutomaticOtpEnabled: vi.fn(),
  runColdModelCheck: vi.fn(),
  listPacks: vi.fn(),
  removePack: vi.fn(),
  pickPack: vi.fn(),
  catalogue: vi.fn(),
  installCataloguePack: vi.fn(),
  listNotificationApps: vi.fn(),
  saveNotificationApps: vi.fn(),
  diagnostics: vi.fn(),
  developerDataStatus: vi.fn(),
  unlockDeveloperData: vi.fn(),
  setNotificationCaptureEnabled: vi.fn(),
  exportNotificationCapture: vi.fn(),
  clearNotificationCapture: vi.fn(),
  listPersistentDiagnostics: vi.fn(),
  clearPersistentDiagnostics: vi.fn(),
  getPackUpdateStatus: vi.fn(),
  setAutomaticPackUpdates: vi.fn(),
  checkForPackUpdates: vi.fn(),
  authenticateDeveloperLab: vi.fn(),
  closeDeveloperLab: vi.fn(),
  listNotificationRecords: vi.fn(),
  getNotificationRecord: vi.fn(),
}))

vi.mock('./native', () => ({ needleBub: mockNeedleBub }))

const activeStatus = {
  otpPackInstalled: true,
  notificationAccess: true,
  notificationPermission: true,
  allApps: true,
  selectedAppCount: 0,
  automaticOtpConfigured: true,
  automaticOtpEnabled: true,
  macroDroidInstalled: true,
}

const otpPack = {
  id: 'de.x0bubbuff.needlebub.otp',
  version: '1.0.0-alpha.2',
  name: 'OTP Extractor',
  author: 'BubbleBuffer',
  description: 'Extracts one-time authentication codes.',
  license: 'Apache-2.0',
  verified: true,
  active: true,
  surfaces: ['notification'],
  outputs: ['nb_code', 'nb_source'],
}

beforeEach(() => {
  window.location.hash = ''
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  for (const value of Object.values(mockNeedleBub)) value.mockReset()
  mockNeedleBub.status.mockResolvedValue(activeStatus)
  mockNeedleBub.listPacks.mockResolvedValue({ packs: [otpPack] })
  mockNeedleBub.catalogue.mockResolvedValue({ entries: [] })
  mockNeedleBub.listNotificationApps.mockResolvedValue({ apps: [] })
  mockNeedleBub.diagnostics.mockResolvedValue({ platform: 'test' })
  mockNeedleBub.developerDataStatus.mockResolvedValue({ unlocked: false, labAuthenticated: false, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null })
  mockNeedleBub.unlockDeveloperData.mockResolvedValue({ unlocked: true })
  mockNeedleBub.setNotificationCaptureEnabled.mockResolvedValue(undefined)
  mockNeedleBub.exportNotificationCapture.mockResolvedValue({ exported: 0, deleted: false })
  mockNeedleBub.clearNotificationCapture.mockResolvedValue({ removed: 0 })
  mockNeedleBub.listPersistentDiagnostics.mockResolvedValue({ entries: [] })
  mockNeedleBub.clearPersistentDiagnostics.mockResolvedValue({ removed: 0 })
  mockNeedleBub.getPackUpdateStatus.mockResolvedValue({
    enabled: true,
    networkPolicy: 'unmetered',
    state: 'up_to_date',
    currentVersion: '1.0.0-alpha.2',
    availableVersion: null,
    lastCheckedAt: 1_800_000_000_000,
    lastUpdatedAt: null,
    lastError: null,
  })
  mockNeedleBub.setAutomaticPackUpdates.mockResolvedValue(undefined)
  mockNeedleBub.checkForPackUpdates.mockResolvedValue(undefined)
  mockNeedleBub.authenticateDeveloperLab.mockResolvedValue({ authenticated: true })
  mockNeedleBub.closeDeveloperLab.mockResolvedValue(undefined)
  mockNeedleBub.listNotificationRecords.mockResolvedValue({ records: [], nextCursor: null })
  mockNeedleBub.getNotificationRecord.mockRejectedValue(new Error('missing'))
  mockNeedleBub.setAutomaticOtpEnabled.mockResolvedValue(undefined)
  mockNeedleBub.saveNotificationApps.mockResolvedValue(undefined)
  mockNeedleBub.removePack.mockResolvedValue({ removed: true })
})

describe('NeedleBub compact utility shell', () => {
  it('shows one compact operational home without showcase navigation', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Automatic OTP' })).toBeInTheDocument()
    expect(screen.getByText('Listening to all notification apps')).toBeInTheDocument()
    expect(screen.getByText('All apps → OTP Extractor → private notification')).toBeInTheDocument()
    expect(screen.queryByText('Tiny models.')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Status' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect' })).not.toBeInTheDocument()
  })

  it('offers only the next missing setup action', async () => {
    mockNeedleBub.status.mockResolvedValue({
      ...activeStatus,
      notificationPermission: false,
      automaticOtpConfigured: false,
    })
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Set up automatic OTP' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Allow notifications' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Install OTP pack' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Choose sources' })).not.toBeInTheDocument()
  })

  it('pauses automatic OTP without changing setup', async () => {
    const user = userEvent.setup()
    mockNeedleBub.status
      .mockResolvedValueOnce(activeStatus)
      .mockResolvedValue({ ...activeStatus, automaticOtpEnabled: false })
    render(<App />)

    const toggle = await screen.findByRole('switch', { name: 'Automatic OTP' })
    await user.click(toggle)

    await waitFor(() => expect(mockNeedleBub.setAutomaticOtpEnabled).toHaveBeenCalledWith({ enabled: false }))
    expect(await screen.findByText('Paused')).toBeInTheDocument()
  })

  it('uses hash routes for drill-ins and Android-compatible back navigation', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: /Sources/ }))
    expect(window.location.hash).toBe('#/sources')
    expect(await screen.findByRole('heading', { name: 'Sources' })).toBeInTheDocument()

    window.history.back()
    await waitFor(() => expect(window.location.hash).toBe(''))
    expect(await screen.findByRole('heading', { name: 'Automatic OTP' })).toBeInTheDocument()
  })

  it('shows only sanitized output from the cold model check', async () => {
    window.location.hash = '#/advanced'
    mockNeedleBub.runColdModelCheck.mockResolvedValue({
      passed: true,
      errorCode: null,
      durationMs: 716,
      coldLoad: true,
      pssKb: 60_058,
      code: '739241',
      resultJson: '{"code":"739241"}',
    })
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Run cold model check' }))
    expect(await screen.findByText('Passed · cold load · 716 ms · 59 MiB')).toBeInTheDocument()
    expect(screen.queryByText('739241')).not.toBeInTheDocument()
    expect(screen.queryByText(/resultJson/)).not.toBeInTheDocument()
  })

  it('autosaves source changes and restores the previous choice on failure', async () => {
    window.location.hash = '#/sources'
    mockNeedleBub.listNotificationApps.mockResolvedValue({ apps: [{ packageName: 'de.bank', label: 'Needle Bank', selected: true }] })
    mockNeedleBub.saveNotificationApps.mockRejectedValue(new Error('storage unavailable'))
    const user = userEvent.setup()
    render(<App />)

    const toggle = await screen.findByRole('switch', { name: 'All notification apps' })
    expect(toggle).toBeChecked()
    await user.click(toggle)

    await waitFor(() => expect(mockNeedleBub.saveNotificationApps).toHaveBeenCalledWith({ allApps: false, packages: ['de.bank'] }))
    expect(await screen.findByText('Could not save that source change. Your previous selection is still active.')).toBeInTheDocument()
    expect(toggle).toBeChecked()
  })

  it('names the automation consequence before removing the official pack', async () => {
    window.location.hash = '#/model'
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByText('OTP Extractor'))
    await user.click(screen.getByRole('button', { name: 'Remove OTP Extractor' }))

    expect(confirm).toHaveBeenCalledWith('Remove OTP Extractor 1.0.0-alpha.2? Automatic OTP will stop until it is reinstalled.')
    expect(mockNeedleBub.removePack).toHaveBeenCalledWith({ id: 'de.x0bubbuff.needlebub.otp', version: '1.0.0-alpha.2' })
    confirm.mockRestore()
  })

  it('shows automatic update state on the focused Model route', async () => {
    window.location.hash = '#/model'
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Model' })).toBeInTheDocument()
    expect(screen.getByText('Wi-Fi only')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Automatic pack updates' })).toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Check now' }))
    await waitFor(() => expect(mockNeedleBub.checkForPackUpdates).toHaveBeenCalled())
  })

  it('adds an authenticated Lab route only after developer unlock', async () => {
    mockNeedleBub.developerDataStatus.mockResolvedValue({
      unlocked: true,
      labAuthenticated: false,
      captureEnabled: true,
      recordCount: 1,
      storedBytes: 1024,
      oldestAt: 1,
    })
    mockNeedleBub.listNotificationRecords.mockResolvedValue({
      records: [{
        id: 'record-1',
        capturedAt: 1_800_000_000_000,
        appLabel: 'YouTube',
        title: 'Avoiding Bot Detection',
        decision: 'REJECTED',
        reasonCode: 'MODEL_NO_MATCH',
      }],
      nextCursor: null,
    })
    render(<App />)

    expect(await screen.findByRole('button', { name: /Lab/ })).toBeInTheDocument()
    window.location.hash = '#/lab'
    expect(await screen.findByRole('heading', { name: 'Notification Lab' })).toBeInTheDocument()
    await waitFor(() => expect(mockNeedleBub.authenticateDeveloperLab).toHaveBeenCalled())
    expect(await screen.findByText('Avoiding Bot Detection')).toBeInTheDocument()
    expect(screen.getAllByText('Rejected')).toHaveLength(2)
  })

  it('clears decrypted Lab state in the background and authenticates again on return', async () => {
    window.location.hash = '#/lab'
    mockNeedleBub.developerDataStatus.mockResolvedValue({
      unlocked: true,
      labAuthenticated: false,
      captureEnabled: true,
      recordCount: 1,
      storedBytes: 1024,
      oldestAt: 1,
    })
    mockNeedleBub.listNotificationRecords.mockResolvedValue({
      records: [{
        id: 'record-1',
        capturedAt: 1_800_000_000_000,
        appLabel: 'YouTube',
        title: 'Avoiding Bot Detection',
        decision: 'REJECTED',
        reasonCode: 'MODEL_NO_MATCH',
      }],
      nextCursor: null,
    })
    render(<App />)
    expect(await screen.findByText('Avoiding Bot Detection')).toBeInTheDocument()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() => expect(screen.queryByText('Avoiding Bot Detection')).not.toBeInTheDocument())
    expect(mockNeedleBub.closeDeveloperLab).toHaveBeenCalled()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() => expect(mockNeedleBub.authenticateDeveloperLab).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Avoiding Bot Detection')).toBeInTheDocument()
  })

  it('paginates Lab records without replacing the current page', async () => {
    window.location.hash = '#/lab'
    mockNeedleBub.developerDataStatus.mockResolvedValue({
      unlocked: true,
      labAuthenticated: false,
      captureEnabled: true,
      recordCount: 2,
      storedBytes: 2048,
      oldestAt: 1,
    })
    mockNeedleBub.listNotificationRecords
      .mockResolvedValueOnce({
        records: [{ id: 'one', capturedAt: 2, appLabel: 'Bank', title: 'First trace', decision: 'OTP', reasonCode: 'OTP_ACCEPTED' }],
        nextCursor: 2,
      })
      .mockResolvedValueOnce({
        records: [{ id: 'two', capturedAt: 1, appLabel: 'Shop', title: 'Second trace', decision: 'REJECTED', reasonCode: 'MODEL_NO_MATCH' }],
        nextCursor: null,
      })
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByText('First trace')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await screen.findByText('Second trace')).toBeInTheDocument()
    expect(screen.getByText('First trace')).toBeInTheDocument()
    expect(mockNeedleBub.listNotificationRecords).toHaveBeenLastCalledWith({ cursor: 2, limit: 50, filter: 'ALL' })
  })

  it('reveals developer data only after seven version taps and reports progress from the second tap', async () => {
    window.location.hash = '#/advanced'
    mockNeedleBub.diagnostics.mockResolvedValue({ version: '0.1.0-alpha.6', platform: 'test' })
    mockNeedleBub.developerDataStatus
      .mockResolvedValueOnce({ unlocked: false, labAuthenticated: false, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null })
      .mockResolvedValue({ unlocked: true, labAuthenticated: false, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null })
    const user = userEvent.setup()
    render(<App />)

    const buildFacts = await screen.findByText('Diagnostics and build facts')
    await user.click(buildFacts)
    await user.click(buildFacts)
    expect(mockNeedleBub.unlockDeveloperData).not.toHaveBeenCalled()

    await user.click(buildFacts)
    const version = await screen.findByRole('button', { name: 'Version 0.1.0-alpha.6' })
    await user.click(version)
    expect(screen.queryByText(/more taps to unlock Developer Mode/)).not.toBeInTheDocument()
    await user.click(version)
    expect(await screen.findByText('5 more taps to unlock Developer Mode.')).toBeInTheDocument()
    for (let index = 0; index < 4; index += 1) await user.click(version)
    expect(screen.queryByText('Notification Lab')).not.toBeInTheDocument()

    await user.click(version)
    await waitFor(() => expect(mockNeedleBub.unlockDeveloperData).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('Notification Lab')).toBeInTheDocument()
    expect(screen.getByText(/authentication required/)).toBeInTheDocument()
  })

  it('keeps capture opt-in and names its sensitive local persistence', async () => {
    window.location.hash = '#/lab'
    mockNeedleBub.developerDataStatus.mockResolvedValue({ unlocked: true, labAuthenticated: false, captureEnabled: false, recordCount: 12, storedBytes: 4096, oldestAt: 1_700_000_000_000 })
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<App />)

    const toggle = await screen.findByRole('switch', { name: 'Notification capture' })
    expect(toggle).not.toBeChecked()
    expect(screen.getByText(/full notification text and model output/i)).toBeInTheDocument()
    await user.click(toggle)

    await waitFor(() => expect(mockNeedleBub.setNotificationCaptureEnabled).toHaveBeenCalledWith({ enabled: true }))

    const password = screen.getByLabelText('Export password')
    await user.type(password, 'twelve-chars!')
    await user.click(screen.getByRole('button', { name: 'Export encrypted capture' }))
    await waitFor(() => expect(mockNeedleBub.exportNotificationCapture).toHaveBeenCalledWith({ passphrase: 'twelve-chars!', deleteAfterExport: true }))
    expect(password).toHaveValue('')
    confirm.mockRestore()
  })
})
