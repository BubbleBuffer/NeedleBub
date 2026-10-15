const $ = id => document.getElementById(id);
const initialState = new URLSearchParams(location.search).get('state');
let state = initialState === 'attention' ? 'attention' : 'ready';
let paused = initialState === 'paused';
function render() {
  const attention = state === 'attention';
  const expression = attention ? 'attention' : paused ? 'paused' : 'ready';
  $('display').className = 'display ' + expression;
  $('expression').src = '../svg/face-' + expression + '-96.svg';
  $('primary-glyph').src = '../svg/' + (attention ? 'reconnect' : 'activity') + '.svg';
  $('mode').textContent = attention ? 'Access is off' : paused ? 'Tool is paused' : 'Tool is on';
  $('status-title').textContent = attention ? 'A hand, please.' : paused ? 'Taking a break.' : 'Ready for codes.';
  $('status-description').textContent = attention ? 'Reconnect notification access.' : paused ? 'Code processing is paused.' : 'Watching selected notifications.';
  $('primary-label').textContent = attention ? 'Reconnect access' : 'View activity';
  $('pause-label').textContent = paused ? 'Resume' : 'Pause';
  $('pause').setAttribute('aria-pressed', String(paused));
  $('pause').disabled = attention;
  $('pause-glyph').src = '../svg/' + (paused ? 'resume' : 'pause') + '.svg';
}
function openSheet(title, body) {
  $('sheet-title').textContent = title;
  $('sheet-body').innerHTML = body;
  $('sheet').showModal();
}
$('close-sheet').addEventListener('click', () => $('sheet').close());
$('sheet').addEventListener('click', event => { if(event.target === $('sheet')) { const r = $('sheet').getBoundingClientRect(); if(event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) $('sheet').close(); } });
$('sources').addEventListener('click', () => openSheet('Your sources', '<p>One-time codes looks at these selected notification sources.</p><div class="app-row">Messages <span>Selected</span></div><div class="app-row">Mail <span>Selected</span></div><p class="sheet-note">Two demo apps. This prototype does not read notifications or change source settings.</p>'));
$('pause').addEventListener('click', () => { paused = !paused; render(); });
$('primary').addEventListener('click', () => {
  if(state === 'attention') {
    openSheet('Reconnect access', '<p>In the app, you would allow NeedleBub to read selected notifications in Android settings.</p><p class="sheet-note">This is a prototype. No device settings will open.</p><button class="primary sheet-action" id="demo-reconnect">Preview reconnected state</button>');
    $('demo-reconnect').addEventListener('click', () => {state = 'ready'; paused = false; render(); $('sheet').close();});
  } else {
    openSheet('Today’s activity', '<div class="activity-summary"><strong>3</strong><span>codes handled</span></div><p>Latest activity: 2 minutes ago.</p><div class="demo-message">Just a count and a time. No code, message content or source identity is saved here.</div><p class="sheet-note">Illustrative activity only. This prototype does not read notifications.</p>');
  }
});
window.addEventListener('message', event => {
  if(event.origin !== location.origin || event.source !== parent || !event.data || event.data.type !== 'study-state' || !['ready','paused','attention'].includes(event.data.state)) return;
  state = event.data.state === 'attention' ? 'attention' : 'ready'; paused = event.data.state === 'paused';
  if($('sheet').open) $('sheet').close();
  render();
});
render();
