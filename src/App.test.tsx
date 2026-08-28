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
  setPackUpdateNetworkPolicy: vi.fn(),
  checkForPackUpdates: vi.fn(),
  authenticateDeveloperLab: vi.fn(),
  grantAdbCapturePull: vi.fn(),
  revokeAdbCapturePull: vi.fn(),
  closeDeveloperLab: vi.fn(),
  listNotificationRecords: vi.fn(),
  getNotificationRecord: vi.fn(),
  getFeatureActivity: vi.fn(),
  resetFeatureActivity: vi.fn(),
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

const activity = {
  featureId: 'otp',
  days: 7,
  todayOtp: 1,
  todayRejected: 37,
  todayErrors: 0,
  todaySuppressed: 0,
  todayNotRun: 0,
  totalOtp: 4,
  totalRejected: 82,
  totalErrors: 0,
  totalSuppressed: 0,
  totalNotRun: 3,
  completedInferenceCount: 86,
  averageDurationMs: 662,
  lastActivityAt: 1_800_000_000_000,
}

const lockedDeveloper = {
  unlocked: false,
  labAuthenticated: false,
  captureEnabled: false,
  recordCount: 0,
  storedBytes: 0,
  oldestAt: null,
  adbPullExpiresAt: null,
}

const unlockedDeveloper = {
  unlocked: true,
  labAuthenticated: true,
  captureEnabled: false,
  recordCount: 12,
  storedBytes: 4096,
  oldestAt: 1_700_000_000_000,
  adbPullExpiresAt: null,
}

beforeEach(() => {
  window.location.hash = ''
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  for (const value of Object.values(mockNeedleBub)) value.mockReset()
  mockNeedleBub.status.mockResolvedValue(activeStatus)
  mockNeedleBub.listPacks.mockResolvedValue({ packs: [otpPack] })
  mockNeedleBub.catalogue.mockResolvedValue({ entries: [] })
  mockNeedleBub.listNotificationApps.mockResolvedValue({ apps: [] })
  mockNeedleBub.diagnostics.mockResolvedValue({ version: '0.1.0-alpha.7', engineAbi: 'needle-2.0.9' })
  mockNeedleBub.developerDataStatus.mockResolvedValue(lockedDeveloper)
  mockNeedleBub.unlockDeveloperData.mockResolvedValue({ unlocked: true })
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
  mockNeedleBub.getFeatureActivity.mockResolvedValue(activity)
  mockNeedleBub.setAutomaticOtpEnabled.mockResolvedValue(undefined)
  mockNeedleBub.saveNotificationApps.mockResolvedValue(undefined)
  mockNeedleBub.removePack.mockResolvedValue({ removed: true })
  mockNeedleBub.setAutomaticPackUpdates.mockResolvedValue(undefined)
  mockNeedleBub.setPackUpdateNetworkPolicy.mockResolvedValue(undefined)
  mockNeedleBub.checkForPackUpdates.mockResolvedValue(undefined)
  mockNeedleBub.authenticateDeveloperLab.mockResolvedValue({ authenticated: true })
  mockNeedleBub.closeDeveloperLab.mockResolvedValue(undefined)
  mockNeedleBub.listNotificationRecords.mockResolvedValue({ records: [], nextCursor: null })
  mockNeedleBub.setNotificationCaptureEnabled.mockResolvedValue(undefined)
  mockNeedleBub.exportNotificationCapture.mockResolvedValue({ exported: 0, deleted: false })
  mockNeedleBub.clearNotificationCapture.mockResolvedValue({ removed: 0 })
  mockNeedleBub.grantAdbCapturePull.mockResolvedValue({ expiresAt: Date.now() + 600_000 })
  mockNeedleBub.revokeAdbCapturePull.mockResolvedValue(undefined)
  mockNeedleBub.resetFeatureActivity.mockResolvedValue(undefined)
})

describe('NeedleBub capability host alpha.7', () => {
  it('renders One-time codes as a compact operational module', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'One-time codes' })).toBeInTheDocument()
    expect(screen.getByText('All apps')).toBeInTheDocument()
    expect(screen.getByText('OTP Extractor α2')).toBeInTheDocument()
    expect(screen.getByText('1 code · 37 filtered today')).toBeInTheDocument()
    expect(screen.getByText('Avg 0.7s')).toBeInTheDocument()
    expect(screen.getByText('Local runtime')).toBeInTheDocument()
    expect(screen.queryByText(/private notification$/)).not.toBeInTheDocument()
  })

  it('shows only the next setup action in the same feature module', async () => {
    mockNeedleBub.status.mockResolvedValue({
      ...activeStatus,
      notificationPermission: false,
      automaticOtpConfigured: false,
    })
    render(<App />)

    expect(await screen.findByRole('button', { name: 'Allow notifications' })).toBeInTheDocument()
    expect(screen.getByText('Private result notifications')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download model' })).not.toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'One-time codes' })).not.toBeInTheDocument()
  })

  it('keeps an independent Home toggle and opens the feature page without a duplicate body title', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('switch', { name: 'One-time codes' }))
    await waitFor(() => expect(mockNeedleBub.setAutomaticOtpEnabled).toHaveBeenCalledWith({ enabled: false }))

    await user.click(screen.getByRole('button', { name: 'Manage One-time codes' }))
    expect(window.location.hash).toBe('#/features/otp')
    expect(await screen.findByText('Seven-day activity')).toBeInTheDocument()
    expect(screen.queryAllByRole('heading', { name: 'One-time codes' })).toHaveLength(0)
  })

  it('autosaves source changes and restores the previous selection on failure', async () => {
    window.location.hash = '#/features/otp/sources'
    mockNeedleBub.listNotificationApps.mockResolvedValue({
      apps: [{ packageName: 'de.bank', label: 'Needle Bank', selected: true }],
    })
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

  it('warns that removing the official model stops the feature', async () => {
    window.location.hash = '#/features/otp'
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Remove OTP Extractor' }))
    expect(confirm).toHaveBeenCalledWith('Remove OTP Extractor 1.0.0-alpha.2? One-time codes will stop until it is reinstalled.')
    expect(mockNeedleBub.removePack).toHaveBeenCalledWith({ id: otpPack.id, version: otpPack.version })
    confirm.mockRestore()
  })

  it('moves update policy into Downloads and allows an explicit mobile manual check', async () => {
    window.location.hash = '#/settings/downloads'
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('switch', { name: 'Automatic pack updates' })).toBeChecked()
    const mobile = screen.getByRole('switch', { name: 'Allow mobile downloads' })
    expect(mobile).not.toBeChecked()
    await user.click(mobile)
    await waitFor(() => expect(mockNeedleBub.setPackUpdateNetworkPolicy).toHaveBeenCalledWith({ allowMetered: true }))

    await user.click(screen.getByRole('button', { name: 'Check now' }))
    await waitFor(() => expect(mockNeedleBub.checkForPackUpdates).toHaveBeenCalledWith({ allowMetered: true }))
  })

  it('unlocks the Developer group with seven taps on Version and reports progress after the second', async () => {
    window.location.hash = '#/settings'
    let unlocked = false
    mockNeedleBub.developerDataStatus.mockImplementation(async () => ({ ...lockedDeveloper, unlocked }))
    mockNeedleBub.unlockDeveloperData.mockImplementation(async () => {
      unlocked = true
      return { unlocked: true }
    })
    const user = userEvent.setup()
    render(<App />)

    const version = await screen.findByRole('button', { name: 'Version 0.1.0-alpha.7' })
    await user.click(version)
    expect(screen.queryByText(/more taps/)).not.toBeInTheDocument()
    await user.click(version)
    expect(await screen.findByText('5 more taps to unlock Developer Mode.')).toBeInTheDocument()
    for (let index = 0; index < 5; index += 1) await user.click(version)

    expect(await screen.findByRole('button', { name: /Notification records/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Capture and export/ })).toBeInTheDocument()
  })

  it('shows only sanitized cold-model-check evidence', async () => {
    window.location.hash = '#/settings'
    mockNeedleBub.developerDataStatus.mockResolvedValue(unlockedDeveloper)
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

    await user.click(await screen.findByRole('button', { name: 'Run check' }))
    expect(await screen.findByText('Passed · cold load · 716 ms · 59 MiB')).toBeInTheDocument()
    expect(screen.queryByText('739241')).not.toBeInTheDocument()
    expect(screen.queryByText(/resultJson/)).not.toBeInTheDocument()
  })

  it('shares one authenticated foreground session between Records and Data', async () => {
    window.location.hash = '#/developer/records'
    let authenticated = false
    mockNeedleBub.developerDataStatus.mockImplementation(async () => ({ ...unlockedDeveloper, labAuthenticated: authenticated }))
    mockNeedleBub.authenticateDeveloperLab.mockImplementation(async () => {
      authenticated = true
      return { authenticated: true }
    })
    mockNeedleBub.listNotificationRecords.mockResolvedValue({
      records: [{ id: 'one', capturedAt: 2, appLabel: 'Bank', title: 'First trace', decision: 'OTP', reasonCode: 'OTP_ACCEPTED' }],
      nextCursor: null,
    })
    render(<App />)

    expect(await screen.findByText('First trace')).toBeInTheDocument()
    expect(mockNeedleBub.authenticateDeveloperLab).toHaveBeenCalledTimes(1)

    window.location.hash = '#/developer/data'
    expect(await screen.findByRole('switch', { name: 'Notification capture' })).toBeInTheDocument()
    expect(mockNeedleBub.authenticateDeveloperLab).toHaveBeenCalledTimes(1)
  })

  it('clears decrypted Records state in the background and authenticates again on return', async () => {
    window.location.hash = '#/developer/records'
    let authenticated = false
    mockNeedleBub.developerDataStatus.mockImplementation(async () => ({ ...unlockedDeveloper, labAuthenticated: authenticated }))
    mockNeedleBub.authenticateDeveloperLab.mockImplementation(async () => {
      authenticated = true
      return { authenticated: true }
    })
    mockNeedleBub.closeDeveloperLab.mockImplementation(async () => { authenticated = false })
    mockNeedleBub.listNotificationRecords.mockResolvedValue({
      records: [{ id: 'one', capturedAt: 2, appLabel: 'Bank', title: 'First trace', decision: 'OTP', reasonCode: 'OTP_ACCEPTED' }],
      nextCursor: null,
    })
    render(<App />)
    expect(await screen.findByText('First trace')).toBeInTheDocument()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() => expect(screen.queryByText('First trace')).not.toBeInTheDocument())
    expect(mockNeedleBub.closeDeveloperLab).toHaveBeenCalled()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(await screen.findByText('First trace')).toBeInTheDocument()
    expect(mockNeedleBub.authenticateDeveloperLab).toHaveBeenCalledTimes(2)
  })

  it('paginates Records without replacing the current page', async () => {
    window.location.hash = '#/developer/records'
    mockNeedleBub.developerDataStatus.mockResolvedValue(unlockedDeveloper)
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
  })

  it('keeps capture opt-in and exports only from the Data page', async () => {
    window.location.hash = '#/developer/data'
    let developer = { ...unlockedDeveloper }
    mockNeedleBub.developerDataStatus.mockImplementation(async () => developer)
    mockNeedleBub.setNotificationCaptureEnabled.mockImplementation(async ({ enabled }) => {
      developer = { ...developer, captureEnabled: enabled }
    })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<App />)

    const toggle = await screen.findByRole('switch', { name: 'Notification capture' })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)
    await waitFor(() => expect(mockNeedleBub.setNotificationCaptureEnabled).toHaveBeenCalledWith({ enabled: true }))

    const password = screen.getByLabelText('Export password')
    await user.type(password, 'twelve-chars!')
    await user.click(screen.getByRole('button', { name: 'Export encrypted capture' }))
    await waitFor(() => expect(mockNeedleBub.exportNotificationCapture).toHaveBeenCalledWith({ passphrase: 'twelve-chars!', deleteAfterExport: true }))
    expect(password).toHaveValue('')
    confirm.mockRestore()
  })

  it('renders and refreshes temporary ADB access from native status', async () => {
    window.location.hash = '#/developer/data'
    let adbPullExpiresAt: number | null = 1_900_000_600_000
    mockNeedleBub.developerDataStatus.mockImplementation(async () => ({ ...unlockedDeveloper, adbPullExpiresAt }))
    mockNeedleBub.revokeAdbCapturePull.mockImplementation(async () => { adbPullExpiresAt = null })
    mockNeedleBub.grantAdbCapturePull.mockImplementation(async () => {
      adbPullExpiresAt = Date.now() + 600_000
      return { expiresAt: adbPullExpiresAt }
    })
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByText(/ADB pull allowed until/i)).toBeInTheDocument()
    expect(mockNeedleBub.grantAdbCapturePull).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Revoke ADB pull' }))
    expect(await screen.findByRole('button', { name: 'Allow ADB pull for 10 minutes' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Allow ADB pull for 10 minutes' }))
    await waitFor(() => expect(mockNeedleBub.grantAdbCapturePull).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/ADB pull allowed until/i)).toBeInTheDocument()
  })

  it('resets content-free summaries from Privacy and data', async () => {
    window.location.hash = '#/settings/privacy'
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Reset activity summaries' }))
    await waitFor(() => expect(mockNeedleBub.resetFeatureActivity).toHaveBeenCalledWith())
    confirm.mockRestore()
  })
})
