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
const waitFor = async expression => {
  const deadline = Date.now()+3500;
  while(Date.now() < deadline) {
    if(await evaluate(expression)) return;
    await wait(50);
  }
  assert.fail('Condition did not become true: '+expression);
};

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

after(() => {
  socket?.close();
  assert.deepEqual(errors, []);
  assert.deepEqual(requests.filter(request => !request.startsWith('file:') && !/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(request)), []);
});

test('server selection reaches home and switching disconnects the demo', async () => {
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length >= 3'), true);
  await click('[data-server="nl"]');
  assert.match(await evaluate('document.querySelector("#selected-name")?.textContent'), /Амстердам/);
  await click('#connect');
  await wait(750);
  assert.match(await evaluate('document.querySelector("#connection-status")?.textContent'), /Подключено/);
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelector("[data-server=nl] .latency").textContent'),'45 мс');
  await click('[data-server="de"]');
  assert.match(await evaluate('document.querySelector("#connection-status")?.textContent'), /Отключено/);
});

test('server actions distinguish read-only information from manual editing', async () => {
  await click('#tab-servers');
  const actions = await evaluate(`Array.from(document.querySelectorAll('[data-config]'),button => ({
    text:button.textContent.trim(),width:button.getBoundingClientRect().width,
    id:button.dataset.config,icon:button.querySelector('use')?.getAttribute('href'),label:button.getAttribute('aria-label'),title:button.title
  }))`);
  assert.equal(actions.length,60);
  for(const action of actions) {
    assert.equal(action.text,'');
    assert.equal(action.icon,action.id === 'manual' ? '#i-edit' : '#i-info');
    assert.ok(action.width <= 44);
    assert.ok(action.label.length > 0 && action.title.length > 0);
  }
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
  await waitFor('!document.querySelector("#check-servers").disabled');
  assert.match(await evaluate('document.querySelector("#server-results")?.textContent'), /доступно.*офлайн/);
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
  await click('#advanced-input summary');
  assert.equal(await evaluate('document.querySelector("#server-dialog")?.open'), true);
  await value('#server-name', '<img src=x onerror=alert(1)>');
  await value('#server-config', '{broken');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.match(await evaluate('document.querySelector("#server-error").textContent'), /JSON/);
  await value('#server-config', '{"outbounds":[{"protocol":"vless","settings":{}}]}');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'), 61);
  assert.equal(await evaluate('document.querySelectorAll("#server-list img").length'), 0);
  const id = await evaluate('Array.from(document.querySelectorAll("[data-server]")).find(button => button.textContent.includes("<img")).dataset.server');
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

test('manual deletion confirms, disconnects and reveals onboarding after the last server', async () => {
  await click('#tab-servers');
  await click('[data-server="manual"]');
  await click('#connect');
  await wait(750);
  await click('#tab-servers');
  await click('[data-config="manual"]');
  assert.equal(await evaluate('document.querySelector("#delete-server")?.hidden'),false);
  await click('#delete-server');
  await click('[data-close="delete-server-dialog"]');
  assert.equal(await evaluate('servers.length'),60);
  await click('#delete-server');
  await click('#confirm-delete-server');
  assert.equal(await evaluate('servers.some(s=>s.id==="manual")'),false);
  assert.equal(await evaluate('connectionState'),'disconnected');
  await click('[data-config="nl"]');
  assert.equal(await evaluate('document.querySelector("#delete-server").hidden'),true);
  await evaluate('document.querySelector("#delete-server").click();document.querySelector("#confirm-delete-server").click()');
  assert.equal(await evaluate('servers.length'),59);
  await click('#close-server');
  await click('#tab-subscriptions');
  await click('[data-delete="sample"]');
  await click('#confirm-delete');
  await click('#tab-home');
  assert.equal(await evaluate('document.querySelector("#home-empty").hidden'),false);
  assert.equal(await evaluate('document.querySelector("#connection").hidden'),true);
  await click('#welcome-subscription');
  assert.equal(await evaluate('document.querySelector("#subscription-dialog").open'),true);
});

test('share links import credentials and transport without silently discarding unsupported fields', async () => {
  await click('#tab-servers');
  await click('#add-server');
  assert.equal(await evaluate('document.querySelector("#advanced-input")?.open'),false);
  assert.equal(await value('#server-link','vless://00000000-0000-4000-8000-000000000001@vpn.example.com:443?type=ws&security=tls&sni=edge.example.com&path=%2Fvpn&host=cdn.example.com#%D0%9C%D0%BE%D0%B9'),true);
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('servers.at(-1).name'),'Мой');
  const config = await evaluate('JSON.parse(servers.at(-1).config).outbounds[0]');
  assert.equal(config.settings.vnext[0].address,'vpn.example.com');
  assert.equal(config.streamSettings.wsSettings.path,'/vpn');
  assert.equal(config.streamSettings.wsSettings.headers.Host,'cdn.example.com');
  assert.equal(config.streamSettings.tlsSettings.serverName,'edge.example.com');
  await click('#add-server');
  await value('#server-link','trojan://p%40ss%3Aword@[2001:db8::1]:8443?security=tls#Private');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.deepEqual(await evaluate('JSON.parse(servers.at(-1).config).outbounds[0].settings.servers[0]'),{address:'2001:db8::1',port:8443,password:'p@ss:word'});
  const vmess = 'vmess://'+Buffer.from(JSON.stringify({v:'2',ps:'VMess',add:'vm.example.com',port:'443',id:'00000000-0000-4000-8000-000000000001',aid:'0',scy:'auto',net:'ws',type:'none',host:'cdn.example.com',path:'/ws',tls:'tls',sni:'vm.example.com'})).toString('base64');
  await click('#add-server');
  await value('#server-link',vmess);
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('JSON.parse(servers.at(-1).config).outbounds[0].protocol'),'vmess');
  for(const link of ['vless://bad@vpn.example.com:443','vless://00000000-0000-4000-8000-000000000001@vpn.example.com:443?unknown=secret','vmess://not-base64','trojan://pass@vpn.example.com:0']) {
    await click('#add-server');
    await value('#server-link',link);
    await evaluate('document.querySelector("#server-form").requestSubmit()');
    assert.equal(await evaluate('document.querySelector("#server-dialog").open'),true);
    assert.ok(await evaluate('document.querySelector("#server-error").textContent.length > 0'));
    assert.equal(await evaluate('servers.length'),63);
    await click('#close-server');
  }
});

test('ping categories use boundaries and availability progress reports actual totals', async () => {
  assert.equal(await evaluate('typeof pingQuality'),'function');
  assert.deepEqual(await evaluate('[99,100,250,251].map(ping=>pingQuality({ping}))'),['fast','medium','medium','slow']);
  assert.equal(await evaluate('pingQuality({ping:45,unavailable:true})'),'offline');
  await click('#tab-servers');
  await click('#check-servers');
  await wait(200);
  assert.match(await evaluate('document.querySelector("#server-results").textContent'),/Проверка \d+ из 60/);
  assert.equal(await evaluate('document.querySelector("#check-servers").disabled'),true);
  await waitFor('!document.querySelector("#check-servers").disabled');
  assert.match(await evaluate('document.querySelector("#server-results").textContent'),/55 доступно.*5 офлайн/);
  assert.equal(await evaluate('document.querySelectorAll(".latency[data-quality=offline]").length'),5);
});

test('subscription navigation filters by identity and both filters can be cleared', async () => {
  await click('#tab-subscriptions');
  await click('[data-sub-servers="sample"]');
  assert.equal(await evaluate('document.querySelector("#page-servers").hidden'),false);
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'),59);
  await value('#server-search','франк');
  assert.ok(await evaluate('document.querySelectorAll("[data-server]").length < 59'));
  await click('#clear-search');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'),59);
  await click('#clear-sub-filter');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'),60);
});

test('clipboard is explicit, routes to a review form and fails without losing typed input', async () => {
  await evaluate('window.clipboardReads=0;Object.defineProperty(navigator,"clipboard",{configurable:true,value:{readText:async()=>{window.clipboardReads++;return "https://provider.example.com/secret";}}})');
  await value('#scenario','empty');
  assert.equal(await evaluate('window.clipboardReads'),0);
  await click('#welcome-paste');
  await waitFor('document.querySelector("#subscription-dialog").open');
  assert.equal(await evaluate('document.querySelector("#sub-url").value'),'https://provider.example.com/secret');
  assert.equal(await evaluate('subscriptions.length'),0);
  await click('[data-close="subscription-dialog"]');
  await click('#tab-servers');
  await click('#add-server');
  assert.equal(await evaluate('window.clipboardReads'),1);
  await value('#server-link','trojan://keep@vpn.example.com:443');
  await evaluate('navigator.clipboard.readText=async()=>{throw new DOMException("denied","NotAllowedError")}');
  await click('#paste-server');
  await waitFor('document.querySelector("#server-error").textContent.length>0');
  assert.equal(await evaluate('document.querySelector("#server-link").value'),'trojan://keep@vpn.example.com:443');
});

test('error state offers retry and server selection and diagnostics never include imported secrets', async () => {
  assert.equal(await value('#scenario','error'),true);
  await click('#connect');
  await wait(750);
  assert.equal(await evaluate('connectionState'),'error');
  await click('#connect');
  assert.equal(await evaluate('document.querySelector("#connection-error-dialog").open'),true);
  await click('#retry-connection');
  assert.equal(await evaluate('connectionState'),'connecting');
  await wait(750);
  assert.equal(await evaluate('connectionState'),'connected');
  await value('#scenario','error');
  await click('#connect');
  await wait(750);
  await click('#connect');
  await click('#choose-other-server');
  assert.equal(await evaluate('document.querySelector("#page-servers").hidden'),false);
  await click('#add-server');
  await value('#server-link','trojan://DO-NOT-LOG@vpn.example.com:443#SECRET-NAME');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  await click('#tab-settings');
  await click('[data-open="diagnostics"]');
  assert.match(await evaluate('document.querySelector("#diagnostic-log").value'),/ERROR/);
  assert.doesNotMatch(await evaluate('document.querySelector("#diagnostic-log").value'),/DO-NOT-LOG|SECRET-NAME|vpn.example.com/);
  await evaluate('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{writeText:async text=>{window.copiedLog=text}}})');
  await click('#copy-log');
  await waitFor('Boolean(window.copiedLog)');
  assert.match(await evaluate('window.copiedLog'),/ERROR/);
});

test('QR unsupported fallback keeps manual input usable without requesting a camera', async () => {
  await evaluate('window.BarcodeDetector=undefined');
  await click('#tab-servers');
  await click('#add-server');
  assert.equal(await evaluate('Boolean(document.querySelector("#scan-qr"))'),true);
  await click('#scan-qr');
  await waitFor('document.querySelector("#qr-status")?.textContent.includes("Вставьте")');
  await click('[data-close="qr-dialog"]');
  assert.equal(await evaluate('document.querySelector("#server-dialog").open'),true);
});

test('QR camera result fills the review form and releases camera tracks', async () => {
  await evaluate(`window.BarcodeDetector=class {static async getSupportedFormats(){return ['qr_code']} async detect(){return [{rawValue:'trojan://qr-secret@qr.example.com:443#QR'}]}};
    window.qrCanvas=document.createElement('canvas');qrCanvas.width=100;qrCanvas.height=100;qrCanvas.getContext('2d').fillRect(0,0,100,100);
    window.testCamera=qrCanvas.captureStream(1);
    Object.defineProperty(navigator.mediaDevices,'getUserMedia',{configurable:true,value:async()=>testCamera});`);
  await click('#tab-servers');
  await click('#add-server');
  await click('#scan-qr');
  await waitFor('document.querySelector("#server-link").value.includes("qr-secret")');
  assert.equal(await evaluate('document.querySelector("#qr-dialog").open'),false);
  assert.equal(await evaluate('testCamera.getTracks()[0].readyState'),'ended');
  assert.equal(await evaluate('servers.length'),60);
  assert.doesNotMatch(await evaluate('document.querySelector("#diagnostic-log").value'),/qr-secret/);
});

test('QR cancellation stops a camera granted after the dialog was closed', async () => {
  await evaluate(`window.BarcodeDetector=class {static async getSupportedFormats(){return ['qr_code']}};
    Object.defineProperty(navigator.mediaDevices,'getUserMedia',{configurable:true,value:()=>new Promise(resolve=>window.grantCamera=resolve)});`);
  await click('#tab-servers');
  await click('#add-server');
  await click('#scan-qr');
  await waitFor('typeof window.grantCamera==="function"');
  await click('[data-close="qr-dialog"]');
  await evaluate(`window.lateCanvas=document.createElement('canvas');window.lateCamera=lateCanvas.captureStream();window.grantCamera(lateCamera)`);
  await waitFor('lateCamera.getTracks()[0].readyState==="ended"');
  assert.equal(await evaluate('document.querySelector("#server-link").value'),'');
});

test('clipboard late reply cannot overwrite a reopened form or newer typing', async () => {
  await evaluate('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{readText:()=>new Promise(resolve=>window.resolveClipboard=resolve)}})');
  await click('#tab-servers');
  await click('#add-server');
  await click('#paste-server');
  await value('#server-link','trojan://typed@vpn.example.com:443');
  await evaluate('resolveClipboard("trojan://late@vpn.example.com:443")');
  assert.equal(await evaluate('document.querySelector("#server-link").value'),'trojan://typed@vpn.example.com:443');
  await click('#paste-server');
  await click('#close-server');
  await click('#add-server');
  await evaluate('resolveClipboard("trojan://late@vpn.example.com:443")');
  assert.equal(await evaluate('document.querySelector("#server-link").value'),'');
});

test('pending clipboard paste cannot overwrite a newer JSON configuration', async () => {
  await evaluate('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{readText:()=>new Promise(resolve=>window.resolveClipboard=resolve)}})');
  await click('#tab-servers');
  await click('#add-server');
  await click('#paste-server');
  await click('#advanced-input summary');
  await value('#server-name','Edited JSON');
  await value('#server-config','{"outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"typed.example.com","port":443,"password":"typed"}]}}]}');
  await evaluate('resolveClipboard("trojan://late@clipboard.example.com:443")');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('JSON.parse(servers.at(-1).config).outbounds[0].settings.servers[0].address'),'typed.example.com');
});

test('new modal states stay inside the phone on narrow and desktop viewports', async () => {
  for(const [width,height] of [[1440,1100],[390,844],[538,656]]) {
    await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<600});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    for(const id of ['delete-server-dialog','connection-error-dialog','qr-dialog']) {
      const bounds=await evaluate(`(()=>{const dialog=document.querySelector('#${id}');dialog.showModal();const d=dialog.getBoundingClientRect(),s=document.querySelector('#screen').getBoundingClientRect();return {inside:d.left>=s.left && d.right<=s.right && d.top>=Math.max(0,s.top) && d.bottom<=Math.min(innerHeight,s.bottom),overflow:dialog.scrollWidth>dialog.clientWidth}})()`);
      assert.equal(bounds.inside,true,id+' outside display at '+width+'x'+height);
      assert.equal(bounds.overflow,false,id+' horizontal overflow');
      await evaluate(`document.querySelector('#${id}').close()`);
    }
  }
});

test('expanding JSON does not replace a share link and collapsing does not discard JSON edits', async () => {
  await click('#tab-servers');
  await click('#add-server');
  await value('#server-link','trojan://preserve@real.example.com:8443#Linked');
  await value('#server-name','My server');
  await click('#advanced-input summary');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('JSON.parse(servers.at(-1).config).outbounds[0].settings.servers?.[0]?.address'),'real.example.com');
  await click('#add-server');
  await click('#advanced-input summary');
  await value('#server-name','JSON');
  await value('#server-config','{"outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"json.example.com","port":443,"password":"json"}]}}]}');
  await click('#advanced-input summary');
  await evaluate('document.querySelector("#server-form").requestSubmit()');
  assert.equal(await evaluate('JSON.parse(servers.at(-1).config).outbounds[0].settings.servers[0].address'),'json.example.com');
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
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'), 60);
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

test('home has a 112px power button, live speeds, ping and reduced-motion support', async () => {
  assert.equal(await evaluate('document.querySelector("#connect").getBoundingClientRect().width'),112);
  assert.equal(await evaluate('document.querySelector("#selected-ping")?.textContent'),'—');
  await click('#tab-servers');
  await click('#check-servers');
  await waitFor('!document.querySelector("#check-servers").disabled');
  await click('#tab-home');
  assert.equal(await evaluate('document.querySelector("#selected-ping").textContent'),'42 мс');
  await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  await click('#connect');
  assert.equal(await evaluate('getComputedStyle(document.querySelector("#connect"),"::after").animationName'),'none');
  await wait(750);
  const first = await evaluate('document.querySelector("#speed-down").textContent');
  await waitFor(`document.querySelector('#speed-down').textContent !== ${JSON.stringify(first)}`);
  const next = await evaluate('document.querySelector("#speed-down").textContent');
  assert.notEqual(first,next);
  assert.match(next,/МБ\/с/);
  await click('#connect');
  assert.equal(await evaluate('document.querySelector("#speed-down").textContent'),'0 КБ/с');
  await send('Emulation.setEmulatedMedia',{features:[]});
});

test('large server list searches all fields, groups, remembers collapse and sorts numeric ping', async () => {
  await click('#tab-servers');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'),60);
  await value('#server-search','Германия');
  assert.ok(await evaluate('document.querySelectorAll("[data-server]").length > 1'));
  assert.equal(await evaluate('Array.from(document.querySelectorAll("[data-server]")).every(button=>button.textContent.includes("Германия"))'),true);
  await value('#server-search','неттакогосервера');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'),0);
  assert.match(await evaluate('document.querySelector("#server-list").textContent'),/Ничего не найдено/);
  await value('#server-search','Основная подписка');
  assert.equal(await evaluate('document.querySelectorAll("[data-server]").length'),59);
  await value('#server-search','');
  await click('#server-list details summary');
  assert.equal(await evaluate('document.querySelector("#server-list details").open'),false);
  await value('#server-sort','ping');
  assert.equal(await evaluate('document.querySelector("#server-list details").open'),false);
  await value('#server-search','Амстердам');
  assert.equal(await evaluate('document.querySelector("#server-list details").open'),true);
  await value('#server-search','');
  await click('#check-servers');
  await waitFor('!document.querySelector("#check-servers").disabled');
  const pings = await evaluate('Array.from(document.querySelectorAll("#server-list details:first-child .latency"),el=>el.textContent)');
  assert.equal(pings[0],'42 мс');
  assert.equal(pings.at(-1),'Недоступен');
  await value('#server-group','country');
  assert.ok(await evaluate('document.querySelectorAll("#server-list details").length >= 6'));
});

test('subscription usage handles missing values, exceeded quotas and expiry without invented limits', async () => {
  assert.equal(await evaluate('typeof subscriptionUsage'),'function');
  const inputs = [
    ['',{used:null,total:null,remaining:null,percent:null,expires:null,expired:false,days:null}],
    ['upload=10; download=20; total=100; expire=2000',{used:30,total:100,remaining:70,percent:30,expires:2000000,expired:false,days:1}],
    ['upload=100; download=20; total=100; expire=500',{used:120,total:100,remaining:0,percent:100,expires:500000,expired:true,days:0}],
    ['upload=-1; download=abc; total=0; expire=0',{used:null,total:null,remaining:null,percent:null,expires:null,expired:false,days:null}]
  ];
  for(const [header,expected] of inputs) assert.deepEqual(await evaluate(`subscriptionUsage(${JSON.stringify(header)},1000000)`),expected);
  await click('#tab-subscriptions');
  assert.ok(await evaluate('document.querySelector(".subscription-card progress")?.value > 0'));
  await click('#add-subscription');
  await value('#sub-url','https://unknown.example.com/sub');
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  assert.equal(await evaluate('document.querySelector(".subscription-card:last-child progress")'),null);
  assert.equal(await evaluate('document.querySelector(".subscription-card:last-child .expiry")'),null);
  await evaluate('subscriptions[0].userinfo="upload=120; download=10; total=100; expire=1";renderSubscriptions()');
  assert.equal(await evaluate('document.querySelector(".subscription-card").dataset.expired'),'true');
  assert.equal(await evaluate('document.querySelector(".subscription-card progress").value'),100);
  assert.match(await evaluate('document.querySelector(".subscription-card .expiry").textContent'),/истёк/);
});

test('subscription update policy saves independently and daily update runs only when due', async () => {
  await click('#tab-subscriptions');
  await click('[data-edit-sub="sample"]');
  assert.equal(await value('#sub-refresh','daily'),true);
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  await click('[data-edit-sub="sample"]');
  assert.equal(await evaluate('document.querySelector("#sub-refresh").value'),'daily');
  await value('#sub-refresh','startup');
  await click('[data-close="subscription-dialog"]');
  await click('[data-edit-sub="sample"]');
  assert.equal(await evaluate('document.querySelector("#sub-refresh").value'),'daily');
  await click('[data-close="subscription-dialog"]');
  assert.equal(await evaluate('typeof refreshDueSubscriptions'),'function');
  assert.equal(await evaluate('refreshDueSubscriptions(Date.now(),false)'),0);
  assert.equal(await evaluate('refreshDueSubscriptions(Date.now()+86400001,false)'),1);
  await wait(750);
  assert.match(await evaluate('document.querySelector(".subscription-card").textContent'),/Обновлено/);
  assert.equal(await evaluate('refreshDueSubscriptions(Date.now(),true)'),0);
  await click('[data-edit-sub="sample"]');
  await value('#sub-refresh','startup');
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  assert.equal(await evaluate('refreshDueSubscriptions(Date.now(),false)'),0);
  assert.equal(await evaluate('refreshDueSubscriptions(Date.now(),true)'),1);
  await wait(750);
  await click('[data-edit-sub="sample"]');
  await value('#sub-refresh','manual');
  await evaluate('document.querySelector("#subscription-form").requestSubmit()');
  assert.equal(await evaluate('refreshDueSubscriptions(Date.now()+86400001,true)'),0);
});
