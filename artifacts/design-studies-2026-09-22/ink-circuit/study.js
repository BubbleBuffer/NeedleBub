(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const shell = document.querySelector('.console');
  const sheet = $('demo-sheet');
  let access = 'ready';
  let paused = false;
  let opener = null;
  const expressions = {
    ready: '<path d="M16 34 27 23 38 34M62 34 73 23 84 34M39 49 50 57 61 49"/>',
    attention: '<path d="M22 22v15M76 22v15M39 55h22"/>',
    paused: '<path d="M16 33h22M62 33h22M41 53h18"/>'
  };
  function render() {
    const state = access === 'attention' ? 'attention' : paused ? 'paused' : 'ready';
    shell.dataset.state = state;
    $('expression-lines').innerHTML = expressions[state];
    $('status-title').textContent = state === 'attention' ? 'Access is off.' : state === 'paused' ? 'Taking a break.' : 'On. Ready.';
    $('status-note').textContent = state === 'attention' ? 'Reconnect notifications' : state === 'paused' ? 'One-time codes paused' : 'Watching 2 sources';
    $('pause-label').textContent = paused ? 'Resume' : 'Pause';
    $('pause-button').setAttribute('aria-label', paused ? 'Resume one-time codes' : 'Pause one-time codes');
    $('pause-button').disabled = state === 'attention';
    $('pause-icon').innerHTML = paused ? '<path d="m8 5 11 7-11 7V5Z"/>' : '<path d="M8 5v14M16 5v14"/>';
    $('primary-label').textContent = state === 'attention' ? 'Reconnect access' : 'View activity';
    $('context-note').textContent = state === 'attention' ? 'Notification access is off. Reconnect to find new codes.' : state === 'paused' ? 'New codes are paused. Resume whenever you’re ready.' : 'Your codes pass through. They don’t stay here.';
  }
  function showSheet(title, content) {
    opener = document.activeElement;
    $('sheet-heading').textContent = title;
    $('sheet-content').innerHTML = content;
    sheet.showModal();
  }
  $('close-sheet').addEventListener('click', () => sheet.close());
  sheet.addEventListener('click', (event) => { if (event.target === sheet) { const r=sheet.getBoundingClientRect(); if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom) sheet.close(); } });
  sheet.addEventListener('close', () => opener?.focus());
  $('pause-button').addEventListener('click', () => { paused = !paused; render(); });
  $('sources-button').addEventListener('click', () => showSheet('Your sources', '<p>Two selected notification sources.</p><div class="app-row"><span>Messages</span><span>Selected</span></div><div class="app-row"><span>Mail</span><span>Selected</span></div><p class="demo-note">Demo sources for this prototype. No notification access is being used.</p>'));
  $('primary-button').addEventListener('click', () => {
    if (access === 'attention') {
      showSheet('Reconnect access', '<p>In the app, allow NeedleBub to read notifications in Android settings, then return here.</p><p class="demo-note">This study cannot open settings or change permissions. You can preview the connected state below.</p><button class="sheet-action" id="preview-ready" type="button">Preview connected state</button>');
      $('preview-ready').addEventListener('click', () => { access = 'ready'; paused = false; render(); sheet.close(); });
    } else {
      showSheet('Today’s activity', '<p class="activity-summary"><strong>3</strong> codes handled today</p><p>Latest activity: 2 minutes ago.</p><p>Codes and notification contents are never saved here.</p><p class="demo-note">Illustrative activity for this prototype. No real notifications were read or processed.</p>');
    }
  });
  window.addEventListener('message', (event) => {
    if (event.origin !== window.location.origin || event.source !== window.parent) return;
    const data = event.data;
    if (!data || data.type !== 'study-state' || !['ready','attention'].includes(data.state)) return;
    if (sheet.open) sheet.close();
    access = data.state;
    paused = false;
    render();
  });
  render();
})();
