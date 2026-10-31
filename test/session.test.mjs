import test from 'node:test';
import assert from 'node:assert/strict';
import { SecureSession, SecurityError } from '../client/js/session.js';
import { randomBytes } from '../client/js/util.js';

async function pair(pskA = null, pskB = pskA) {
  const a = await SecureSession.create(pskA);
  const b = await SecureSession.create(pskB);
  const helloA = a.helloMessage();
  const helloB = b.helloMessage();
  await a.acceptHello(helloB);
  await b.acceptHello(helloA);
  return [a, b];
}

test('ambos extremos obtienen el mismo número de seguridad', async () => {
  const psk = randomBytes(32);
  const [a, b] = await pair(psk, new Uint8Array(psk));
  assert.match(a.safetyNumber, /^(\d{5} ){5}\d{5}$/);
  assert.equal(a.safetyNumber, b.safetyNumber);
  const [c] = await pair();
  assert.notEqual(a.safetyNumber, c.safetyNumber); // claves efímeras nuevas cada sesión
});

test('mensajes en ambas direcciones, en orden', async () => {
  const [a, b] = await pair(randomBytes(32));
  for (let i = 0; i < 5; i++) {
    assert.deepEqual(await b.decrypt(await a.encrypt({ t: 'msg', text: `hola ${i}` })), { t: 'msg', text: `hola ${i}` });
    assert.deepEqual(await a.decrypt(await b.encrypt({ t: 'msg', text: `adiós ${i} ñ 👻` })), { t: 'msg', text: `adiós ${i} ñ 👻` });
  }
});

test('cifrados concurrentes no reutilizan claves', async () => {
  const [a, b] = await pair();
  const frames = await Promise.all(Array.from({ length: 20 }, (_, i) => a.encrypt({ i })));
  for (let i = 0; i < 20; i++) assert.deepEqual(await b.decrypt(frames[i]), { i });
});

test('se rechazan repeticiones y reordenaciones', async () => {
  const [a, b] = await pair();
  const f1 = await a.encrypt({ n: 1 });
  const f2 = await a.encrypt({ n: 2 });
  await assert.rejects(b.decrypt(f2), SecurityError);
  await b.decrypt(f1);
  await assert.rejects(b.decrypt(f1), SecurityError);
  assert.deepEqual(await b.decrypt(f2), { n: 2 });
});

test('cualquier manipulación se detecta y no avanza el estado', async () => {
  const [a, b] = await pair();
  const f = await a.encrypt({ secreto: 'x' });
  for (const pos of [0, 4, 10, f.length - 1]) {
    const bad = new Uint8Array(f);
    bad[pos] ^= 0x40;
    await assert.rejects(b.decrypt(bad), SecurityError);
  }
  assert.deepEqual(await b.decrypt(f), { secreto: 'x' });
});

test('un mensaje propio reflejado no se acepta', async () => {
  const [a] = await pair();
  const f = await a.encrypt({ t: 'msg' });
  await assert.rejects(a.decrypt(f), SecurityError);
});

test('códigos distintos (o un intermediario sin el código) no pueden comunicarse', async () => {
  const [a, b] = await pair(randomBytes(32), randomBytes(32));
  await assert.rejects(b.decrypt(await a.encrypt({ t: 'confirm' })), SecurityError);
});

test('el relleno oculta la longitud de los mensajes cortos', async () => {
  const [a] = await pair();
  const s1 = (await a.encrypt({ t: 'msg', text: 'sí' })).length;
  const s2 = (await a.encrypt({ t: 'msg', text: 'x'.repeat(150) })).length;
  assert.equal(s1, s2);
});

test('hello inválido o duplicado se rechaza', async () => {
  const a = await SecureSession.create();
  await assert.rejects(a.acceptHello('no json'), SecurityError);
  await assert.rejects(a.acceptHello(JSON.stringify({ t: 'hello', v: 99, pub: 'AA==' })), SecurityError);
  await assert.rejects(a.acceptHello(a.helloMessage()), SecurityError); // reflexión
  const bad = Buffer.alloc(65, 7);
  bad[0] = 4;
  await assert.rejects(a.acceptHello(JSON.stringify({ t: 'hello', v: 1, pub: bad.toString('base64') })));
  const b = await SecureSession.create();
  await a.acceptHello(b.helloMessage());
  await assert.rejects(a.acceptHello(b.helloMessage()), SecurityError);
});

test('tras destruir la sesión no se puede cifrar ni descifrar', async () => {
  const [a, b] = await pair();
  const f = await a.encrypt({ x: 1 });
  b.destroy();
  await assert.rejects(b.decrypt(f), SecurityError);
  a.destroy();
  await assert.rejects(a.encrypt({ x: 2 }), SecurityError);
});
