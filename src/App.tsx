import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AlertTriangle, ArrowLeft, Check, ChevronRight, CircleCheck, CircleX, Clock,
  FlaskConical, Info, LayoutGrid, Package, Settings, Trash2, Wifi, type LucideIcon,
} from 'lucide-react'

import './App.css'
import {
  needleBub, type AppStatus, type CatalogueEntry, type ColdModelCheck,
  type DeveloperDataStatus, type DiagnosticInfo, type NotificationApp,
  type NotificationRecord, type NotificationRecordSummary, type PackInfo,
  type PackUpdateStatus,
} from './native'

type Route = 'home' | 'sources' | 'model' | 'advanced' | 'lab'
type IconName = 'apps' | 'arrow' | 'back' | 'check' | 'info' | 'lab' | 'package' | 'settings' | 'wifi'

const emptyStatus: AppStatus = {
  otpPackInstalled: false, notificationAccess: false, notificationPermission: false,
  allApps: false, selectedAppCount: 0, automaticOtpConfigured: false,
  automaticOtpEnabled: true, macroDroidInstalled: false,
}
const emptyDeveloper: DeveloperDataStatus = {
  unlocked: false, labAuthenticated: false, captureEnabled: false,
  recordCount: 0, storedBytes: 0, oldestAt: null,
}
const emptyUpdate: PackUpdateStatus = {
  enabled: true, networkPolicy: 'unmetered', state: 'idle', currentVersion: null,
  availableVersion: null, lastCheckedAt: null, lastUpdatedAt: null, lastError: null,
}

function routeFromHash(): Route {
  const value = window.location.hash.replace(/^#\/?/, '')
  if (value === 'packs') return 'model'
  return value === 'sources' || value === 'model' || value === 'advanced' || value === 'lab' ? value : 'home'
}
function navigate(route: Route) { window.location.hash = route === 'home' ? '' : `#/${route}` }

function KittyMark() {
  return <svg className="kitty-mark" viewBox="0 0 48 48" aria-hidden="true">
    <path d="M40 28V17l-4-4-5-5-7 8-7-8-9 9v18l6 6h13" className="kitty-contour" />
    <path d="m32 9 3 3" className="kitty-seam" />
    <path d="m14 29 3-3 3 3m8 0 3-3 3 3M21 32c0 4 3 4 3 0 0 4 3 4 3 0" className="kitty-face" />
    <path d="m27 42 7.5-12.5q1-2 2.5-.5t0 2.5L29.5 43Z" className="kitty-needle" />
    <path d="m35 29.5 1.5 1-1 1.5-1.5-1Z" className="kitty-needle-eye" />
    <path d="M35.8 30c4.5 1.5 6.2 5.7 4 8.8-1.8 2.7-5.2 1.8-4.4-.8" className="kitty-thread-loop" />
  </svg>
}

const icons: Record<IconName, LucideIcon> = {
  apps: LayoutGrid, arrow: ChevronRight, back: ArrowLeft, check: Check,
  info: Info, lab: FlaskConical, package: Package, settings: Settings, wifi: Wifi,
}
function Icon({ name }: { name: IconName }) {
  const Glyph = icons[name]
  return <Glyph className="line-icon" aria-hidden="true" focusable="false" strokeWidth={1.8} strokeLinecap="square" strokeLinejoin="bevel" />
}

function AppBar({ route }: { route: Route }) {
  if (route === 'home') return <header className="app-bar">
    <button className="brand" onClick={() => navigate('home')} aria-label="NeedleBub home"><KittyMark /><span>NeedleBub</span></button>
    <button className="icon-button" onClick={() => navigate('advanced')} aria-label="Settings"><Icon name="settings" /></button>
  </header>
  const labels: Record<Exclude<Route, 'home'>, string> = {
    sources: 'Sources', model: 'Model', advanced: 'Advanced', lab: 'Notification Lab',
  }
  return <header className="app-bar app-bar--detail">
    <button className="icon-button" onClick={() => window.history.back()} aria-label="Back"><Icon name="back" /></button>
    <span>{labels[route]}</span>
  </header>
}

function Toggle({ checked, label, disabled, onChange }: { checked: boolean; label: string; disabled?: boolean; onChange: (checked: boolean) => void }) {
  return <label className="toggle"><input role="switch" type="checkbox" aria-label={label} checked={checked} disabled={disabled} onChange={(event) => onChange(event.target.checked)} /><span aria-hidden="true" /></label>
}
function UtilityRow({ icon, label, value, onClick }: { icon: IconName; label: string; value: string; onClick: () => void }) {
  return <button className="utility-row" onClick={onClick} aria-label={`${label}, ${value}`}><Icon name={icon} /><span><strong>{label}</strong><small>{value}</small></span><Icon name="arrow" /></button>
}
function RequirementRow({ complete, label }: { complete: boolean; label: string }) {
  return <li className={complete ? 'requirement requirement--complete' : 'requirement'}><span className="requirement-mark" aria-hidden="true">{complete ? <Icon name="check" /> : null}</span><span>{label}</span><small>{complete ? 'Ready' : 'Needed'}</small></li>
}
function updateLabel(update: PackUpdateStatus): string {
  if (update.state === 'waiting_for_wifi') return 'Waiting for Wi-Fi'
  if (update.state === 'failed') return 'Update failed'
  if (['checking', 'downloading', 'verifying', 'health_check'].includes(update.state)) return 'Updating…'
  if (update.state === 'available') return `${update.availableVersion} available`
  if (update.state === 'up_to_date') return 'Up to date'
  return update.currentVersion ?? 'Not installed'
}

export default function App() {
  const [route, setRoute] = useState<Route>(routeFromHash)
  const [status, setStatus] = useState<AppStatus>(emptyStatus)
  const [packs, setPacks] = useState<PackInfo[]>([])
  const [catalogue, setCatalogue] = useState<CatalogueEntry[]>([])
  const [apps, setApps] = useState<NotificationApp[]>([])
  const [diagnostics, setDiagnostics] = useState<DiagnosticInfo | null>(null)
  const [developer, setDeveloper] = useState<DeveloperDataStatus>(emptyDeveloper)
  const [packUpdate, setPackUpdate] = useState<PackUpdateStatus>(emptyUpdate)
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [coldCheck, setColdCheck] = useState<ColdModelCheck | null>(null)

  const refresh = useCallback(async () => {
    const [nextStatus, nextPacks, nextDeveloper, nextUpdate] = await Promise.all([
      needleBub.status(), needleBub.listPacks(), needleBub.developerDataStatus(), needleBub.getPackUpdateStatus(),
    ])
    setStatus(nextStatus); setPacks(nextPacks.packs); setDeveloper(nextDeveloper); setPackUpdate(nextUpdate)
  }, [])

  useEffect(() => {
    void Promise.all([
      refresh(),
      needleBub.catalogue().then((value) => setCatalogue(value.entries)).catch(() => setCatalogue([])),
    ]).finally(() => setLoaded(true))
    const onHashChange = () => setRoute(routeFromHash())
    const onVisible = () => document.visibilityState === 'visible' ? void refresh() : void needleBub.closeDeveloperLab()
    window.addEventListener('hashchange', onHashChange)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.removeEventListener('hashchange', onHashChange)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [refresh])

  useEffect(() => {
    document.documentElement.scrollTop = 0; document.body.scrollTop = 0
    if (route === 'sources') void needleBub.listNotificationApps().then((value) => setApps(value.apps))
    if (route === 'advanced') void needleBub.diagnostics().then(setDiagnostics)
  }, [route])

  const run = async (key: string, work: () => Promise<unknown>, success?: string) => {
    setBusy(key); setNotice(null)
    try { await work(); await refresh(); if (success) setNotice(success) }
    catch (error) { setNotice(error instanceof Error ? error.message : 'That did not complete. Your existing setup is unchanged.') }
    finally { setBusy(null) }
  }
  const officialEntry = catalogue.find((entry) => entry.id === 'de.x0bubbuff.needlebub.otp')
  const officialPack = packs.find((pack) => pack.id === 'de.x0bubbuff.needlebub.otp' && pack.active)
    ?? packs.find((pack) => pack.id === 'de.x0bubbuff.needlebub.otp')

  let content: React.ReactNode
  if (!loaded) content = <main className="screen"><p className="loading-line">Reading local setup…</p></main>
  else if (route === 'sources') content = <SourcesView apps={apps} status={status} onAppsChange={setApps} onRefresh={refresh} />
  else if (route === 'model') content = <ModelView packs={packs} officialEntry={officialEntry} update={packUpdate} busy={busy} notice={notice} onRun={run} onUpdate={setPackUpdate} />
  else if (route === 'advanced') content = <AdvancedView status={status} diagnostics={diagnostics} developer={developer} coldCheck={coldCheck} busy={busy} onDeveloperChange={setDeveloper} onRunColdCheck={() => void run('cold-check', async () => setColdCheck(await needleBub.runColdModelCheck()))} />
  else if (route === 'lab') content = <LabView developer={developer} onDeveloperChange={setDeveloper} />
  else content = <HomeView status={status} developer={developer} update={packUpdate} officialEntry={officialEntry} officialPack={officialPack} busy={busy} notice={notice} onRefresh={refresh} onRun={run} onStatusChange={setStatus} />
  return <div className="app-shell"><AppBar route={route} />{content}</div>
}

function HomeView({ status, developer, update, officialEntry, officialPack, busy, notice, onRefresh, onRun, onStatusChange }: {
  status: AppStatus; developer: DeveloperDataStatus; update: PackUpdateStatus; officialEntry?: CatalogueEntry; officialPack?: PackInfo; busy: string | null; notice: string | null;
  onRefresh: () => Promise<void>; onRun: (key: string, work: () => Promise<unknown>, success?: string) => Promise<void>; onStatusChange: React.Dispatch<React.SetStateAction<AppStatus>>;
}) {
  const sourceValue = status.allApps ? 'All apps' : status.selectedAppCount === 1 ? '1 app' : `${status.selectedAppCount} apps`
  const setEnabled = async (enabled: boolean) => {
    const previous = status.automaticOtpEnabled
    onStatusChange((current) => ({ ...current, automaticOtpEnabled: enabled }))
    try { await needleBub.setAutomaticOtpEnabled({ enabled }); await onRefresh() }
    catch { onStatusChange((current) => ({ ...current, automaticOtpEnabled: previous })) }
  }
  if (!status.automaticOtpConfigured) {
    const hasSources = status.allApps || status.selectedAppCount > 0
    const requirements = [
      { complete: status.otpPackInstalled, label: 'OTP model pack' },
      { complete: status.notificationAccess, label: 'Notification access' },
      { complete: status.notificationPermission, label: 'Private result notifications' },
      { complete: hasSources, label: 'Notification sources' },
    ]
    let actionLabel = 'Choose sources'; let action = () => navigate('sources')
    if (!status.otpPackInstalled) { actionLabel = 'Install OTP pack'; action = () => void onRun('install', () => officialEntry ? needleBub.installCataloguePack({ id: officialEntry.id }) : needleBub.checkForPackUpdates()) }
    else if (!status.notificationAccess) { actionLabel = 'Open notification access'; action = () => void needleBub.openNotificationAccess() }
    else if (!status.notificationPermission) { actionLabel = 'Allow notifications'; action = () => void onRun('permission', () => needleBub.requestNotificationPermission()) }
    return <main className="screen home-screen"><section className="setup-block" aria-labelledby="setup-title"><h1 id="setup-title">Set up automatic OTP</h1><p>Four local requirements, then NeedleBub can stay out of the way.</p><ol className="requirements">{requirements.map((item) => <RequirementRow key={item.label} {...item} />)}</ol>{notice && <p className="inline-notice" role="status">{notice}</p>}<button className="primary-action" disabled={busy !== null} onClick={action}>{busy ? 'Working…' : actionLabel}</button></section><p className="privacy-note">Normal OTP processing does not store or transmit notification text or extracted codes.</p></main>
  }
  const active = status.automaticOtpEnabled
  return <main className="screen home-screen"><section className="status-block" aria-labelledby="automatic-title"><div className="status-heading"><div><h1 id="automatic-title">Automatic OTP</h1><p className={active ? 'status-copy status-copy--active' : 'status-copy'}>{active ? `Listening to ${status.allApps ? 'all notification apps' : sourceValue}` : 'Paused'}</p></div><Toggle label="Automatic OTP" checked={active} disabled={busy !== null} onChange={(enabled) => void setEnabled(enabled)} /></div><p className="route-line">{sourceValue} → {officialPack?.name ?? 'OTP Extractor'} → private notification</p></section>{notice && <p className="inline-notice" role="status">{notice}</p>}<div className="utility-list" aria-label="Automatic OTP configuration"><UtilityRow icon="apps" label="Sources" value={sourceValue} onClick={() => navigate('sources')} /><UtilityRow icon="package" label="Model" value={updateLabel(update)} onClick={() => navigate('model')} />{developer.unlocked && <UtilityRow icon="lab" label="Lab" value={`${developer.recordCount} ${developer.recordCount === 1 ? 'record' : 'records'}`} onClick={() => navigate('lab')} />}</div><p className="privacy-note">Processing stays on this device.</p></main>
}

function SourcesView({ apps, status, onAppsChange, onRefresh }: { apps: NotificationApp[]; status: AppStatus; onAppsChange: (apps: NotificationApp[]) => void; onRefresh: () => Promise<void> }) {
  const [allApps, setAllApps] = useState(status.allApps); const [filter, setFilter] = useState(''); const [saving, setSaving] = useState(false); const [error, setError] = useState<string | null>(null)
  const selectedPackages = (entries: NotificationApp[]) => entries.filter((app) => app.selected).map((app) => app.packageName)
  const save = async (nextAllApps: boolean, nextApps: NotificationApp[], rollback: () => void) => { setSaving(true); setError(null); try { await needleBub.saveNotificationApps({ allApps: nextAllApps, packages: selectedPackages(nextApps) }); await onRefresh() } catch { rollback(); setError('Could not save that source change. Your previous selection is still active.') } finally { setSaving(false) } }
  const visible = apps.filter((app) => `${app.label} ${app.packageName}`.toLowerCase().includes(filter.toLowerCase()))
  return <main className="screen detail-screen"><h1>Sources</h1><p className="screen-intro">Choose which notifications may reach the OTP model.</p>{error && <p className="inline-notice inline-notice--error" role="alert">{error}</p>}<div className="switch-row"><span><strong>All notification apps</strong><small>Process every non-empty notification.</small></span><Toggle label="All notification apps" checked={allApps} disabled={saving} onChange={(checked) => { const previous = allApps; setAllApps(checked); void save(checked, apps, () => setAllApps(previous)) }} /></div>{!allApps && <><label className="field-label" htmlFor="app-filter">Find an app</label><input id="app-filter" className="search-input" value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="App name or package" /><div className="app-list">{visible.map((app) => <label key={app.packageName}><input type="checkbox" checked={app.selected} disabled={saving} onChange={(event) => { const previous = apps; const next = apps.map((entry) => entry.packageName === app.packageName ? { ...entry, selected: event.target.checked } : entry); onAppsChange(next); void save(false, next, () => onAppsChange(previous)) }} /><span><strong>{app.label}</strong><code>{app.packageName}</code></span></label>)}</div></>}<p className="privacy-note">Hidden lock-screen content stays hidden.</p></main>
}

function ModelView({ packs, officialEntry, update, busy, notice, onRun, onUpdate }: { packs: PackInfo[]; officialEntry?: CatalogueEntry; update: PackUpdateStatus; busy: string | null; notice: string | null; onRun: (key: string, work: () => Promise<unknown>, success?: string) => Promise<void>; onUpdate: (value: PackUpdateStatus) => void }) {
  const official = packs.find((pack) => pack.id === 'de.x0bubbuff.needlebub.otp' && pack.active)
  const imported = packs.filter((pack) => pack.id !== 'de.x0bubbuff.needlebub.otp')
  const setUpdates = async (enabled: boolean) => { onUpdate({ ...update, enabled }); try { await needleBub.setAutomaticPackUpdates({ enabled }); onUpdate(await needleBub.getPackUpdateStatus()) } catch { onUpdate(update) } }
  return <main className="screen detail-screen"><div className="screen-title-row"><div><h1>Model</h1><p className="screen-intro">Official OTP capability and local imports.</p></div><button className="secondary-action" disabled={busy !== null} onClick={() => void onRun('import', () => needleBub.pickPack(), 'Pack imported as unverified.')}>Import</button></div>{notice && <p className="inline-notice" role="status">{notice}</p>}<section className="model-primary"><div className="model-heading"><Package aria-hidden="true" /><span><strong>{official?.name ?? 'OTP Extractor'}</strong><small>{official ? `${official.version} · Verified · Active` : 'Not installed'}</small></span></div><div className="switch-row"><span><strong>Automatic pack updates</strong><small>Verified downloads over Wi-Fi only.</small></span><Toggle label="Automatic pack updates" checked={update.enabled} disabled={busy !== null} onChange={(enabled) => void setUpdates(enabled)} /></div><dl className="diagnostic-list compact-evidence"><div><dt>Status</dt><dd>{updateLabel(update)}</dd></div><div><dt>Network</dt><dd>Wi-Fi only</dd></div><div><dt>Last checked</dt><dd>{update.lastCheckedAt ? new Date(update.lastCheckedAt).toLocaleString() : 'Not yet'}</dd></div>{update.lastError && <div><dt>Last error</dt><dd>{update.lastError}</dd></div>}</dl><button className="primary-action" disabled={busy !== null} onClick={() => void onRun('update-check', async () => onUpdate(await needleBub.checkForPackUpdates()), 'Model check complete.')}>{busy === 'update-check' ? 'Checking…' : 'Check now'}</button></section>{!official && officialEntry && <section className="install-row"><div><strong>OTP Extractor</strong><small>Signed official catalogue · {Math.round(officialEntry.size / 1_000_000)} MB</small></div><button className="primary-small" disabled={busy !== null} onClick={() => void onRun('install', () => needleBub.installCataloguePack({ id: officialEntry.id }), 'OTP Extractor installed.')}>Install</button></section>}<details className="advanced-details imported-packs"><summary><Icon name="package" /><span>Imported packs ({imported.length})</span><Icon name="arrow" /></summary><div>{imported.length === 0 ? <p>No external packs installed.</p> : imported.map((pack) => <div className="imported-pack" key={`${pack.id}@${pack.version}`}><span><strong>{pack.name}</strong><small>{pack.version} · Unverified</small></span><button className="danger-icon" aria-label={`Remove ${pack.name}`} onClick={() => { if (window.confirm(`Remove ${pack.name} ${pack.version}?`)) void onRun(`remove-${pack.id}`, () => needleBub.removePack({ id: pack.id, version: pack.version }), `${pack.name} removed.`) }}><Trash2 aria-hidden="true" /></button></div>)}</div></details>{official && <button className="danger-action model-remove" disabled={busy !== null} onClick={() => { if (window.confirm(`Remove ${official.name} ${official.version}? Automatic OTP will stop until it is reinstalled.`)) void onRun('remove-official', () => needleBub.removePack({ id: official.id, version: official.version }), `${official.name} removed.`) }}>Remove {official.name}</button>}</main>
}

function AdvancedView({ status, diagnostics, developer, coldCheck, busy, onDeveloperChange, onRunColdCheck }: { status: AppStatus; diagnostics: DiagnosticInfo | null; developer: DeveloperDataStatus; coldCheck: ColdModelCheck | null; busy: string | null; onDeveloperChange: (value: DeveloperDataStatus) => void; onRunColdCheck: () => void }) {
  const buildTaps = useRef(0); const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null); const [toast, setToast] = useState<string | null>(null)
  useEffect(() => () => { if (toastTimer.current) clearTimeout(toastTimer.current) }, [])
  const showToast = (message: string) => { setToast(message); if (toastTimer.current) clearTimeout(toastTimer.current); toastTimer.current = setTimeout(() => setToast(null), 2_200) }
  const tapVersion = () => { if (developer.unlocked) return; buildTaps.current += 1; const remaining = 7 - buildTaps.current; if (buildTaps.current > 1 && remaining > 0) showToast(`${remaining} more ${remaining === 1 ? 'tap' : 'taps'} to unlock Developer Mode.`); if (remaining === 0) { showToast('Unlocking Developer Mode…'); void needleBub.unlockDeveloperData().then(async () => { onDeveloperChange(await needleBub.developerDataStatus()); showToast('Developer Mode unlocked.') }).catch(() => { buildTaps.current = 6; showToast('Could not unlock. Tap Version again to retry.') }) } }
  const result = coldCheck ? coldCheck.passed ? `Passed · ${coldCheck.coldLoad ? 'cold load' : 'warm load'} · ${coldCheck.durationMs} ms · ${Math.round(coldCheck.pssKb / 1024)} MiB` : `Failed · ${coldCheck.errorCode ?? 'RUNTIME_CRASH'} · ${coldCheck.durationMs} ms` : null
  return <main className="screen detail-screen"><h1>Advanced</h1>{developer.unlocked && <section className="advanced-section"><h2>Developer Mode</h2><button className="plain-row" onClick={() => navigate('lab')}><span><strong>Notification Lab</strong><small>{developer.recordCount} encrypted records · authentication required</small></span><Icon name="arrow" /></button></section>}<section className="advanced-section"><h2>Runtime check</h2><p>Reloads the installed OTP model and reports sanitized runtime evidence.</p><button className="primary-action" disabled={busy !== null} onClick={onRunColdCheck}>{busy === 'cold-check' ? 'Running cold check…' : 'Run cold model check'}</button>{result && <p className={coldCheck?.passed ? 'check-result check-result--passed' : 'check-result'} role="status">{result}</p>}</section><section className="advanced-section"><h2>Android</h2><button className="plain-row" onClick={() => void needleBub.openNotificationSettings()}><span><strong>Notification settings</strong><small>Result channel and lock-screen privacy</small></span><Icon name="arrow" /></button><button className="plain-row" disabled={!status.macroDroidInstalled} onClick={() => void needleBub.openMacroDroid()}><span><strong>MacroDroid</strong><small>{status.macroDroidInstalled ? 'Installed · open automation app' : 'Not installed'}</small></span><Icon name="arrow" /></button></section><section className="advanced-section"><h2>Developer gateway</h2><code className="code-block">de.x0bubbuff.needlebub.action.INFERENCE_GATEWAY</code><p>One in flight per UID · burst 3 · 10 requests per minute.</p></section><details className="advanced-details"><summary><Icon name="info" /><span>Diagnostics and build facts</span><Icon name="arrow" /></summary><div><code className="code-block">adb logcat -s NeedleRuntime:I</code>{diagnostics ? <dl className="diagnostic-list">{Object.entries(diagnostics).map(([key, value]) => { const displayed = String(value ?? 'Not detected'); return <div key={key}><dt>{key === 'version' ? 'Version' : key}</dt><dd>{key === 'version' ? <button className="diagnostic-unlock-target" aria-label={`Version ${displayed}`} onClick={tapVersion}>{displayed}</button> : displayed}</dd></div> })}</dl> : <p>Reading build facts…</p>}</div></details>{toast && <div className="unlock-toast" role="status" aria-live="polite">{toast}</div>}<details className="advanced-details"><summary><Icon name="info" /><span>Privacy and licenses</span><Icon name="arrow" /></summary><div><p>Normal operation keeps notification text, reasoning, codes, calls, and results in memory only. Explicit Developer capture encrypts them locally.</p><p>NeedleBub is MIT licensed. Needle and Locale notices are Apache-2.0. Interface icons are Lucide.</p><p>Appearance follows Android automatically.</p></div></details></main>
}

const recordFilters = ['ALL', 'OTP', 'REJECTED', 'NOT_RUN', 'ERROR'] as const
function decisionLabel(value: string): string { if (value === 'OTP') return 'OTP'; if (value === 'NOT_RUN') return 'Not run'; if (value === 'SUPPRESSED') return 'Suppressed'; if (value === 'ERROR') return 'Error'; if (value === 'PENDING') return 'Pending'; return 'Rejected' }
function DecisionIcon({ decision }: { decision: string }) { if (decision === 'OTP') return <CircleCheck aria-hidden="true" />; if (decision === 'ERROR') return <AlertTriangle aria-hidden="true" />; if (decision === 'PENDING') return <Clock aria-hidden="true" />; return <CircleX aria-hidden="true" /> }

function LabView({ developer, onDeveloperChange }: { developer: DeveloperDataStatus; onDeveloperChange: (value: DeveloperDataStatus) => void }) {
  const [authenticated, setAuthenticated] = useState(false); const [records, setRecords] = useState<NotificationRecordSummary[]>([]); const [nextCursor, setNextCursor] = useState<number | null>(null); const [filter, setFilter] = useState<(typeof recordFilters)[number]>('ALL'); const [selected, setSelected] = useState<NotificationRecord | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false); const [passphrase, setPassphrase] = useState(''); const [deleteAfterExport, setDeleteAfterExport] = useState(true)
  const refreshDeveloper = useCallback(async () => onDeveloperChange(await needleBub.developerDataStatus()), [onDeveloperChange])
  const load = useCallback(async (nextFilter: string, cursor?: number, append = false) => {
    const result = await needleBub.listNotificationRecords({ cursor, limit: 50, filter: nextFilter })
    setRecords((current) => append ? [...current, ...result.records] : result.records)
    setNextCursor(result.nextCursor)
    await refreshDeveloper()
  }, [refreshDeveloper])
  useEffect(() => {
    let live = true
    if (!developer.unlocked) return
    const clearPlaintext = () => {
      setAuthenticated(false); setRecords([]); setNextCursor(null); setSelected(null); setError(null); setPassphrase('')
    }
    const authenticate = async () => {
      clearPlaintext()
      try {
        const result = await needleBub.authenticateDeveloperLab()
        if (!live || document.visibilityState === 'hidden' || !result.authenticated) return
        setAuthenticated(true)
        await load('ALL')
      } catch (failure) {
        if (live && document.visibilityState !== 'hidden') setError(failure instanceof Error ? failure.message : 'Authentication failed.')
      }
    }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        clearPlaintext()
        void needleBub.closeDeveloperLab()
      } else {
        void authenticate()
      }
    }
    void authenticate()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      live = false; document.removeEventListener('visibilitychange', onVisibility)
      clearPlaintext(); void needleBub.closeDeveloperLab()
    }
  }, [developer.unlocked, load])
  const toggleCapture = async (enabled: boolean) => { if (enabled && !window.confirm('Capture full notification text, model reasoning, calls, and outcomes locally?')) return; setBusy(true); try { await needleBub.setNotificationCaptureEnabled({ enabled }); await refreshDeveloper() } finally { setBusy(false) } }
  const chooseFilter = async (value: (typeof recordFilters)[number]) => { setFilter(value); setSelected(null); await load(value) }
  const openRecord = async (id: string) => { setBusy(true); try { setSelected(await needleBub.getNotificationRecord({ id })) } finally { setBusy(false) } }
  if (!developer.unlocked) return <main className="screen detail-screen"><h1>Notification Lab</h1><p className="inline-notice inline-notice--error">Developer Mode is locked.</p></main>
  if (!authenticated) return <main className="screen detail-screen"><h1>Notification Lab</h1>{error ? <p className="inline-notice inline-notice--error">{error}</p> : <p className="loading-line">Confirming device authentication…</p>}</main>
  return <main className="screen detail-screen lab-screen"><h1>Notification Lab</h1><p className="screen-intro">Encrypted local traces for model and postprocessor behavior.</p><div className="switch-row"><span><strong>Notification capture</strong><small>{developer.captureEnabled ? 'Capturing all apps except NeedleBub' : 'Off · normal privacy behavior'}</small></span><Toggle label="Notification capture" checked={developer.captureEnabled} disabled={busy} onChange={(enabled) => void toggleCapture(enabled)} /></div><p className="sensitive-note">When enabled, full notification text and model output are encrypted locally for inspection.</p><div className="lab-filters" aria-label="Record filter">{recordFilters.map((value) => <button key={value} aria-pressed={filter === value} onClick={() => void chooseFilter(value)}>{value === 'ALL' ? 'All' : decisionLabel(value)}</button>)}</div>{selected ? <RecordDetail record={selected} onBack={() => setSelected(null)} /> : <div className="record-list">{records.map((record) => <button key={record.id} className="record-row" onClick={() => void openRecord(record.id)}><DecisionIcon decision={record.decision} /><span><strong>{record.title || record.appLabel}</strong><small>{record.appLabel} · {new Date(record.capturedAt).toLocaleString()}</small></span><span className={`decision-text decision-text--${record.decision.toLowerCase()}`}>{decisionLabel(record.decision)}</span></button>)}{records.length === 0 && <p className="empty-copy">No matching notification records.</p>}{nextCursor != null && <button className="secondary-action load-more" disabled={busy} onClick={() => void load(filter, nextCursor, true)}>Load more</button>}</div>}{!selected && <section className="lab-data-actions"><dl className="diagnostic-list compact-evidence"><div><dt>Records</dt><dd>{developer.recordCount}</dd></div><div><dt>Encrypted size</dt><dd>{Math.ceil(developer.storedBytes / 1024)} KiB</dd></div><div><dt>Retention</dt><dd>30 days · 10,000 max</dd></div></dl><label className="field-label" htmlFor="capture-passphrase">Export password</label><input id="capture-passphrase" className="search-input" type="password" autoComplete="new-password" minLength={12} value={passphrase} onChange={(event) => setPassphrase(event.target.value)} /><label className="check-row"><input type="checkbox" checked={deleteAfterExport} onChange={(event) => setDeleteAfterExport(event.target.checked)} /><span>Delete records after verified export</span></label><div className="developer-actions"><button className="secondary-action" disabled={busy || passphrase.length < 12 || developer.recordCount === 0} onClick={() => void (async () => { setBusy(true); try { await needleBub.exportNotificationCapture({ passphrase, deleteAfterExport }); setPassphrase(''); await refreshDeveloper(); await load(filter) } finally { setBusy(false) } })()}>Export encrypted capture</button><button className="danger-action" disabled={busy || developer.recordCount === 0} onClick={() => { if (window.confirm(`Permanently remove ${developer.recordCount} captured notification records?`)) void (async () => { setBusy(true); try { await needleBub.clearNotificationCapture(); await refreshDeveloper(); await load(filter) } finally { setBusy(false) } })() }}>Clear records</button></div></section>}</main>
}

function RecordDetail({ record, onBack }: { record: NotificationRecord; onBack: () => void }) {
  const runtime = record.runtime; const outcome = record.outcome
  return <article className="record-detail"><button className="back-inline" onClick={onBack}><ArrowLeft aria-hidden="true" />All records</button><section><h2>Notification</h2><dl className="trace-list"><div><dt>App</dt><dd>{record.appLabel}</dd></div><div><dt>Package</dt><dd><code>{record.packageName}</code></dd></div><div><dt>Title</dt><dd>{record.title || 'None'}</dd></div><div><dt>Message</dt><dd className="trace-message">{record.body || 'Empty'}</dd></div></dl></section><section><h2>Model</h2><dl className="trace-list"><div><dt>Response</dt><dd>{runtime?.responseType ?? runtime?.status ?? 'Not run'}</dd></div><div><dt>Reasoning</dt><dd className="trace-reasoning">{runtime?.reasoning || 'No reasoning returned.'}</dd></div><div><dt>Tool</dt><dd><code>{runtime?.toolName ?? 'No call'}</code></dd></div><div><dt>Arguments</dt><dd><code>{runtime?.resultJson ?? 'None'}</code></dd></div></dl></section><section><h2>Final decision</h2><div className="decision-summary"><DecisionIcon decision={outcome?.decision ?? 'PENDING'} /><span><strong>{decisionLabel(outcome?.decision ?? 'PENDING')}</strong><small>{outcome?.reasonCode ?? 'INTERRUPTED'}</small></span></div>{outcome?.code && <dl className="trace-list"><div><dt>Code</dt><dd><code>{outcome.code}</code></dd></div><div><dt>Source</dt><dd>{outcome.source ?? 'Sender fallback'} · {outcome.sourceDisposition ?? 'absent'}</dd></div></dl>}</section><section><h2>Performance</h2><dl className="trace-list"><div><dt>Load</dt><dd>{runtime?.coldLoad ? 'Cold' : runtime ? 'Warm' : 'Not run'}</dd></div><div><dt>Duration</dt><dd>{runtime?.durationMs == null ? '—' : `${runtime.durationMs} ms`}</dd></div><div><dt>PSS</dt><dd>{runtime?.pssKb == null ? '—' : `${Math.round(runtime.pssKb / 1024)} MiB`}</dd></div><div><dt>Pack</dt><dd><code>{runtime?.packId && runtime?.packVersion ? `${runtime.packId}@${runtime.packVersion}` : 'None'}</code></dd></div></dl></section></article>
}
