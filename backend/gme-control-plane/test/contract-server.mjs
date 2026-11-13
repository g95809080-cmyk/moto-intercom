// Loopback fixture for the Android JVM contract tests. Never imports AWS/GME secrets.
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { createControlPlane } from '../src/control-plane.mjs';
import { MemoryStore } from './memory-store.mjs';

const store = new MemoryStore(), sockets = new Map(), delivered = new Set();
let ticketCalls = 0;
const control = createControlPlane({ store, issueTicket: async grant => {
  ticketCalls++;
  return { ...grant, appId: 1400802321, authBuffer: 'AQID', expiresAt: Math.floor(Date.now() / 1000) + 300 };
} });
async function dispatch() {
  for (const record of store.records.values()) {
    if (record.kind !== 'EVENT' || delivered.has(record.pk)) continue;
    delivered.add(record.pk);
    for (const target of record.targets) {
      const device = await store.get('DEVICE#'+target);
      for (const cid of device?.connectionIds ?? []) {
        const socket = sockets.get(cid);
        if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(record.event));
      }
    }
  }
}
const server = http.createServer(async (request,response) => {
  try {
    if (request.url === '/__fixture/stats') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ticketCalls, connections: sockets.size })); return;
    }
    let body = '';
    for await (const chunk of request) { body += chunk; if (body.length > 65536) throw new Error('BODY_LIMIT'); }
    const result = await control.http({ rawPath: new URL(request.url,'http://127.0.0.1').pathname,
      requestContext: { http: { method: request.method } }, headers: request.headers, body });
    response.writeHead(result.statusCode, result.headers); response.end(result.body);
    await dispatch();
  } catch {
    if (!response.headersSent) response.writeHead(500);
    response.end('{"error":"FIXTURE_FAILURE"}');
  }
});
const websocket = new WebSocketServer({ noServer: true });
server.on('upgrade',async (request,socket,head) => {
  const cid = randomUUID();
  const token = new URL(request.url,'http://127.0.0.1').searchParams.get('token');
  const result = await control.websocket({ requestContext: { routeKey: '$connect', connectionId: cid }, queryStringParameters: { token } });
  if (result.statusCode !== 200) { socket.destroy(); return; }
  websocket.handleUpgrade(request,socket,head,ws => {
    sockets.set(cid,ws);
    ws.on('close',async () => {
      sockets.delete(cid);
      await control.websocket({ requestContext: { routeKey: '$disconnect', connectionId: cid } }); await dispatch();
    });
    void dispatch();
  });
});
server.listen(0,'127.0.0.1',() => process.stdout.write(JSON.stringify({ port: server.address().port })+'\n'));
