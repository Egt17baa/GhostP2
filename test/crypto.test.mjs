import test from 'node:test';
import assert from 'node:assert/strict';
import {
  generateCode, isValidCode, normalizeCode, deriveRoomSecrets, sealSignal, openSignal, CODE_LENGTH,
} from '../client/js/crypto.js';

test('el código generado es válido, tiene 100 bits y no se repite', () => {
  const seen = new Set();
  for (let i = 0; i < 200; i++) {
    const c = generateCode();
    assert.match(c, /^[0-9A-Z]{4}(-[0-9A-Z]{4}){4}$/);
    assert.ok(isValidCode(c));
    assert.equal(normalizeCode(c).length, CODE_LENGTH);
    seen.add(c);
  }
  assert.equal(seen.size, 200);
});

test('normalización tolerante a errores de escritura', () => {
  const c = generateCode();
  const messy = ` ${c.toLowerCase().replace(/-/g, ' ')} `;
  assert.equal(normalizeCode(messy), normalizeCode(c));
  assert.equal(normalizeCode('o0il'), '0011');
  assert.equal(isValidCode('ABC'), false);
  assert.equal(isValidCode('UUUU-UUUU-UUUU-UUUU-UUUU'), false);
});

test('la derivación es determinista y distinta por código', async () => {
  const code = generateCode();
  const a = await deriveRoomSecrets(code);
  const b = await deriveRoomSecrets(code.toLowerCase());
  const c = await deriveRoomSecrets(generateCode());
  assert.equal(a.roomId, b.roomId);
  assert.match(a.roomId, /^[0-9a-f]{64}$/);
  assert.deepEqual(a.psk, b.psk);
  assert.notEqual(a.roomId, c.roomId);
  assert.notDeepEqual(a.psk, c.psk);
  await assert.rejects(deriveRoomSecrets('malo'));
});

test('la señalización cifrada solo la abre quien tiene el código', async () => {
  const code = generateCode();
  const a = await deriveRoomSecrets(code);
  const b = await deriveRoomSecrets(code);
  const other = await deriveRoomSecrets(generateCode());
  const blob = await sealSignal(a.signalKey, a.roomId, { k: 'offer', d: { sdp: 'v=0' } });
  assert.ok(!blob.includes('offer'));
  assert.deepEqual(await openSignal(b.signalKey, b.roomId, blob), { k: 'offer', d: { sdp: 'v=0' } });
  await assert.rejects(openSignal(other.signalKey, other.roomId, blob));
  await assert.rejects(openSignal(b.signalKey, other.roomId, blob)); // AAD distinta
  const raw = Buffer.from(blob, 'base64');
  raw[raw.length - 1] ^= 1;
  await assert.rejects(openSignal(b.signalKey, b.roomId, raw.toString('base64')));
});
