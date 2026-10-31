import test from 'node:test';
import assert from 'node:assert/strict';
import { encodeDescription, decodeDescription } from '../client/js/codec.js';

const sdp = 'v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n' + 'a=candidate:1 1 udp 2122260223 192.168.1.10 50000 typ host\r\n'.repeat(4);

test('ida y vuelta de oferta y respuesta', async () => {
  const offer = await encodeDescription({ type: 'offer', sdp });
  assert.match(offer, /^GP2O\.[A-Za-z0-9_-]+$/);
  assert.ok(offer.length < sdp.length);
  assert.deepEqual(await decodeDescription(`  ${offer.slice(0, 20)}\n${offer.slice(20)} `, 'offer'), { type: 'offer', sdp });
  const answer = await encodeDescription({ type: 'answer', sdp });
  assert.deepEqual(await decodeDescription(answer, 'answer'), { type: 'answer', sdp });
});

test('se rechazan códigos del tipo equivocado o dañados', async () => {
  const offer = await encodeDescription({ type: 'offer', sdp });
  await assert.rejects(decodeDescription(offer, 'answer'), /respuesta/);
  await assert.rejects(decodeDescription(offer.slice(0, -10), 'offer'));
  await assert.rejects(decodeDescription('GP2O.@@@', 'offer'));
  await assert.rejects(decodeDescription('', 'offer'));
});
