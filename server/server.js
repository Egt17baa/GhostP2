#!/usr/bin/env node
// Servidor de señalización de GhostP2 (opcional, autoalojable, público o privado).
//
// - Solo reenvía blobs cifrados entre como mucho DOS clientes de la misma sala.
// - Nunca guarda nada en disco, no registra IPs ni contenidos.
// - Las salas se identifican por un hash de 256 bits: el servidor no conoce el código.
// - Opcionalmente sirve la aplicación web (carpeta client/) con cabeceras estrictas.
//
// Variables de entorno:
//   PORT (8080), HOST (0.0.0.0), GHOSTP2_STATIC (1 = servir la app, 0 = solo señalización),
//   GHOSTP2_ALLOWED_ORIGINS (lista separada por comas; vacío = cualquiera),
//   GHOSTP2_TRUST_PROXY (1 = usar X-Forwarded-For para el límite por IP)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const here = path.dirname(fileURLToPath(import.meta.url));

const LIMITS = {
  maxPayload: 32 * 1024,
  maxPerIp: 16,
  burst: 40, // mensajes
  refillPerSec: 8,
  unpairedTimeoutMs: 15 * 60 * 1000,
  maxLifetimeMs: 60 * 60 * 1000,
  heartbeatMs: 30 * 1000,
};

export const SECURITY_HEADERS = {
  'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' ws: wss:; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), interest-cohort=()',
};

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

function serveStatic(rootDir, req, res) {
  const headers = { ...SECURITY_HEADERS };
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, headers).end();
    return;
  }
  let urlPath;
  try {
    urlPath = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  } catch {
    res.writeHead(400, headers).end();
    return;
  }
  if (urlPath.endsWith('/')) urlPath += 'index.html';
  const filePath = path.resolve(rootDir, '.' + path.posix.normalize(urlPath));
  if (!filePath.startsWith(rootDir + path.sep) || urlPath.includes('\0')) {
    res.writeHead(404, headers).end();
    return;
  }
  fs.stat(filePath, (err, st) => {
    const type = MIME[path.extname(filePath)];
    if (err || !st.isFile() || !type) {
      res.writeHead(404, { ...headers, 'Content-Type': 'text/plain; charset=utf-8' }).end('No encontrado');
      return;
    }
    res.writeHead(200, { ...headers, 'Content-Type': type, 'Content-Length': st.size, 'Cache-Control': 'no-cache' });
    if (req.method === 'HEAD') res.end();
    else fs.createReadStream(filePath).pipe(res);
  });
}

export function createSignalingServer({
  port = 8080,
  host = '0.0.0.0',
  staticDir = path.resolve(here, '../client'),
  serveApp = true,
  allowedOrigins = [],
  trustProxy = false,
  limits = {},
} = {}) {
  const L = { ...LIMITS, ...limits };
  const rooms = new Map(); // roomId -> Set<ws>
  const perIp = new Map();
  const rootDir = path.resolve(staticDir);

  const server = http.createServer((req, res) => {
    if (req.url === '/healthz') {
      res.writeHead(200, { ...SECURITY_HEADERS, 'Content-Type': 'text/plain' }).end('ok');
      return;
    }
    if (serveApp) serveStatic(rootDir, req, res);
    else res.writeHead(404, SECURITY_HEADERS).end();
  });

  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: L.maxPayload, perMessageDeflate: false });

  const send = (ws, obj) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
  };

  const leave = (ws) => {
    if (!ws.room) return;
    const set = rooms.get(ws.room);
    if (set) {
      set.delete(ws);
      for (const other of set) send(other, { type: 'peer-left' });
      if (set.size === 0) rooms.delete(ws.room);
    }
    ws.room = null;
  };

  wss.on('connection', (ws, req) => {
    const origin = req.headers.origin || '';
    if (allowedOrigins.length && !allowedOrigins.includes(origin)) {
      ws.close(1008, 'origin');
      return;
    }
    const ip = (trustProxy && String(req.headers['x-forwarded-for'] || '').split(',')[0].trim()) || req.socket.remoteAddress || '?';
    const count = (perIp.get(ip) || 0) + 1;
    if (count > L.maxPerIp) {
      ws.close(1008, 'too-many');
      return;
    }
    perIp.set(ip, count);

    ws.isAlive = true;
    ws.room = null;
    ws.tokens = L.burst;
    ws.lastRefill = Date.now();
    ws.on('pong', () => { ws.isAlive = true; });

    const unpaired = setTimeout(() => {
      const set = ws.room && rooms.get(ws.room);
      if (!set || set.size < 2) ws.close(1000, 'timeout');
    }, L.unpairedTimeoutMs);
    const lifetime = setTimeout(() => ws.close(1000, 'lifetime'), L.maxLifetimeMs);

    ws.on('message', (data, isBinary) => {
      const now = Date.now();
      ws.tokens = Math.min(L.burst, ws.tokens + ((now - ws.lastRefill) / 1000) * L.refillPerSec);
      ws.lastRefill = now;
      if (ws.tokens < 1) {
        ws.close(1008, 'rate');
        return;
      }
      ws.tokens -= 1;
      if (isBinary) return;
      let m;
      try { m = JSON.parse(data.toString('utf8')); } catch { return; }
      if (!m || typeof m !== 'object') return;

      if (m.type === 'join') {
        if (ws.room) return;
        if (typeof m.room !== 'string' || !/^[0-9a-f]{64}$/.test(m.room)) {
          send(ws, { type: 'error', reason: 'bad-room' });
          ws.close(1008, 'bad-room');
          return;
        }
        const set = rooms.get(m.room) || new Set();
        if (set.size >= 2) {
          send(ws, { type: 'error', reason: 'room-full' });
          ws.close(1008, 'room-full');
          return;
        }
        const peers = set.size;
        set.add(ws);
        rooms.set(m.room, set);
        ws.room = m.room;
        send(ws, { type: 'joined', peers });
        for (const other of set) if (other !== ws) send(other, { type: 'peer-joined' });
        return;
      }

      if (m.type === 'signal') {
        if (!ws.room || typeof m.data !== 'string' || m.data.length > L.maxPayload) return;
        const set = rooms.get(ws.room);
        if (!set) return;
        for (const other of set) if (other !== ws) send(other, { type: 'signal', data: m.data });
      }
    });

    ws.on('close', () => {
      clearTimeout(unpaired);
      clearTimeout(lifetime);
      leave(ws);
      const c = (perIp.get(ip) || 1) - 1;
      if (c <= 0) perIp.delete(ip);
      else perIp.set(ip, c);
    });
    ws.on('error', () => ws.terminate());
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, L.heartbeatMs);

  return new Promise((resolve) => {
    server.listen(port, host, () => {
      resolve({
        server,
        wss,
        rooms,
        port: server.address().port,
        close: () => new Promise((r) => {
          clearInterval(heartbeat);
          for (const ws of wss.clients) ws.terminate();
          wss.close();
          server.close(() => r());
        }),
      });
    });
  });
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const env = process.env;
  const srv = await createSignalingServer({
    port: Number(env.PORT) || 8080,
    host: env.HOST || '0.0.0.0',
    serveApp: env.GHOSTP2_STATIC !== '0',
    allowedOrigins: (env.GHOSTP2_ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean),
    trustProxy: env.GHOSTP2_TRUST_PROXY === '1',
  });
  console.log(`GhostP2 escuchando en el puerto ${srv.port} (señalización en /ws)`);
  const stop = () => srv.close().then(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
