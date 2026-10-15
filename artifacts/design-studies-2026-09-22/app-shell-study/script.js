// Isolated browser study. Device state is an illustrative sample; theme is in memory only.
const app = document.getElementById('app');
const announcer = document.getElementById('announcer');
const params = new URL(location.href).searchParams;
let view = params.get('view') === 'settings' ? 'settings' : 'overview';
let theme = params.get('theme') === 'dark' ? 'dark' : 'light';
const arrow = '<svg class="arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6"/></svg>';
const chevron = '<svg class="chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m7 4 6 6-6 6"/></svg>';
const shield = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 2 16 5v4c0 4-2.7 6.9-6 9-3.3-2.1-6-5-6-9V5l6-3Z"/><path d="m7 10 2 2 4-4"/></svg>';
function navIcon(name) {
  if (name === 'overview') return '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M2.5 8 10 2.5 17.5 8v9h-15V8Z"/><path d="M7 17v-6h6v6"/></svg>';
  return '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M2.5 5h15M2.5 10h15M2.5 15h15"/><circle cx="7" cy="5" r="1.5" fill="var(--paper)"/><circle cx="13" cy="10" r="1.5" fill="var(--paper)"/><circle cx="8" cy="15" r="1.5" fill="var(--paper)"/></svg>';
}
function header() { return '<header class="brand"><div class="brand-name"><img class="brand-mark" src="../asset-studies/reference/needlebub-mark.svg" alt=""><span>NeedleBub</span></div><span class="prototype">Prototype · sample data</span></header>'; }
function navigation() { return `<nav class="bottom-nav" aria-label="Main navigation"><a href="?view=overview&theme=${theme}" data-view="overview" ${view === 'overview' ? 'aria-current="page"' : ''}>${navIcon('overview')}Overview</a><a href="?view=settings&theme=${theme}" data-view="settings" ${view === 'settings' ? 'aria-current="page"' : ''}>${navIcon('settings')}Settings</a></nav>`; }
function overview() { return `<main class="content overview-content"><h1 tabindex="-1">Your models<span class="dot">.</span></h1>
  <section aria-labelledby="built-in-title"><div class="section-head"><h2 id="built-in-title">Built-in</h2><span>1 model</span></div><div class="tool-list"><article class="tool"><div class="tool-top"><img class="tool-face" src="../asset-studies/open/svg/face-ready-32.svg" alt=""><div class="tool-main"><h3 class="tool-title">One-time codes</h3><p class="tool-role">Find codes in chosen notifications</p></div><span class="tool-state">Ready</span></div><p class="tool-meta">OTP Extractor · verified official model</p><a class="tool-action" href="../slim-a-flow/index.html?view=home&theme=${theme}"><span>Open one-time codes</span>${arrow}</a></article></div></section>
  <section class="external" aria-labelledby="external-title"><div class="section-head"><h2 id="external-title">Imported models</h2></div><div class="external-empty"><strong>No imported models in this preview</strong><p>Imported models serve other apps; they do not add screens here.</p></div></section>
  <p class="local-note">${shield}Runs locally on your phone.</p></main>`; }
function settings() { return `<main class="content settings-content"><h1 tabindex="-1">Settings<span class="dot">.</span></h1>
  <section class="settings-group" aria-labelledby="appearance-title"><h2 id="appearance-title">Appearance preview</h2><div class="theme-options" role="group" aria-label="Preview appearance"><button data-theme="light" aria-pressed="${theme === 'light'}">Light</button><button data-theme="dark" aria-pressed="${theme === 'dark'}">Dark</button></div></section>
  <section class="settings-group" aria-labelledby="access-title"><h2 id="access-title">Access</h2><div class="setting-row"><div><strong>Notification access</strong><small>Needed to watch chosen sources</small></div><span class="setting-value">Allowed in sample</span></div><div class="setting-row"><div><strong>Result notifications</strong><small>Private code delivery</small></div><span class="setting-value">Allowed in sample</span></div><div class="setting-row"><div><strong>Notification sources</strong><small>Manage inside One-time codes</small></div><a href="../slim-a-flow/index.html?view=sources&theme=${theme}">Open sources</a></div></section>
  <section class="settings-group" aria-labelledby="privacy-title"><h2 id="privacy-title">Privacy and data</h2><details><summary><span>Normal operation</span>${chevron}</summary><div class="detail-copy"><p>Notification text and extracted codes stay in memory during normal use and are not transmitted.</p><p>There is no normal-user code history.</p></div></details><details><summary><span>Activity summaries</span>${chevron}</summary><div class="detail-copy"><p>Seven local-day buckets keep content-free counts, timing totals, and last activity time. They do not keep app identity or message content.</p><p>The Android app can reset these summaries. This study does not alter phone data.</p></div></details></section>
  <section class="settings-group" aria-labelledby="about-title"><h2 id="about-title">About</h2><div class="setting-row"><div><strong>Version</strong></div><span class="setting-value version">0.1.0-beta.3</span></div><details class="advanced"><summary><span>Advanced model and runtime options</span>${chevron}</summary><div class="detail-copy"><ul><li><strong>Downloads</strong>Automatic catalogue updates and network policy.</li><li><strong>Models and imports</strong>Manage unverified external packs.</li><li><strong>Runtime</strong>On-device inference and a five-second idle release.</li><li><strong>Integrations</strong>MacroDroid and the inference gateway.</li></ul><p>These are existing Android settings sections. They are described here but do not change native settings in this study.</p></div></details></section>
  </main>`; }
function render() {
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]').content = theme === 'dark' ? '#292d2b' : '#f5f0e5';
  document.title = `NeedleBub — ${view === 'overview' ? 'Overview' : 'Settings'} study`;
  app.innerHTML = `<div class="pocket">${header()}${view === 'overview' ? overview() : settings()}${navigation()}</div>`;
}
function applyTheme(next, notifyParent = false) {
  if (next !== 'light' && next !== 'dark') return;
  theme = next;
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]').content = theme === 'dark' ? '#292d2b' : '#f5f0e5';
  app.querySelectorAll('[data-theme]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.theme === theme)));
  app.querySelectorAll('a[href]').forEach(link => { const url = new URL(link.href, location.href); url.searchParams.set('theme', theme); link.href = url.href; });
  const url = new URL(location.href);
  url.searchParams.set('theme', theme);
  history.replaceState(history.state, '', url);
  if (notifyParent && window.parent !== window) window.parent.postMessage({ type: 'study-theme', theme }, location.origin);
}
function show(next, push = true) {
  if (next !== 'overview' && next !== 'settings') return;
  view = next;
  const url = new URL(location.href);
  url.searchParams.set('view', view);
  url.searchParams.set('theme', theme);
  if (push) history.pushState({view}, '', url); else history.replaceState({view}, '', url);
  render();
  window.scrollTo(0, 0);
  app.querySelector('h1')?.focus({preventScroll:true});
}
document.addEventListener('click', event => {
  const link = event.target.closest('[data-view]');
  if (link) { event.preventDefault(); show(link.dataset.view); return; }
  const choice = event.target.closest('[data-theme]');
  if (choice) { applyTheme(choice.dataset.theme, true); choice.focus(); announcer.textContent = `${theme} preview selected; no setting saved.`; }
});
window.addEventListener('popstate', () => { const next = new URL(location.href).searchParams.get('view'); view = next === 'settings' ? 'settings' : 'overview'; render(); app.querySelector('h1')?.focus({preventScroll:true}); });
window.addEventListener('message', event => {
  if (event.origin !== location.origin || event.source !== window.parent || event.data?.type !== 'study-theme') return;
  applyTheme(event.data.theme);
});
show(view, false);
