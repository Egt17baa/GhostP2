// Genera dist/ghostp2.html: la app completa en UN solo archivo, sin dependencias,
// que funciona offline abriéndolo directamente (file://). La CSP lleva el hash
// SHA-256 del script y del estilo incrustados, así que nada más puede ejecutarse.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const clientDir = path.join(root, 'client');
const order = ['util', 'crypto', 'session', 'codec', 'peer', 'signaling', 'app'];

function bundleModule(name) {
  const src = fs.readFileSync(path.join(clientDir, 'js', `${name}.js`), 'utf8');
  const exportsList = [];
  const body = src
    .replace(/^import\s*\{([^}]*)\}\s*from\s*'\.\/([\w-]+)\.js';\s*$/gm, (_, names, mod) => {
      if (!order.includes(mod)) throw new Error(`Módulo desconocido: ${mod}`);
      return `const {${names}} = __mod_${mod};`;
    })
    .replace(/^export\s+((?:async\s+)?function\*?|class|const|let)\s+([A-Za-z_$][\w$]*)/gm, (_, kind, id) => {
      exportsList.push(id);
      return `${kind} ${id}`;
    });
  if (/^\s*(import|export)\b/m.test(body)) throw new Error(`Sintaxis de módulo no soportada en ${name}.js`);
  return `const __mod_${name} = (() => {\n${body}\nreturn { ${exportsList.join(', ')} };\n})();\n`;
}

const js = `'use strict';\n(() => {\n${order.map(bundleModule).join('\n')}\n})();\n`;
const css = fs.readFileSync(path.join(clientDir, 'style.css'), 'utf8');
const sha = (s) => `'sha256-${crypto.createHash('sha256').update(s, 'utf8').digest('base64')}'`;

let html = fs.readFileSync(path.join(clientDir, 'index.html'), 'utf8');
const csp = `default-src 'none'; script-src ${sha(js)}; style-src ${sha(css)}; img-src data:; connect-src ws: wss:; base-uri 'none'; form-action 'none'`;
html = html
  .replace(/<meta http-equiv="Content-Security-Policy"[^>]*>/, `<meta http-equiv="Content-Security-Policy" content="${csp}">`)
  .replace('<link rel="stylesheet" href="style.css">', () => `<style>${css}</style>`)
  .replace('<script type="module" src="js/app.js"></script>', '')
  .replace('</body>', () => `<script>${js}</script>\n</body>`);

if (html.includes('src="js/') || html.includes('href="style.css"')) throw new Error('Quedaron referencias externas');

const outDir = path.join(root, 'dist');
fs.mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, 'ghostp2.html');
fs.writeFileSync(outFile, html);
const digest = crypto.createHash('sha256').update(html).digest('hex');
fs.writeFileSync(path.join(outDir, 'ghostp2.html.sha256'), `${digest}  ghostp2.html\n`);
console.log(`Generado ${path.relative(root, outFile)} (${html.length} bytes)\nSHA-256: ${digest}`);
