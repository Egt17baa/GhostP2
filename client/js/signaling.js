// Cliente del servidor de señalización (opcional).
// El servidor solo ve un identificador de sala opaco (hash) y blobs cifrados con
// AES-GCM usando una clave derivada del código de emparejamiento. No puede leer
// ni modificar la señalización, ni saber quién habla con quién más allá de las IPs.
//
// Eventos: 'joined' (detail = nº de pares ya presentes), 'peer-joined', 'peer-left',
//          'signal' (detail = objeto descifrado), 'error' (detail = texto), 'close'

import { sealSignal, openSignal } from './crypto.js';

export class SignalingClient extends EventTarget {
  constructor(url, secrets) {
    super();
    this.url = url;
    this.secrets = secrets;
    this.ws = null;
    this.closed = false;
  }

  _emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  connect() {
    let ws;
    try {
      ws = new WebSocket(this.url);
    } catch {
      this._emit('error', 'URL del servidor de señalización no válida');
      this.close();
      return;
    }
    this.ws = ws;
    ws.onopen = () => ws.send(JSON.stringify({ type: 'join', room: this.secrets.roomId }));
    ws.onerror = () => this._emit('error', 'No se pudo contactar con el servidor de señalización');
    ws.onclose = () => this.close();
    ws.onmessage = async (e) => {
      let m;
      try { m = JSON.parse(e.data); } catch { return; }
      switch (m.type) {
        case 'joined': this._emit('joined', Number(m.peers) || 0); break;
        case 'peer-joined': this._emit('peer-joined'); break;
        case 'peer-left': this._emit('peer-left'); break;
        case 'error': this._emit('error', m.reason === 'room-full'
          ? 'La sala ya tiene dos personas conectadas' : `Error del servidor: ${m.reason}`); break;
        case 'signal':
          try {
            this._emit('signal', await openSignal(this.secrets.signalKey, this.secrets.roomId, m.data));
          } catch {
            // Señal que no se puede descifrar: alguien sin el código o un servidor malicioso.
          }
          break;
        default: break;
      }
    };
  }

  async send(obj) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const data = await sealSignal(this.secrets.signalKey, this.secrets.roomId, obj);
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ type: 'signal', data }));
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    try { this.ws?.close(); } catch { /* nada */ }
    this._emit('close');
  }
}
