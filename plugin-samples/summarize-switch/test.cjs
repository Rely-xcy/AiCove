const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const page = fs.readFileSync(path.join(__dirname, 'pages/summarize.html'), 'utf8');
const bridge = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/plugin-ui/aicode.js'), 'utf8');
const script = [...page.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n');

function boot(options = {}) {
  const calls = [];
  let component;
  let mounted;
  let sent = false;
  let polls = 0;
  const history = Array.from({ length: 205 }, (_, i) => ({
    id: String(i), role: i % 2 ? 'assistant' : 'user', content: `message-${i}`, timestamp: i + 1
  }));
  const context = vm.createContext({ console, setTimeout: fn => { fn(); }, clearTimeout });
  context.window = context;
  context.Vue = { createApp(config) {
    assert.equal(typeof config.data, 'function');
    component = config.data();
    for (const [key, method] of Object.entries(config.methods)) component[key] = method.bind(component);
    return { mount(selector) {
      assert.equal(selector, '#app');
      mounted = config.mounted.call(component);
      return component;
    } };
  } };
  context.__aicodeBridge = { postMessage(raw) {
    const { id, method, params } = JSON.parse(raw);
    calls.push({ method, params });
    let data;
    switch (method) {
      case 'workspaces.list':
        data = { current: '/source', workspaces: [{ path: '/source', name: 'Source' }, { path: '/target', name: 'Target' }] };
        break;
      case 'chat.listSessions': data = { sessions: [{ id: 'source', title: 'Source session' }] }; break;
      case 'chat.listMessages': {
        if (params.sessionId === 'new') {
          data = { messages: [{ role: 'assistant', content: 'summary-result', timestamp: 999 }], hasMore: false };
        } else {
          const rows = history.filter(m => m.timestamp > (params.after || 0));
          data = { messages: rows.slice(0, 200).reverse(), hasMore: rows.length > 200 };
        }
        break;
      }
      case 'chat.createSession': data = { session: { id: 'new' } }; break;
      case 'chat.sendMessage': sent = true; data = { accepted: true }; break;
      case 'chat.getRunState':
        assert.ok(sent);
        data = { run: { running: polls++ > 0, status: polls === 1 ? 'idle' : 'running' } };
        break;
      case 'chat.waitForRun':
        data = { timedOut: options.timeout || false, run: { running: false, status: options.failure ? 'error' : 'success', error: options.failure ? 'model failed' : undefined } };
        break;
      case 'chat.openSession': data = { accepted: true }; break;
      case 'ui.close': data = { ok: true }; break;
      default: throw new Error(`Unexpected method: ${method}`);
    }
    context.__aicodeResolve(id, JSON.stringify({ ok: true, data }));
  } };
  vm.runInContext(bridge, context);
  vm.runInContext(script, context);
  return { component, calls, ready: mounted };
}

test('page loads the bundled Vue 3 runtime before mounting', () => {
  const runtime = page.indexOf('src="/__aicode/vue.global.prod.js"');
  assert.ok(runtime >= 0, 'Vue runtime must be explicitly loaded');
  assert.ok(runtime < page.indexOf('Vue.createApp('));
  assert.match(page, /\.mount\('#app'\)/);
});

test('summary reads all pages in chronological order and opens then closes the page', async () => {
  const { component, calls, ready } = boot();
  await ready;
  assert.equal(component.sourceId, 'source');
  component.workspacePath = '/target';
  await component.run();
  assert.equal(component.failed, false);
  assert.equal(component.summary, 'summary-result');
  const prompt = calls.find(c => c.method === 'chat.sendMessage').params.text;
  assert.ok(prompt.indexOf('message-0') < prompt.indexOf('message-204'));
  assert.ok(prompt.includes('message-204'));
  assert.equal(calls.find(c => c.method === 'chat.createSession').params.workspacePath, '/target');
  assert.equal(calls.filter(c => c.method === 'chat.getRunState').length, 2);
  assert.equal(calls.filter(c => c.method === 'chat.listMessages' && c.params.sessionId === 'source').length, 2);
  assert.deepEqual(calls.slice(-2).map(c => c.method), ['chat.openSession', 'ui.close']);
});

for (const [name, options, message] of [
  ['model failure', { failure: true }, 'model failed'],
  ['timeout', { timeout: true }, '总结超时']
]) {
  test(`${name} stays on the plugin page with an error`, async () => {
    const { component, calls, ready } = boot(options);
    await ready;
    await component.run();
    assert.equal(component.failed, true);
    assert.equal(component.busy, false);
    assert.ok(component.status.includes(message));
    assert.equal(calls.some(c => c.method === 'chat.openSession' || c.method === 'ui.close'), false);
  });
}
