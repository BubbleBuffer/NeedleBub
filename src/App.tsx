import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ArrowLeft,
  Check,
  ChevronRight,
  Info,
  LayoutGrid,
  Package,
  Settings,
  type LucideIcon,
} from 'lucide-react'

import './App.css'
import {
  needleBub,
  type AppStatus,
  type CatalogueEntry,
  type ColdModelCheck,
  type DiagnosticInfo,
  type DeveloperDataStatus,
  type PersistentDiagnostic,
  type NotificationApp,
  type PackInfo,
} from './native'

type Route = 'home' | 'sources' | 'packs' | 'advanced'
type IconName = 'apps' | 'arrow' | 'back' | 'check' | 'info' | 'package' | 'settings'

const emptyStatus: AppStatus = {
  otpPackInstalled: false,
  notificationAccess: false,
  notificationPermission: false,
  allApps: false,
  selectedAppCount: 0,
  automaticOtpConfigured: false,
  automaticOtpEnabled: true,
  macroDroidInstalled: false,
}

function routeFromHash(): Route {
  const value = window.location.hash.replace(/^#\/?/, '')
  return value === 'sources' || value === 'packs' || value === 'advanced' ? value : 'home'
}

function navigate(route: Route) {
  window.location.hash = route === 'home' ? '' : `#/${route}`
}

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
  apps: LayoutGrid,
  arrow: ChevronRight,
  back: ArrowLeft,
  check: Check,
  info: Info,
  package: Package,
  settings: Settings,
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

  const labels: Record<Exclude<Route, 'home'>, string> = { sources: 'Sources', packs: 'Packs', advanced: 'Advanced' }
  return <header className="app-bar app-bar--detail">
    <button className="icon-button" onClick={() => window.history.back()} aria-label="Back"><Icon name="back" /></button>
    <span>{labels[route]}</span>
  </header>
}

function Toggle({ checked, label, disabled, onChange }: { checked: boolean; label: string; disabled?: boolean; onChange: (checked: boolean) => void }) {
  return <label className="toggle">
    <input role="switch" type="checkbox" aria-label={label} checked={checked} disabled={disabled} onChange={(event) => onChange(event.target.checked)} />
    <span aria-hidden="true" />
  </label>
}

function UtilityRow({ icon, label, value, onClick }: { icon: IconName; label: string; value: string; onClick: () => void }) {
  return <button className="utility-row" onClick={onClick} aria-label={`${label}, ${value}`}>
    <Icon name={icon} />
    <span><strong>{label}</strong><small>{value}</small></span>
    <Icon name="arrow" />
  </button>
}

function RequirementRow({ complete, label }: { complete: boolean; label: string }) {
  return <li className={complete ? 'requirement requirement--complete' : 'requirement'}>
    <span className="requirement-mark" aria-hidden="true">{complete ? <Icon name="check" /> : null}</span>
    <span>{label}</span>
    <small>{complete ? 'Ready' : 'Needed'}</small>
  </li>
}

export default function App() {
  const [route, setRoute] = useState<Route>(routeFromHash)
  const [status, setStatus] = useState<AppStatus>(emptyStatus)
  const [packs, setPacks] = useState<PackInfo[]>([])
  const [catalogue, setCatalogue] = useState<CatalogueEntry[]>([])
  const [apps, setApps] = useState<NotificationApp[]>([])
  const [diagnostics, setDiagnostics] = useState<DiagnosticInfo | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [coldCheck, setColdCheck] = useState<ColdModelCheck | null>(null)

  const refresh = useCallback(async () => {
    const [nextStatus, nextPacks] = await Promise.all([needleBub.status(), needleBub.listPacks()])
    setStatus(nextStatus)
    setPacks(nextPacks.packs)
  }, [])

  useEffect(() => {
    void Promise.all([
      // oxlint-disable-next-line react/set-state-in-effect -- initial state is supplied by the native bridge.
      refresh(),
      needleBub.catalogue().then((value) => setCatalogue(value.entries)).catch(() => setCatalogue([])),
    ]).finally(() => setLoaded(true))
    const onHashChange = () => setRoute(routeFromHash())
    const onVisible = () => { if (document.visibilityState === 'visible') void refresh() }
    window.addEventListener('hashchange', onHashChange)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.removeEventListener('hashchange', onHashChange)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [refresh])

  useEffect(() => {
    document.documentElement.scrollTop = 0
    document.body.scrollTop = 0
    if (route === 'sources') void needleBub.listNotificationApps().then((value) => setApps(value.apps))
    if (route === 'advanced') void needleBub.diagnostics().then(setDiagnostics)
  }, [route])

  const run = async (key: string, work: () => Promise<unknown>, success?: string) => {
    setBusy(key)
    setNotice(null)
    try {
      await work()
      await refresh()
      if (success) setNotice(success)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'That did not complete. Your existing setup is unchanged.')
    } finally {
      setBusy(null)
    }
  }

  const officialEntry = catalogue.find((entry) => entry.id === 'de.x0bubbuff.needlebub.otp')
  const officialPack = packs.find((pack) => pack.id === 'de.x0bubbuff.needlebub.otp')

  let content: React.ReactNode
  if (!loaded) content = <main className="screen"><p className="loading-line">Reading local setup…</p></main>
  else if (route === 'sources') content = <SourcesView apps={apps} status={status} onAppsChange={setApps} onRefresh={refresh} />
  else if (route === 'packs') content = <PacksView packs={packs} officialEntry={officialEntry} busy={busy} notice={notice} run={run} />
  else if (route === 'advanced') content = <AdvancedView
    status={status}
    diagnostics={diagnostics}
    coldCheck={coldCheck}
    busy={busy}
    onRunColdCheck={() => void run('cold-check', async () => setColdCheck(await needleBub.runColdModelCheck()))}
  />
  else content = <HomeView
    status={status}
    officialEntry={officialEntry}
    officialPack={officialPack}
    busy={busy}
    notice={notice}
    onRefresh={refresh}
    onRun={run}
    onStatusChange={setStatus}
  />

  return <div className="app-shell"><AppBar route={route} />{content}</div>
}

function HomeView({ status, officialEntry, officialPack, busy, notice, onRefresh, onRun, onStatusChange }: {
  status: AppStatus
  officialEntry?: CatalogueEntry
  officialPack?: PackInfo
  busy: string | null
  notice: string | null
  onRefresh: () => Promise<void>
  onRun: (key: string, work: () => Promise<unknown>, success?: string) => Promise<void>
  onStatusChange: React.Dispatch<React.SetStateAction<AppStatus>>
}) {
  const sourceValue = status.allApps ? 'All apps' : status.selectedAppCount === 1 ? '1 app' : `${status.selectedAppCount} apps`
  const setEnabled = async (enabled: boolean) => {
    const previous = status.automaticOtpEnabled
    onStatusChange((current) => ({ ...current, automaticOtpEnabled: enabled }))
    try {
      await needleBub.setAutomaticOtpEnabled({ enabled })
      await onRefresh()
    } catch {
      onStatusChange((current) => ({ ...current, automaticOtpEnabled: previous }))
    }
  }

  if (!status.automaticOtpConfigured) {
    const hasSources = status.allApps || status.selectedAppCount > 0
    const requirements = [
      { complete: status.otpPackInstalled, label: 'OTP model pack' },
      { complete: status.notificationAccess, label: 'Notification access' },
      { complete: status.notificationPermission, label: 'Private result notifications' },
      { complete: hasSources, label: 'Notification sources' },
    ]
    let actionLabel = 'Choose sources'
    let action = () => navigate('sources')
    if (!status.otpPackInstalled) {
      actionLabel = 'Install OTP pack'
      action = () => void onRun('install', () => officialEntry ? needleBub.installCataloguePack({ id: officialEntry.id }) : needleBub.pickPack())
    } else if (!status.notificationAccess) {
      actionLabel = 'Open notification access'
      action = () => void needleBub.openNotificationAccess()
    } else if (!status.notificationPermission) {
      actionLabel = 'Allow notifications'
      action = () => void onRun('permission', async () => { await needleBub.requestNotificationPermission() })
    }
    return <main className="screen home-screen">
      <section className="setup-block" aria-labelledby="setup-title">
        <h1 id="setup-title">Set up automatic OTP</h1>
        <p>Four local requirements, then NeedleBub can stay out of the way.</p>
        <ol className="requirements">{requirements.map((item) => <RequirementRow key={item.label} {...item} />)}</ol>
        {notice && <p className="inline-notice" role="status">{notice}</p>}
        <button className="primary-action" disabled={busy !== null} onClick={action}>{busy ? 'Working…' : actionLabel}</button>
      </section>
      <p className="privacy-note">Normal OTP processing does not store or transmit notification text or extracted codes.</p>
    </main>
  }

  const active = status.automaticOtpEnabled
  return <main className="screen home-screen">
    <section className="status-block" aria-labelledby="automatic-title">
      <div className="status-heading"><div><h1 id="automatic-title">Automatic OTP</h1><p className={active ? 'status-copy status-copy--active' : 'status-copy'}>{active ? `Listening to ${status.allApps ? 'all notification apps' : sourceValue}` : 'Paused'}</p></div><Toggle label="Automatic OTP" checked={active} disabled={busy !== null} onChange={(enabled) => void setEnabled(enabled)} /></div>
      <p className="route-line">{sourceValue} → {officialPack?.name ?? 'OTP Extractor'} → private notification</p>
    </section>
    {notice && <p className="inline-notice" role="status">{notice}</p>}
    <div className="utility-list" aria-label="Automatic OTP configuration">
      <UtilityRow icon="apps" label="Sources" value={sourceValue} onClick={() => navigate('sources')} />
      <UtilityRow icon="package" label="Model pack" value={officialPack ? `${officialPack.name} ${officialPack.version}` : 'Not installed'} onClick={() => navigate('packs')} />
    </div>
    <p className="privacy-note">Processing stays on this device.</p>
  </main>
}

function SourcesView({ apps, status, onAppsChange, onRefresh }: { apps: NotificationApp[]; status: AppStatus; onAppsChange: (apps: NotificationApp[]) => void; onRefresh: () => Promise<void> }) {
  const [allApps, setAllApps] = useState(status.allApps)
  const [filter, setFilter] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const selectedPackages = (entries: NotificationApp[]) => entries.filter((app) => app.selected).map((app) => app.packageName)
  const save = async (nextAllApps: boolean, nextApps: NotificationApp[], rollback: () => void) => {
    setSaving(true)
    setError(null)
    try {
      await needleBub.saveNotificationApps({ allApps: nextAllApps, packages: selectedPackages(nextApps) })
      await onRefresh()
    } catch {
      rollback()
      setError('Could not save that source change. Your previous selection is still active.')
    } finally {
      setSaving(false)
    }
  }
  const visible = apps.filter((app) => `${app.label} ${app.packageName}`.toLowerCase().includes(filter.toLowerCase()))

  return <main className="screen detail-screen">
    <h1>Sources</h1>
    <p className="screen-intro">Choose which notifications may reach the OTP model.</p>
    {error && <p className="inline-notice inline-notice--error" role="alert">{error}</p>}
    <div className="switch-row"><span><strong>All notification apps</strong><small>Process every eligible notification.</small></span><Toggle label="All notification apps" checked={allApps} disabled={saving} onChange={(checked) => { const previous = allApps; setAllApps(checked); void save(checked, apps, () => setAllApps(previous)) }} /></div>
    {!allApps && <>
      <label className="field-label" htmlFor="app-filter">Find an app</label>
      <input id="app-filter" className="search-input" value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="App name or package" />
      <div className="app-list">{visible.map((app) => <label key={app.packageName}>
        <input type="checkbox" checked={app.selected} disabled={saving} onChange={(event) => { const previous = apps; const next = apps.map((entry) => entry.packageName === app.packageName ? { ...entry, selected: event.target.checked } : entry); onAppsChange(next); void save(false, next, () => onAppsChange(previous)) }} />
        <span><strong>{app.label}</strong><code>{app.packageName}</code></span>
      </label>)}</div>
    </>}
    <p className="privacy-note">Hidden lock-screen content stays hidden.</p>
  </main>
}

function PacksView({ packs, officialEntry, busy, notice, run }: { packs: PackInfo[]; officialEntry?: CatalogueEntry; busy: string | null; notice: string | null; run: (key: string, work: () => Promise<unknown>, success?: string) => Promise<void> }) {
  const officialInstalled = packs.find((pack) => pack.id === officialEntry?.id)
  return <main className="screen detail-screen">
    <div className="screen-title-row"><div><h1>Packs</h1><p className="screen-intro">Installed local capabilities.</p></div><button className="secondary-action" disabled={busy !== null} onClick={() => void run('import', () => needleBub.pickPack(), 'Pack imported as unverified.')}>Import</button></div>
    {notice && <p className="inline-notice" role="status">{notice}</p>}
    {!officialInstalled && officialEntry && <section className="install-row"><div><strong>OTP Extractor</strong><small>Official catalogue · {Math.round(officialEntry.size / 1_000_000)} MB</small></div><button className="primary-small" disabled={busy !== null} onClick={() => void run('install', () => needleBub.installCataloguePack({ id: officialEntry.id }), 'OTP Extractor installed.')}>{busy === 'install' ? 'Installing…' : 'Install'}</button></section>}
    <div className="pack-list">{packs.map((pack) => {
      const updateAvailable = pack.id === officialEntry?.id && pack.version !== officialEntry.version
      return <details className="pack-item" key={`${pack.id}@${pack.version}`}>
        <summary><Icon name="package" /><span><strong>{pack.name}</strong><small>{pack.version} · {pack.verified ? 'Verified' : 'Unverified'}</small></span><Icon name="arrow" /></summary>
        <div className="pack-detail"><p>{pack.description}</p><dl><div><dt>Author</dt><dd>{pack.author}</dd></div><div><dt>License</dt><dd>{pack.license}</dd></div><div><dt>Outputs</dt><dd>{pack.outputs.join(', ')}</dd></div></dl>{updateAvailable && <button className="secondary-action" disabled={busy !== null} onClick={() => void run('update', () => needleBub.installCataloguePack({ id: officialEntry.id }), 'Pack updated.')}>Update to {officialEntry.version}</button>}<button className="danger-action" disabled={busy !== null} onClick={() => { const warning = pack.id === 'de.x0bubbuff.needlebub.otp' ? `Remove ${pack.name} ${pack.version}? Automatic OTP will stop until it is reinstalled.` : `Remove ${pack.name} ${pack.version}?`; if (window.confirm(warning)) void run(`remove-${pack.id}`, () => needleBub.removePack({ id: pack.id, version: pack.version }), `${pack.name} removed.`) }}>Remove {pack.name}</button></div>
      </details>
    })}</div>
    {packs.length === 0 && !officialEntry && <p className="empty-copy">No packs installed. Import a local <code>.nbpack</code> to begin.</p>}
  </main>
}

function AdvancedView({ status, diagnostics, coldCheck, busy, onRunColdCheck }: { status: AppStatus; diagnostics: DiagnosticInfo | null; coldCheck: ColdModelCheck | null; busy: string | null; onRunColdCheck: () => void }) {
  const [developerData, setDeveloperData] = useState<DeveloperDataStatus | null>(null)
  const [persistent, setPersistent] = useState<PersistentDiagnostic[]>([])
  const buildTaps = useRef(0)
  const unlockToastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [unlockToast, setUnlockToast] = useState<string | null>(null)
  const [developerBusy, setDeveloperBusy] = useState<string | null>(null)
  const [developerNotice, setDeveloperNotice] = useState<string | null>(null)
  const [passphrase, setPassphrase] = useState('')
  const [deleteAfterExport, setDeleteAfterExport] = useState(true)

  const refreshDeveloper = useCallback(async () => {
    const next = await needleBub.developerDataStatus()
    setDeveloperData(next)
    if (next.unlocked) setPersistent((await needleBub.listPersistentDiagnostics({ limit: 30 })).entries)
  }, [])

  // oxlint-disable-next-line react/set-state-in-effect -- native developer state is loaded when this route mounts.
  useEffect(() => { void refreshDeveloper() }, [refreshDeveloper])
  useEffect(() => () => { if (unlockToastTimer.current !== null) clearTimeout(unlockToastTimer.current) }, [])

  const developerRun = async (key: string, work: () => Promise<unknown>, success: string) => {
    setDeveloperBusy(key)
    setDeveloperNotice(null)
    try {
      await work()
      setDeveloperNotice(success)
      await refreshDeveloper()
      return true
    } catch (error) {
      setDeveloperNotice(error instanceof Error ? error.message : 'Developer data did not change.')
      return false
    } finally {
      setDeveloperBusy(null)
    }
  }

  const showUnlockToast = (message: string) => {
    setUnlockToast(message)
    if (unlockToastTimer.current !== null) clearTimeout(unlockToastTimer.current)
    unlockToastTimer.current = setTimeout(() => setUnlockToast(null), 2_200)
  }

  const tapVersion = () => {
    if (developerData?.unlocked) return
    buildTaps.current += 1
    const remaining = 7 - buildTaps.current
    if (buildTaps.current > 1 && remaining > 0) showUnlockToast(`${remaining} more ${remaining === 1 ? 'tap' : 'taps'} to unlock Developer data.`)
    if (remaining === 0) {
      showUnlockToast('Unlocking Developer data…')
      void developerRun('unlock', () => needleBub.unlockDeveloperData(), 'Developer data unlocked. Capture remains off.').then((unlocked) => {
        if (unlocked) showUnlockToast('Developer data unlocked.')
        else {
          buildTaps.current = 6
          showUnlockToast('Could not unlock Developer data. Tap Version again to retry.')
        }
      })
    }
  }
  const result = coldCheck ? coldCheck.passed
    ? `Passed · ${coldCheck.coldLoad ? 'cold load' : 'warm load'} · ${coldCheck.durationMs} ms · ${Math.round(coldCheck.pssKb / 1024)} MiB`
    : `Failed · ${coldCheck.errorCode ?? 'RUNTIME_CRASH'} · ${coldCheck.durationMs} ms`
    : null
  return <main className="screen detail-screen">
    <h1>Advanced</h1>
    <section className="advanced-section"><h2>Runtime check</h2><p>Reloads the installed OTP model and runs a fixed local fixture. No code or model output is shown.</p><button className="primary-action" disabled={busy !== null} onClick={onRunColdCheck}>{busy === 'cold-check' ? 'Running cold check…' : 'Run cold model check'}</button>{result && <p className={coldCheck?.passed ? 'check-result check-result--passed' : 'check-result'} role="status">{result}</p>}</section>
    <section className="advanced-section"><h2>Android</h2><button className="plain-row" onClick={() => void needleBub.openNotificationSettings()}><span><strong>Notification settings</strong><small>Result channel and lock-screen privacy</small></span><Icon name="arrow" /></button><button className="plain-row" disabled={!status.macroDroidInstalled} onClick={() => void needleBub.openMacroDroid()}><span><strong>MacroDroid</strong><small>{status.macroDroidInstalled ? 'Installed · open automation app' : 'Not installed'}</small></span><Icon name="arrow" /></button></section>
    <section className="advanced-section"><h2>Developer gateway</h2><code className="code-block">de.x0bubbuff.needlebub.action.INFERENCE_GATEWAY</code><p>One in flight per UID · burst 3 · 10 requests per minute.</p></section>
    <details className="advanced-details"><summary><Icon name="info" /><span>Diagnostics and build facts</span><Icon name="arrow" /></summary><div><code className="code-block">adb logcat -s NeedleRuntime:I</code>{diagnostics ? <dl className="diagnostic-list">{Object.entries(diagnostics).map(([key, value]) => { const displayed = String(value ?? 'Not detected'); return <div key={key}><dt>{key === 'version' ? 'Version' : key}</dt><dd>{key === 'version' ? <button className="diagnostic-unlock-target" aria-label={`Version ${displayed}`} onClick={tapVersion}>{displayed}</button> : displayed}</dd></div> })}</dl> : <p>Reading build facts…</p>}</div></details>
    {unlockToast && <div className="unlock-toast" role="status" aria-live="polite">{unlockToast}</div>}
    {developerData?.unlocked && <section className="advanced-section developer-data" aria-labelledby="developer-data-title">
      <h2 id="developer-data-title">Developer data</h2>
      <p className="sensitive-note">When capture is on, full notification text and model output are encrypted and stored only on this device.</p>
      {developerNotice && <p className="inline-notice" role="status">{developerNotice}</p>}
      <div className="switch-row"><span><strong>Notification capture</strong><small>{developerData.captureEnabled ? 'Capture is on · all apps except NeedleBub' : 'Capture is off · normal privacy behavior'}</small></span><Toggle label="Notification capture" checked={developerData.captureEnabled} disabled={developerBusy !== null} onChange={(enabled) => { if (!enabled || window.confirm('Capture full notification text and model output locally for research?')) void developerRun('capture', () => needleBub.setNotificationCaptureEnabled({ enabled }), enabled ? 'Capture enabled for all apps except NeedleBub.' : 'Capture stopped. Existing encrypted records remain.') }} /></div>
      <dl className="diagnostic-list compact-evidence"><div><dt>Records</dt><dd>{developerData.recordCount}</dd></div><div><dt>Encrypted size</dt><dd>{Math.ceil(developerData.storedBytes / 1024)} KiB</dd></div><div><dt>Retention</dt><dd>30 days · 10,000 max</dd></div></dl>
      <label className="field-label" htmlFor="capture-passphrase">Export password</label>
      <input id="capture-passphrase" className="search-input" type="password" autoComplete="new-password" minLength={12} value={passphrase} onChange={(event) => setPassphrase(event.target.value)} />
      <label className="check-row"><input type="checkbox" checked={deleteAfterExport} onChange={(event) => setDeleteAfterExport(event.target.checked)} /><span>Delete captured records after a verified export</span></label>
      <div className="developer-actions"><button className="secondary-action" disabled={developerBusy !== null || passphrase.length < 12 || developerData.recordCount === 0} onClick={() => void (async () => { if (await developerRun('export', () => needleBub.exportNotificationCapture({ passphrase, deleteAfterExport }), 'Encrypted capture exported.')) setPassphrase('') })()}>{developerBusy === 'export' ? 'Exporting…' : 'Export encrypted capture'}</button><button className="danger-action" disabled={developerBusy !== null || developerData.recordCount === 0} onClick={() => { if (window.confirm(`Permanently remove ${developerData.recordCount} captured notification records?`)) void developerRun('clear', () => needleBub.clearNotificationCapture(), 'Captured notifications cleared.') }}>Clear captured notifications</button></div>
      <details className="developer-log"><summary>Persistent metadata diagnostics ({persistent.length})</summary><div className="developer-log-list">{persistent.map((entry) => <code key={entry.id}>{new Date(entry.createdAt).toLocaleString()} · {entry.stage} · {entry.packageName ?? 'app'} · {entry.status}{entry.errorCode ? ` · ${entry.errorCode}` : ''}</code>)}</div><button className="danger-action" disabled={developerBusy !== null || persistent.length === 0} onClick={() => void developerRun('clear-logs', () => needleBub.clearPersistentDiagnostics(), 'Persistent diagnostics cleared.')}>Clear metadata diagnostics</button></details>
    </section>}
    <details className="advanced-details"><summary><Icon name="info" /><span>Privacy and licenses</span><Icon name="arrow" /></summary><div><p>During normal operation, notification text, extracted codes, tool arguments, and result JSON are never persisted or transmitted. Explicit Developer capture stores encrypted records locally until export, clearing, or expiry.</p><p>NeedleBub is MIT licensed. Needle and the Locale protocol notices are Apache-2.0. Interface icons are from Lucide under ISC/MIT terms.</p><p>Appearance follows Android automatically.</p></div></details>
  </main>
}
