import test from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { createSignalingServer } from '../server/server.js';

const ROOM = 'a'.repeat(64);

function client(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString());
    const w = waiters.shift();
    if (w) w(m); else inbox.push(m);
  });
  const closed = new Promise((r) => ws.on('close', (code) => r(code)));
  return {
    ws,
    closed,
    open: () => new Promise((r) => ws.once('open', r)),
    send: (o) => ws.send(JSON.stringify(o)),
    next: () => (inbox.length ? Promise.resolve(inbox.shift()) : new Promise((r) => waiters.push(r))),
  };
}

test('empareja dos clientes, reenvía señales y avisa de la salida', async (t) => {
  const srv = await createSignalingServer({ port: 0, host: '127.0.0.1' });
  t.after(() => srv.close());
  const a = client(srv.port);
  await a.open();
  a.send({ type: 'join', room: ROOM });
  assert.deepEqual(await a.next(), { type: 'joined', peers: 0 });

  const b = client(srv.port);
  await b.open();
  b.send({ type: 'join', room: ROOM });
  assert.deepEqual(await b.next(), { type: 'joined', peers: 1 });
  assert.deepEqual(await a.next(), { type: 'peer-joined' });

  b.send({ type: 'signal', data: 'blob-cifrado' });
  assert.deepEqual(await a.next(), { type: 'signal', data: 'blob-cifrado' });
  a.send({ type: 'signal', data: 'respuesta' });
  assert.deepEqual(await b.next(), { type: 'signal', data: 'respuesta' });

  const c = client(srv.port);
  await c.open();
  c.send({ type: 'join', room: ROOM });
  assert.deepEqual(await c.next(), { type: 'error', reason: 'room-full' });
  await c.closed;

  b.ws.close();
  assert.deepEqual(await a.next(), { type: 'peer-left' });
  a.ws.close();
  await a.closed;
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(srv.rooms.size, 0); // no queda rastro de la sala
});

test('rechaza salas mal formadas y no mezcla salas distintas', async (t) => {
  const srv = await createSignalingServer({ port: 0, host: '127.0.0.1' });
  t.after(() => srv.close());
  const bad = client(srv.port);
  await bad.open();
  bad.send({ type: 'join', room: 'sala-legible' });
  assert.deepEqual(await bad.next(), { type: 'error', reason: 'bad-room' });
  await bad.closed;

  const a = client(srv.port);
  const b = client(srv.port);
  await Promise.all([a.open(), b.open()]);
  a.send({ type: 'join', room: ROOM });
  b.send({ type: 'join', room: 'b'.repeat(64) });
  assert.equal((await a.next()).peers, 0);
  assert.equal((await b.next()).peers, 0);
  a.send({ type: 'signal', data: 'x' });
  const got = await Promise.race([b.next(), new Promise((r) => setTimeout(() => r('nada'), 200))]);
  assert.equal(got, 'nada');
  a.ws.close();
  b.ws.close();
});

test('limita la tasa de mensajes', async (t) => {
  const srv = await createSignalingServer({ port: 0, host: '127.0.0.1', limits: { burst: 5, refillPerSec: 0 } });
  t.after(() => srv.close());
  const a = client(srv.port);
  await a.open();
  for (let i = 0; i < 10; i++) a.send({ type: 'signal', data: 'spam' });
  assert.equal(await a.closed, 1008);
});

test('sirve la app con cabeceras de seguridad y evita path traversal', async (t) => {
  const srv = await createSignalingServer({ port: 0, host: '127.0.0.1' });
  t.after(() => srv.close());
  const base = `http://127.0.0.1:${srv.port}`;
  const res = await fetch(`${base}/`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-security-policy'), /script-src 'self'/);
  assert.equal(res.headers.get('referrer-policy'), 'no-referrer');
  assert.match(await res.text(), /GhostP2/);
  assert.equal((await fetch(`${base}/js/app.js`)).status, 200);
  assert.equal((await fetch(`${base}/..%2fpackage.json`)).status, 404);
  assert.equal((await fetch(`${base}/%2e%2e/server/server.js`)).status, 404);
});
