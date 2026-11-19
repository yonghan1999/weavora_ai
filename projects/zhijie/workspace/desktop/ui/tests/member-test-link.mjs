import { createServer, request } from 'node:http';

export async function memberTestLink(port) {
  let online = true, rejectedUpgrades = 0;
  const cursors = [], connections = new Set(), tunnels = new Set();
  const options = incoming => ({ host: '127.0.0.1', port, method: incoming.method,
    path: incoming.url, headers: { ...incoming.headers, host: `127.0.0.1:${port}` }, agent: false });
  const server = createServer((incoming, outgoing) => {
    if (!online) { outgoing.writeHead(503).end(); return; }
    const upstream = request(options(incoming), response => {
      outgoing.writeHead(response.statusCode, response.headers); response.pipe(outgoing);
    });
    upstream.on('error', () => outgoing.destroy());
    outgoing.on('close', () => upstream.destroy());
    incoming.pipe(upstream);
  });
  server.on('connection', socket => {
    connections.add(socket); socket.on('close', () => connections.delete(socket));
    socket.on('error', () => socket.destroy());
  });
  server.on('upgrade', (incoming, downstream, head) => {
    if (!online) { rejectedUpgrades++; downstream.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n'); return; }
    const cursor = new URL(incoming.url, 'http://127.0.0.1').searchParams.get('cursor');
    const upstream = request(options(incoming));
    upstream.on('error', () => downstream.destroy());
    upstream.on('response', response => { response.resume(); downstream.destroy(); });
    downstream.on('close', () => upstream.destroy());
    upstream.on('upgrade', (response, backend, extra) => {
      if (!online || downstream.destroyed) { backend.destroy(); downstream.destroy(); return; }
      const headers = response.rawHeaders.map((value, index) => index % 2 ? `${value}\r\n` : `${value}: `).join('');
      downstream.write(`HTTP/1.1 101 Switching Protocols\r\n${headers}\r\n`);
      if (head.length) backend.write(head);
      if (extra.length) downstream.write(extra);
      backend.on('error', () => downstream.destroy()); backend.on('close', () => downstream.destroy());
      downstream.on('close', () => { backend.destroy(); tunnels.delete(downstream); });
      tunnels.add(downstream); cursors.push(cursor);
      downstream.pipe(backend).pipe(downstream);
    });
    upstream.end();
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return { port: server.address().port, cursors, get rejectedUpgrades() { return rejectedUpgrades; },
    disconnect() {
      if (tunnels.size !== 1) throw Error('expected one established member WebSocket');
      online = false;
      for (const connection of connections) connection.destroy();
    },
    restore() { online = true; },
    async close() {
      online = false;
      for (const connection of connections) connection.destroy();
      await new Promise(resolve => server.close(resolve));
    },
  };
}
