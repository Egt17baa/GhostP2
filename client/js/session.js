// Canal seguro extremo a extremo sobre el DataChannel de WebRTC.
//
// Protocolo (v1):
//   1. Cada extremo genera un par ECDH P-256 EFÍMERO (solo en memoria, no exportable)
//      y envía su clave pública en un mensaje "hello".
//   2. secreto = ECDH(priv_propia, pub_ajena)
//      okm     = HKDF-SHA256(ikm=secreto, salt=psk, info="GhostP2 session v1" || pubBaja || pubAlta)
//      -> cadena A→B, cadena B→A y 32 bytes para el número de seguridad.
//      El psk sale del código de emparejamiento: sin el código no se obtiene la clave,
//      así que un intermediario no puede suplantar a nadie.
//   3. Cada mensaje usa una clave nueva (ratchet simétrico HMAC):
//        mk_n = HMAC(ck_n, 0x01) ; ck_{n+1} = HMAC(ck_n, 0x02)
//      Las claves usadas se olvidan: secreto hacia adelante dentro de la sesión.
//   4. AES-256-GCM, AAD = versión || dirección || contador. Contador estrictamente
//      secuencial: cualquier repetición, reordenación o manipulación se rechaza.
//   5. Relleno a bloques de 256 bytes para ocultar la longitud real del mensaje.

import { textEncoder, textDecoder, concatBytes, compareBytes, b64encode, b64decode } from './util.js';
import { hkdf } from './crypto.js';

const subtle = globalThis.crypto.subtle;
const PROTOCOL_VERSION = 1;
const PAD_BLOCK = 256;
export const MAX_PLAINTEXT = 32 * 1024;
const HEADER_LEN = 5; // 1 byte versión + 4 bytes contador

export class SecurityError extends Error {
  constructor(msg) {
    super(msg);
    this.name = 'SecurityError';
  }
}

async function chainStep(chainKey) {
  const k = await subtle.importKey('raw', chainKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const messageKey = new Uint8Array(await subtle.sign('HMAC', k, Uint8Array.of(1)));
  const nextChain = new Uint8Array(await subtle.sign('HMAC', k, Uint8Array.of(2)));
  return [messageKey, nextChain];
}

function formatSafetyNumber(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const groups = [];
  for (let i = 0; i < 6; i++) groups.push(String(view.getUint32(i * 4) % 100000).padStart(5, '0'));
  return groups.join(' ');
}

function nonceFor(n) {
  const iv = new Uint8Array(12);
  new DataView(iv.buffer).setUint32(8, n);
  return iv;
}

function aadFor(direction, n) {
  const head = textEncoder.encode(`GhostP2 msg v${PROTOCOL_VERSION} ${direction}`);
  const ctr = new Uint8Array(4);
  new DataView(ctr.buffer).setUint32(0, n);
  return concatBytes(head, ctr);
}

function pad(plain) {
  const total = Math.ceil((plain.length + 4) / PAD_BLOCK) * PAD_BLOCK;
  const out = new Uint8Array(total); // relleno con ceros (va cifrado)
  new DataView(out.buffer).setUint32(0, plain.length);
  out.set(plain, 4);
  return out;
}

function unpad(padded) {
  if (padded.length < 4) throw new SecurityError('Relleno inválido');
  const len = new DataView(padded.buffer, padded.byteOffset, padded.byteLength).getUint32(0);
  if (len > padded.length - 4) throw new SecurityError('Relleno inválido');
  return padded.subarray(4, 4 + len);
}

export class SecureSession {
  static async create(psk = null) {
    const s = new SecureSession();
    if (psk !== null && psk.length !== 32) throw new Error('psk debe tener 32 bytes');
    s.psk = psk ? new Uint8Array(psk) : new Uint8Array(32);
    s.hasPsk = psk !== null;
    s.keyPair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    s.publicRaw = new Uint8Array(await subtle.exportKey('raw', s.keyPair.publicKey));
    return s;
  }

  constructor() {
    this.ready = false;
    this.destroyed = false;
    this.safetyNumber = null;
    this._sendQueue = Promise.resolve();
    this._recvQueue = Promise.resolve();
  }

  helloMessage() {
    return JSON.stringify({ t: 'hello', v: PROTOCOL_VERSION, pub: b64encode(this.publicRaw) });
  }

  async acceptHello(text) {
    if (this.destroyed) throw new SecurityError('Sesión destruida');
    if (this.ready) throw new SecurityError('Handshake duplicado');
    let m;
    try { m = JSON.parse(text); } catch { throw new SecurityError('Hello mal formado'); }
    if (!m || m.t !== 'hello' || m.v !== PROTOCOL_VERSION || typeof m.pub !== 'string') {
      throw new SecurityError('Versión de protocolo incompatible');
    }
    const peerRaw = b64decode(m.pub);
    if (peerRaw.length !== 65 || peerRaw[0] !== 4) throw new SecurityError('Clave pública inválida');
    if (compareBytes(peerRaw, this.publicRaw) === 0) throw new SecurityError('Ataque de reflexión detectado');

    // importKey valida que el punto pertenece a la curva.
    const peerKey = await subtle.importKey('raw', peerRaw, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const shared = new Uint8Array(await subtle.deriveBits({ name: 'ECDH', public: peerKey }, this.keyPair.privateKey, 256));

    const iAmLow = compareBytes(this.publicRaw, peerRaw) < 0;
    const low = iAmLow ? this.publicRaw : peerRaw;
    const high = iAmLow ? peerRaw : this.publicRaw;
    const info = concatBytes(textEncoder.encode(`GhostP2 session v${PROTOCOL_VERSION}`), low, high);
    const okm = await hkdf(shared, this.psk, info, 96);
    shared.fill(0);

    const lowToHigh = okm.slice(0, 32);
    const highToLow = okm.slice(32, 64);
    this.sendChain = iAmLow ? lowToHigh : highToLow;
    this.recvChain = iAmLow ? highToLow : lowToHigh;
    this.sendDir = iAmLow ? 'L>H' : 'H>L';
    this.recvDir = iAmLow ? 'H>L' : 'L>H';
    this.sendN = 0;
    this.recvN = 0;
    this.safetyNumber = formatSafetyNumber(okm.subarray(64, 96));
    okm.fill(0);
    this.psk.fill(0);
    this.keyPair = null; // la clave privada efímera deja de ser accesible
    this.ready = true;
  }

  encrypt(obj) {
    const job = this._sendQueue.then(() => this._encrypt(obj));
    this._sendQueue = job.catch(() => {});
    return job;
  }

  decrypt(frame) {
    const job = this._recvQueue.then(() => this._decrypt(frame));
    this._recvQueue = job.catch(() => {});
    return job;
  }

  async _encrypt(obj) {
    if (!this.ready || this.destroyed) throw new SecurityError('Sesión no establecida');
    const plain = textEncoder.encode(JSON.stringify(obj));
    if (plain.length > MAX_PLAINTEXT) throw new Error('Mensaje demasiado largo');
    if (this.sendN >= 0xffffffff) throw new SecurityError('Contador agotado');
    const n = this.sendN;
    const [mk, next] = await chainStep(this.sendChain);
    this.sendChain.fill(0);
    this.sendChain = next;
    this.sendN = n + 1;
    const key = await subtle.importKey('raw', mk, 'AES-GCM', false, ['encrypt']);
    mk.fill(0);
    const ct = new Uint8Array(await subtle.encrypt(
      { name: 'AES-GCM', iv: nonceFor(n), additionalData: aadFor(this.sendDir, n) }, key, pad(plain)));
    const header = new Uint8Array(HEADER_LEN);
    header[0] = PROTOCOL_VERSION;
    new DataView(header.buffer).setUint32(1, n);
    return concatBytes(header, ct);
  }

  async _decrypt(frame) {
    if (!this.ready || this.destroyed) throw new SecurityError('Sesión no establecida');
    const bytes = frame instanceof Uint8Array ? frame : new Uint8Array(frame);
    if (bytes.length < HEADER_LEN + 16 || bytes.length > HEADER_LEN + MAX_PLAINTEXT + PAD_BLOCK + 16) {
      throw new SecurityError('Tamaño de trama inválido');
    }
    if (bytes[0] !== PROTOCOL_VERSION) throw new SecurityError('Versión desconocida');
    const n = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(1);
    if (n !== this.recvN) throw new SecurityError('Contador inesperado (repetición o reordenación)');
    const [mk, next] = await chainStep(this.recvChain);
    const key = await subtle.importKey('raw', mk, 'AES-GCM', false, ['decrypt']);
    mk.fill(0);
    let padded;
    try {
      padded = new Uint8Array(await subtle.decrypt(
        { name: 'AES-GCM', iv: nonceFor(n), additionalData: aadFor(this.recvDir, n) }, key, bytes.subarray(HEADER_LEN)));
    } catch {
      next.fill(0);
      throw new SecurityError('Autenticación fallida: mensaje manipulado o claves distintas');
    }
    // Solo se avanza la cadena si el mensaje es auténtico.
    this.recvChain.fill(0);
    this.recvChain = next;
    this.recvN = n + 1;
    try {
      return JSON.parse(textDecoder.decode(unpad(padded)));
    } catch (e) {
      throw e instanceof SecurityError ? e : new SecurityError('Contenido mal formado');
    }
  }

  destroy() {
    this.destroyed = true;
    this.ready = false;
    this.sendChain?.fill(0);
    this.recvChain?.fill(0);
    this.psk?.fill(0);
    this.keyPair = null;
  }
}
