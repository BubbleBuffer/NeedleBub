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
  version: '1.0.0-alpha.1',
  name: 'OTP Extractor',
  author: 'BubbleBuffer',
  description: 'Extracts one-time authentication codes.',
  license: 'Apache-2.0',
  verified: true,
  surfaces: ['notification'],
  outputs: ['nb_code', 'nb_source'],
}

beforeEach(() => {
  window.location.hash = ''
  for (const value of Object.values(mockNeedleBub)) value.mockReset()
  mockNeedleBub.status.mockResolvedValue(activeStatus)
  mockNeedleBub.listPacks.mockResolvedValue({ packs: [otpPack] })
  mockNeedleBub.catalogue.mockResolvedValue({ entries: [] })
  mockNeedleBub.listNotificationApps.mockResolvedValue({ apps: [] })
  mockNeedleBub.diagnostics.mockResolvedValue({ platform: 'test' })
  mockNeedleBub.developerDataStatus.mockResolvedValue({ unlocked: false, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null })
  mockNeedleBub.unlockDeveloperData.mockResolvedValue({ unlocked: true })
  mockNeedleBub.setNotificationCaptureEnabled.mockResolvedValue(undefined)
  mockNeedleBub.exportNotificationCapture.mockResolvedValue({ exported: 0, deleted: false })
  mockNeedleBub.clearNotificationCapture.mockResolvedValue({ removed: 0 })
  mockNeedleBub.listPersistentDiagnostics.mockResolvedValue({ entries: [] })
  mockNeedleBub.clearPersistentDiagnostics.mockResolvedValue({ removed: 0 })
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
    window.location.hash = '#/packs'
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByText('OTP Extractor'))
    await user.click(screen.getByRole('button', { name: 'Remove OTP Extractor' }))

    expect(confirm).toHaveBeenCalledWith('Remove OTP Extractor 1.0.0-alpha.1? Automatic OTP will stop until it is reinstalled.')
    expect(mockNeedleBub.removePack).toHaveBeenCalledWith({ id: 'de.x0bubbuff.needlebub.otp', version: '1.0.0-alpha.1' })
    confirm.mockRestore()
  })

  it('reveals developer data only after seven build-fact activations', async () => {
    window.location.hash = '#/advanced'
    mockNeedleBub.developerDataStatus
      .mockResolvedValueOnce({ unlocked: false, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null })
      .mockResolvedValue({ unlocked: true, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null })
    const user = userEvent.setup()
    render(<App />)

    const buildFacts = await screen.findByText('Diagnostics and build facts')
    for (let index = 0; index < 6; index += 1) await user.click(buildFacts)
    expect(screen.queryByText('Notification capture')).not.toBeInTheDocument()

    await user.click(buildFacts)
    await waitFor(() => expect(mockNeedleBub.unlockDeveloperData).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('Notification capture')).toBeInTheDocument()
    expect(screen.getByText(/Capture is off/)).toBeInTheDocument()
  })

  it('keeps capture opt-in and names its sensitive local persistence', async () => {
    window.location.hash = '#/advanced'
    mockNeedleBub.developerDataStatus.mockResolvedValue({ unlocked: true, captureEnabled: false, recordCount: 12, storedBytes: 4096, oldestAt: 1_700_000_000_000 })
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
