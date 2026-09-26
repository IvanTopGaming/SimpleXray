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

test('power circle is the only connection button and supports connect, disconnect and cancel', async () => {
  assert.equal(await evaluate('document.querySelector(".status-orbit")?.tagName'), 'BUTTON');
  assert.equal(await evaluate('document.querySelectorAll("#page-home .connect-button").length'), 0);
  await click('.status-orbit');
  assert.match(await evaluate('document.querySelector(".status-orbit").getAttribute("aria-label")'), /Отменить/);
  await wait(750);
  assert.match(await evaluate('document.querySelector("#connection-status").textContent'), /Подключено/);
  assert.equal(await evaluate('document.querySelector(".status-orbit").getAttribute("aria-pressed")'), 'true');
  assert.equal(await evaluate('document.querySelectorAll(".status-orbit svg").length'), 1);
  await click('.status-orbit');
  assert.match(await evaluate('document.querySelector("#connection-status").textContent'), /Отключено/);
  await click('.status-orbit');
  await click('.status-orbit');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#connection-status").textContent'), /Отключено/);
  assert.equal(await evaluate('document.querySelector(".status-orbit").getAttribute("aria-pressed")'), 'false');
});

test('availability check completes and displays results', async () => {
  await click('#tab-servers');
  await click('#check-servers');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#server-results")?.textContent'), /Проверка завершена/);
  assert.match(await evaluate('document.querySelector("#server-list")?.textContent'), /мс/);
});

test('subscription form validates and adds, refreshes and deletes only demo data', async () => {
  await click('#tab-subscriptions');
  await click('#add-subscription');
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
  assert.match(await evaluate('document.querySelector("#subscription-list .subscription-card:last-child").textContent'), /Обновлено/);
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
  assert.match(await evaluate('document.querySelector("#geoip-status")?.textContent'), /Обновлено/);
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
  await value('#sub-url', 'https://'+'x'.repeat(60)+'.example.com/subscription');
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelector(".pages").scrollWidth <= document.querySelector(".pages").clientWidth'), true);
});

test('app screens and dialogs use product copy without prototype instructions', async () => {
  const copy = await evaluate(`(() => {
    const app = document.querySelector('#screen');
    return app.textContent + [...app.querySelectorAll('[aria-label],[title]')].map(el => el.getAttribute('aria-label') || el.title).join(' ');
  })()`);
  assert.doesNotMatch(copy, /демо|демонстрац|макет|вымышлен|нажми|тестов.*подписк/iu);
  assert.equal(await evaluate('document.querySelector("#connection-hint") === null'), true);
  await click('#connect');
  await wait(750);
  assert.equal(await evaluate('document.querySelector("#connection-status").textContent'), 'Подключено');
  assert.equal(await evaluate('document.querySelector("#connect").getAttribute("aria-label")'), 'Отключиться');
});

test('manual servers can be added, selected and edited with JSON validation', async () => {
  await click('#tab-servers');
  await click('#add-server');
  assert.equal(await evaluate('document.querySelector("#server-dialog")?.open'), true);
  await value('#server-name', '<img src=x onerror=alert(1)>');
  await value('#server-config', '{broken');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.match(await evaluate('document.querySelector("#server-error").textContent'), /JSON/);
  await value('#server-config', '{"outbounds":[{"protocol":"vless","settings":{}}]}');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'), 5);
  assert.equal(await evaluate('document.querySelectorAll("#server-list img").length'), 0);
  const id = await evaluate('document.querySelector("#server-list .server-entry:last-child [data-server]").dataset.server');
  await click(`[data-server="${id}"]`);
  await click('#connect');
  await wait(750);
  await click('#tab-servers');
  await click(`[data-config="${id}"]`);
  await value('#server-name', 'Новый сервер');
  await value('#server-config', '{"outbounds":[{"protocol":"trojan","settings":{}}]}');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  await click('#tab-home');
  assert.equal(await evaluate('document.querySelector("#selected-name").textContent'), 'Новый сервер');
  assert.match(await evaluate('document.querySelector("#selected-detail").textContent'), /TROJAN/);
  assert.equal(await evaluate('document.querySelector("#connection-status").textContent'), 'Отключено');
  await click('#tab-servers');
  await click(`[data-config="${id}"]`);
  assert.match(await evaluate('document.querySelector("#server-config").value'), /trojan/);
  await value('#server-name', 'Не сохранять');
  await click('[data-close="server-dialog"]');
  assert.doesNotMatch(await evaluate('document.querySelector("#server-list").textContent'), /Не сохранять/);
});

test('subscription server config is read-only and a forced submit cannot change it', async () => {
  await click('#tab-servers');
  await click('[data-config="nl"]');
  assert.equal(await evaluate('document.querySelector("#server-config")?.readOnly'), true);
  assert.equal(await evaluate('document.querySelector("#server-name")?.readOnly'), true);
  assert.equal(await evaluate('document.querySelector("#save-server")?.hidden'), true);
  const original = await evaluate('document.querySelector("#server-config").value');
  await value('#server-config', '{"outbounds":[{"protocol":"freedom"}]}');
  await value('#server-name', 'Changed');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  await click('[data-close="server-dialog"]');
  await click('[data-config="nl"]');
  assert.equal(await evaluate('document.querySelector("#server-config").value'), original);
  assert.equal(await evaluate('document.querySelector("#server-name").value'), 'Амстердам');
});

test('subscription URL edit is prefilled, cancellable and derives the name from the new domain', async () => {
  await click('#tab-subscriptions');
  await click('[data-edit-sub="sample"]');
  assert.equal(await evaluate('document.querySelector("#subscription-dialog")?.open'), true);
  assert.equal(await evaluate('document.querySelector("#sub-name")'), null);
  assert.equal(await evaluate('document.querySelector("#sub-url").value'), 'https://connect.example.com/subscription');
  await value('#sub-url', 'https://cancel.example.com/sub');
  await click('[data-close="subscription-dialog"]');
  await click('[data-edit-sub="sample"]');
  assert.equal(await evaluate('document.querySelector("#sub-url").value'), 'https://connect.example.com/subscription');
  await value('#sub-url', 'https://new.example.com/private-token');
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  assert.equal(await evaluate('document.querySelector(".subscription-card h3").textContent'), 'new.example.com');
  assert.equal(await evaluate('document.querySelectorAll(".subscription-card").length'), 1);
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'), 4);
  await click('[data-refresh="sample"]');
  await wait(750);
  assert.equal(await evaluate('document.querySelector(".subscription-card h3").textContent'), 'new.example.com');
  await click('[data-edit-sub="sample"]');
  assert.equal(await evaluate('document.querySelector("#sub-url").value'), 'https://new.example.com/private-token');
});

test('subscription title prefers response headers, decodes UTF-8 and falls back to hostname', async () => {
  assert.equal(await evaluate('typeof subscriptionTitle'), 'function');
  for (const [headers, expected] of [
    [{'pRoFiLe-TiTlE':'Provider'}, 'Provider'],
    [{'Profile-Title':'base64:0J/RgNC40LLQtdGC'}, 'Привет'],
    [{'Profile-Title':'Top','Content-Disposition':'attachment; filename="Other.txt"'}, 'Top'],
    [{'Content-Disposition':'attachment; filename="My VPN.txt"'}, 'My VPN'],
    [{"Content-Disposition":"attachment; filename*=UTF-8''%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82.txt"}, 'Привет'],
    [{'Profile-Title':'base64:???'}, 'sub.example.com'],
    [{'Profile-Title':'   '}, 'sub.example.com'],
    [{}, 'sub.example.com']
  ]) {
    assert.equal(await evaluate(`subscriptionTitle('https://sub.example.com:8443/private?token=secret',${JSON.stringify(headers)})`), expected);
  }
});

test('no script errors or external network calls occur', () => {
  assert.deepEqual(errors, []);
  assert.deepEqual(requests.filter(request => !request.startsWith('file:') && !/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(request)), []);
});

test('all dialogs and their backdrops fit the phone display on desktop and mobile', async () => {
  for (const [width,height] of [[1440,1100],[538,656],[390,844]]) {
    await send('Emulation.setDeviceMetricsOverride', { width,height,deviceScaleFactor:1,mobile:width < 600 });
    for(const [tab,trigger,id] of [
      ['servers','[data-config="nl"]','server-dialog'],
      ['servers','#add-server','server-dialog'],
      ['subscriptions','#add-subscription','subscription-dialog'],
      ['subscriptions','[data-edit-sub="sample"]','subscription-dialog'],
      ['subscriptions','[data-delete="sample"]','delete-dialog']
    ]) {
      await click('#tab-'+tab);
      await click(trigger);
      await wait(80);
      const geometry = await evaluate(`(() => {
        const screen=document.querySelector('#screen').getBoundingClientRect();
        const dialog=document.querySelector('#${id}');
        const rect=dialog.getBoundingClientRect();
        const backdrop=getComputedStyle(dialog,'::backdrop');
        return {screen:screen.toJSON(),rect:rect.toJSON(),backdrop:{left:parseFloat(backdrop.left),top:parseFloat(backdrop.top),width:parseFloat(backdrop.width),height:parseFloat(backdrop.height)},overflow:dialog.scrollWidth > dialog.clientWidth};
      })()`);
      const {screen,rect,backdrop} = geometry;
      assert.ok(rect.left >= screen.left && rect.right <= screen.right, `${id}: horizontal overflow at ${width}`);
      assert.ok(rect.top >= screen.top && rect.bottom <= screen.bottom, `${id}: vertical overflow at ${width}`);
      assert.ok(rect.top >= 0 && rect.bottom <= height, `${id}: viewport overflow at ${width}`);
      assert.equal(geometry.overflow,false);
      assert.ok(Math.abs(backdrop.left-screen.left)<1 && Math.abs(backdrop.top-screen.top)<1 && Math.abs(backdrop.width-screen.width)<1 && Math.abs(backdrop.height-screen.height)<1, `${id}: backdrop outside display`);
      await click(`[data-close="${id}"]`);
    }
  }
});

test('an open dialog follows display resizing and page scrolling and remains closable', async () => {
  await click('#tab-servers');
  await click('[data-config="nl"]');
  await send('Emulation.setDeviceMetricsOverride', {width:538,height:656,deviceScaleFactor:1,mobile:false});
  await evaluate('window.scrollTo(0,90)');
  await wait(100);
  assert.equal(await evaluate(`(() => {
    const phone=document.querySelector('#screen').getBoundingClientRect();
    const dialog=document.querySelector('#server-dialog').getBoundingClientRect();
    return dialog.left >= phone.left && dialog.right <= phone.right && dialog.top >= Math.max(0,phone.top) && dialog.bottom <= Math.min(innerHeight,phone.bottom);
  })()`),true);
  await evaluate('document.querySelector("#close-server").scrollIntoView({block:"nearest"})');
  await wait(80);
  assert.equal(await evaluate(`(() => {
    const button=document.querySelector('#close-server').getBoundingClientRect();
    const dialog=document.querySelector('#server-dialog').getBoundingClientRect();
    return button.top >= dialog.top && button.bottom <= dialog.bottom;
  })()`),true);
  await click('#close-server');
  assert.equal(await evaluate('document.querySelector("#server-dialog").open'),false);
});
