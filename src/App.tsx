import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AlertTriangle, ArrowLeft, Bell, Bot, Check, ChevronRight, CircleCheck, CircleX,
  Clock, Download, FileArchive, FlaskConical, Gauge, Info, LayoutGrid, Package,
  RefreshCw, Search, Settings, Shield, Trash2, type LucideIcon,
} from 'lucide-react'

import './App.css'
import {
  needleBub, type AppStatus, type CatalogueEntry, type ColdModelCheck,
  type DeveloperDataStatus, type DiagnosticInfo, type FeatureActivitySummary,
  type NotificationApp, type NotificationRecord, type NotificationRecordSummary,
  type PackInfo, type PackUpdateStatus,
} from './native'

type Route =
  | 'home'
  | 'feature-otp'
  | 'feature-otp-sources'
  | 'settings'
  | 'settings-runtime'
  | 'settings-downloads'
  | 'settings-models'
  | 'settings-integrations'
  | 'settings-privacy'
  | 'developer-records'
  | 'developer-data'

type FeatureDescriptor = {
  id: 'otp'
  name: string
  description: string
  packId: string
}

const builtInFeatures: FeatureDescriptor[] = [{
  id: 'otp',
  name: 'One-time codes',
  description: 'Extract codes from notification messages',
  packId: 'de.x0bubbuff.needlebub.otp',
}]

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

const emptyDeveloper: DeveloperDataStatus = {
  unlocked: false,
  labAuthenticated: false,
  captureEnabled: false,
  recordCount: 0,
  storedBytes: 0,
  oldestAt: null,
  adbPullExpiresAt: null,
}

const emptyUpdate: PackUpdateStatus = {
  enabled: true,
  networkPolicy: 'unmetered',
  state: 'idle',
  currentVersion: null,
  availableVersion: null,
  lastCheckedAt: null,
  lastUpdatedAt: null,
  lastError: null,
}

const emptyActivity: FeatureActivitySummary = {
  featureId: 'otp',
  days: 7,
  todayOtp: 0,
  todayRejected: 0,
  todayErrors: 0,
  todaySuppressed: 0,
  todayNotRun: 0,
  totalOtp: 0,
  totalRejected: 0,
  totalErrors: 0,
  totalSuppressed: 0,
  totalNotRun: 0,
  completedInferenceCount: 0,
  averageDurationMs: null,
  lastActivityAt: null,
}

const routeLabels: Record<Exclude<Route, 'home'>, string> = {
  'feature-otp': 'One-time codes',
  'feature-otp-sources': 'Notification sources',
  settings: 'Settings',
  'settings-runtime': 'Runtime',
  'settings-downloads': 'Downloads',
  'settings-models': 'Models and imports',
  'settings-integrations': 'Integrations',
  'settings-privacy': 'Privacy and data',
  'developer-records': 'Notification records',
  'developer-data': 'Capture and export',
}

function routeFromHash(): Route {
  const value = window.location.hash.replace(/^#\/?/, '')
  const legacy: Record<string, Route> = {
    sources: 'feature-otp-sources',
    model: 'feature-otp',
    advanced: 'settings',
    lab: 'developer-records',
  }
  if (legacy[value]) return legacy[value]
  const routes: Record<string, Route> = {
    'features/otp': 'feature-otp',
    'features/otp/sources': 'feature-otp-sources',
    settings: 'settings',
    'settings/runtime': 'settings-runtime',
    'settings/downloads': 'settings-downloads',
    'settings/models': 'settings-models',
    'settings/integrations': 'settings-integrations',
    'settings/privacy': 'settings-privacy',
    'developer/records': 'developer-records',
    'developer/data': 'developer-data',
  }
  return routes[value] ?? 'home'
}

const routeHashes: Record<Route, string> = {
  home: '',
  'feature-otp': '#/features/otp',
  'feature-otp-sources': '#/features/otp/sources',
  settings: '#/settings',
  'settings-runtime': '#/settings/runtime',
  'settings-downloads': '#/settings/downloads',
  'settings-models': '#/settings/models',
  'settings-integrations': '#/settings/integrations',
  'settings-privacy': '#/settings/privacy',
  'developer-records': '#/developer/records',
  'developer-data': '#/developer/data',
}

function navigate(route: Route) {
  window.location.hash = routeHashes[route]
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

function AppBar({ route }: { route: Route }) {
  if (route === 'home') {
    return <header className="app-bar">
      <button className="brand" onClick={() => navigate('home')} aria-label="NeedleBub home">
        <KittyMark /><span>NeedleBub</span>
      </button>
      <button className="icon-button" onClick={() => navigate('settings')} aria-label="Settings">
        <Settings aria-hidden="true" />
      </button>
    </header>
  }
  return <header className="app-bar app-bar--detail">
    <button className="icon-button" onClick={() => window.history.back()} aria-label="Back">
      <ArrowLeft aria-hidden="true" />
    </button>
    <span>{routeLabels[route]}</span>
  </header>
}

function Toggle({
  checked,
  label,
  disabled,
  onChange,
}: {
  checked: boolean
  label: string
  disabled?: boolean
  onChange: (checked: boolean) => void
}) {
  return <label className="toggle">
    <input
      role="switch"
      type="checkbox"
      aria-label={label}
      checked={checked}
      disabled={disabled}
      onChange={(event) => onChange(event.target.checked)}
    />
    <span aria-hidden="true" />
  </label>
}

function Row({
  icon: Icon,
  label,
  value,
  disabled,
  onClick,
}: {
  icon: LucideIcon
  label: string
  value: string
  disabled?: boolean
  onClick: () => void
}) {
  return <button className="list-row" disabled={disabled} onClick={onClick} aria-label={`${label}, ${value}`}>
    <Icon aria-hidden="true" />
    <span><strong>{label}</strong><small>{value}</small></span>
    <ChevronRight aria-hidden="true" />
  </button>
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="settings-group">
    <h2>{title}</h2>
    <div className="row-group">{children}</div>
  </section>
}

function formatPackVersion(version: string | null | undefined) {
  if (!version) return 'Not installed'
  const alpha = /alpha\.(\d+)/.exec(version)
  return alpha ? `α${alpha[1]}` : version
}

function sourceLabel(status: AppStatus) {
  if (status.allApps) return 'All apps'
  return status.selectedAppCount === 1 ? '1 app' : `${status.selectedAppCount} apps`
}

function updateLabel(update: PackUpdateStatus) {
  if (update.state === 'waiting_for_wifi') return 'Waiting for Wi-Fi'
  if (update.state === 'failed' || update.lastError) return 'Update failed'
  if (update.availableVersion && update.availableVersion !== update.currentVersion) return 'Update available'
  if (update.currentVersion) return 'Up to date'
  return 'Not installed'
}

function formatDuration(durationMs: number | null) {
  if (durationMs == null) return 'Average —'
  return durationMs < 100 ? `Avg ${durationMs}ms` : `Avg ${(durationMs / 1000).toFixed(1)}s`
}

function requirementState(status: AppStatus) {
  return [
    { label: 'OTP model pack', complete: status.otpPackInstalled },
    { label: 'Notification access', complete: status.notificationAccess },
    { label: 'Private result notifications', complete: status.notificationPermission },
    { label: 'Notification sources', complete: status.allApps || status.selectedAppCount > 0 },
  ]
}

function App() {
  const [route, setRoute] = useState<Route>(routeFromHash)
  const [status, setStatus] = useState<AppStatus>(emptyStatus)
  const [packs, setPacks] = useState<PackInfo[]>([])
  const [catalogue, setCatalogue] = useState<CatalogueEntry[]>([])
  const [apps, setApps] = useState<NotificationApp[]>([])
  const [developer, setDeveloper] = useState<DeveloperDataStatus>(emptyDeveloper)
  const [diagnostics, setDiagnostics] = useState<DiagnosticInfo | null>(null)
  const [update, setUpdate] = useState<PackUpdateStatus>(emptyUpdate)
  const [activity, setActivity] = useState<FeatureActivitySummary>(emptyActivity)
  const [coldCheck, setColdCheck] = useState<ColdModelCheck | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    const [nextStatus, nextPacks, nextCatalogue, nextDeveloper, nextUpdate, nextActivity] = await Promise.all([
      needleBub.status(),
      needleBub.listPacks(),
      needleBub.catalogue(),
      needleBub.developerDataStatus(),
      needleBub.getPackUpdateStatus(),
      needleBub.getFeatureActivity({ featureId: 'otp', days: 7 }),
    ])
    setStatus(nextStatus)
    setPacks(nextPacks.packs)
    setCatalogue(nextCatalogue.entries)
    setDeveloper(nextDeveloper)
    setUpdate(nextUpdate)
    setActivity(nextActivity)
  }, [])

  useEffect(() => {
    void refresh()
    const onHash = () => setRoute(routeFromHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [refresh])

  useEffect(() => {
    document.documentElement.scrollTop = 0
    document.body.scrollTop = 0
    if (route === 'feature-otp-sources') {
      void needleBub.listNotificationApps().then((result) => setApps(result.apps))
    }
    if (route === 'settings' || route === 'settings-runtime') {
      void needleBub.diagnostics().then(setDiagnostics)
    }
    if (route === 'developer-data' || route === 'developer-records') {
      void needleBub.developerDataStatus().then(setDeveloper)
    }
  }, [route])

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        setDeveloper((current) => ({ ...current, labAuthenticated: false, adbPullExpiresAt: null }))
        void needleBub.closeDeveloperLab()
      } else {
        void refresh()
      }
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [refresh])

  const run = useCallback(async (key: string, work: () => Promise<unknown>, success?: string) => {
    setBusy(key)
    setNotice(null)
    try {
      await work()
      if (success) setNotice(success)
      await refresh()
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'That action could not be completed.')
    } finally {
      setBusy(null)
    }
  }, [refresh])

  const common = { status, packs, catalogue, update, activity, busy, notice, run, refresh }
  let content: React.ReactNode
  if (route === 'home') content = <HomeView {...common} />
  else if (route === 'feature-otp') content = <OtpFeatureView {...common} />
  else if (route === 'feature-otp-sources') content = <SourcesView status={status} apps={apps} onAppsChange={setApps} onRefresh={refresh} />
  else if (route === 'settings') content = <SettingsView status={status} developer={developer} diagnostics={diagnostics} coldCheck={coldCheck} busy={busy} onDeveloperChange={setDeveloper} onColdCheck={() => void run('cold-check', async () => setColdCheck(await needleBub.runColdModelCheck()))} />
  else if (route === 'settings-runtime') content = <RuntimeView diagnostics={diagnostics} />
  else if (route === 'settings-downloads') content = <DownloadsView update={update} busy={busy} onUpdate={setUpdate} run={run} />
  else if (route === 'settings-models') content = <ModelsView packs={packs} busy={busy} run={run} />
  else if (route === 'settings-integrations') content = <IntegrationsView status={status} />
  else if (route === 'settings-privacy') content = <PrivacyView busy={busy} run={run} />
  else if (route === 'developer-records') content = <RecordsView developer={developer} onDeveloperChange={setDeveloper} />
  else content = <DataControlsView developer={developer} onDeveloperChange={setDeveloper} />

  return <div className="app-shell">
    <AppBar route={route} />
    {content}
  </div>
}

type CommonViewProps = {
  status: AppStatus
  packs: PackInfo[]
  catalogue: CatalogueEntry[]
  update: PackUpdateStatus
  activity: FeatureActivitySummary
  busy: string | null
  notice: string | null
  run: (key: string, work: () => Promise<unknown>, success?: string) => Promise<void>
  refresh: () => Promise<void>
}

function HomeView({ status, packs, catalogue, activity, busy, notice, run, refresh }: CommonViewProps) {
  const feature = builtInFeatures[0]
  const pack = packs.find((value) => value.id === feature.packId && value.active)
  const active = status.automaticOtpConfigured && status.automaticOtpEnabled
  const requirements = requirementState(status)
  const next = requirements.find((item) => !item.complete)
  const [optimisticStatus, setOptimisticStatus] = useState(status.automaticOtpEnabled)
  useEffect(() => setOptimisticStatus(status.automaticOtpEnabled), [status.automaticOtpEnabled])

  const setEnabled = async (enabled: boolean) => {
    setOptimisticStatus(enabled)
    try {
      await needleBub.setAutomaticOtpEnabled({ enabled })
      await refresh()
    } catch {
      await refresh()
    }
  }

  const setupAction = () => {
    if (!status.otpPackInstalled) {
      const entry = catalogue.find((value) => value.id === feature.packId)
      void run('install', () => entry ? needleBub.installCataloguePack({ id: entry.id }) : needleBub.checkForPackUpdates({ allowMetered: true }), 'OTP Extractor installed.')
    } else if (!status.notificationAccess) {
      void needleBub.openNotificationAccess()
    } else if (!status.notificationPermission) {
      void run('permission', () => needleBub.requestNotificationPermission())
    } else {
      navigate('feature-otp-sources')
    }
  }

  const errorMetric = activity.todayErrors > 0
    ? `${activity.todayErrors} ${activity.todayErrors === 1 ? 'error' : 'errors'} today`
    : formatDuration(activity.averageDurationMs)
  const activityMetric = activity.todayOtp === 0 && activity.todayRejected === 0
    ? 'No activity today'
    : `${activity.todayOtp} ${activity.todayOtp === 1 ? 'code' : 'codes'} · ${activity.todayRejected} filtered today`

  return <main className="screen home-screen">
    <article className={`feature-module ${active ? 'feature-module--active' : ''}`}>
      <div className="feature-module__head">
        <button className="feature-module__open" onClick={() => navigate('feature-otp')} aria-label="Manage One-time codes">
          <h1>{feature.name}</h1>
          <p>{status.automaticOtpConfigured && !optimisticStatus ? 'Paused' : feature.description}</p>
        </button>
        {status.automaticOtpConfigured && <Toggle
          label="One-time codes"
          checked={optimisticStatus}
          disabled={busy !== null}
          onChange={(enabled) => void setEnabled(enabled)}
        />}
      </div>
      <div className="feature-module__seam">
        {status.automaticOtpConfigured ? <>
          <button className="feature-routing" onClick={() => navigate('feature-otp')} aria-label="Manage One-time codes routing">
            <span>{sourceLabel(status)}</span>
            <span>{pack?.name ?? 'OTP Extractor'} {formatPackVersion(pack?.version)}</span>
          </button>
          <button className="feature-metrics" onClick={() => navigate('feature-otp')} aria-label="Manage One-time codes activity">
            <span>{activityMetric}</span>
            <span className={activity.todayErrors > 0 ? 'metric-error' : ''}>{errorMetric}</span>
          </button>
        </> : <>
          <div className="setup-summary">
            <span>{requirements.filter((item) => item.complete).length} of {requirements.length} ready</span>
            <span>{next?.label ?? 'Ready'}</span>
          </div>
          <button className="primary-action setup-action" disabled={busy !== null} onClick={setupAction}>
            {busy ? 'Working…' : !status.otpPackInstalled ? 'Download model' : !status.notificationAccess ? 'Open notification access' : !status.notificationPermission ? 'Allow notifications' : 'Choose sources'}
          </button>
        </>}
      </div>
    </article>
    {notice && <p className="inline-notice" role="status">{notice}</p>}
    <button className="runtime-row" onClick={() => navigate('settings-runtime')} aria-label="Runtime, local and on device">
      <Gauge aria-hidden="true" />
      <span><strong>Local runtime</strong><small>On device · releases after 5 seconds idle</small></span>
      <ChevronRight aria-hidden="true" />
    </button>
  </main>
}

function OtpFeatureView({ status, packs, catalogue, update, activity, busy, notice, run }: CommonViewProps) {
  const pack = packs.find((value) => value.id === builtInFeatures[0].packId && value.active)
  const entry = catalogue.find((value) => value.id === builtInFeatures[0].packId)
  const requirements = requirementState(status)
  const setEnabled = async (enabled: boolean) => {
    await run('toggle-otp', () => needleBub.setAutomaticOtpEnabled({ enabled }))
  }
  const modelAction = async () => {
    if (!pack && entry) {
      await run('model', () => needleBub.installCataloguePack({ id: entry.id }), 'OTP Extractor installed.')
    } else {
      await run('model', () => needleBub.checkForPackUpdates({ allowMetered: true }), 'Model check complete.')
    }
  }

  return <main className="screen detail-screen">
    <section className="feature-overview">
      <div>
        <p className={`state-line ${status.automaticOtpConfigured && status.automaticOtpEnabled ? 'state-line--active' : ''}`}>
          {status.automaticOtpConfigured ? status.automaticOtpEnabled ? 'Active' : 'Paused' : 'Setup required'}
        </p>
        <p>Extract codes from notification messages and deliver them through a private result notification.</p>
      </div>
      {status.automaticOtpConfigured && <Toggle label="One-time codes" checked={status.automaticOtpEnabled} disabled={busy !== null} onChange={(enabled) => void setEnabled(enabled)} />}
    </section>

    {!status.automaticOtpConfigured && <section className="setup-panel">
      <h2>Finish setup</h2>
      <ul className="requirement-list">
        {requirements.map((item) => <li key={item.label} className={item.complete ? 'complete' : ''}>
          <span aria-hidden="true">{item.complete ? <Check /> : null}</span>
          <span>{item.label}</span>
          <small>{item.complete ? 'Ready' : 'Needed'}</small>
        </li>)}
      </ul>
    </section>}

    <section className="activity-section">
      <h2>Seven-day activity</h2>
      <div className="activity-grid">
        <div><strong>{activity.totalOtp}</strong><small>Codes</small></div>
        <div><strong>{activity.totalRejected}</strong><small>Filtered</small></div>
        <div><strong>{activity.averageDurationMs == null ? '—' : `${(activity.averageDurationMs / 1000).toFixed(1)}s`}</strong><small>Average</small></div>
        <div><strong>{activity.totalErrors}</strong><small>Errors</small></div>
      </div>
    </section>

    {notice && <p className="inline-notice" role="status">{notice}</p>}
    <section className="feature-settings">
      <h2>Feature settings</h2>
      <div className="feature-setting-row">
        <Package aria-hidden="true" />
        <span><strong>Model</strong><small>{pack ? `${pack.name} ${pack.version} · Verified` : 'OTP Extractor is not installed'}</small></span>
        <button className={pack ? 'text-action' : 'primary-small'} disabled={busy !== null} onClick={() => void modelAction()}>
          {busy === 'model' ? 'Working…' : !pack ? 'Download' : update.availableVersion ? 'Update' : 'Check'}
        </button>
      </div>
      <Row icon={LayoutGrid} label="Notification sources" value={sourceLabel(status)} onClick={() => navigate('feature-otp-sources')} />
      <Row icon={Bell} label="Result delivery" value={status.notificationPermission ? 'Private notifications allowed' : 'Permission needed'} onClick={() => void needleBub.openNotificationSettings()} />
    </section>
    {pack && <button className="danger-text" disabled={busy !== null} onClick={() => {
      if (window.confirm(`Remove ${pack.name} ${pack.version}? One-time codes will stop until it is reinstalled.`)) {
        void run('remove-official', () => needleBub.removePack({ id: pack.id, version: pack.version }), `${pack.name} removed.`)
      }
    }}>Remove {pack.name}</button>}
  </main>
}

function SourcesView({
  status,
  apps,
  onAppsChange,
  onRefresh,
}: {
  status: AppStatus
  apps: NotificationApp[]
  onAppsChange: (apps: NotificationApp[]) => void
  onRefresh: () => Promise<void>
}) {
  const [allApps, setAllApps] = useState(status.allApps)
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => setAllApps(status.allApps), [status.allApps])

  const save = async (nextAll: boolean, nextApps: NotificationApp[], previousAll: boolean, previousApps: NotificationApp[]) => {
    setError(null)
    setAllApps(nextAll)
    onAppsChange(nextApps)
    try {
      await needleBub.saveNotificationApps({
        allApps: nextAll,
        packages: nextApps.filter((app) => app.selected).map((app) => app.packageName),
      })
      await onRefresh()
    } catch {
      setAllApps(previousAll)
      onAppsChange(previousApps)
      setError('Could not save that source change. Your previous selection is still active.')
    }
  }

  const filtered = apps.filter((app) => app.label.toLocaleLowerCase().includes(query.toLocaleLowerCase()) || app.packageName.includes(query))
  return <main className="screen detail-screen">
    <div className="switch-row switch-row--first">
      <span><strong>All notification apps</strong><small>Process notifications from every installed app.</small></span>
      <Toggle label="All notification apps" checked={allApps} onChange={(enabled) => void save(enabled, apps, allApps, apps)} />
    </div>
    {error && <p className="inline-notice inline-notice--error" role="alert">{error}</p>}
    {!allApps && <>
      <label className="search-field">
        <Search aria-hidden="true" />
        <span className="sr-only">Search apps</span>
        <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search apps" />
      </label>
      <div className="app-list">
        {filtered.map((app) => <label className="app-choice" key={app.packageName}>
          <input type="checkbox" checked={app.selected} onChange={() => {
            const next = apps.map((value) => value.packageName === app.packageName ? { ...value, selected: !value.selected } : value)
            void save(false, next, allApps, apps)
          }} />
          <span><strong>{app.label}</strong><small>{app.packageName}</small></span>
        </label>)}
        {filtered.length === 0 && <p className="empty-copy">No matching apps.</p>}
      </div>
    </>}
  </main>
}

function SettingsView({
  status,
  developer,
  diagnostics,
  coldCheck,
  busy,
  onDeveloperChange,
  onColdCheck,
}: {
  status: AppStatus
  developer: DeveloperDataStatus
  diagnostics: DiagnosticInfo | null
  coldCheck: ColdModelCheck | null
  busy: string | null
  onDeveloperChange: (developer: DeveloperDataStatus) => void
  onColdCheck: () => void
}) {
  const [tapCount, setTapCount] = useState(0)
  const [toast, setToast] = useState<string | null>(null)
  const toastTimer = useRef<number | null>(null)

  const showToast = (message: string) => {
    setToast(message)
    if (toastTimer.current != null) window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(null), 2400)
  }

  const tapVersion = async () => {
    if (developer.unlocked) {
      showToast('Developer Mode is already unlocked.')
      return
    }
    const next = tapCount + 1
    setTapCount(next)
    if (next >= 7) {
      await needleBub.unlockDeveloperData()
      onDeveloperChange(await needleBub.developerDataStatus())
      showToast('Developer Mode unlocked.')
    } else if (next > 1) {
      showToast(`${7 - next} more taps to unlock Developer Mode.`)
    }
  }

  const coldResult = coldCheck
    ? `${coldCheck.passed ? 'Passed' : `Failed · ${coldCheck.errorCode ?? 'UNKNOWN'}`} · ${coldCheck.coldLoad ? 'cold load' : 'warm load'} · ${coldCheck.durationMs} ms · ${Math.round(coldCheck.pssKb / 1024)} MiB`
    : null

  return <main className="screen settings-screen">
    <Group title="Inference">
      <Row icon={Gauge} label="Runtime" value="Local · 5 second idle release" onClick={() => navigate('settings-runtime')} />
      <Row icon={Download} label="Downloads" value="Automatic updates and network policy" onClick={() => navigate('settings-downloads')} />
      <Row icon={FileArchive} label="Models and imports" value="Manage unverified external packs" onClick={() => navigate('settings-models')} />
    </Group>
    <Group title="Connections">
      <Row icon={Bot} label="Integrations" value={status.macroDroidInstalled ? 'MacroDroid and inference gateway' : 'Inference gateway'} onClick={() => navigate('settings-integrations')} />
      <Row icon={Shield} label="Privacy and data" value="On-device processing and activity summaries" onClick={() => navigate('settings-privacy')} />
    </Group>
    {developer.unlocked && <Group title="Developer">
      <Row icon={FlaskConical} label="Notification records" value={`${developer.recordCount} encrypted ${developer.recordCount === 1 ? 'record' : 'records'}`} onClick={() => navigate('developer-records')} />
      <Row icon={FileArchive} label="Capture and export" value={developer.captureEnabled ? 'Capture enabled' : 'Capture disabled'} onClick={() => navigate('developer-data')} />
      <div className="inline-tool">
        <span><strong>Cold model check</strong><small>Forces a sanitized local model reload.</small></span>
        <button className="secondary-action" disabled={busy !== null} onClick={onColdCheck}>{busy === 'cold-check' ? 'Running…' : 'Run check'}</button>
      </div>
      {coldResult && <p className={coldCheck?.passed ? 'check-result check-result--passed' : 'check-result'} role="status">{coldResult}</p>}
    </Group>}
    <Group title="About">
      <div className="fact-row"><span>Version</span><button aria-label={`Version ${String(diagnostics?.version ?? 'Reading')}`} onClick={() => void tapVersion()}>{String(diagnostics?.version ?? 'Reading…')}</button></div>
      <div className="fact-row"><span>Engine</span><code>{String(diagnostics?.engineAbi ?? 'Reading…')}</code></div>
      <details className="about-details">
        <summary><Info aria-hidden="true" /><span>Privacy and licenses</span><ChevronRight aria-hidden="true" /></summary>
        <div>
          <p>Normal feature activity stores counters and timing totals only. Notification text and model output are stored only when encrypted Developer capture is explicitly enabled.</p>
          <p>NeedleBub is MIT licensed. Needle and Locale notices are Apache-2.0. Interface icons are Lucide.</p>
        </div>
      </details>
    </Group>
    {toast && <div className="unlock-toast" role="status" aria-live="polite">{toast}</div>}
  </main>
}

function RuntimeView({ diagnostics }: { diagnostics: DiagnosticInfo | null }) {
  return <main className="screen detail-screen">
    <p className="page-lead">NeedleBub loads installed models into a permissionless isolated Android process only when work exists.</p>
    <dl className="evidence-list">
      <div><dt>Execution</dt><dd>On device</dd></div>
      <div><dt>Idle release</dt><dd>5 seconds</dd></div>
      <div><dt>Engine ABI</dt><dd><code>{String(diagnostics?.engineAbi ?? 'Reading…')}</code></dd></div>
      <div><dt>Gateway</dt><dd><code>de.x0bubbuff.needlebub.action.INFERENCE_GATEWAY</code></dd></div>
    </dl>
  </main>
}

function DownloadsView({
  update,
  busy,
  onUpdate,
  run,
}: {
  update: PackUpdateStatus
  busy: string | null
  onUpdate: (update: PackUpdateStatus) => void
  run: CommonViewProps['run']
}) {
  const setUpdates = async (enabled: boolean) => {
    const previous = update
    onUpdate({ ...update, enabled })
    try {
      await needleBub.setAutomaticPackUpdates({ enabled })
      onUpdate(await needleBub.getPackUpdateStatus())
    } catch {
      onUpdate(previous)
    }
  }
  const setNetwork = async (allowMetered: boolean) => {
    const previous = update
    onUpdate({ ...update, networkPolicy: allowMetered ? 'any' : 'unmetered' })
    try {
      await needleBub.setPackUpdateNetworkPolicy({ allowMetered })
      onUpdate(await needleBub.getPackUpdateStatus())
    } catch {
      onUpdate(previous)
    }
  }
  return <main className="screen detail-screen">
    <div className="switch-row switch-row--first">
      <span><strong>Automatic model updates</strong><small>Checks the signed catalogue every 24 hours.</small></span>
      <Toggle label="Automatic pack updates" checked={update.enabled} disabled={busy !== null} onChange={(enabled) => void setUpdates(enabled)} />
    </div>
    <div className="switch-row">
      <span><strong>Allow mobile downloads</strong><small>Background downloads may use mobile data.</small></span>
      <Toggle label="Allow mobile downloads" checked={update.networkPolicy === 'any'} disabled={busy !== null} onChange={(enabled) => void setNetwork(enabled)} />
    </div>
    <dl className="evidence-list">
      <div><dt>Status</dt><dd>{updateLabel(update)}</dd></div>
      <div><dt>Network</dt><dd>{update.networkPolicy === 'any' ? 'Wi-Fi and mobile' : 'Wi-Fi only'}</dd></div>
      <div><dt>Last checked</dt><dd>{update.lastCheckedAt ? new Date(update.lastCheckedAt).toLocaleString() : 'Not yet'}</dd></div>
      {update.lastError && <div><dt>Last error</dt><dd>{update.lastError}</dd></div>}
    </dl>
    <button className="secondary-action" disabled={busy !== null} onClick={() => void run('update-check', async () => onUpdate(await needleBub.checkForPackUpdates({ allowMetered: true })), 'Model check complete.')}>
      <RefreshCw aria-hidden="true" />{busy === 'update-check' ? 'Checking…' : 'Check now'}
    </button>
    <p className="support-copy">A deliberate manual check may download over mobile regardless of the background policy.</p>
  </main>
}

function ModelsView({ packs, busy, run }: { packs: PackInfo[]; busy: string | null; run: CommonViewProps['run'] }) {
  const imported = packs.filter((pack) => pack.id !== builtInFeatures[0].packId)
  return <main className="screen detail-screen">
    <button className="secondary-action" disabled={busy !== null} onClick={() => void run('import', () => needleBub.pickPack(), 'Pack imported as unverified.')}>
      <FileArchive aria-hidden="true" />Import .nbpack
    </button>
    <p className="support-copy">Imported packs are unverified data-only capabilities for external callers. They cannot add application code or screens.</p>
    <div className="import-list">
      {imported.map((pack) => <div className="import-row" key={`${pack.id}@${pack.version}`}>
        <span><strong>{pack.name}</strong><small>{pack.version} · Unverified</small></span>
        <button className="icon-button danger-icon" aria-label={`Remove ${pack.name}`} onClick={() => {
          if (window.confirm(`Remove ${pack.name} ${pack.version}?`)) void run(`remove-${pack.id}`, () => needleBub.removePack({ id: pack.id, version: pack.version }), `${pack.name} removed.`)
        }}><Trash2 aria-hidden="true" /></button>
      </div>)}
      {imported.length === 0 && <p className="empty-copy">No external packs installed.</p>}
    </div>
  </main>
}

function IntegrationsView({ status }: { status: AppStatus }) {
  return <main className="screen detail-screen">
    <section className="copy-section">
      <h2>MacroDroid</h2>
      <p>Run any installed external capability pack from MacroDroid.</p>
      <Row icon={Bot} label="Open MacroDroid" value={status.macroDroidInstalled ? 'Installed' : 'Not installed'} disabled={!status.macroDroidInstalled} onClick={() => void needleBub.openMacroDroid()} />
    </section>
    <section className="technical-block">
      <h2>Inference gateway</h2>
      <code>de.x0bubbuff.needlebub.action.INFERENCE_GATEWAY</code>
      <p>Installed external packs can process caller-supplied text through the bounded public Binder gateway.</p>
    </section>
  </main>
}

function PrivacyView({ busy, run }: { busy: string | null; run: CommonViewProps['run'] }) {
  return <main className="screen detail-screen">
    <section className="copy-section">
      <h2>Normal operation</h2>
      <p>Notification text, extracted codes, reasoning, calls, and result JSON remain in memory and are not transmitted.</p>
    </section>
    <section className="copy-section">
      <h2>Activity summaries</h2>
      <p>NeedleBub keeps seven daily buckets containing outcome counts, completed inference counts, duration totals, and the last activity time. They contain no app identity or message content.</p>
      <button className="danger-action" disabled={busy !== null} onClick={() => {
        if (window.confirm('Reset all seven-day feature activity summaries?')) {
          void run('reset-activity', () => needleBub.resetFeatureActivity(), 'Activity summaries reset.')
        }
      }}>Reset activity summaries</button>
    </section>
  </main>
}

function useDeveloperAuthentication(
  developer: DeveloperDataStatus,
  onDeveloperChange: (developer: DeveloperDataStatus) => void,
) {
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    if (!developer.unlocked || developer.labAuthenticated) return
    setError(null)
    void needleBub.authenticateDeveloperLab()
      .then(async () => {
        const next = await needleBub.developerDataStatus()
        if (live) onDeveloperChange(next)
      })
      .catch((reason) => {
        if (live) setError(reason instanceof Error ? reason.message : 'Device authentication failed.')
      })
    return () => { live = false }
  }, [developer.labAuthenticated, developer.unlocked, onDeveloperChange])
  return { authenticated: developer.unlocked && developer.labAuthenticated, error }
}

const recordFilters = ['ALL', 'OTP', 'REJECTED', 'NOT_RUN', 'ERROR'] as const

function decisionLabel(value: string) {
  if (value === 'OTP') return 'OTP'
  if (value === 'NOT_RUN') return 'Not run'
  if (value === 'SUPPRESSED') return 'Suppressed'
  if (value === 'ERROR') return 'Error'
  if (value === 'PENDING') return 'Pending'
  return 'Rejected'
}

function DecisionIcon({ decision }: { decision: string }) {
  if (decision === 'OTP') return <CircleCheck aria-hidden="true" />
  if (decision === 'ERROR') return <AlertTriangle aria-hidden="true" />
  if (decision === 'PENDING') return <Clock aria-hidden="true" />
  return <CircleX aria-hidden="true" />
}

function RecordsView({
  developer,
  onDeveloperChange,
}: {
  developer: DeveloperDataStatus
  onDeveloperChange: (developer: DeveloperDataStatus) => void
}) {
  const { authenticated, error } = useDeveloperAuthentication(developer, onDeveloperChange)
  const [records, setRecords] = useState<NotificationRecordSummary[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [filter, setFilter] = useState<(typeof recordFilters)[number]>('ALL')
  const [selected, setSelected] = useState<NotificationRecord | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async (nextFilter: string, cursor?: number, append = false) => {
    const result = await needleBub.listNotificationRecords({ cursor, limit: 50, filter: nextFilter })
    setRecords((current) => append ? [...current, ...result.records] : result.records)
    setNextCursor(result.nextCursor)
  }, [])

  useEffect(() => {
    if (!authenticated) {
      setRecords([])
      setSelected(null)
      setNextCursor(null)
      return
    }
    void load(filter)
  }, [authenticated, filter, load])

  if (!developer.unlocked) return <LockedDeveloperView />
  if (!authenticated) return <AuthenticationView error={error} />
  if (selected) return <main className="screen detail-screen"><RecordDetail record={selected} onBack={() => setSelected(null)} /></main>

  return <main className="screen detail-screen records-screen">
    <div className="filter-strip" aria-label="Record filter">
      {recordFilters.map((value) => <button key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>
        {value === 'ALL' ? 'All' : decisionLabel(value)}
      </button>)}
    </div>
    <div className="record-list">
      {records.map((record) => <button key={record.id} className="record-row" onClick={() => void (async () => {
        setBusy(true)
        try { setSelected(await needleBub.getNotificationRecord({ id: record.id })) } finally { setBusy(false) }
      })()}>
        <DecisionIcon decision={record.decision} />
        <span><strong>{record.title || record.appLabel}</strong><small>{record.appLabel} · {new Date(record.capturedAt).toLocaleString()}</small></span>
        <span className={`decision-text decision-text--${record.decision.toLowerCase()}`}>{decisionLabel(record.decision)}</span>
      </button>)}
      {records.length === 0 && <p className="empty-copy">No matching notification records.</p>}
      {nextCursor != null && <button className="secondary-action load-more" disabled={busy} onClick={() => void load(filter, nextCursor, true)}>Load more</button>}
    </div>
  </main>
}

function DataControlsView({
  developer,
  onDeveloperChange,
}: {
  developer: DeveloperDataStatus
  onDeveloperChange: (developer: DeveloperDataStatus) => void
}) {
  const { authenticated, error } = useDeveloperAuthentication(developer, onDeveloperChange)
  const [busy, setBusy] = useState(false)
  const [passphrase, setPassphrase] = useState('')
  const [deleteAfterExport, setDeleteAfterExport] = useState(true)

  const refresh = useCallback(async () => onDeveloperChange(await needleBub.developerDataStatus()), [onDeveloperChange])
  useEffect(() => {
    if (!developer.adbPullExpiresAt) return
    const remaining = developer.adbPullExpiresAt - Date.now() + 50
    const timeout = window.setTimeout(() => void refresh(), Math.max(0, Math.min(remaining, 2_147_483_647)))
    return () => window.clearTimeout(timeout)
  }, [developer.adbPullExpiresAt, refresh])

  if (!developer.unlocked) return <LockedDeveloperView />
  if (!authenticated) return <AuthenticationView error={error} />

  const toggleCapture = async (enabled: boolean) => {
    if (enabled && !window.confirm('Capture full notification text, model reasoning, calls, and outcomes locally?')) return
    setBusy(true)
    try {
      await needleBub.setNotificationCaptureEnabled({ enabled })
      await refresh()
    } finally {
      setBusy(false)
    }
  }

  return <main className="screen detail-screen data-screen">
    <div className="switch-row switch-row--first">
      <span><strong>Notification capture</strong><small>{developer.captureEnabled ? 'Encrypted local capture is active' : 'Off · normal privacy behavior'}</small></span>
      <Toggle label="Notification capture" checked={developer.captureEnabled} disabled={busy} onChange={(enabled) => void toggleCapture(enabled)} />
    </div>
    <p className="sensitive-note">When enabled, full notification text and model output are encrypted locally for developer inspection.</p>
    <dl className="evidence-list">
      <div><dt>Records</dt><dd>{developer.recordCount}</dd></div>
      <div><dt>Encrypted size</dt><dd>{Math.ceil(developer.storedBytes / 1024)} KiB</dd></div>
      <div><dt>Retention</dt><dd>30 days · 10,000 max</dd></div>
    </dl>

    <section className="technical-block">
      <h2>Temporary ADB access</h2>
      <p>Streams decrypted JSONL only to Android’s shell user for ten minutes or until this authenticated session closes.</p>
      <code>adb exec-out content read --uri content://de.x0bubbuff.needlebub.developer/captures &gt; needlebub-captures.jsonl</code>
      {developer.adbPullExpiresAt ? <>
        <p className="check-result check-result--passed" role="status">ADB pull allowed until {new Date(developer.adbPullExpiresAt).toLocaleTimeString()}.</p>
        <button className="secondary-action" disabled={busy} onClick={() => void (async () => {
          setBusy(true)
          try { await needleBub.revokeAdbCapturePull(); await refresh() } finally { setBusy(false) }
        })()}>Revoke ADB pull</button>
      </> : <button className="secondary-action" disabled={busy || developer.recordCount === 0} onClick={() => void (async () => {
        setBusy(true)
        try { await needleBub.grantAdbCapturePull(); await refresh() } finally { setBusy(false) }
      })()}>Allow ADB pull for 10 minutes</button>}
    </section>

    <section className="export-block">
      <label htmlFor="capture-passphrase">Export password</label>
      <input id="capture-passphrase" type="password" autoComplete="new-password" minLength={12} value={passphrase} onChange={(event) => setPassphrase(event.target.value)} />
      <label className="check-row"><input type="checkbox" checked={deleteAfterExport} onChange={(event) => setDeleteAfterExport(event.target.checked)} /><span>Delete records after verified export</span></label>
      <button className="secondary-action" disabled={busy || passphrase.length < 12 || developer.recordCount === 0} onClick={() => void (async () => {
        setBusy(true)
        try {
          await needleBub.exportNotificationCapture({ passphrase, deleteAfterExport })
          setPassphrase('')
          await refresh()
        } finally {
          setBusy(false)
        }
      })()}>Export encrypted capture</button>
    </section>
    <button className="danger-action" disabled={busy || developer.recordCount === 0} onClick={() => {
      if (window.confirm(`Permanently remove ${developer.recordCount} captured notification records?`)) {
        void (async () => {
          setBusy(true)
          try { await needleBub.clearNotificationCapture(); await refresh() } finally { setBusy(false) }
        })()
      }
    }}>Clear captured records</button>
  </main>
}

function LockedDeveloperView() {
  return <main className="screen detail-screen"><p className="inline-notice inline-notice--error">Developer Mode is locked.</p></main>
}

function AuthenticationView({ error }: { error: string | null }) {
  return <main className="screen detail-screen">
    {error ? <p className="inline-notice inline-notice--error">{error}</p> : <p className="loading-line">Confirming device authentication…</p>}
  </main>
}

function RecordDetail({ record, onBack }: { record: NotificationRecord; onBack: () => void }) {
  const runtime = record.runtime
  const outcome = record.outcome
  return <article className="record-detail">
    <button className="back-inline" onClick={onBack}><ArrowLeft aria-hidden="true" />All records</button>
    <section>
      <h2>Notification</h2>
      <dl className="trace-list">
        <div><dt>App</dt><dd>{record.appLabel}</dd></div>
        <div><dt>Package</dt><dd><code>{record.packageName}</code></dd></div>
        <div><dt>Title</dt><dd>{record.title || 'None'}</dd></div>
        <div><dt>Message</dt><dd className="trace-message">{record.body || 'Empty'}</dd></div>
      </dl>
    </section>
    <section>
      <h2>Model</h2>
      <dl className="trace-list">
        <div><dt>Response</dt><dd>{runtime?.responseType ?? runtime?.status ?? 'Not run'}</dd></div>
        <div><dt>Reasoning</dt><dd className="trace-reasoning">{runtime?.reasoning || 'No reasoning returned.'}</dd></div>
        <div><dt>Tool</dt><dd><code>{runtime?.toolName ?? 'No call'}</code></dd></div>
        <div><dt>Arguments</dt><dd><code>{runtime?.resultJson ?? 'None'}</code></dd></div>
      </dl>
    </section>
    <section>
      <h2>Final decision</h2>
      <div className="decision-summary">
        <DecisionIcon decision={outcome?.decision ?? 'PENDING'} />
        <span><strong>{decisionLabel(outcome?.decision ?? 'PENDING')}</strong><small>{outcome?.reasonCode ?? 'INTERRUPTED'}</small></span>
      </div>
      {outcome?.code && <dl className="trace-list">
        <div><dt>Code</dt><dd><code>{outcome.code}</code></dd></div>
        <div><dt>Source</dt><dd>{outcome.source ?? 'Sender fallback'} · {outcome.sourceDisposition ?? 'absent'}</dd></div>
      </dl>}
    </section>
    <section>
      <h2>Performance</h2>
      <dl className="trace-list">
        <div><dt>Load</dt><dd>{runtime?.coldLoad ? 'Cold' : runtime ? 'Warm' : 'Not run'}</dd></div>
        <div><dt>Duration</dt><dd>{runtime?.durationMs == null ? '—' : `${runtime.durationMs} ms`}</dd></div>
        <div><dt>PSS</dt><dd>{runtime?.pssKb == null ? '—' : `${Math.round(runtime.pssKb / 1024)} MiB`}</dd></div>
        <div><dt>Pack</dt><dd><code>{runtime?.packId && runtime?.packVersion ? `${runtime.packId}@${runtime.packVersion}` : 'None'}</code></dd></div>
      </dl>
    </section>
  </article>
}

export default App
