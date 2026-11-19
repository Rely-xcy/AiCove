const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/plugin-ui/aicode.js'), 'utf8');

function loadBridge(handle) {
  const requests = [];
  const window = {
    dispatchEvent() {},
    __aicodeBridge: {
      postMessage(text) {
        const request = JSON.parse(text);
        requests.push(request);
        Promise.resolve().then(() => handle(request)).then(data => {
          window.__aicodeResolve(request.id, JSON.stringify({ ok: true, data }));
        }, error => {
          window.__aicodeResolve(request.id, JSON.stringify({ ok: false, error }));
        });
      }
    }
  };
  const sandbox = { window, console, document: { documentElement: { style: { setProperty() {} } } }, CustomEvent: function () {} };
  vm.runInNewContext(source, sandbox, { filename: 'aicode.js' });
  return { window, api: window.aicove, requests };
}

test('AiCove API and the existing plugin API share one implementation', async () => {
  const { api, window, requests } = loadBridge(() => ({ visible: true }));
  assert.equal(api, window.aicode);
  assert.equal((await api.context()).visible, true);
  assert.equal(requests[0].method, 'context');
});

test('model streaming passes cursors and excludes read-only wait options from start', async () => {
  const events = [];
  const { api, requests } = loadBridge(request => {
    if (request.method === 'model.start') return { streamId: 'stream-1' };
    if (request.params.cursor === 0) return { cursor: 2, events: [{ type: 'text', text: 'first' }], done: false };
    return { cursor: 3, events: [{ type: 'text', text: 'last' }], done: true };
  });
  const result = await api.model.stream({ handleId: 'handle-1', messages: [], waitMs: 20 }, event => events.push(event.text));
  assert.equal(result.done, true);
  assert.deepEqual(events, ['first', 'last']);
  assert.equal('waitMs' in requests[0].params, false);
  assert.equal(requests[1].params.cursor, 0);
  assert.equal(requests[2].params.cursor, 2);
  assert.equal(requests[2].params.waitMs, 20);
});

test('watch listener is ready before confirmation and unsubscribe is idempotent', async () => {
  let windowRef;
  const received = [];
  const fixture = loadBridge(request => {
    if (request.method === 'fs.watch') {
      windowRef.__aicodeEvent(JSON.stringify({ name: 'fs.changed', payload: { subscriptionId: request.params.subscriptionId, changes: ['first'] } }));
      return {};
    }
    return {};
  });
  windowRef = fixture.window;
  const off = await fixture.api.fs.watch('data://state', { recursive: true, contextKey: 'workspace-1' }, event => received.push(event.changes[0]));
  assert.deepEqual(received, ['first']);
  await off();
  await off();
  assert.equal(fixture.requests.filter(request => request.method === 'fs.unwatch').length, 1);
  assert.equal(fixture.requests[0].params.contextKey, 'workspace-1');
});

test('existing summarize-switch calls keep workspace selection and session aliases', async () => {
  const { api, requests } = loadBridge(() => ({ sessions: [] }));
  await api.workspaces.list();
  await api.chat.sessions({ workspacePath: '/srv/other' });
  await api.chat.create({ title: 'Summary', workspacePath: '/srv/new' });
  await api.chat.messages({ sessionId: 'session-1', after: 12, limit: 20 });
  await api.chat.send({ sessionId: 'session-1', text: 'summarize' });
  await api.chat.open('session-1');
  await api.chat.waitRun('session-1', 3000);
  assert.deepEqual(requests.map(request => request.method), [
    'workspaces.list', 'chat.listSessions', 'chat.createSession', 'chat.listMessages',
    'chat.sendMessage', 'chat.openSession', 'chat.waitForRun'
  ]);
  assert.equal(requests[1].params.workspacePath, '/srv/other');
  assert.equal(requests[2].params.workspacePath, '/srv/new');
  assert.equal(requests[3].params.after, 12);
  assert.equal(requests[6].params.timeoutMs, 3000);
});

test('bridge errors preserve stable host error codes', async () => {
  const { api } = loadBridge(() => Promise.reject({ code: 'PLUGIN_INACTIVE', message: 'Plugin disabled' }));
  await assert.rejects(api.secrets.get('setting'), error => error.code === 'PLUGIN_INACTIVE');
});

test('tool calls preserve their plugin session and selected code context', async () => {
  const { api, requests } = loadBridge(() => ({ status: 'success' }));
  await api.tools.execute('readFile', { path: 'README.md' }, {
    sessionId: 'plugin:demo.one:task', context: { path: 'README.md', selection: 'text', language: 'md' }, timeoutMs: 100
  });
  assert.equal(requests[0].method, 'tools.execute');
  assert.deepEqual(requests[0].params, {
    name: 'readFile', arguments: { path: 'README.md' }, sessionId: 'plugin:demo.one:task',
    timeoutMs: 100, context: { path: 'README.md', selection: 'text', language: 'md' }
  });
});
