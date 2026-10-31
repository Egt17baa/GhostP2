// Envoltorio de RTCPeerConnection + un único DataChannel ordenado y fiable.
// Eventos: 'open', 'close', 'message' (detail = string | ArrayBuffer),
//          'candidate' (detail = RTCIceCandidateInit), 'state' (detail = connectionState)

export function parseIceServers(text) {
  const servers = [];
  for (const rawLine of String(text ?? '').split(/\n|,/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    // turn:usuario:clave@host:puerto  |  stun:host:puerto
    const m = line.match(/^(turns?):([^:@]+):([^@]+)@(.+)$/);
    if (m) servers.push({ urls: `${m[1]}:${m[4]}`, username: decodeURIComponent(m[2]), credential: decodeURIComponent(m[3]) });
    else if (/^(stun|turns?):/.test(line)) servers.push({ urls: line });
    else throw new Error(`Servidor ICE no válido: ${line}`);
  }
  return servers;
}

export class PeerLink extends EventTarget {
  constructor({ iceServers = [], relayOnly = false } = {}) {
    super();
    this.pc = new RTCPeerConnection({
      iceServers,
      iceTransportPolicy: relayOnly ? 'relay' : 'all',
      bundlePolicy: 'max-bundle',
    });
    this.channel = null;
    this.closed = false;
    this._pendingCandidates = [];
    this.pc.onicecandidate = (e) => {
      if (e.candidate) this._emit('candidate', e.candidate.toJSON());
    };
    this.pc.onconnectionstatechange = () => {
      const st = this.pc.connectionState;
      this._emit('state', st);
      if (st === 'failed' || st === 'closed') this.close();
    };
    this.pc.ondatachannel = (e) => {
      if (this.channel) {
        e.channel.close(); // solo se acepta un canal
        return;
      }
      this._setupChannel(e.channel);
    };
  }

  _emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  _setupChannel(ch) {
    this.channel = ch;
    ch.binaryType = 'arraybuffer';
    ch.onopen = () => this._emit('open');
    ch.onclose = () => this.close();
    ch.onerror = () => this.close();
    ch.onmessage = (e) => this._emit('message', e.data);
  }

  async createOffer() {
    this._setupChannel(this.pc.createDataChannel('ghostp2', { ordered: true }));
    await this.pc.setLocalDescription(await this.pc.createOffer());
    return this.pc.localDescription.toJSON();
  }

  async acceptOffer(desc) {
    if (desc?.type !== 'offer') throw new Error('Se esperaba una oferta');
    await this.pc.setRemoteDescription(desc);
    await this._flushCandidates();
    await this.pc.setLocalDescription(await this.pc.createAnswer());
    return this.pc.localDescription.toJSON();
  }

  async acceptAnswer(desc) {
    if (desc?.type !== 'answer') throw new Error('Se esperaba una respuesta');
    if (this.pc.signalingState !== 'have-local-offer') throw new Error('No hay ninguna invitación pendiente');
    await this.pc.setRemoteDescription(desc);
    await this._flushCandidates();
  }

  async addCandidate(candidate) {
    if (!this.pc.remoteDescription) {
      this._pendingCandidates.push(candidate);
      return;
    }
    try { await this.pc.addIceCandidate(candidate); } catch { /* candidato inválido: se ignora */ }
  }

  async _flushCandidates() {
    const list = this._pendingCandidates.splice(0);
    for (const c of list) await this.addCandidate(c);
  }

  // Para el modo manual: espera a reunir todos los candidatos (sin "trickle").
  waitIceGathering(timeoutMs = 6000) {
    if (this.pc.iceGatheringState === 'complete') return Promise.resolve(this.pc.localDescription.toJSON());
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        this.pc.removeEventListener('icegatheringstatechange', check);
        resolve(this.pc.localDescription.toJSON());
      };
      const check = () => { if (this.pc.iceGatheringState === 'complete') done(); };
      const timer = setTimeout(done, timeoutMs);
      this.pc.addEventListener('icegatheringstatechange', check);
    });
  }

  get isOpen() {
    return !this.closed && this.channel?.readyState === 'open';
  }

  send(data) {
    if (!this.isOpen) throw new Error('Canal cerrado');
    this.channel.send(data);
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    try { this.channel?.close(); } catch { /* ya cerrado */ }
    try { this.pc.close(); } catch { /* ya cerrado */ }
    this._emit('close');
  }
}
