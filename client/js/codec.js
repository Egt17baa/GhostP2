// Codificación de ofertas/respuestas WebRTC para el modo manual (sin servidor).
// SDP -> JSON -> deflate -> base64url, con prefijo GP2O (oferta) o GP2A (respuesta).

import { textEncoder, textDecoder, b64urlEncode, b64urlDecode } from './util.js';

const MAX_DECOMPRESSED = 64 * 1024;
const PREFIX = { offer: 'GP2O', answer: 'GP2A' };

async function readAll(stream, limit) {
  const reader = stream.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > limit) {
      await reader.cancel();
      throw new Error('Código demasiado grande');
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}

async function transform(bytes, stream, limit) {
  const src = new Blob([bytes]).stream().pipeThrough(stream);
  return readAll(src, limit);
}

export async function encodeDescription(desc) {
  const prefix = PREFIX[desc.type];
  if (!prefix) throw new Error('Tipo de descripción no soportado');
  const json = textEncoder.encode(JSON.stringify({ t: desc.type, s: desc.sdp }));
  const packed = await transform(json, new CompressionStream('deflate-raw'), MAX_DECOMPRESSED);
  return `${prefix}.${b64urlEncode(packed)}`;
}

export async function decodeDescription(code, expectedType) {
  const clean = String(code ?? '').replace(/\s+/g, '');
  const dot = clean.indexOf('.');
  const prefix = clean.slice(0, dot);
  if (dot < 0 || prefix !== PREFIX[expectedType]) {
    throw new Error(expectedType === 'offer'
      ? 'Esto no es un código de invitación (debe empezar por GP2O.)'
      : 'Esto no es un código de respuesta (debe empezar por GP2A.)');
  }
  let obj;
  try {
    const packed = b64urlDecode(clean.slice(dot + 1));
    const json = await transform(packed, new DecompressionStream('deflate-raw'), MAX_DECOMPRESSED);
    obj = JSON.parse(textDecoder.decode(json));
  } catch {
    throw new Error('Código dañado o incompleto. Cópialo de nuevo entero.');
  }
  if (!obj || obj.t !== expectedType || typeof obj.s !== 'string' || !obj.s.startsWith('v=0')) {
    throw new Error('Código inválido');
  }
  return { type: obj.t, sdp: obj.s };
}
