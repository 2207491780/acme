import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { fileURLToPath } from 'node:url';
import { startDev } from '../scripts/dev.mjs';

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function fixture() {
  const calls = { configs: [], spawns: [], listens: 0, closes: 0, kills: 0 };
  const spawned = deferred();
  const signals = new EventEmitter();
  const electron = Object.assign(new EventEmitter(), {
    pid: 123,
    exitCode: null,
    signalCode: null,
    killed: false,
    kill() {
      calls.kills += 1;
      this.killed = true;
      this.signalCode = 'SIGTERM';
      this.emit('exit', null, 'SIGTERM');
      this.emit('close', null, 'SIGTERM');
      return true;
    },
  });
  const server = {
    resolvedUrls: null,
    async listen() {
      calls.listens += 1;
      this.resolvedUrls = { local: ['http://127.0.0.1:5173/owned-base/'], network: [] };
    },
    async close() { calls.closes += 1; },
  };
  const options = {
    async createServer(config) {
      calls.configs.push(config);
      return server;
    },
    electronPath: '/fake/electron',
    spawnElectron(...args) {
      calls.spawns.push(args);
      spawned.resolve();
      return electron;
    },
    env: { WEB_DEV_SERVER_URL: 'http://unrelated.invalid/', TEST_ENV: 'preserved' },
    signals,
    logger: { log() {} },
  };
  return { calls, spawned, signals, electron, server, options };
}

function assertCleaned(f, expectedKills = 0) {
  assert.equal(f.calls.closes, 1);
  assert.equal(f.calls.kills, expectedKills);
  assert.equal(f.signals.listenerCount('SIGINT'), 0);
  assert.equal(f.signals.listenerCount('SIGTERM'), 0);
}

test('should_wait_for_owned_listener_and_use_its_url_when_starting_electron', async () => {
  const f = fixture();
  const listening = deferred();
  const enteredListen = deferred();
  f.options.env.PORT = '5432';
  f.server.listen = async () => {
    enteredListen.resolve();
    await listening.promise;
    f.server.resolvedUrls = { local: ['http://127.0.0.1:5432/owned-base/'], network: [] };
  };
  const running = startDev(f.options);
  await enteredListen.promise;
  assert.equal(f.calls.spawns.length, 0);
  assert.deepEqual(f.calls.configs, [{
    root: fileURLToPath(new URL('../../web', import.meta.url)),
    server: { host: '127.0.0.1', port: 5432, strictPort: true },
  }]);
  listening.resolve();
  await f.spawned.promise;
  assert.deepEqual(f.calls.spawns, [[
    '/fake/electron',
    [fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')],
    {
      env: { ...f.options.env, WEB_DEV_SERVER_URL: 'http://127.0.0.1:5432/owned-base/' },
      stdio: 'inherit',
    },
  ]]);
  f.electron.exitCode = 0;
  f.electron.emit('exit', 0, null);
  f.electron.emit('close', 0, null);
  assert.equal(await running, 0);
  assertCleaned(f);
});

for (const code of ['EADDRINUSE', 'EACCES']) {
  test(`should_not_spawn_electron_when_listen_fails_with_${code}`, async () => {
    const f = fixture();
    const error = Object.assign(new Error(`listen failed: ${code}`), { code });
    f.server.listen = async () => { throw error; };
    await assert.rejects(startDev(f.options), (actual) => actual === error);
    assert.equal(f.calls.configs[0].server.port, 5173);
    assert.equal(f.calls.configs[0].server.strictPort, true);
    assert.equal(f.calls.spawns.length, 0);
    assertCleaned(f);
  });
}

test('should_not_spawn_electron_when_create_server_fails', async () => {
  const f = fixture();
  const error = new Error('config failed');
  f.options.createServer = async () => { throw error; };
  await assert.rejects(startDev(f.options), (actual) => actual === error);
  assert.equal(f.calls.spawns.length, 0);
  assert.equal(f.calls.closes, 0);
  assert.equal(f.signals.listenerCount('SIGINT'), 0);
  assert.equal(f.signals.listenerCount('SIGTERM'), 0);
});

test('should_not_fall_back_to_unowned_url_when_server_has_no_local_url', async () => {
  const f = fixture();
  f.server.listen = async () => {};
  await assert.rejects(startDev(f.options), /未提供本地监听地址/);
  assert.equal(f.calls.spawns.length, 0);
  assertCleaned(f);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  test(`should_close_only_owned_resources_once_when_receiving_${signal}`, async () => {
    const f = fixture();
    const unrelated = fixture();
    const existingHandler = () => {};
    f.signals.on(signal, existingHandler);
    const running = startDev(f.options);
    await f.spawned.promise;
    f.signals.emit(signal);
    f.signals.emit(signal);
    assert.equal(await running, 0);
    assert.deepEqual(f.signals.listeners(signal), [existingHandler]);
    f.signals.off(signal, existingHandler);
    assertCleaned(f, 1);
    assert.equal(unrelated.calls.kills, 0);
    assert.equal(unrelated.calls.closes, 0);
  });
}

for (const stage of ['createServer', 'listen']) {
  test(`should_cancel_startup_without_spawning_when_signalled_during_${stage}`, async () => {
    const f = fixture();
    const entered = deferred();
    const pending = deferred();
    const wait = async () => {
      entered.resolve();
      await pending.promise;
      return f.server;
    };
    if (stage === 'createServer') f.options.createServer = wait;
    else f.server.listen = wait;
    const running = startDev(f.options);
    await entered.promise;
    f.signals.emit('SIGTERM');
    assert.equal(f.calls.closes, 0);
    pending.resolve();
    assert.equal(await running, 0);
    assert.equal(f.calls.spawns.length, 0);
    assert.equal(f.calls.listens, 0);
    assertCleaned(f);
  });
}

test('should_close_server_when_spawn_throws', async () => {
  const f = fixture();
  const error = new Error('spawn failed');
  f.options.spawnElectron = () => { throw error; };
  await assert.rejects(startDev(f.options), (actual) => actual === error);
  assertCleaned(f);
});

test('should_handle_spawn_error_and_close_server_when_electron_is_missing', async () => {
  const f = fixture();
  const error = Object.assign(new Error('spawn ENOENT'), { code: 'ENOENT' });
  f.electron.pid = undefined;
  const running = startDev(f.options);
  const rejected = assert.rejects(running, (actual) => actual === error);
  await f.spawned.promise;
  f.electron.emit('error', error);
  f.electron.emit('close', -2, null);
  await rejected;
  assertCleaned(f);
});

for (const [code, signal, expected] of [[7, null, 7], [null, 'SIGTERM', 1]]) {
  test(`should_propagate_failure_and_close_server_when_electron_exits_with_${code ?? signal}`, async () => {
    const f = fixture();
    const running = startDev(f.options);
    await f.spawned.promise;
    f.electron.exitCode = code;
    f.electron.signalCode = signal;
    f.electron.emit('exit', code, signal);
    f.electron.emit('close', code, signal);
    assert.equal(await running, expected);
    assertCleaned(f);
  });
}

test('should_wait_for_server_and_electron_cleanup_before_finishing_shutdown', async () => {
  const f = fixture();
  const closing = deferred();
  const closed = deferred();
  f.server.close = async () => {
    f.calls.closes += 1;
    closing.resolve();
    await closed.promise;
  };
  f.electron.kill = () => {
    f.calls.kills += 1;
    f.electron.killed = true;
    return true;
  };
  let finished = false;
  const running = startDev(f.options).then((code) => { finished = true; return code; });
  await f.spawned.promise;
  f.signals.emit('SIGINT');
  await closing.promise;
  assert.equal(finished, false);
  closed.resolve();
  await closed.promise;
  assert.equal(finished, false);
  f.electron.emit('close', null, 'SIGTERM');
  assert.equal(await running, 0);
  assertCleaned(f, 1);
});
