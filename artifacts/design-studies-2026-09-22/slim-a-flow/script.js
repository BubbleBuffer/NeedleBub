// Isolated design study: every value below is illustrative, in memory, and local to this page.
const allowedViews = ['home', 'sources', 'activity'];
const allowedStates = ['ready', 'paused', 'attention', 'empty'];
const allowedTreatments = ['open', 'visor'];
const allowedThemes = ['light', 'dark'];
const demoApps = [
  { id: 'messages', name: 'Messages' },
  { id: 'mail', name: 'Mail' },
  { id: 'authenticator', name: 'Authenticator' },
  { id: 'chat', name: 'Chat' },
  { id: 'calendar', name: 'Calendar' },
];
const demoActivity = { todayCodes: 3, sevenDayCodes: 12, sevenDayFiltered: 4, latest: '2 min ago' };
const $app = document.getElementById('app');
const $announcer = document.getElementById('announcer');
const initial = new URL(location.href);
const valid = (name, choices, fallback) => choices.includes(initial.searchParams.get(name)) ? initial.searchParams.get(name) : fallback;
let theme = valid('theme', allowedThemes, 'light');
let model = {
  view: valid('view', allowedViews, 'home'),
  state: valid('state', allowedStates, 'ready'),
  treatment: valid('treatment', allowedTreatments, 'open'),
  policy: 'only',
  allow: valid('state', allowedStates, 'ready') === 'empty' ? [] : ['messages', 'mail'],
  exclude: [],
  sampleHasActivity: valid('state', allowedStates, 'ready') !== 'empty',
};
let draft = { policy: model.policy, allow: [...model.allow], exclude: [...model.exclude] };
let query = '';
let recoveryOpen = false;
let returnToHome = false;
function selectedIds(config) { return config.policy === 'only' ? config.allow : config.exclude; }
function effectiveCount(config) { return config.policy === 'only' ? config.allow.length : demoApps.length - config.exclude.length; }
function resetDraft() { draft = { policy: model.policy, allow: [...model.allow], exclude: [...model.exclude] }; query = ''; }

function urlFor(next) {
  const url = new URL(location.href);
  url.searchParams.set('view', next.view);
  url.searchParams.set('state', next.state);
  url.searchParams.set('treatment', next.treatment);
  url.searchParams.set('theme', theme);
  return url.pathname + url.search + url.hash;
}
function snapshot() { return { ...model, allow: [...model.allow], exclude: [...model.exclude], study: true }; }
function commit(replace = false, fromHome = false) {
  const method = replace ? 'replaceState' : 'pushState';
  history[method]({ ...snapshot(), fromHome }, '', urlFor(model));
  render();
}
history.replaceState({ ...snapshot(), fromHome: false }, '', urlFor(model));
window.addEventListener('popstate', event => {
  if (event.state?.study) {
    model = { view: event.state.view, state: event.state.state, treatment: event.state.treatment, policy: event.state.policy, allow: [...event.state.allow], exclude: [...event.state.exclude], sampleHasActivity: event.state.sampleHasActivity };
  } else {
    const url = new URL(location.href);
    model.view = allowedViews.includes(url.searchParams.get('view')) ? url.searchParams.get('view') : 'home';
    model.state = allowedStates.includes(url.searchParams.get('state')) ? url.searchParams.get('state') : 'ready';
    model.sampleHasActivity = model.state !== 'empty';
  }
  resetDraft(); recoveryOpen = false;
  returnToHome = !!event.state?.fromHome;
  render();
  focusHeading();
});
function focusHeading() { $app.querySelector('h1')?.focus({ preventScroll: true }); }
function navigate(view) {
  const fromHome = model.view === 'home';
  model.view = view;
  if (view === 'sources') resetDraft();
  recoveryOpen = false;
  returnToHome = fromHome;
  commit(false, fromHome);
  scrollTo(0, 0);
  focusHeading();
}
function back() {
  if (returnToHome && history.length > 1) history.back();
  else navigate('home');
}
const icon = (name, size = 20) => `<img src="../asset-studies/${model.treatment}/svg/${name}.svg" width="${size}" height="${size}" alt="" aria-hidden="true">`;
const arrow = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>';
const backArrow = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 12H5m6-6-6 6 6 6"/></svg>';
function brand() {
  if (model.view !== 'home') return `<header class="brand detail-brand"><button class="back" data-action="back">${backArrow}Back</button><div class="brand-name">NeedleBub</div></header>`;
  return `<header class="brand home-brand"><div class="brand-name"><img src="../asset-studies/reference/needlebub-mark.svg" alt="" width="30" height="30"><span>NeedleBub</span></div><a class="all-models" href="../app-shell-study/index.html?theme=${theme}">All models ${arrow}</a></header>`;
}
function countLabel(count) { return `${count} ${count === 1 ? 'source' : 'sources'}`; }
function data() { return model.sampleHasActivity ? demoActivity : { todayCodes: 0, sevenDayCodes: 0, sevenDayFiltered: 0, latest: 'None yet' }; }

function home() {
  const sourceCount = effectiveCount(model);
  const state = model.state;
  const info = {
    ready: ['Tool is on', 'Ready for codes.', model.policy === 'only' ? 'Watching selected notifications.' : 'Watching all except excluded apps.'],
    paused: ['Tool is paused', 'Taking a break.', 'Code processing is paused.'],
    attention: ['Access is off', 'A hand, please.', 'Reconnect notification access.'],
    empty: ['No sources enabled', 'Choose sources.', model.policy === 'only' ? 'Select apps to start watching.' : 'Clear an exclusion to start watching.'],
  }[state];
  const expression = state === 'attention' ? 'attention' : state === 'paused' ? 'paused' : 'ready';
  const action = state === 'attention' ? 'Reconnect access' : state === 'empty' ? 'Choose sources' : 'View activity';
  const totalToday = data().todayCodes;
  return `<section class="home"><h1 tabindex="-1">One-time codes<span class="title-dot">.</span></h1>
    <section class="console" aria-label="One-time code controls">
      <div class="display ${state}"><div class="display-top"><span class="switch-label"><span class="status-dot"></span>${info[0]}</span><span class="source-count">${countLabel(sourceCount)}</span></div>
        <div class="status-group"><img class="face" src="../asset-studies/${model.treatment}/svg/face-${expression}-96.svg" width="96" height="48" alt=""><div class="status-copy" aria-live="polite"><h2>${info[1]}</h2><p>${info[2]}</p></div></div>
      </div>
      <div class="control-deck"><div class="recent"><div class="recent-caption"><span>Today</span><time>Latest: ${data().latest}</time></div><div class="activity-summary"><strong>${totalToday}</strong><span>${totalToday === 1 ? 'code' : 'codes'} handled</span></div></div>
        <button class="primary" data-action="primary"><span>${action}</span>${arrow}</button>
        <div class="utility-controls"><button data-action="sources">${icon('sources', 18)}<span>Sources <span class="count">${sourceCount}</span></span></button><button data-action="pause" aria-pressed="${state === 'paused'}" ${state === 'attention' || state === 'empty' ? 'disabled' : ''}>${icon(state === 'paused' ? 'resume' : 'pause', 18)}<span>${state === 'paused' ? 'Resume' : 'Pause'}</span></button></div>
      </div>
    </section>
    ${state === 'attention' && recoveryOpen ? `<section class="recovery" aria-label="Restore access"><h2 tabindex="-1">Notification access is off</h2><p>Your source policy is still saved. In the Android app, allow NeedleBub notification access to resume processing.</p><button class="primary" data-action="restore">Preview access restored</button><small>Study interaction only. No Android settings are opened.</small></section>` : ''}
    <p class="study-note">Prototype · sample data</p>
  </section>`;
}
function sources() {
  const matching = demoApps.filter(app => app.name.toLowerCase().includes(query.toLowerCase()));
  const checked = selectedIds(draft);
  const isOnly = draft.policy === 'only';
  const dirty = draft.policy !== model.policy || demoApps.some(app => draft.allow.includes(app.id) !== model.allow.includes(app.id) || draft.exclude.includes(app.id) !== model.exclude.includes(app.id));
  const count = effectiveCount(draft);
  const scopeCopy = isOnly
    ? count ? `Only the ${count} checked demo ${count === 1 ? 'app is' : 'apps are'} allowed.` : 'No apps are allowed. No sources will be watched.'
    : checked.length ? `${checked.length} demo ${checked.length === 1 ? 'app is' : 'apps are'} excluded.` : 'No apps are excluded. All demo apps are allowed.';
  return `<section class="detail detail-sources"><h1 tabindex="-1">Notification sources<span class="title-dot">.</span></h1>
    <p class="lead">Choose which apps can supply one-time code notifications.</p>
    <fieldset class="policy-choices"><legend>Source policy</legend>
      <label class="policy-option"><input type="radio" name="policy" value="only" data-policy ${isOnly ? 'checked' : ''}><span><strong>Only these apps</strong><small>Watch checked apps only.</small></span></label>
      <label class="policy-option"><input type="radio" name="policy" value="except" data-policy ${isOnly ? '' : 'checked'}><span><strong>All except these apps</strong><small>Watch every app except checked ones.</small></span></label>
    </fieldset>
    <div class="effective-summary"><strong>${count} of ${demoApps.length} demo apps allowed</strong><span>${scopeCopy}</span></div>
    ${isOnly ? '' : '<p class="proposal-note">Proposed mode: the current Android app does not support an exclusion list.</p>'}
    <label class="search-label" for="source-search">Find an app</label><input class="search" id="source-search" type="search" placeholder="Search demo apps" value="${escapeHtml(query)}" autocomplete="off">
    <div class="list-heading"><h2>${isOnly ? 'Allowed apps' : 'Excluded apps'}</h2><span>${matching.length} shown</span></div>
    <div class="bulk-actions"><button class="secondary" data-bulk="all" ${!matching.length ? 'disabled' : ''}>${isOnly ? 'Allow all shown' : 'Exclude all shown'}</button><button class="secondary" data-bulk="clear" ${!matching.length ? 'disabled' : ''}>Clear shown</button></div>
    <div class="source-list" role="group" aria-label="${isOnly ? 'Allowed apps' : 'Excluded apps'}">${matching.length ? matching.map(app => {
      const isChecked = checked.includes(app.id);
      const outcome = isOnly ? (isChecked ? 'Allowed' : 'Not allowed') : (isChecked ? 'Excluded' : 'Allowed');
      return `<label class="source-row"><input type="checkbox" data-source="${app.id}" aria-label="${app.name}: ${outcome}" ${isChecked ? 'checked' : ''}><span>${app.name}</span><small>${outcome}</small></label>`;
    }).join('') : `<div class="empty-search">No demo apps match “${escapeHtml(query)}”. Your other choices are unchanged.</div>`}</div>
    <p class="data-note">Prototype · sample apps. Save changes this preview only.</p>
    <div class="form-actions"><button class="secondary" data-action="cancel">Cancel</button><button class="primary" data-action="save" ${dirty ? '' : 'disabled'}>Save policy</button></div>
  </section>`;
}
function escapeHtml(value) { return value.replace(/[&<>"']/g, character => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[character]); }
function activity() {
  const report = data();
  return `<section class="detail detail-activity"><h1 tabindex="-1">Activity<span class="title-dot">.</span></h1>
    ${model.sampleHasActivity ? `<div class="activity-total"><span class="activity-total__count"><strong>${report.sevenDayCodes}</strong> codes handled</span><span class="period">Past 7 days</span></div>
      <div class="metric-pair"><div><strong>${report.todayCodes}</strong><span>Today</span></div><div><strong>${report.sevenDayFiltered}</strong><span>Filtered in 7 days</span></div></div>
      <div class="last-activity"><span>Last activity</span><time>${report.latest}</time></div>`
      : `<div class="activity-empty"><h2>No activity yet</h2><p>${effectiveCount(model) ? 'No code activity has been recorded in this preview yet.' : 'No notification sources are enabled. Choose apps to start watching.'}</p>${effectiveCount(model) ? '' : `<button class="primary" data-action="sources">Choose sources ${arrow}</button>`}</div>`}
    <p class="study-note">Prototype · sample data</p>
  </section>`;
}
function render() {
  document.documentElement.dataset.theme = theme;
  document.title = `NeedleBub — ${model.view === 'home' ? 'One-time codes' : model.view === 'sources' ? 'Sources' : 'Activity'} prototype`;
  $app.innerHTML = brand() + (model.view === 'sources' ? sources() : model.view === 'activity' ? activity() : home());
}
function applyTheme(next) {
  if (!allowedThemes.includes(next) || next === theme) return;
  theme = next;
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]').content = theme === 'dark' ? '#292d2b' : '#f5f0e5';
  const url = new URL(location.href);
  url.searchParams.set('theme', theme);
  history.replaceState(history.state, '', url);
  const allModels = $app.querySelector('.all-models');
  if (allModels) allModels.href = `../app-shell-study/index.html?theme=${theme}`;
}

$app.addEventListener('click', event => {
  const bulkButton = event.target.closest('[data-bulk]');
  if (bulkButton) {
    const visible = demoApps.filter(app => app.name.toLowerCase().includes(query.toLowerCase())).map(app => app.id);
    const key = draft.policy === 'only' ? 'allow' : 'exclude';
    draft[key] = bulkButton.dataset.bulk === 'all'
      ? [...new Set([...draft[key], ...visible])]
      : draft[key].filter(id => !visible.includes(id));
    render();
    $app.querySelector(`[data-bulk="${bulkButton.dataset.bulk}"]`)?.focus();
    $announcer.textContent = `${visible.length} shown demo ${visible.length === 1 ? 'app' : 'apps'} ${bulkButton.dataset.bulk === 'all' ? (key === 'allow' ? 'allowed' : 'excluded') : 'cleared'} in the draft.`;
    return;
  }
  const button = event.target.closest('[data-action]');
  if (!button) return;
  switch (button.dataset.action) {
    case 'primary': if (model.state === 'attention') { recoveryOpen = true; render(); $app.querySelector('.recovery h2')?.focus(); } else navigate(model.state === 'empty' ? 'sources' : 'activity'); break;
    case 'sources': navigate('sources'); break;
    case 'pause': model.state = model.state === 'paused' ? (effectiveCount(model) ? 'ready' : 'empty') : 'paused'; commit(); (model.state === 'empty' ? $app.querySelector('h1') : $app.querySelector('[data-action="pause"]'))?.focus(); $announcer.textContent = model.state === 'paused' ? 'Code processing paused.' : model.state === 'empty' ? 'No sources enabled. Choose sources to start watching.' : 'Tool on. Watching configured notifications.'; break;
    case 'restore': model.state = effectiveCount(model) ? 'ready' : 'empty'; recoveryOpen = false; commit(); $app.querySelector('.status-copy h2')?.setAttribute('tabindex', '-1'); $app.querySelector('.status-copy h2')?.focus(); $announcer.textContent = model.state === 'ready' ? 'Preview access restored.' : 'No sources enabled.'; break;
    case 'back': case 'cancel': back(); break;
    case 'save': {
      if (button.disabled) return;
      model.policy = draft.policy;
      model.allow = [...draft.allow];
      model.exclude = [...draft.exclude];
      if (!effectiveCount(model) && (model.state === 'ready' || model.state === 'empty')) model.state = 'empty';
      else if (model.state === 'empty') model.state = 'ready';
      // Keep the saved selection on the prior Sources history entry too.
      history.replaceState({ ...snapshot(), fromHome: returnToHome }, '', urlFor(model));
      navigate('home');
      $announcer.textContent = `${countLabel(effectiveCount(model))} enabled in this preview.`;
      break;
    }
  }
});
$app.addEventListener('change', event => {
  const policyInput = event.target.closest('[data-policy]');
  if (policyInput) {
    draft.policy = policyInput.value;
    render();
    const focusPolicy = draft.policy;
    // Let the original label click finish before focusing its replacement radio.
    setTimeout(() => $app.querySelector(`[data-policy][value="${focusPolicy}"]`)?.focus(), 0);
    $announcer.textContent = `${draft.policy === 'only' ? 'Only these apps' : 'All except these apps'} draft policy selected. ${effectiveCount(draft)} demo sources enabled after Save.`;
    return;
  }
  const input = event.target.closest('[data-source]');
  if (!input) return;
  const key = draft.policy === 'only' ? 'allow' : 'exclude';
  draft[key] = input.checked ? [...draft[key], input.dataset.source] : draft[key].filter(id => id !== input.dataset.source);
  render();
  $app.querySelector(`[data-source="${input.dataset.source}"]`)?.focus();
});
$app.addEventListener('input', event => {
  if (event.target.id !== 'source-search') return;
  const cursor = event.target.selectionStart;
  query = event.target.value;
  render();
  const input = document.getElementById('source-search');
  input.focus(); input.setSelectionRange(cursor, cursor);
});
window.addEventListener('message', event => {
  if (event.origin !== location.origin || event.source !== parent) return;
  if (event.data?.type === 'study-theme') { applyTheme(event.data.theme); return; }
  if (event.data?.type !== 'study-state' || !allowedStates.includes(event.data.state)) return;
  model.state = event.data.state;
  model.policy = 'only';
  model.allow = model.state === 'empty' ? [] : ['messages', 'mail'];
  model.exclude = [];
  model.sampleHasActivity = model.state !== 'empty';
  model.view = 'home'; recoveryOpen = false;
  commit(true);
});
render();
