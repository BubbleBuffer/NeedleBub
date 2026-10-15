import { useState } from 'react'
import { Bell, ChevronRight, ClipboardCopy, Package, Search } from 'lucide-react'
import {
  needleBub, type AppStatus, type CatalogueEntry, type FeatureActivitySummary,
  type NotificationApp, type PackInfo, type PackUpdateStatus,
} from './native'

type Route = 'feature-otp' | 'feature-otp-sources' | 'feature-otp-activity' | 'settings-models'
const go = (route: Route) => { window.location.hash = route === 'settings-models' ? '#/settings/models' : `#/features/otp${route === 'feature-otp' ? '' : route === 'feature-otp-sources' ? '/sources' : '/activity'}` }
const officialId = 'de.x0bubbuff.needlebub.otp'
const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`
const excludedCount = (status: AppStatus) => status.excludedAppCount ?? 0
const sourceLabel = (status: AppStatus) => status.allApps
  ? excludedCount(status) ? `All except ${plural(excludedCount(status), 'app')}` : 'All apps'
  : plural(status.selectedAppCount, 'app')
const hasActivity = (activity: FeatureActivitySummary) => activity.lastActivityAt !== null ||
  activity.totalOtp + activity.totalRejected + activity.totalSkipped + activity.totalBackpressure + activity.totalErrors + activity.totalSuppressed + activity.totalNotRun > 0
const lastActivity = (timestamp: number | null) => {
  if (timestamp === null) return 'None yet'
  const elapsed = Math.max(0, Date.now() - timestamp)
  if (elapsed < 60_000) return 'Just now'
  if (elapsed < 60 * 60_000) return `${Math.floor(elapsed / 60_000)} min ago`
  return new Date(timestamp).toLocaleString()
}
const icon = (name: string) => <span className={`da-icon da-icon--${name}`} aria-hidden="true" />

export type CommonViewProps = {
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

export function OverviewView({ status, packs, activity, busy, notice }: CommonViewProps) {
  const official = packs.find(pack => pack.id === officialId && pack.active)
  const imported = packs.filter(pack => pack.id !== officialId)
  const feature = featureState(status)
  const state = feature === 'ready' ? 'Ready' : feature === 'paused' ? 'Paused' : feature === 'empty' ? 'No sources' : 'Setup needed'
  return <main className="da-screen da-overview">
    <h1>Your models<span className="da-dot">.</span></h1>
    <section aria-labelledby="da-built-in-title">
      <div className="da-section-heading"><h2 id="da-built-in-title">Built-in</h2><span>1 model</span></div>
      <article className="da-model-row">
        <div className="da-model-top"><img className="da-small-face" src={`/design-a/face-${feature === 'empty' ? 'attention' : feature}-32.svg`} alt="" /><div className="da-model-copy"><h3>One-time codes</h3><p>Find codes in chosen notifications</p></div><span className="da-state-tag">{state}</span></div>
        <p className="da-model-meta">{official ? `${official.name} · verified official model` : 'OTP Extractor · model needed'}{hasActivity(activity) ? ` · ${plural(activity.todayOtp, 'code')} today` : ''}</p>
        <button className="da-primary" onClick={() => go('feature-otp')} disabled={busy !== null}><span>Open one-time codes</span><ChevronRight aria-hidden="true" /></button>
      </article>
    </section>
    <section className="da-imported" aria-labelledby="da-imported-title"><div className="da-section-heading"><h2 id="da-imported-title">Imported models</h2></div>
      {imported.length ? <div className="da-import-list">{imported.map(pack => <div className="da-import-row" key={`${pack.id}@${pack.version}`}><span><strong>{pack.name}</strong><small>{pack.version} · {pack.verified ? 'Verified' : 'Unverified'}</small></span></div>)}</div> : <p className="da-empty-line">No imported models.</p>}
      <button className="da-text-link" onClick={() => go('settings-models')}>Manage models <ChevronRight aria-hidden="true" /></button>
    </section>
    {notice && <p className="inline-notice" role="status">{notice}</p>}
  </main>
}

function featureState(status: AppStatus): 'ready' | 'paused' | 'attention' | 'empty' {
  if (!status.otpPackInstalled || !status.notificationAccess || !status.notificationPermission) return 'attention'
  if (!status.automaticOtpConfigured) return 'empty'
  if (!status.automaticOtpEnabled) return 'paused'
  return 'ready'
}

export function OtpFeatureView({ status, packs, catalogue, update, activity, busy, notice, run, refresh }: CommonViewProps) {
  const pack = packs.find(value => value.id === officialId && value.active)
  const entry = catalogue.find(value => value.id === officialId)
  const state = featureState(status)
  const sourceText = sourceLabel(status)
  const stateCopy = {
    ready: ['Tool is on', 'Ready for codes.', status.allApps ? 'Watching allowed notifications.' : 'Watching selected notifications.'],
    paused: ['Tool is paused', 'Taking a break.', 'Code processing is paused.'],
    attention: ['Setup needed', 'A hand, please.', !status.otpPackInstalled ? 'Download the OTP model.' : !status.notificationAccess ? 'Reconnect notification access.' : 'Allow private result notifications.'],
    empty: ['No sources enabled', 'Choose sources.', 'Choose apps to start watching.'],
  }[state]
  const setupAction = () => {
    if (!status.otpPackInstalled) void run('install', () => entry ? needleBub.installCataloguePack({ id: entry.id }) : needleBub.checkForPackUpdates({ allowMetered: true }), entry ? 'Model download requested.' : 'Model check complete.')
    else if (!status.notificationAccess) void run('access', () => needleBub.openNotificationAccess())
    else if (!status.notificationPermission) void run('permission', () => needleBub.requestNotificationPermission())
    else go('feature-otp-sources')
  }
  const primaryLabel = state === 'attention' ? !status.otpPackInstalled ? entry ? 'Download model' : 'Find model' : !status.notificationAccess ? 'Reconnect access' : 'Allow notifications' : state === 'empty' ? 'Choose sources' : 'View activity'
  const primaryAction = () => state === 'attention' ? setupAction() : go(state === 'empty' ? 'feature-otp-sources' : 'feature-otp-activity')
  const modelAction = async () => {
    if (!pack && entry) await run('model', () => needleBub.installCataloguePack({ id: entry.id }), 'Model download requested.')
    else await run('model', () => needleBub.checkForPackUpdates({ allowMetered: true }), 'Model check complete.')
  }
  return <main className="da-screen da-otp">
    <h1>One-time codes<span className="da-dot">.</span></h1>
    <section className="da-console" aria-label="One-time code controls">
      <div className={`da-display da-display--${state}`}>
        <div className="da-display-top"><span className="da-mode"><span className="da-mode-dot" />{stateCopy[0]}</span><span>{sourceText}</span></div>
        <div className="da-status-group"><img src={`/design-a/face-${state === 'empty' ? 'ready' : state}-96.svg`} alt="" width="96" height="48" /><div aria-live="polite"><h2>{stateCopy[1]}</h2><p>{stateCopy[2]}</p></div></div>
      </div>
      <div className="da-deck"><div className="da-recent"><div><span>Today</span><time>{`Latest: ${lastActivity(activity.lastActivityAt)}`}</time></div><strong>{plural(activity.todayOtp, 'code')} handled</strong></div>
        <button className="da-primary" disabled={busy !== null} onClick={primaryAction}><span>{busy === 'install' || busy === 'permission' || busy === 'access' ? 'Working…' : primaryLabel}</span><ChevronRight aria-hidden="true" /></button>
        <div className="da-utility"><button onClick={() => go('feature-otp-sources')}>{icon('sources')}<span>Sources</span></button><button disabled={busy !== null || state === 'attention' || state === 'empty'} onClick={() => void run('toggle-otp', () => needleBub.setAutomaticOtpEnabled({ enabled: state === 'paused' }))}>{icon(state === 'paused' ? 'resume' : 'pause')}<span>{state === 'paused' ? 'Resume' : 'Pause'}</span></button></div>
      </div>
    </section>
    {notice && <p className="inline-notice" role="status">{notice}</p>}
    {(state === 'attention' || state === 'empty') && hasActivity(activity) && <button className="da-text-link" onClick={() => go('feature-otp-activity')}>View past activity <ChevronRight aria-hidden="true" /></button>}
    <section className="da-feature-settings" aria-labelledby="da-feature-settings-title"><h2 id="da-feature-settings-title">Feature settings</h2>
      <div className="da-setting-row"><Package aria-hidden="true" /><span><strong>Model</strong><small>{pack ? `${pack.name} ${pack.version} · Verified` : 'OTP Extractor is not installed'}</small></span><button disabled={busy !== null} onClick={() => void modelAction()}>{busy === 'model' ? 'Working…' : !pack ? 'Download' : update.availableVersion ? 'Update' : 'Check'}</button></div>
      <button className="da-setting-row da-setting-button" onClick={() => go('feature-otp-sources')}>{icon('sources')}<span><strong>Notification sources</strong><small>{sourceText}</small></span><ChevronRight aria-hidden="true" /></button>
      <button className="da-setting-row da-setting-button" onClick={() => void run('notification-settings', () => needleBub.openNotificationSettings())}><Bell aria-hidden="true" /><span><strong>Result delivery</strong><small>{status.notificationPermission ? 'Private notifications allowed' : 'Permission needed'}</small></span><ChevronRight aria-hidden="true" /></button>
      <div className="da-setting-row"><ClipboardCopy aria-hidden="true" /><span><strong>Auto-copy codes</strong><small>Replace your clipboard with new codes while your phone is unlocked.</small></span><label className="da-switch"><input type="checkbox" role="switch" aria-label="Auto-copy codes" checked={status.autoCopyEnabled} disabled={busy !== null} onChange={event => void run('toggle-auto-copy', async () => { try { await needleBub.setAutoCopyEnabled({ enabled: event.target.checked }) } catch (error) { await refresh(); throw error } })} /><i aria-hidden="true" /></label></div>
    </section>
    {pack && <button className="da-danger" disabled={busy !== null} onClick={() => { if (window.confirm(`Remove ${pack.name} ${pack.version}? One-time codes will stop until it is reinstalled.`)) void run('remove-official', () => needleBub.removePack({ id: pack.id, version: pack.version }), `${pack.name} removed.`) }}>Remove {pack.name}</button>}
  </main>
}

export function ActivityView({ status, activity }: { status: AppStatus; activity: FeatureActivitySummary }) {
  const noSources = status.otpPackInstalled && status.notificationAccess && status.notificationPermission && !status.automaticOtpConfigured
  return <main className="da-screen da-activity"><h1>Activity<span className="da-dot">.</span></h1>
    {hasActivity(activity) ? <>
      <div className="da-activity-total"><span><strong>{activity.totalOtp}</strong> {activity.totalOtp === 1 ? 'code' : 'codes'} handled</span><span>Past 7 days</span></div>
      <div className="da-activity-pair"><div><strong>{activity.todayOtp}</strong><span>Today</span></div><div><strong>{activity.totalRejected}</strong><span>Filtered in 7 days</span></div></div>
      <div className="da-last"><span>Last activity</span><time>{lastActivity(activity.lastActivityAt)}</time></div>
      {activity.totalErrors > 0 && <p className="da-activity-error">{plural(activity.totalErrors, 'error')} in 7 days</p>}
    </> : <div className="da-activity-empty"><h2>No activity yet</h2><p>{noSources ? 'No notification sources are enabled. Choose apps to start watching.' : 'No code activity has been recorded yet.'}</p>{noSources && <button className="da-primary" onClick={() => go('feature-otp-sources')}>Choose sources <ChevronRight aria-hidden="true" /></button>}</div>}
  </main>
}

type SourceDraft = { mode: 'only' | 'except'; allow: string[]; exclude: string[] }
const draftFrom = (status: AppStatus, apps: NotificationApp[]): SourceDraft => ({
  mode: status.allApps ? 'except' : 'only',
  allow: apps.filter(app => app.selected).map(app => app.packageName),
  exclude: apps.filter(app => app.excluded).map(app => app.packageName),
})
const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every(value => b.includes(value))
export function SourcesView({ status, apps, loading, loadError, onRetry, onAppsChange, onRefresh }: {
  status: AppStatus; apps: NotificationApp[]; loading: boolean; loadError: string | null; onRetry: () => void; onAppsChange: (apps: NotificationApp[]) => void; onRefresh: () => Promise<void>
}) {
  const [draftOverride, setDraft] = useState<SourceDraft | null>(null)
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const base = draftFrom(status, apps)
  const draft = draftOverride ?? base
  const matching = apps.filter(app => `${app.label} ${app.packageName}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
  const activeList = draft.mode === 'only' ? draft.allow : draft.exclude
  const enabledCount = draft.mode === 'only' ? draft.allow.length : Math.max(0, apps.length - draft.exclude.length)
  const dirty = draft.mode !== base.mode || !sameSet(draft.allow, base.allow) || !sameSet(draft.exclude, base.exclude)
  const updateList = (next: string[]) => setDraft({ ...draft, [draft.mode === 'only' ? 'allow' : 'exclude']: next })
  const bulk = (select: boolean) => {
    const shown = matching.map(app => app.packageName)
    updateList(select ? [...new Set([...activeList, ...shown])] : activeList.filter(value => !shown.includes(value)))
  }
  const save = async () => {
    if (!dirty || saving || saved || loading || loadError) return
    setSaving(true); setError(null)
    try {
      await needleBub.saveNotificationApps({ allApps: draft.mode === 'except', packages: draft.allow, excludedPackages: draft.exclude })
    } catch {
      setError('Could not save this source policy. Your previous selection is still active.')
      setSaving(false)
      return
    }
    onAppsChange(apps.map(app => ({ ...app, selected: draft.allow.includes(app.packageName), excluded: draft.exclude.includes(app.packageName) })))
    try {
      await onRefresh()
      go('feature-otp')
    } catch {
      setSaved(true)
      setError('Source policy saved, but current status could not be refreshed. Try again.')
    }
    setSaving(false)
  }
  const retryRefresh = async () => {
    setSaving(true)
    try { await onRefresh(); go('feature-otp') }
    catch { setError('Source policy saved, but current status could not be refreshed. Try again.') }
    finally { setSaving(false) }
  }
  return <main className="da-screen da-sources"><h1>Notification sources<span className="da-dot">.</span></h1><p className="da-lead">Choose which apps can supply one-time code notifications.</p>
    {loading ? <p className="da-source-load" role="status">Loading installed apps…</p> : loadError ? <div className="da-source-load" role="alert"><p>Could not load installed apps. Your saved source policy has not changed.</p><button onClick={onRetry}>Try again</button></div> : <>
    <fieldset className="da-policy" disabled={saving || saved}><legend>Source policy</legend><label><input type="radio" name="source-mode" checked={draft.mode === 'only'} onChange={() => setDraft({ ...draft, mode: 'only' })} /><span><strong>Only these apps</strong><small>Watch checked apps only.</small></span></label><label><input type="radio" name="source-mode" checked={draft.mode === 'except'} onChange={() => setDraft({ ...draft, mode: 'except' })} /><span><strong>All except these apps</strong><small>Watch every app except checked ones.</small></span></label></fieldset>
    <div className="da-effective"><strong>{enabledCount} of {apps.length} {draft.mode === 'except' ? 'listed apps' : 'apps'} allowed</strong><span>{draft.mode === 'only' ? enabledCount ? `Only the ${plural(enabledCount, 'checked app')} ${enabledCount === 1 ? 'is' : 'are'} allowed.` : 'No apps are allowed. No sources will be watched.' : draft.exclude.length ? `${plural(draft.exclude.length, 'app')} excluded.` : 'No apps are excluded. All apps are allowed.'}</span></div>
    {error && <p className="inline-notice inline-notice--error" role="alert">{error}</p>}
    <label className="da-search-label" htmlFor="da-source-search">Find an app</label><div className="da-search"><Search aria-hidden="true" /><input id="da-source-search" type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="Search apps" /></div>
    <div className="da-list-heading"><h2>{draft.mode === 'only' ? 'Allowed apps' : 'Excluded apps'}</h2><span>{matching.length} shown</span></div>
    <div className="da-bulk"><button disabled={saving || saved || matching.length === 0} onClick={() => bulk(true)}>{draft.mode === 'only' ? 'Allow all shown' : 'Exclude all shown'}</button><button disabled={saving || saved || matching.length === 0} onClick={() => bulk(false)}>Clear shown</button></div>
    <div className="da-app-list" role="group" aria-label={draft.mode === 'only' ? 'Allowed apps' : 'Excluded apps'}>{matching.map(app => { const checked = activeList.includes(app.packageName); const outcome = draft.mode === 'only' ? checked ? 'Allowed' : 'Not allowed' : checked ? 'Excluded' : 'Allowed'; return <label key={app.packageName} className="da-app-row"><input type="checkbox" checked={checked} disabled={saving || saved} aria-label={`${app.label}: ${outcome}`} onChange={event => updateList(event.target.checked ? [...activeList, app.packageName] : activeList.filter(value => value !== app.packageName))} /><span>{app.label}</span><small>{outcome}</small></label> })}{matching.length === 0 && <p className="da-no-match">{apps.length === 0 ? 'No apps available.' : `No apps match “${query}”. Other choices are unchanged.`}</p>}</div>
    </>}
    <div className="da-source-actions"><button onClick={() => go('feature-otp')} disabled={saving}>{saved ? 'Back' : 'Cancel'}</button>{!loading && !loadError && <button onClick={() => void (saved ? retryRefresh() : save())} disabled={(!dirty && !saved) || saving}>{saving ? 'Working…' : saved ? 'Retry status' : 'Save policy'}</button>}</div>
  </main>
}
