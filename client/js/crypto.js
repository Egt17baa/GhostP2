// Primitivas criptográficas de GhostP2 (Web Crypto API nativa).
//
// - Código de emparejamiento: 20 caracteres Base32 Crockford (100 bits aleatorios).
// - Del código se derivan (PBKDF2 + HKDF-SHA256):
//     roomId    -> identificador opaco de sala para el servidor de señalización
//     signalKey -> clave AES-256-GCM para cifrar TODA la señalización
//     psk       -> secreto que se mezcla en la clave de sesión (anti-MITM)

import { textEncoder, randomBytes, concatBytes, toHex, b64encode, b64decode, textDecoder } from './util.js';

const subtle = globalThis.crypto.subtle;

export const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
export const CODE_LENGTH = 20;
const PBKDF2_ITERATIONS = 210000;
const ZERO_SALT = new Uint8Array(32);

export function formatCode(normalized) {
  return normalized.match(/.{1,4}/g).join('-');
}

export function generateCode() {
  const bytes = randomBytes(CODE_LENGTH);
  let s = '';
  // 256 es múltiplo de 32: tomar 5 bits por byte no introduce sesgo.
  for (const b of bytes) s += CODE_ALPHABET[b & 31];
  return formatCode(s);
}

export function normalizeCode(input) {
  return String(input ?? '')
    .toUpperCase()
    .replace(/[^0-9A-Z]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1');
}

export function isValidCode(input) {
  const n = normalizeCode(input);
  if (n.length !== CODE_LENGTH) return false;
  for (const c of n) if (!CODE_ALPHABET.includes(c)) return false;
  return true;
}

export async function hkdf(ikm, salt, info, length) {
  const key = await subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  const infoBytes = typeof info === 'string' ? textEncoder.encode(info) : info;
  const bits = await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info: infoBytes }, key, length * 8);
  return new Uint8Array(bits);
}

export async function deriveRoomSecrets(code) {
  if (!isValidCode(code)) throw new Error('Código no válido');
  const norm = normalizeCode(code);
  const pw = await subtle.importKey('raw', textEncoder.encode(norm), 'PBKDF2', false, ['deriveBits']);
  const base = new Uint8Array(await subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: textEncoder.encode('GhostP2/room/v1'), iterations: PBKDF2_ITERATIONS },
    pw, 256));
  const roomId = toHex(await hkdf(base, ZERO_SALT, 'GhostP2 room-id v1', 32));
  const signalRaw = await hkdf(base, ZERO_SALT, 'GhostP2 signal-key v1', 32);
  const psk = await hkdf(base, ZERO_SALT, 'GhostP2 session-psk v1', 32);
  const signalKey = await subtle.importKey('raw', signalRaw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  base.fill(0);
  signalRaw.fill(0);
  return { roomId, signalKey, psk };
}

// Cifra un objeto de señalización (oferta/respuesta/candidatos ICE).
export async function sealSignal(key, roomId, obj) {
  const iv = randomBytes(12);
  const pt = textEncoder.encode(JSON.stringify(obj));
  const ct = await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: textEncoder.encode(roomId) }, key, pt);
  return b64encode(concatBytes(iv, new Uint8Array(ct)));
}

export async function openSignal(key, roomId, blob) {
  const raw = b64decode(blob);
  if (raw.length < 12 + 16) throw new Error('Señal demasiado corta');
  const pt = await subtle.decrypt(
    { name: 'AES-GCM', iv: raw.subarray(0, 12), additionalData: textEncoder.encode(roomId) },
    key, raw.subarray(12));
  return JSON.parse(textDecoder.decode(new Uint8Array(pt)));
}
