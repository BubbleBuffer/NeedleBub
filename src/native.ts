import { Capacitor, registerPlugin } from '@capacitor/core'

export type AppStatus = { otpPackInstalled: boolean; notificationAccess: boolean; notificationPermission: boolean; allApps: boolean; selectedAppCount: number; automaticOtpConfigured: boolean; automaticOtpEnabled: boolean; macroDroidInstalled: boolean }
export type PackInfo = { id: string; version: string; name: string; author: string; description: string; license: string; verified: boolean; active: boolean; surfaces: string[]; outputs: string[] }
export type CatalogueEntry = { id: string; version: string; name: string; url: string; size: number; sha256: string; engineAbi: string }
export type NotificationApp = { packageName: string; label: string; selected: boolean }
export type DiagnosticInfo = Record<string, string | number | null>
export type ColdModelCheck = { passed: boolean; errorCode: string | null; durationMs: number; coldLoad: boolean; pssKb: number }
export type DeveloperDataStatus = { unlocked: boolean; labAuthenticated: boolean; captureEnabled: boolean; recordCount: number; storedBytes: number; oldestAt: number | null }
export type PersistentDiagnostic = { id: number; createdAt: number; packageName: string | null; category: string | null; stage: string; pack: string | null; status: string; errorCode: string | null; durationMs: number | null; pssKb: number | null; coldLoad: boolean | null }
export type PackUpdateStatus = { enabled: boolean; networkPolicy: 'unmetered'; state: string; currentVersion: string | null; availableVersion: string | null; lastCheckedAt: number | null; lastUpdatedAt: number | null; lastError: string | null }
export type NotificationRecordSummary = { id: string; capturedAt: number; appLabel: string; title: string; decision: string; reasonCode: string }
export type NotificationRecord = {
  id: string
  capturedAtEpochMs: number
  packageName: string
  appLabel: string
  category: string | null
  title: string
  body: string
  policyDecision: string
  automaticOtpEnabled: boolean
  runtime?: { packId?: string; packVersion?: string; status?: string; responseType?: string; engineSuccess?: boolean; engineErrorCode?: string; reasoning?: string; toolName?: string; resultJson?: string; errorCode?: string; durationMs?: number; coldLoad?: boolean; pssKb?: number; callCount?: number }
  outcome?: { decision: string; reasonCode: string; code?: string; source?: string; sourceDisposition?: string }
}

interface NeedleBubNative {
  status(): Promise<AppStatus>
  requestNotificationPermission(): Promise<{ state: string }>
  openNotificationAccess(): Promise<void>
  openNotificationSettings(): Promise<void>
  openMacroDroid(): Promise<void>
  setAutomaticOtpEnabled(options: { enabled: boolean }): Promise<void>
  runColdModelCheck(): Promise<ColdModelCheck>
  listPacks(): Promise<{ packs: PackInfo[] }>
  removePack(options: { id: string; version: string }): Promise<{ removed: boolean }>
  pickPack(): Promise<{ id: string; version: string; verified: boolean }>
  catalogue(): Promise<{ entries: CatalogueEntry[] }>
  installCataloguePack(options: { id: string }): Promise<{ id: string; version: string; verified: boolean }>
  listNotificationApps(): Promise<{ apps: NotificationApp[] }>
  saveNotificationApps(options: { allApps: boolean; packages: string[] }): Promise<void>
  diagnostics(): Promise<DiagnosticInfo>
  developerDataStatus(): Promise<DeveloperDataStatus>
  unlockDeveloperData(): Promise<{ unlocked: boolean }>
  setNotificationCaptureEnabled(options: { enabled: boolean }): Promise<void>
  exportNotificationCapture(options: { passphrase: string; deleteAfterExport: boolean }): Promise<{ exported: number; deleted: boolean }>
  clearNotificationCapture(): Promise<{ removed: number }>
  listPersistentDiagnostics(options?: { limit?: number }): Promise<{ entries: PersistentDiagnostic[] }>
  clearPersistentDiagnostics(): Promise<{ removed: number }>
  getPackUpdateStatus(): Promise<PackUpdateStatus>
  setAutomaticPackUpdates(options: { enabled: boolean }): Promise<void>
  checkForPackUpdates(): Promise<PackUpdateStatus>
  authenticateDeveloperLab(): Promise<{ authenticated: boolean }>
  closeDeveloperLab(): Promise<void>
  listNotificationRecords(options?: { cursor?: number; limit?: number; filter?: string }): Promise<{ records: NotificationRecordSummary[]; nextCursor: number | null }>
  getNotificationRecord(options: { id: string }): Promise<NotificationRecord>
}

const native = registerPlugin<NeedleBubNative>('NeedleBub')
const webStatus: AppStatus = { otpPackInstalled: false, notificationAccess: false, notificationPermission: false, allApps: false, selectedAppCount: 0, automaticOtpConfigured: false, automaticOtpEnabled: true, macroDroidInstalled: false }
const web: NeedleBubNative = {
  status: async () => webStatus,
  requestNotificationPermission: async () => ({ state: 'prompt' }),
  openNotificationAccess: async () => undefined,
  openNotificationSettings: async () => undefined,
  openMacroDroid: async () => { throw new Error('MacroDroid was not detected.') },
  setAutomaticOtpEnabled: async () => undefined,
  runColdModelCheck: async () => ({ passed: false, errorCode: 'PACK_NOT_FOUND', durationMs: 0, coldLoad: true, pssKb: 0 }),
  listPacks: async () => ({ packs: [] }),
  removePack: async () => ({ removed: false }),
  pickPack: async () => { throw new Error('Pack import is available in the Android app.') },
  catalogue: async () => ({ entries: [] }),
  installCataloguePack: async () => { throw new Error('Pack installation is available in the Android app.') },
  listNotificationApps: async () => ({ apps: [] }),
  saveNotificationApps: async () => undefined,
  diagnostics: async () => ({ platform: 'web preview', privacy: 'Inputs and results are memory-only' }),
  developerDataStatus: async () => ({ unlocked: false, labAuthenticated: false, captureEnabled: false, recordCount: 0, storedBytes: 0, oldestAt: null }),
  unlockDeveloperData: async () => ({ unlocked: true }),
  setNotificationCaptureEnabled: async () => undefined,
  exportNotificationCapture: async () => { throw new Error('Capture export is available in the Android app.') },
  clearNotificationCapture: async () => ({ removed: 0 }),
  listPersistentDiagnostics: async () => ({ entries: [] }),
  clearPersistentDiagnostics: async () => ({ removed: 0 }),
  getPackUpdateStatus: async () => ({ enabled: true, networkPolicy: 'unmetered', state: 'idle', currentVersion: null, availableVersion: null, lastCheckedAt: null, lastUpdatedAt: null, lastError: null }),
  setAutomaticPackUpdates: async () => undefined,
  checkForPackUpdates: async () => web.getPackUpdateStatus(),
  authenticateDeveloperLab: async () => ({ authenticated: true }),
  closeDeveloperLab: async () => undefined,
  listNotificationRecords: async () => ({ records: [], nextCursor: null }),
  getNotificationRecord: async () => { throw new Error('Notification records are available in the Android app.') },
}

export const needleBub: NeedleBubNative = Capacitor.isNativePlatform() ? native : web
