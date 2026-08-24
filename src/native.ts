import { Capacitor, registerPlugin } from '@capacitor/core'

export type AppStatus = { otpPackInstalled: boolean; notificationAccess: boolean; notificationPermission: boolean; allApps: boolean; selectedAppCount: number; automaticOtpConfigured: boolean; automaticOtpEnabled: boolean; macroDroidInstalled: boolean }
export type PackInfo = { id: string; version: string; name: string; author: string; description: string; license: string; verified: boolean; surfaces: string[]; outputs: string[] }
export type CatalogueEntry = { id: string; version: string; name: string; url: string; size: number; sha256: string; engineAbi: string }
export type NotificationApp = { packageName: string; label: string; selected: boolean }
export type DiagnosticInfo = Record<string, string | number | null>
export type ColdModelCheck = { passed: boolean; errorCode: string | null; durationMs: number; coldLoad: boolean; pssKb: number }

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
}

export const needleBub: NeedleBubNative = Capacitor.isNativePlatform() ? native : web
