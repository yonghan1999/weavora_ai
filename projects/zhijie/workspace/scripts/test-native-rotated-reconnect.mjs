import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';
import { resolve } from 'node:path';

assert.ok(process.argv[2], 'Pass the compiled zhijie-shell test binary');
const tokens = version => ({ userId: 'user', sessionId: 'session', accessToken: `access-${version}`,
  refreshToken: `refresh-${version}`, accessExpiresAt: '2099-01-01T00:00:00Z', refreshExpiresAt: '2099-02-01T00:00:00Z' });
const cursors = [], connections = new Set(), rejection = process.argv[3] ?? 'http-503';
assert.ok(['http-503', 'disconnect'].includes(rejection), 'Expected http-503 or disconnect');
const eventGroups = [];
let refreshes = 0, baselines = 0, rejected = 0, logins = 0, login = false;
const finished = Promise.withResolvers();
const server = createServer(async (request, response) => {
  try {
    let data;
    if (request.url === '/api/v1/auth/login' && request.method === 'POST') {
      logins++;
      data = tokens('old');
    } else if (request.url === '/api/v1/sync/cursor' && request.method === 'GET') {
      assert.ok(request.headers.authorization === 'Bearer access-old', 'unexpected cursor credential');
      baselines++;
      data = { cursor: 10 };
    } else if (request.url === '/api/v1/auth/refresh' && request.method === 'POST') {
      let body = '';
      for await (const chunk of request) body += chunk;
      assert.ok(JSON.parse(body).refreshToken === 'refresh-old', 'unexpected refresh credential');
      refreshes++;
      data = tokens('new');
    } else if (request.url === '/api/v1/presence/heartbeat' && request.method === 'POST') {
      assert.ok(request.headers.authorization === `Bearer ${tokens(refreshes ? 'new' : 'old').accessToken}`, 'unexpected heartbeat credential');
      data = {};
    } else throw Error(`unexpected HTTP operation: ${request.method} ${new URL(request.url, 'http://localhost').pathname}`);
    response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data }));
  } catch (error) { response.destroy(); finished.reject(error); }
});
server.on('connection', socket => { connections.add(socket); socket.once('close', () => connections.delete(socket)); });
function frame(opcode, body) {
  const bytes = Buffer.from(body);
  assert.ok(bytes.length < 126);
  return Buffer.concat([Buffer.from([0x80 | opcode, bytes.length]), bytes]);
}
server.on('upgrade', (request, socket) => {
  try {
    const address = new URL(request.url, 'http://localhost');
    const index = cursors.length;
    assert.equal(address.pathname, '/api/v1/ws');
    assert.deepEqual([...address.searchParams.keys()], ['cursor']);
    assert.ok(request.headers.authorization === `Bearer ${tokens(index === 0 ? 'old' : 'new').accessToken}`, 'unexpected upgrade credential');
    cursors.push(address.searchParams.get('cursor'));
    if (index === 1) {
      rejected++;
      if (rejection === 'disconnect') socket.destroy();
      else socket.end('HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
      return;
    }
    const accept = createHash('sha1').update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    if (index === 3) { finished.resolve(); return; }
    const sequence = index === 0 ? 11 : 12;
    const event = frame(1, JSON.stringify({ sequence, type: 'message.created', groupId: `group-${sequence}`, payload: '{}' }));
    const reason = index === 0 ? 'ACCESS_TOKEN_INVALID' : '';
    const closeCode = Buffer.alloc(2);
    closeCode.writeUInt16BE(index === 0 ? 1008 : 1000);
    socket.end(Buffer.concat([event, frame(8, Buffer.concat([closeCode, Buffer.from(reason)]))]));
  } catch (error) { socket.destroy(); finished.reject(error); }
});
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const probe = spawn(resolve(process.argv[2]), ['--exact', 'sync::browser_probe::product_page_client', '--ignored', '--nocapture'], {
  env: { ...process.env, ZHIJIE_TEST_GROUP_PORT: String(server.address().port) }, stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true,
});
const exit = once(probe, 'close');
probe.stdin.on('error', error => finished.reject(error));
exit.then(() => finished.reject(Error('native probe exited before final handshake')), error => finished.reject(error));
const lines = createInterface({ input: probe.stdout });
lines.on('line', line => {
  if (!line.startsWith('BROWSER_PROBE ')) return;
  try {
    const result = JSON.parse(line.slice('BROWSER_PROBE '.length));
    if (result.id === 1) login = !result.error && result.value?.userId === 'user';
    if (result.changed?.userId === 'user' && result.changed.messageGroupId) eventGroups.push(result.changed.messageGroupId);
  } catch { finished.reject(Error('invalid probe output')); }
});
let timer;
const deadline = new Promise((_, reject) => { timer = setTimeout(() => reject(Error('native reconnect deadline')), 15000); });
try {
  probe.stdin.write(JSON.stringify({ id: 1, command: 'session_login', args: { email: 'local@example.test', password: 'fixture', deviceId: 'test' } }) + '\n');
  await Promise.race([finished.promise, deadline]);
  probe.stdin.end();
  assert.deepEqual(await Promise.race([exit, deadline]), [0, null]);
  assert.deepEqual(cursors, ['10', '11', '11', '12']);
  assert.ok(login, 'native login required');
  assert.deepEqual(eventGroups, ['group-11', 'group-12']);
  assert.deepEqual([logins, baselines, refreshes, rejected], [1, 1, 1, 1]);
  console.log(`Native Shell (${rejection}): one refresh, one failed upgrade, retained cursor 11, processed event 12; no new login.`);
} finally {
  clearTimeout(timer);
  if (probe.exitCode === null) probe.kill();
  lines.close();
  for (const socket of connections) socket.destroy();
  await new Promise(done => server.close(done));
}
