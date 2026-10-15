const frames = [...document.querySelectorAll('iframe[data-view]')];
let theme = new URL(location.href).searchParams.get('theme') === 'dark' ? 'dark' : 'light';
function previewUrl(view) {
  const page = ['overview', 'settings'].includes(view) ? 'app-shell-study/index.html' : 'slim-a-flow/index.html';
  const state = document.querySelector(`[data-state-for="${view}"]`)?.value || 'ready';
  return page + '?' + new URLSearchParams({ view, treatment: 'open', state, theme, revision: 'distill-final-1' });
}
function updateLinks() {
  document.querySelectorAll('[data-full]').forEach(link => { link.href = previewUrl(link.dataset.full); });
  document.querySelector('.launch').href = previewUrl('overview');
}
function setTheme(next) {
  theme = next;
  document.documentElement.dataset.theme = theme;
  document.querySelectorAll('[data-theme]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.theme === theme)));
  frames.forEach(frame => frame.contentWindow.postMessage({ type: 'study-theme', theme }, location.origin));
  const url = new URL(location.href);
  url.searchParams.set('theme', theme);
  history.replaceState(null, '', url);
  updateLinks();
}
document.querySelectorAll('[data-theme]').forEach(button => button.addEventListener('click', () => setTheme(button.dataset.theme)));
document.querySelectorAll('[data-state-for]').forEach(select => select.addEventListener('change', () => {
  const frame = frames.find(item => item.dataset.view === select.dataset.stateFor);
  frame.src = previewUrl(frame.dataset.view);
  updateLinks();
}));
window.addEventListener('message', event => {
  if (event.origin !== location.origin || !frames.some(frame => frame.contentWindow === event.source)) return;
  if (event.data?.type === 'study-theme' && ['light', 'dark'].includes(event.data.theme)) setTheme(event.data.theme);
});
frames.forEach(frame => {
  frame.addEventListener('load', () => frame.contentWindow.postMessage({ type: 'study-theme', theme }, location.origin));
  frame.src = previewUrl(frame.dataset.view);
});
setTheme(theme);
