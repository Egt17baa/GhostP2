// Interfaz de GhostP2. Nada de lo que se escribe o recibe se guarda en disco:
// los mensajes viven solo en la memoria de esta pestaña.

import { toHex, randomBytes } from './util.js';
import { generateCode, isValidCode, normalizeCode, formatCode, deriveRoomSecrets } from './crypto.js';
import { SecureSession, MAX_PLAINTEXT } from './session.js';
import { PeerLink, parseIceServers } from './peer.js';
import { SignalingClient } from './signaling.js';
import { encodeDescription, decodeDescription } from './codec.js';

const $ = (id) => document.getElementById(id);

const MAX_TEXT = 8000;
const ACK_TIMEOUT_MS = 15000;
const HEARTBEAT_MS = 4000;
const PEER_TIMEOUT_MS = 15000;
const DEFAULT_ICE = 'stun:stun.l.google.com:19302\nstun:stun.cloudflare.com:3478';

const state = {
  mode: null, // 'code' | 'manual'
  psk: null,
  signaling: null,
  peer: null,
  session: null,
  secured: false,
  everConnected: false,
  pending: new Map(), // id -> { li, timer }
  lastSeen: 0,
  heartbeat: null,
  queue: Promise.resolve(),
};

// ---------- Ajustes (solo configuración; jamás mensajes ni claves) ----------

function loadSetting(key, fallback) {
  try { return localStorage.getItem(`ghostp2.${key}`) ?? fallback; } catch { return fallback; }
}
function saveSetting(key, value) {
  try { localStorage.setItem(`ghostp2.${key}`, value); } catch { /* sin almacenamiento */ }
}

function defaultSignalUrl() {
  if (location.protocol === 'https:') return `wss://${location.host}/ws`;
  if (location.protocol === 'http:') return `ws://${location.host}/ws`;
  return '';
}

function initSettings() {
  $('signal-url').value = loadSetting('signalUrl', defaultSignalUrl());
  $('ice-servers').value = loadSetting('iceServers', DEFAULT_ICE);
  $('relay-only').checked = loadSetting('relayOnly', '0') === '1';
  $('signal-url').addEventListener('change', () => saveSetting('signalUrl', $('signal-url').value.trim()));
  $('ice-servers').addEventListener('change', () => saveSetting('iceServers', $('ice-servers').value));
  $('relay-only').addEventListener('change', () => saveSetting('relayOnly', $('relay-only').checked ? '1' : '0'));
}

function iceConfig() {
  return { iceServers: parseIceServers($('ice-servers').value), relayOnly: $('relay-only').checked };
}

// ---------- Utilidades de interfaz ----------

function setStatus(text, kind = 'info') {
  const el = $('setup-status');
  el.textContent = text;
  el.dataset.kind = kind;
}

function showScreen(name) {
  $('setup').hidden = name !== 'setup';
  $('chat').hidden = name !== 'chat';
  if (name === 'chat') $('message-input').focus();
}

async function copyText(text, button) {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = document.createElement('textarea');
    ta.value = text;
    document.body.append(ta);
    ta.select();
    try { document.execCommand('copy'); } catch { /* sin portapapeles */ }
    ta.remove();
  }
  const old = button.textContent;
  button.textContent = 'Copiado ✓';
  setTimeout(() => { button.textContent = old; }, 1500);
}

function timeLabel() {
  const d = new Date();
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function addMessage(direction, text) {
  const li = document.createElement('li');
  li.className = `msg ${direction}`;
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text; // nunca innerHTML: el texto ajeno no se interpreta
  const meta = document.createElement('div');
  meta.className = 'meta';
  meta.textContent = timeLabel();
  li.append(bubble, meta);
  const list = $('messages');
  list.append(li);
  while (list.children.length > 500) list.firstElementChild.remove();
  list.scrollTop = list.scrollHeight;
  return li;
}

function addSystem(text, kind = 'info') {
  const li = document.createElement('li');
  li.className = `system ${kind}`;
  li.textContent = text;
  const list = $('messages');
  list.append(li);
  list.scrollTop = list.scrollHeight;
}

function setMeta(li, text, cls) {
  const meta = li.querySelector('.meta');
  meta.textContent = `${meta.textContent.slice(0, 5)} · ${text}`;
  li.classList.remove('sending', 'delivered', 'invalid');
  if (cls) li.classList.add(cls);
}

function markInvalid(li, reason) {
  setMeta(li, `✖ ${reason}`, 'invalid');
}

function setPeerState(kind, text) {
  $('peer-state').dataset.state = kind;
  $('peer-state-text').textContent = text;
  $('reconnect').hidden = kind === 'online';
}

// ---------- Ciclo de vida de la conexión ----------

function enqueue(fn) {
  state.queue = state.queue.then(fn).catch((e) => console.error(e));
  return state.queue;
}

function stopHeartbeat() {
  clearInterval(state.heartbeat);
  state.heartbeat = null;
}

function invalidatePending(reason) {
  for (const [id, p] of state.pending) {
    clearTimeout(p.timer);
    markInvalid(p.li, reason);
    state.pending.delete(id);
  }
}

function teardown() {
  stopHeartbeat();
  invalidatePending('Conexión cerrada antes de entregarse. Descartado.');
  const { peer, signaling, session } = state;
  state.peer = null;
  state.signaling = null;
  state.session = null;
  state.secured = false;
  session?.destroy();
  signaling?.close();
  peer?.close();
  state.queue = Promise.resolve();
}

function wipeEverything() {
  teardown();
  state.psk?.fill(0);
  state.psk = null;
  state.everConnected = false;
  $('messages').replaceChildren();
  $('safety-number').textContent = '';
  $('verify').textContent = 'Marcar como verificado';
  $('verify').classList.remove('verified');
  for (const id of ['manual-offer-out', 'manual-answer-in', 'manual-offer-in', 'manual-answer-out']) $(id).value = '';
}

function newPeer() {
  const peer = new PeerLink(iceConfig());
  peer.addEventListener('open', () => enqueue(() => onChannelOpen(peer)));
  peer.addEventListener('message', (e) => enqueue(() => onFrame(peer, e.detail)));
  peer.addEventListener('close', () => onPeerClosed(peer));
  peer.addEventListener('state', (e) => {
    if (e.detail === 'connecting' && !state.secured) setStatus('Estableciendo conexión directa P2P…');
  });
  return peer;
}

async function sendSecure(obj) {
  const { peer, session } = state;
  if (!peer?.isOpen || !session?.ready) throw new Error('No conectado');
  const frame = await session.encrypt(obj);
  peer.send(frame);
}

async function onChannelOpen(peer) {
  if (peer !== state.peer) return;
  setStatus('Canal directo abierto. Negociando claves efímeras…');
  state.session = await SecureSession.create(state.psk ? new Uint8Array(state.psk) : null);
  peer.send(state.session.helloMessage());
}

async function onFrame(peer, data) {
  if (peer !== state.peer || !state.session) return;
  const session = state.session;
  if (typeof data === 'string') {
    try {
      await session.acceptHello(data);
    } catch (e) {
      securityFailure(e.message);
      return;
    }
    $('safety-number').textContent = session.safetyNumber;
    await sendSecure({ t: 'confirm' });
    return;
  }
  let msg;
  try {
    msg = await session.decrypt(data);
  } catch (e) {
    securityFailure(state.mode === 'code' && !state.secured
      ? 'Las claves no coinciden: ¿habéis usado exactamente el mismo código?'
      : e.message);
    return;
  }
  state.lastSeen = Date.now();
  if (!msg || typeof msg.t !== 'string') return;

  if (msg.t === 'confirm') {
    if (!state.secured) onSecured();
    return;
  }
  if (!state.secured) return;

  switch (msg.t) {
    case 'msg':
      if (typeof msg.id !== 'string' || msg.id.length > 64 || typeof msg.text !== 'string' || msg.text.length > MAX_TEXT) return;
      addMessage('in', msg.text);
      await sendSecure({ t: 'ack', id: msg.id }).catch(() => {});
      break;
    case 'ack': {
      const p = state.pending.get(msg.id);
      if (p) {
        clearTimeout(p.timer);
        setMeta(p.li, '✓ entregado', 'delivered');
        state.pending.delete(msg.id);
      }
      break;
    }
    case 'bye':
      addSystem('Tu contacto ha cerrado la conversación.');
      peer.close();
      break;
    default:
      break; // 'ping' y tipos desconocidos
  }
}

function onSecured() {
  state.secured = true;
  state.everConnected = true;
  state.lastSeen = Date.now();
  // La señalización ya no hace falta: se cierra para no dejar rastro en el servidor.
  state.signaling?.close();
  state.signaling = null;
  showScreen('chat');
  setPeerState('online', 'Conectado · cifrado de extremo a extremo · P2P directo');
  addSystem(state.mode === 'manual'
    ? 'Conexión cifrada establecida sin ningún servidor. IMPORTANTE: comparad el número de seguridad por otro canal.'
    : 'Conexión cifrada establecida. El servidor de señalización ya se ha desconectado.', 'ok');
  stopHeartbeat();
  state.heartbeat = setInterval(() => {
    if (Date.now() - state.lastSeen > PEER_TIMEOUT_MS) {
      addSystem('Tu contacto no responde.');
      state.peer?.close();
      return;
    }
    sendSecure({ t: 'ping' }).catch(() => {});
  }, HEARTBEAT_MS);
}

function onPeerClosed(peer) {
  if (peer !== state.peer) return;
  const wasSecured = state.secured;
  stopHeartbeat();
  invalidatePending('Tu contacto se desconectó antes de recibirlo. Descartado.');
  state.secured = false;
  state.session?.destroy();
  state.session = null;
  state.peer = null;
  if (wasSecured || state.everConnected) {
    setPeerState('offline', 'Desconectado. Lo que envíes ahora NO se entregará.');
    addSystem('Conexión cerrada. Las claves de esta sesión se han destruido.', 'warn');
  } else if (state.signaling && !state.signaling.closed) {
    setStatus('La conexión directa falló. Esperando a que tu contacto vuelva a intentarlo…', 'warn');
  } else {
    setStatus('No se pudo establecer la conexión directa. Revisa los ajustes de red (STUN/TURN).', 'error');
  }
}

function securityFailure(reason) {
  const text = `Alerta de seguridad: ${reason}. Conexión cortada.`;
  if (state.everConnected) addSystem(text, 'error');
  setStatus(text, 'error');
  state.peer?.close();
}

// ---------- Modo con código + servidor de señalización ----------

async function connectWithCode() {
  const code = $('code-input').value;
  if (!isValidCode(code)) {
    setStatus('El código no es válido. Debe tener 20 caracteres (puedes generarlo con «Generar»).', 'error');
    return;
  }
  const url = $('signal-url').value.trim();
  if (!/^wss?:\/\//.test(url)) {
    setStatus('Indica la dirección del servidor de señalización (ws:// o wss://) o usa el modo manual.', 'error');
    return;
  }
  try { iceConfig(); } catch (e) { setStatus(e.message, 'error'); return; }
  wipeEverything();
  $('code-input').value = formatCode(normalizeCode(code));
  state.mode = 'code';
  setStatus('Derivando claves a partir del código…');
  const secrets = await deriveRoomSecrets(code);
  state.psk = secrets.psk;
  attachSignaling(url, secrets);
}

function attachSignaling(url, secrets) {
  const sig = new SignalingClient(url, secrets);
  state.signaling = sig;
  const startOffer = async () => {
    state.peer?.close();
    const peer = newPeer();
    state.peer = peer;
    peer.addEventListener('candidate', (e) => sig.send({ k: 'ice', c: e.detail }));
    const offer = await peer.createOffer();
    await sig.send({ k: 'offer', d: offer });
  };
  sig.addEventListener('joined', (e) => {
    setStatus(e.detail === 0
      ? 'Esperando a tu contacto… Pásale el código por un canal de confianza.'
      : 'Contacto encontrado. Conectando…');
  });
  sig.addEventListener('peer-joined', () => {
    if (sig !== state.signaling) return;
    setStatus('Contacto encontrado. Conectando…');
    enqueue(startOffer);
  });
  sig.addEventListener('signal', (e) => enqueue(async () => {
    if (sig !== state.signaling) return;
    const m = e.detail;
    if (m.k === 'offer') {
      state.peer?.close();
      const peer = newPeer();
      state.peer = peer;
      peer.addEventListener('candidate', (ev) => sig.send({ k: 'ice', c: ev.detail }));
      const answer = await peer.acceptOffer(m.d);
      await sig.send({ k: 'answer', d: answer });
    } else if (m.k === 'answer') {
      await state.peer?.acceptAnswer(m.d);
    } else if (m.k === 'ice' && m.c) {
      await state.peer?.addCandidate(m.c);
    }
  }));
  sig.addEventListener('peer-left', () => {
    if (sig !== state.signaling || state.secured) return;
    state.peer?.close();
    setStatus('Tu contacto se fue. Esperando a que vuelva…', 'warn');
  });
  sig.addEventListener('error', (e) => setStatus(e.detail, 'error'));
  sig.addEventListener('close', () => {
    if (sig === state.signaling && !state.secured) {
      setStatus('Desconectado del servidor de señalización.', 'error');
    }
  });
  sig.connect();
}

// ---------- Modo manual (cero servidores) ----------

function startManual() {
  wipeEverything();
  state.mode = 'manual';
  try { state.peer = newPeer(); } catch (e) { setStatus(e.message, 'error'); return null; }
  return state.peer;
}

async function manualCreate() {
  const peer = startManual();
  if (!peer) return;
  setStatus('Preparando invitación…');
  await peer.createOffer();
  const desc = await peer.waitIceGathering();
  if (peer !== state.peer) return;
  $('manual-offer-out').value = await encodeDescription(desc);
  setStatus('Copia la invitación, envíasela a tu contacto y pega abajo su respuesta.');
}

async function manualApplyAnswer() {
  if (!state.peer || state.mode !== 'manual') {
    setStatus('Primero crea una invitación.', 'error');
    return;
  }
  try {
    const desc = await decodeDescription($('manual-answer-in').value, 'answer');
    await state.peer.acceptAnswer(desc);
    setStatus('Respuesta aceptada. Conectando…');
  } catch (e) {
    setStatus(e.message, 'error');
  }
}

async function manualAccept() {
  let desc;
  try {
    desc = await decodeDescription($('manual-offer-in').value, 'offer');
  } catch (e) {
    setStatus(e.message, 'error');
    return;
  }
  const offerText = $('manual-offer-in').value;
  const peer = startManual();
  if (!peer) return;
  $('manual-offer-in').value = offerText;
  setStatus('Generando respuesta…');
  try {
    await peer.acceptOffer(desc);
  } catch {
    setStatus('No se pudo usar esa invitación.', 'error');
    return;
  }
  const answer = await peer.waitIceGathering();
  if (peer !== state.peer) return;
  $('manual-answer-out').value = await encodeDescription(answer);
  setStatus('Copia la respuesta y envíasela a quien te invitó. La conexión se abrirá sola.');
}

// ---------- Envío de mensajes ----------

async function sendMessage(text) {
  const li = addMessage('out', text);
  if (!state.secured || !state.peer?.isOpen) {
    markInvalid(li, 'No entregado: tu contacto no está conectado. Mensaje descartado.');
    return;
  }
  const id = toHex(randomBytes(12));
  setMeta(li, 'enviando…', 'sending');
  const timer = setTimeout(() => {
    if (state.pending.delete(id)) markInvalid(li, 'Sin confirmación de entrega. Invalidado.');
  }, ACK_TIMEOUT_MS);
  state.pending.set(id, { li, timer });
  try {
    await sendSecure({ t: 'msg', id, text });
  } catch {
    clearTimeout(timer);
    state.pending.delete(id);
    markInvalid(li, 'No se pudo enviar. Mensaje descartado.');
  }
}

// ---------- Arranque ----------

function initTabs() {
  for (const btn of document.querySelectorAll('[data-tab]')) {
    btn.addEventListener('click', () => {
      for (const b of document.querySelectorAll('[data-tab]')) {
        const on = b === btn;
        b.setAttribute('aria-selected', String(on));
        $(`tab-${b.dataset.tab}`).hidden = !on;
      }
    });
  }
}

function init() {
  if (!globalThis.crypto?.subtle || !globalThis.RTCPeerConnection) {
    setStatus('Este navegador no es compatible (se necesita Web Crypto y WebRTC en un contexto seguro: https, localhost o archivo local).', 'error');
    return;
  }
  initSettings();
  initTabs();

  $('code-generate').addEventListener('click', () => { $('code-input').value = generateCode(); });
  $('code-copy').addEventListener('click', (e) => copyText($('code-input').value, e.currentTarget));
  $('code-form').addEventListener('submit', (e) => {
    e.preventDefault();
    connectWithCode().catch((err) => setStatus(err.message, 'error'));
  });

  $('manual-create').addEventListener('click', () => manualCreate().catch((e) => setStatus(e.message, 'error')));
  $('manual-answer-apply').addEventListener('click', () => manualApplyAnswer());
  $('manual-accept').addEventListener('click', () => manualAccept().catch((e) => setStatus(e.message, 'error')));
  $('manual-offer-copy').addEventListener('click', (e) => copyText($('manual-offer-out').value, e.currentTarget));
  $('manual-answer-copy').addEventListener('click', (e) => copyText($('manual-answer-out').value, e.currentTarget));

  $('composer').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = $('message-input');
    const text = input.value.trim();
    if (!text) return;
    if (text.length > MAX_TEXT || new TextEncoder().encode(text).length > MAX_PLAINTEXT - 256) {
      addSystem(`Mensaje demasiado largo (máximo ${MAX_TEXT} caracteres).`, 'error');
      return;
    }
    input.value = '';
    sendMessage(text);
  });
  $('message-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      $('composer').requestSubmit();
    }
  });

  $('verify').addEventListener('click', () => {
    const btn = $('verify');
    const on = !btn.classList.contains('verified');
    btn.classList.toggle('verified', on);
    btn.textContent = on ? 'Verificado ✓' : 'Marcar como verificado';
  });

  $('disconnect').addEventListener('click', () => {
    if (state.secured) sendSecure({ t: 'bye' }).catch(() => {});
    setTimeout(() => {
      wipeEverything();
      showScreen('setup');
      setStatus('Conversación borrada. No queda nada guardado.');
    }, 50);
  });

  $('reconnect').addEventListener('click', () => {
    wipeEverything();
    showScreen('setup');
    setStatus('Vuelve a conectar con el mismo código (o crea una invitación nueva).');
  });

  window.addEventListener('pagehide', () => {
    if (state.secured) sendSecure({ t: 'bye' }).catch(() => {});
  });

  setStatus('Listo. Genera un código o usa el modo manual.');
}

init();
