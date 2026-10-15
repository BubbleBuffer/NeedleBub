import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';

const source = readFileSync(new URL('./script.js', import.meta.url), 'utf8');

function study(state = 'ready') {
  const listeners = {};
  const app = {
    innerHTML: '',
    addEventListener(name, handler) { listeners[name] = handler; },
    querySelector() { return { focus() {}, setAttribute() {} }; },
  };
  const announcer = { textContent: '' };
  const location = { href: `http://study.local/index.html?view=home&state=${state}`, origin: 'http://study.local' };
  const entries = [];
  let position = -1;
  const history = {
    get length() { return entries.length; },
    get state() { return entries[position]?.value ?? null; },
    pushState(value, _, path) { entries.splice(++position); entries.push({ value, path }); location.href = new URL(path, location.href).href; },
    replaceState(value, _, path) { if (position < 0) position = 0; entries[position] = { value, path }; location.href = new URL(path, location.href).href; },
    back() { if (position > 0) { position--; location.href = new URL(entries[position].path, location.href).href; windowListeners.popstate({ state: entries[position].value }); } },
  };
  const windowListeners = {};
  const context = {
    URL, Intl, Date, location, history, scrollTo() {}, parent: {}, setTimeout(handler) { handler(); },
    document: { getElementById(id) { return id === 'app' ? app : announcer; }, querySelector() { return { content: '' }; }, documentElement: { dataset: {} }, title: '' },
    window: { addEventListener(name, handler) { windowListeners[name] = handler; } },
  };
  createContext(context);
  runInContext(source, context);
  const run = code => runInContext(code, context);
  const target = (selector, value) => ({ closest(match) { return match === selector ? value : null; } });
  const clickAction = action => listeners.click({ target: target('[data-action]', { dataset: { action }, disabled: false }) });
  const clickBulk = bulk => listeners.click({ target: target('[data-bulk]', { dataset: { bulk } }) });
  const changePolicy = policy => listeners.change({ target: target('[data-policy]', { value: policy }) });
  const changeSource = (id, checked) => listeners.change({ target: target('[data-source]', { dataset: { source: id }, checked }) });
  return { run, app, clickAction, clickBulk, changePolicy, changeSource, message: payload => windowListeners.message({ origin: location.origin, source: context.parent, data: payload }) };
}

const a = study();
assert.equal(a.run('effectiveCount(model)'), 2);
a.run("navigate('sources')");
a.changePolicy('except');
assert.equal(a.run('effectiveCount(draft)'), 5); // Zero exclusions means all demo apps allowed.
a.changeSource('mail', true);
assert.equal(a.run('effectiveCount(draft)'), 4);
a.changePolicy('only');
assert.equal(a.run('effectiveCount(draft)'), 2); // The allowlist is retained independently.
a.changePolicy('except');
assert.equal(a.run("draft.exclude.includes('mail')"), true);
a.run("query = 'Chat'");
a.clickBulk('all');
assert.equal(a.run("draft.exclude.includes('chat')"), true);
a.clickBulk('clear');
assert.equal(a.run("draft.exclude.includes('chat')"), false);
assert.equal(a.run("draft.exclude.includes('mail')"), true); // Hidden Mail was untouched.
a.clickAction('cancel');
assert.equal(a.run('model.policy'), 'only');
assert.equal(a.run('model.exclude.length'), 0);

const b = study('empty');
assert.equal(b.run('effectiveCount(model)'), 0); // Empty allowlist means no source.
assert.match(b.run('activity()'), /No activity yet/);
assert.doesNotMatch(b.run('activity()'), /day-row|track|fill/);
b.run("navigate('sources')");
b.changePolicy('except');
assert.equal(b.run('effectiveCount(draft)'), 5); // Empty blocklist means all demo sources.
b.clickAction('save');
assert.equal(b.run('model.state'), 'ready');
assert.equal(b.run('model.sampleHasActivity'), false); // Source configuration cannot create history.
assert.match(b.run('activity()'), /No activity yet/);

const c = study('paused');
c.run("navigate('sources')");
c.changeSource('messages', false);
c.changeSource('mail', false);
c.clickAction('save');
assert.equal(c.run('model.state'), 'paused');
c.clickAction('pause');
assert.equal(c.run('model.state'), 'empty'); // Resume cannot watch zero sources.

const d = study('attention');
d.run("navigate('sources')");
d.changeSource('messages', false);
d.changeSource('mail', false);
d.clickAction('save');
assert.equal(d.run('model.state'), 'attention');
d.clickAction('restore');
assert.equal(d.run('model.state'), 'empty');

const e = study();
e.run("navigate('sources')");
e.changePolicy('except');
e.message({ type: 'study-theme', theme: 'dark' });
assert.equal(e.run('theme'), 'dark');
assert.equal(e.run('model.view'), 'sources');
assert.equal(e.run('draft.policy'), 'except');
assert.match(e.run('brand()'), /NeedleBub/);

console.log('Slim A flow assertions passed.');
