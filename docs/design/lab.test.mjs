import assert from 'node:assert/strict';
import { before, after, beforeEach, test } from 'node:test';

let socket;
let counter = 0;
const pending = new Map();
const requests = [];
const errors = [];
const url = process.env.LAB_URL || new URL('./lab.html', import.meta.url).href;
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++counter;
  pending.set(id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params }));
});
const evaluate = async expression => {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  assert.equal(result.exceptionDetails, undefined, JSON.stringify(result.exceptionDetails));
  return result.result.value;
};
const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)})?.click()`);
const value = (selector, value) => evaluate(`(() => {
  const input = document.querySelector(${JSON.stringify(selector)});
  if (!input) return false;
  input.value = ${JSON.stringify(value)};
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

before(async () => {
  const targets = await (await fetch('http://127.0.0.1:9237/json')).json();
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }));
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Network.requestWillBeSent') requests.push(message.params.request.url);
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
    if (!message.id) return;
    const callback = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) callback.reject(message.error);
    else callback.resolve(message.result);
  });
  await send('Runtime.enable');
  await send('Network.enable');
});

beforeEach(async () => {
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url });
  for (let i = 0; i < 50; i++) {
    if (await evaluate('document.readyState === "complete" && !!document.querySelector("#tab-home")')) break;
    await wait(40);
  }
});

after(() => socket?.close());

test('server selection reaches home and switching disconnects the demo', async () => {
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length >= 3'), true);
  await click('[data-server="nl"]');
  assert.match(await evaluate('document.querySelector("#selected-name")?.textContent'), /Амстердам/);
  await click('#connect');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#connection-status")?.textContent'), /Подключено/);
  await click('#tab-servers');
  await click('[data-server="de"]');
  assert.match(await evaluate('document.querySelector("#connection-status")?.textContent'), /Отключено/);
});

test('connection can be cancelled before the simulated completion', async () => {
  await click('#connect');
  await click('#connect');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#connection-status")?.textContent'), /Отключено/);
});

test('availability check produces explicitly simulated results', async () => {
  await click('#tab-servers');
  await click('#check-servers');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#server-results")?.textContent'), /демо/i);
  assert.match(await evaluate('document.querySelector("#server-list")?.textContent'), /мс/);
});

test('subscription form validates and adds, refreshes and deletes only demo data', async () => {
  await click('#tab-subscriptions');
  await click('#add-subscription');
  await value('#sub-name', '<img src=x onerror=alert(1)>');
  await value('#sub-url', 'javascript:alert(1)');
  await evaluate('document.querySelector("#subscription-form")?.requestSubmit()');
  assert.equal(await evaluate('document.querySelector("#subscription-form")?.checkValidity()'), false);
  await value('#sub-url', 'https://example.invalid/private-token');
  await evaluate('document.querySelector("#subscription-form")?.requestSubmit()');
  assert.equal(await evaluate('document.querySelectorAll("#subscription-list .subscription-card").length'), 2);
  assert.equal(await evaluate('document.querySelectorAll("#subscription-list img").length'), 0);
  assert.equal(await evaluate('document.querySelector("#subscription-list").textContent.includes("private-token")'), false);
  await click('#subscription-list .subscription-card:last-child [data-refresh]');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#subscription-list .subscription-card:last-child").textContent'), /Обновлено.*демо/);
  await click('#subscription-list .subscription-card:last-child [data-delete]');
  await click('#confirm-delete');
  assert.equal(await evaluate('document.querySelectorAll("#subscription-list .subscription-card").length'), 1);
});

test('settings change theme, gate IPv6 DNS and validate advanced ports', async () => {
  await click('#tab-settings');
  assert.equal(await value('#app-theme', 'dark'), true);
  assert.equal(await evaluate('document.querySelector("#screen").classList.contains("dark")'), true);
  assert.equal(await evaluate('document.querySelector("#dns-ipv6")?.disabled'), true);
  await click('#ipv6');
  assert.equal(await evaluate('document.querySelector("#dns-ipv6")?.disabled'), false);
  await click('[data-open="advanced"]');
  await value('#socks-port', '70000');
  assert.equal(await evaluate('document.querySelector("#socks-port")?.checkValidity()'), false);
  await value('#socks-port', '10809');
  assert.equal(await evaluate('document.querySelector("#socks-port")?.checkValidity()'), true);
  await click('[data-open="settings"]');
  await click('[data-open="advanced"]');
  assert.equal(await evaluate('document.querySelector("#socks-port")?.value'), '10809');
});

test('routing offers app selection and simulated database update', async () => {
  await click('#tab-settings');
  await click('[data-open="routing"]');
  assert.equal(await evaluate('document.querySelector("#bypass-lan")?.checked'), true);
  await click('#bypass-lan');
  await click('#app-browser');
  assert.equal(await evaluate('document.querySelector("#app-browser")?.checked'), true);
  await click('[data-rule="geoip"]');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#geoip-status")?.textContent'), /Обновлено.*демо/);
});

test('deleting the active subscription disconnects and selects the remaining manual server', async () => {
  await click('#connect');
  await wait(750);
  await click('#tab-subscriptions');
  await click('[data-delete="sample"]');
  await click('[data-close="delete-dialog"]');
  assert.equal(await evaluate('document.querySelectorAll(".subscription-card").length'), 1);
  await click('[data-delete="sample"]');
  await click('#confirm-delete');
  await click('#tab-home');
  assert.equal(await evaluate('document.querySelector("#selected-name").textContent'), 'Личный сервер');
  assert.match(await evaluate('document.querySelector("#connection-status").textContent'), /Отключено/);
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'), 1);
});

test('theme controls agree and disabling VPN gates dependent fields without losing values', async () => {
  await click('#theme');
  await click('#tab-settings');
  assert.equal(await evaluate('document.querySelector("#app-theme").value'), 'dark');
  await click('#ipv6');
  await value('#dns-ipv4', '1.1.1.1');
  await click('[data-open="advanced"]');
  await click('#disable-vpn');
  await click('[data-open="settings"]');
  assert.equal(await evaluate('document.querySelector("#dns-ipv4").disabled'), true);
  assert.equal(await evaluate('document.querySelector("#dns-ipv6").disabled'), true);
  await click('[data-open="advanced"]');
  await click('#disable-vpn');
  await click('[data-open="settings"]');
  assert.equal(await evaluate('document.querySelector("#dns-ipv4").value'), '1.1.1.1');
  assert.equal(await evaluate('document.querySelector("#dns-ipv6").disabled'), false);
});

test('marking does not toggle controls; narrow screens keep four tabs inside the preview', async () => {
  await click('#tab-settings');
  await click('#mark');
  await click('#ipv6');
  assert.equal(await evaluate('document.querySelector("#ipv6")?.checked'), false);
  await click('#mark');
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  assert.equal(await evaluate('document.querySelector(".pages").scrollWidth <= document.querySelector(".pages").clientWidth'), true);
  assert.equal(await evaluate('document.querySelectorAll("[data-tab]").length'), 4);
});

test('long subscription names wrap inside server cards on narrow screens', async () => {
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await click('#tab-subscriptions');
  await click('#add-subscription');
  await value('#sub-name', 'x'.repeat(60));
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelector(".pages").scrollWidth <= document.querySelector(".pages").clientWidth'), true);
});

test('no script errors or external network calls occur', () => {
  assert.deepEqual(errors, []);
  assert.deepEqual(requests.filter(request => !request.startsWith('file:') && !/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(request)), []);
});
