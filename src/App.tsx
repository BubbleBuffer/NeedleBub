import { useEffect, useMemo, useState } from 'react'

import './App.css'
import { needleBub, type AppStatus, type CatalogueEntry, type DiagnosticInfo, type NotificationApp, type PackInfo } from './native'

type Surface = 'start' | 'status' | 'packs' | 'sources' | 'connect' | 'settings'

const surfaces: Array<{ id: Surface; label: string }> = [
  { id: 'start', label: 'Start' }, { id: 'status', label: 'Status' }, { id: 'packs', label: 'Packs' },
  { id: 'sources', label: 'Sources' }, { id: 'connect', label: 'Connect' }, { id: 'settings', label: 'Settings' },
]

const emptyStatus: AppStatus = { otpPackInstalled: false, notificationAccess: false, notificationPermission: false, allApps: false, selectedAppCount: 0, automaticOtpReady: false, macroDroidInstalled: false }

export function KittyMark({ large = false }: { large?: boolean }) {
  return <svg className={large ? 'kitty-mark kitty-mark--large' : 'kitty-mark'} viewBox="0 0 48 48" aria-hidden="true">
    <path d="M8 35V17l9-9 7 8 7-8 9 9v18l-6 6H14l-6-6Z" className="kitty-contour" />
    <path d="M31 8l5 5" className="kitty-thread" /><path d="M17 28h2M29 28h2" className="kitty-face" />
  </svg>
}

function RoutingSeam() {
  return <div className="routing-seam" aria-label="Source to pack to result">
    <span><small>01</small><strong>Source</strong></span><i aria-hidden="true" />
    <span><small>02</small><strong>Pack</strong></span><i aria-hidden="true" />
    <span><small>03</small><strong>Result</strong></span>
  </div>
}

function StateRow({ ready, label, detail, action }: { ready: boolean; label: string; detail: string; action?: React.ReactNode }) {
  return <div className="state-row"><span className={`state-pin ${ready ? 'state-pin--ready' : ''}`} aria-hidden="true" /><div><strong>{label}</strong><p>{detail}</p></div><span className="state-word">{ready ? 'Ready' : 'Needed'}</span>{action}</div>
}

export default function App() {
  const [surface, setSurface] = useState<Surface>('start')
  const [status, setStatus] = useState<AppStatus>(emptyStatus)
  const [packs, setPacks] = useState<PackInfo[]>([])
  const [catalogue, setCatalogue] = useState<CatalogueEntry[]>([])
  const [apps, setApps] = useState<NotificationApp[]>([])
  const [diagnostics, setDiagnostics] = useState<DiagnosticInfo | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [theme, setTheme] = useState(localStorage.getItem('needlebub-theme') ?? 'system')

  const refresh = async () => {
    const [nextStatus, nextPacks] = await Promise.all([needleBub.status(), needleBub.listPacks()])
    setStatus(nextStatus); setPacks(nextPacks.packs)
  }

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- initial state comes from the native bridge.
    void refresh(); void needleBub.catalogue().then((value) => setCatalogue(value.entries))
    const onVisible = () => { if (document.visibilityState === 'visible') void refresh() }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])
  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('needlebub-theme', theme) }, [theme])
  useEffect(() => {
    if (surface === 'sources' && apps.length === 0) void needleBub.listNotificationApps().then((value) => setApps(value.apps))
    if (surface === 'settings' && !diagnostics) void needleBub.diagnostics().then(setDiagnostics)
  }, [surface, apps.length, diagnostics])

  const officialEntry = catalogue.find((entry) => entry.id === 'de.x0bubbuff.needlebub.otp')
  const sourceLabel = status.allApps ? 'All notification apps' : status.selectedAppCount ? `${status.selectedAppCount} selected apps` : 'No apps selected'
  const run = async (key: string, work: () => Promise<unknown>, success: string) => {
    setBusy(key); setNotice(null)
    try { await work(); await refresh(); setNotice(success) }
    catch (error) { setNotice(error instanceof Error ? error.message : 'That did not complete. Your existing setup is unchanged.') }
    finally { setBusy(null) }
  }

  const content = useMemo(() => {
    if (surface === 'start') return <section className="view start-view" aria-labelledby="start-title">
      <div className="hero-copy"><p className="kicker">Local capability host</p><h1 id="start-title">Tiny models.<br />Quietly useful.</h1><p>NeedleBub reads only the notifications you select, routes them through a local Needle pack, and forgets the text when the job is done.</p>
        <div className="hero-actions">{!status.otpPackInstalled && officialEntry ? <button className="primary" disabled={busy !== null} onClick={() => void run('otp', () => needleBub.installCataloguePack({ id: officialEntry.id }), 'OTP Extractor installed.')}>{busy === 'otp' ? 'Installing…' : 'Install OTP pack'}</button> : !status.otpPackInstalled ? <button className="primary" disabled={busy !== null} onClick={() => void run('import', () => needleBub.pickPack(), 'Pack imported. Local imports remain external-only.')}>{busy === 'import' ? 'Opening…' : 'Choose OTP .nbpack'}</button> : <button className="primary" onClick={() => setSurface('status')}>Finish setup</button>}<button className="secondary" onClick={() => setSurface('packs')}>View packs</button></div>
      </div><div className="hero-character" aria-hidden="true"><KittyMark large /><span className="thread-tail" /></div><RoutingSeam /><p className="privacy-line">No notification body, OTP, or inference result is stored or transmitted.</p>
    </section>

    if (surface === 'status') return <section className="view" aria-labelledby="status-title"><header className="view-heading"><div><p className="kicker">Runtime</p><h1 id="status-title">Automatic OTP</h1></div><span className={`readiness ${status.automaticOtpReady ? 'readiness--ready' : ''}`}>{status.automaticOtpReady ? 'Listening' : 'Setup needed'}</span></header><RoutingSeam />
      <div className="state-field"><StateRow ready={status.otpPackInstalled} label="Official OTP pack" detail={status.otpPackInstalled ? 'Verified pack is available to the notification route.' : 'Install the verified OTP pack explicitly.'} action={<button className="text-action" onClick={() => setSurface('packs')}>Open packs</button>} /><StateRow ready={status.notificationAccess} label="Notification access" detail={status.notificationAccess ? 'NeedleBub can receive selected notification updates.' : 'Android must allow the notification listener.'} action={<button className="text-action" onClick={() => void needleBub.openNotificationAccess()}>Open access</button>} /><StateRow ready={status.notificationPermission} label="Private result notification" detail={status.notificationPermission ? 'Short-lived results may be shown privately.' : 'Allow NeedleBub to post the accepted result.'} action={<button className="text-action" onClick={() => void needleBub.requestNotificationPermission().then(refresh)}>Allow</button>} /><StateRow ready={status.allApps || status.selectedAppCount > 0} label="Notification sources" detail={sourceLabel} action={<button className="text-action" onClick={() => setSurface('sources')}>Choose apps</button>} /></div>
      <aside className="recovery-note"><span aria-hidden="true" />Your choices and installed packs remain intact if Android stops the isolated runtime. NeedleBub reconnects for the next request.</aside>
    </section>

    if (surface === 'packs') return <section className="view" aria-labelledby="packs-title"><header className="view-heading"><div><p className="kicker">Capability catalogue</p><h1 id="packs-title">Packs</h1></div><button className="primary compact" disabled={busy !== null} onClick={() => void run('import', () => needleBub.pickPack(), 'Pack imported. Local imports are unverified and external-only.')}>Import .nbpack</button></header>{notice && <p className="notice" role="status">{notice}</p>}
      {packs.length === 0 ? <div className="empty-state"><KittyMark /><h2>No packs fitted yet</h2><p>Install the official OTP pack or inspect any local <code>.nbpack</code> through the same sandbox.</p></div> : <div className="pack-list">{packs.map((pack) => <article className="pack-row" key={`${pack.id}@${pack.version}`}><div><div className="pack-title"><h2>{pack.name}</h2><span className={pack.verified ? 'verified' : 'unverified'}>{pack.verified ? 'Verified catalogue' : 'Unverified import'}</span></div><p>{pack.description}</p><code>{pack.id}@{pack.version}</code></div><div className="pack-actions"><span>{pack.outputs.join(' · ')}</span><button className="danger-text" onClick={() => { if (window.confirm(`Remove ${pack.name} ${pack.version}?`)) void run(`remove-${pack.id}`, () => needleBub.removePack({ id: pack.id, version: pack.version }), 'Pack removed.') }}>Remove</button></div></article>)}</div>}
      {!status.otpPackInstalled && officialEntry && <div className="catalogue-row"><div><strong>OTP Extractor</strong><p>Official notification capability · explicit {Math.round(officialEntry.size / 1_000_000)} MB download</p></div><button className="primary compact" disabled={busy !== null} onClick={() => void run('otp', () => needleBub.installCataloguePack({ id: officialEntry.id }), 'OTP Extractor installed.')}>Install</button></div>}
    </section>

    if (surface === 'sources') return <SourcesView apps={apps} status={status} onChange={setApps} onSaved={async (allApps, packages) => { await needleBub.saveNotificationApps({ allApps, packages }); await refresh(); setNotice('Notification choices saved.') }} notice={notice} />
    if (surface === 'connect') return <section className="view" aria-labelledby="connect-title"><header className="view-heading"><div><p className="kicker">Automation</p><h1 id="connect-title">Connect</h1></div></header><div className="connected-fields"><article><span className="field-number">01</span><h2>MacroDroid</h2><p>Add Applications → Tasker/Locale Plugin → NeedleBub inference. Choose an external pack, insert Magic Text, then bind the declared outputs.</p><code>nb_code · nb_source · nb_error_code</code><p className="status-copy">{status.macroDroidInstalled ? 'MacroDroid is installed. Output interoperability still needs a live macro run.' : 'MacroDroid was not detected on this device.'}</p></article><article><span className="field-number">02</span><h2>Android gateway</h2><p>Bind explicitly to the exported asynchronous service. Callers provide their own text; notification content and NeedleBub settings are never exposed.</p><code>de.x0bubbuff.needlebub.action.INFERENCE_GATEWAY</code><p className="status-copy">One in flight per UID · burst 3 · 10/minute</p></article></div></section>
    return <section className="view" aria-labelledby="settings-title"><header className="view-heading"><div><p className="kicker">About this build</p><h1 id="settings-title">Settings</h1></div></header><div className="settings-field"><label htmlFor="theme">Appearance</label><select id="theme" value={theme} onChange={(event) => setTheme(event.target.value)}><option value="system">Follow system</option><option value="light">Warm light</option><option value="dark">Warm dark</option></select><div><strong>Memory-only inference</strong><p>Input, output, extracted codes, and result JSON are never persisted. Diagnostics contain only pack identity, status, durations, and memory measurements.</p></div><div><strong>Licenses</strong><p>NeedleBub is MIT licensed. Needle and the Locale protocol notices are Apache-2.0.</p></div></div><details className="diagnostics"><summary>Diagnostics</summary>{diagnostics ? <dl>{Object.entries(diagnostics).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value ?? 'Not detected')}</dd></div>)}</dl> : <p>Reading build facts…</p>}</details></section>
  }, [surface, status, officialEntry, busy, notice, packs, apps, diagnostics, theme, sourceLabel])

  return <div className="app-shell"><header className="topbar"><button className="brand" onClick={() => setSurface('start')} aria-label="NeedleBub home"><KittyMark /><span>NeedleBub</span></button><span className="alpha-label">Private alpha</span></header><nav className="surface-nav" aria-label="NeedleBub sections">{surfaces.map((item) => <button key={item.id} className={surface === item.id ? 'active' : ''} aria-current={surface === item.id ? 'page' : undefined} onClick={() => { setNotice(null); setSurface(item.id) }}>{item.label}</button>)}</nav><main>{content}</main></div>
}

function SourcesView({ apps, status, onChange, onSaved, notice }: { apps: NotificationApp[]; status: AppStatus; onChange: (apps: NotificationApp[]) => void; onSaved: (allApps: boolean, packages: string[]) => Promise<void>; notice: string | null }) {
  const [allApps, setAllApps] = useState(status.allApps); const [filter, setFilter] = useState('')
  const visible = apps.filter((app) => `${app.label} ${app.packageName}`.toLowerCase().includes(filter.toLowerCase())); const selected = apps.filter((app) => app.selected).map((app) => app.packageName)
  return <section className="view" aria-labelledby="sources-title"><header className="view-heading"><div><p className="kicker">Notification boundary</p><h1 id="sources-title">Sources</h1></div><button className="primary compact" onClick={() => void onSaved(allApps, selected)}>Save choices</button></header>{notice && <p className="notice" role="status">{notice}</p>}<label className="switch-row"><span><strong>All notification apps</strong><small>Explicitly send every eligible notification through the OTP prefilter.</small></span><input type="checkbox" checked={allApps} onChange={(event) => setAllApps(event.target.checked)} /><i aria-hidden="true" /></label>{!allApps && <><label className="search-label" htmlFor="app-filter">Find an app</label><input id="app-filter" className="search-input" value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="App name or package" /><div className="app-list">{visible.map((app) => <label key={app.packageName}><input type="checkbox" checked={app.selected} onChange={(event) => onChange(apps.map((entry) => entry.packageName === app.packageName ? { ...entry, selected: event.target.checked } : entry))} /><span><strong>{app.label}</strong><code>{app.packageName}</code></span></label>)}</div></>}<p className="privacy-line">Hidden lock-screen content stays hidden; NeedleBub receives only what Android exposes to its listener.</p></section>
}
