// Prueba de extremo a extremo con dos navegadores reales (Playwright + Chromium).
// Se omite si Playwright no está disponible.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createSignalingServer } from '../server/server.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let chromium = null;
try {
  ({ chromium } = createRequire(import.meta.url)('playwright'));
} catch { /* sin playwright */ }

const skip = chromium ? false : 'Playwright no disponible';

async function setup(t) {
  const browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns'] });
  t.after(() => browser.close());
  const open = async (url) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    await page.goto(url);
    // Sin STUN externo: la prueba usa solo la red local.
    await page.locator('#advanced summary').click();
    await page.fill('#ice-servers', '');
    await page.locator('#ice-servers').dispatchEvent('change');
    page.errors = errors;
    return page;
  };
  return { open };
}

async function expectConnected(a, b) {
  await a.locator('#chat').waitFor({ state: 'visible', timeout: 20000 });
  await b.locator('#chat').waitFor({ state: 'visible', timeout: 20000 });
  const sa = await a.textContent('#safety-number');
  const sb = await b.textContent('#safety-number');
  assert.match(sa, /^(\d{5} ){5}\d{5}$/);
  assert.equal(sa, sb);
}

async function send(page, text) {
  await page.fill('#message-input', text);
  await page.press('#message-input', 'Enter');
}

test('modo código: conexión, mensajes, entrega y descarte al desconectar', { skip, timeout: 90000 }, async (t) => {
  const srv = await createSignalingServer({ port: 0, host: '127.0.0.1' });
  t.after(() => srv.close());
  const { open } = await setup(t);
  const base = `http://127.0.0.1:${srv.port}/`;
  const a = await open(base);
  const b = await open(base);

  await a.click('#code-generate');
  const code = await a.inputValue('#code-input');
  await a.click('#code-connect');
  await a.locator('#setup-status', { hasText: 'Esperando' }).waitFor();
  await b.fill('#code-input', code.toLowerCase());
  await b.click('#code-connect');
  await expectConnected(a, b);

  await send(a, 'hola <b>no soy HTML</b> 👻');
  await b.locator('.msg.in .bubble', { hasText: 'hola <b>no soy HTML</b> 👻' }).waitFor();
  await a.locator('.msg.out.delivered').waitFor();
  assert.equal(await b.locator('.msg.in b').count(), 0);
  await send(b, 'recibido');
  await a.locator('.msg.in .bubble', { hasText: 'recibido' }).waitFor();

  // El servidor ya no mantiene la sala: la señalización se cerró al conectar.
  assert.equal(srv.rooms.size, 0);

  await b.close();
  await a.locator('#peer-state[data-state="offline"]').waitFor({ timeout: 25000 });
  await send(a, '¿sigues ahí?');
  const last = a.locator('.msg.out').last();
  await last.locator('.meta', { hasText: 'no está conectado' }).waitFor();
  assert.ok(await last.evaluate((el) => el.classList.contains('invalid')));
  assert.deepEqual(a.errors, []);
});

test('códigos distintos nunca se conectan', { skip, timeout: 60000 }, async (t) => {
  const srv = await createSignalingServer({ port: 0, host: '127.0.0.1' });
  t.after(() => srv.close());
  const { open } = await setup(t);
  const base = `http://127.0.0.1:${srv.port}/`;
  const a = await open(base);
  const b = await open(base);
  await a.click('#code-generate');
  await a.click('#code-connect');
  await b.click('#code-generate');
  await b.click('#code-connect');
  await a.locator('#setup-status', { hasText: 'Esperando' }).waitFor();
  await b.locator('#setup-status', { hasText: 'Esperando' }).waitFor();
  await new Promise((r) => setTimeout(r, 1500));
  assert.ok(await a.locator('#chat').isHidden());
  assert.equal(srv.rooms.size, 2);
});

test('modo manual con el archivo único offline (sin ningún servidor)', { skip, timeout: 90000 }, async (t) => {
  execFileSync(process.execPath, [path.join(root, 'scripts/build.mjs')], { stdio: 'ignore' });
  const { open } = await setup(t);
  const url = pathToFileURL(path.join(root, 'dist/ghostp2.html')).href;
  const a = await open(url);
  const b = await open(url);
  for (const p of [a, b]) await p.click('[data-tab="manual"]');

  await a.click('#manual-create');
  await a.waitForFunction(() => document.getElementById('manual-offer-out').value.startsWith('GP2O.'));
  await b.fill('#manual-offer-in', await a.inputValue('#manual-offer-out'));
  await b.click('#manual-accept');
  await b.waitForFunction(() => document.getElementById('manual-answer-out').value.startsWith('GP2A.'));
  await a.fill('#manual-answer-in', await b.inputValue('#manual-answer-out'));
  await a.click('#manual-answer-apply');
  await expectConnected(a, b);

  await send(b, 'sin servidores');
  await a.locator('.msg.in .bubble', { hasText: 'sin servidores' }).waitFor();
  await b.locator('.msg.out.delivered').waitFor();

  await b.click('#disconnect');
  await b.locator('#setup').waitFor({ state: 'visible' });
  assert.equal(await b.locator('#messages li').count(), 0);
  await a.locator('#peer-state[data-state="offline"]').waitFor({ timeout: 25000 });
  assert.deepEqual(a.errors, []);
  assert.deepEqual(b.errors, []);
});
