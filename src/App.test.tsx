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
  setAutoCopyEnabled: vi.fn(),
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
  autoCopyEnabled: false,
  colorScheme: 'light',
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
  todaySkipped: 3,
  todayBackpressure: 2,
  todaySuppressed: 0,
  todayNotRun: 0,
  totalOtp: 4,
  totalRejected: 82,
  totalErrors: 0,
  totalSkipped: 11,
  totalBackpressure: 4,
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
  lifecycleCount: 0,
  observedRevisionCount: 0,
  storedBytes: 0,
  oldestAt: null,
  adbPullExpiresAt: null,
}

const unlockedDeveloper = {
  unlocked: true,
  labAuthenticated: true,
  captureEnabled: false,
  recordCount: 12,
  lifecycleCount: 7,
  observedRevisionCount: 41,
  storedBytes: 4096,
  oldestAt: 1_700_000_000_000,
  adbPullExpiresAt: null,
}

beforeEach(() => {
  window.location.hash = ''
  localStorage.removeItem('needlebub-theme-preference')
  delete document.documentElement.dataset.theme
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  for (const value of Object.values(mockNeedleBub)) value.mockReset()
  mockNeedleBub.status.mockResolvedValue(activeStatus)
  mockNeedleBub.listPacks.mockResolvedValue({ packs: [otpPack] })
  mockNeedleBub.catalogue.mockResolvedValue({ entries: [] })
  mockNeedleBub.listNotificationApps.mockResolvedValue({ apps: [] })
  mockNeedleBub.diagnostics.mockResolvedValue({ version: '0.1.0-beta.1', engineAbi: 'needle-2.0.9' })
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
  mockNeedleBub.setAutoCopyEnabled.mockResolvedValue(undefined)
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

describe('NeedleBub capability host beta.1', () => {
  it('applies the Android system color scheme returned by the native host', async () => {
    mockNeedleBub.status.mockResolvedValue({ ...activeStatus, colorScheme: 'dark' })
    render(<App />)

    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'))
  })

  it('persists a manual theme choice independently of the Android system color', async () => {
    window.location.hash = '#/settings'
    const user = userEvent.setup()
    const { unmount } = render(<App />)
    await user.click(await screen.findByRole('button', { name: 'Dark' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('needlebub-theme-preference')).toBe('dark')
    unmount()
    render(<App />)
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'))
  })

  it('shows unavailable diagnostics after a completed empty read', async () => {
    window.location.hash = '#/settings'
    mockNeedleBub.diagnostics.mockResolvedValue({})
    render(<App />)
    await waitFor(() => expect(screen.getAllByText('Unavailable')).toHaveLength(2))
  })

  it('opens the built-in model from Overview into a compact operational console', async () => {
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Your models.' })).toBeInTheDocument()
    expect(screen.getByText('OTP Extractor · verified official model · 1 code today')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Open one-time codes' }))
    expect(await screen.findByRole('heading', { name: 'One-time codes.' })).toBeInTheDocument()
    expect(screen.getByText('Ready for codes.')).toBeInTheDocument()
    expect(screen.getByText('1 code handled')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument()
  })

  it('shows only the next setup action in the same feature module', async () => {
    mockNeedleBub.status.mockResolvedValue({
      ...activeStatus,
      notificationPermission: false,
      automaticOtpConfigured: false,
    })
    render(<App />)

    expect(await screen.findByText('Setup needed')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Open one-time codes' }))
    expect(await screen.findByRole('button', { name: 'Allow notifications' })).toBeInTheDocument()
    expect(screen.getByText('Allow private result notifications.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download model' })).not.toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'One-time codes' })).not.toBeInTheDocument()
  })

  it('keeps historical activity accessible when setup needs attention', async () => {
    window.location.hash = '#/features/otp'
    mockNeedleBub.status.mockResolvedValue({ ...activeStatus, notificationAccess: false, automaticOtpConfigured: false })
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByRole('button', { name: 'View past activity' }))
    expect(await screen.findByRole('heading', { name: 'Activity.' })).toBeInTheDocument()
  })

  it('pauses the feature and opens graph-free seven-day activity', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Open one-time codes' }))
    await user.click(screen.getByRole('button', { name: 'Pause' }))
    await waitFor(() => expect(mockNeedleBub.setAutomaticOtpEnabled).toHaveBeenCalledWith({ enabled: false }))

    await user.click(screen.getByRole('button', { name: 'View activity' }))
    expect(window.location.hash).toBe('#/features/otp/activity')
    expect(await screen.findByRole('heading', { name: 'Activity.' })).toBeInTheDocument()
    expect(screen.getByText('Past 7 days')).toBeInTheDocument()
    expect(screen.queryByText('Skipped')).not.toBeInTheDocument()
  })

  it('stages independent source policies and retains draft on failed Save', async () => {
    window.location.hash = '#/features/otp/sources'
    mockNeedleBub.listNotificationApps.mockResolvedValue({
      apps: [{ packageName: 'de.bank', label: 'Needle Bank', selected: true, excluded: false }, { packageName: 'de.mail', label: 'Needle Mail', selected: false, excluded: true }],
    })
    mockNeedleBub.saveNotificationApps.mockRejectedValue(new Error('storage unavailable'))
    const user = userEvent.setup()
    render(<App />)

    const only = await screen.findByRole('radio', { name: /Only these apps/ })
    const except = screen.getByRole('radio', { name: /All except these apps/ })
    expect(except).toBeChecked()
    await user.click(only)
    expect(screen.getByRole('checkbox', { name: 'Needle Bank: Allowed' })).toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Save policy' }))
    await waitFor(() => expect(mockNeedleBub.saveNotificationApps).toHaveBeenCalledWith({ allApps: false, packages: ['de.bank'], excludedPackages: ['de.mail'] }))
    expect(await screen.findByText('Could not save this source policy. Your previous selection is still active.')).toBeInTheDocument()
    expect(only).toBeChecked()
  })

  it('blocks source edits and Save when installed apps fail to load', async () => {
    window.location.hash = '#/features/otp/sources'
    mockNeedleBub.listNotificationApps.mockRejectedValue(new Error('App list unavailable'))
    render(<App />)
    expect(await screen.findByText('Could not load installed apps. Your saved source policy has not changed.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save policy' })).not.toBeInTheDocument()
    expect(mockNeedleBub.saveNotificationApps).not.toHaveBeenCalled()
  })

  it('keeps a saved source policy visible when status refresh fails and retries it', async () => {
    window.location.hash = '#/features/otp/sources'
    mockNeedleBub.listNotificationApps.mockResolvedValue({ apps: [
      { packageName: 'de.bank', label: 'Needle Bank', selected: true, excluded: false },
      { packageName: 'de.mail', label: 'Needle Mail', selected: false, excluded: false },
    ] })
    mockNeedleBub.status
      .mockResolvedValueOnce(activeStatus)
      .mockRejectedValueOnce(new Error('status unavailable'))
      .mockResolvedValue({ ...activeStatus, allApps: false, selectedAppCount: 1 })
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByRole('radio', { name: /Only these apps/ }))
    await user.click(screen.getByRole('button', { name: 'Save policy' }))

    expect(await screen.findByText('Source policy saved, but current status could not be refreshed. Try again.')).toBeInTheDocument()
    expect(screen.getByText('1 of 2 apps allowed')).toBeInTheDocument()
    expect(window.location.hash).toBe('#/features/otp/sources')
    expect(screen.getByRole('radio', { name: /Only these apps/ })).toBeChecked()
    expect(screen.queryByRole('button', { name: 'Save policy' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry status' }))
    expect(await screen.findByRole('heading', { name: 'One-time codes.' })).toBeInTheDocument()
    expect(screen.getAllByText('1 app')).toHaveLength(2)
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

  it('keeps MacroDroid app-wide instead of presenting it as an OTP feature setting', async () => {
    window.location.hash = '#/features/otp'
    const { unmount } = render(<App />)

    expect(await screen.findByRole('heading', { name: 'One-time codes.' })).toBeInTheDocument()
    expect(screen.queryByText('Automation')).not.toBeInTheDocument()
    expect(screen.queryByText('MacroDroid installed')).not.toBeInTheDocument()

    unmount()
    window.location.hash = '#/settings/integrations'
    render(<App />)

    expect(await screen.findByText('MacroDroid')).toBeInTheDocument()
    expect(screen.getByText('Run any installed external capability pack from MacroDroid.')).toBeInTheDocument()
  })

  it('offers auto-copy as an OTP feature setting that persists through the bridge', async () => {
    window.location.hash = '#/features/otp'
    const user = userEvent.setup()
    render(<App />)

    const toggle = await screen.findByRole('switch', { name: 'Auto-copy codes' })
    expect(toggle).not.toBeChecked()
    expect(screen.getByText('Replace your clipboard with new codes while your phone is unlocked.')).toBeInTheDocument()

    await user.click(toggle)
    await waitFor(() => expect(mockNeedleBub.setAutoCopyEnabled).toHaveBeenCalledWith({ enabled: true }))
  })

  it('restores the previous auto-copy selection when the bridge rejects the change', async () => {
    window.location.hash = '#/features/otp'
    mockNeedleBub.setAutoCopyEnabled.mockRejectedValue(new Error('storage unavailable'))
    const user = userEvent.setup()
    render(<App />)

    const toggle = await screen.findByRole('switch', { name: 'Auto-copy codes' })
    await user.click(toggle)

    await waitFor(() => expect(mockNeedleBub.setAutoCopyEnabled).toHaveBeenCalledWith({ enabled: true }))
    expect(toggle).not.toBeChecked()
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

    const version = await screen.findByRole('button', { name: 'Version 0.1.0-beta.1' })
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
      records: [{ id: 'one', capturedAt: 2, appLabel: 'Bank', title: 'First trace', decision: 'OTP', reasonCode: 'OTP_ACCEPTED', firstSeenAt: 1, lastSeenAt: 2, revisionCount: 3, supersededCount: 0, extractionKind: 'messaging' }],
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
      records: [{ id: 'one', capturedAt: 2, appLabel: 'Bank', title: 'First trace', decision: 'OTP', reasonCode: 'OTP_ACCEPTED', firstSeenAt: 1, lastSeenAt: 2, revisionCount: 3, supersededCount: 0, extractionKind: 'messaging' }],
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
      records: [{ id: 'one', capturedAt: 2, appLabel: 'Bank', title: 'First trace', decision: 'OTP', reasonCode: 'OTP_ACCEPTED', firstSeenAt: 1, lastSeenAt: 2, revisionCount: 3, supersededCount: 0, extractionKind: 'messaging' }],
        nextCursor: 2,
      })
      .mockResolvedValueOnce({
        records: [{ id: 'two', capturedAt: 1, appLabel: 'Shop', title: 'Second trace', decision: 'REJECTED', reasonCode: 'MODEL_NO_MATCH', firstSeenAt: 1, lastSeenAt: 1, revisionCount: 1, supersededCount: 0, extractionKind: 'ordinary' }],
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
