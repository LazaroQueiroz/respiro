// Background: intercepta a abertura das redes, conta o tempo e sincroniza com o app.
// No Chrome roda como service worker (importScripts); no Firefox, os scripts vêm do manifest.
if (typeof RSP === 'undefined') importScripts('common.js', 'sync.js');

// 1. Pausa: ao navegar (frame principal) para uma rede sem passe válido, troca pela página de pausa.
chrome.webNavigation.onBeforeNavigate.addListener(async function (d) {
  if (d.frameId !== 0 || !/^https?:/.test(d.url)) return;
  const s = await RSP.settings();
  const site = RSP.siteFor(await RSP.allSites(s), new URL(d.url).hostname);
  if (!site || (s.noint || []).indexOf(site.id) >= 0) return;
  if (!RSP.focusActive(s) && ((await RSP.passes())[site.id] || 0) > Date.now()) return;
  const pause = chrome.runtime.getURL('pause.html') + '?site=' + encodeURIComponent(site.id) + '&url=' + encodeURIComponent(d.url);
  chrome.tabs.update(d.tabId, { url: pause });
});

// 2. Mensagens dos content scripts e das páginas. Gravações de estatística em fila, para não perder somas.
let queue = Promise.resolve();
function serial(fn) { queue = queue.then(fn, fn); return queue; }

async function bump(field, site, n) {
  const day = RSP.dayKey();
  const { stats } = await chrome.storage.local.get('stats');
  const all = stats || {};
  const d = all[day] || (all[day] = {});
  if (site) { d[field] = d[field] || {}; d[field][site] = (d[field][site] || 0) + n; }
  else d[field] = (d[field] || 0) + n;
  // Guarda só os últimos 30 dias.
  Object.keys(all).sort().slice(0, -30).forEach(function (k) { delete all[k]; });
  await chrome.storage.local.set({ stats: all });
}

chrome.runtime.onMessage.addListener(function (m, sender, reply) {
  if (m.t === 'tick') serial(function () { return bump('ms', m.site, m.ms); });
  else if (m.t === 'open') serial(function () { return bump('opens', m.site, 1); });
  else if (m.t === 'gaveUp') serial(function () { return bump('gaveUp', null, 1); });
  else if (m.t === 'close' && sender.tab) chrome.tabs.remove(sender.tab.id);
  else if (m.t === 'sites') { RSP.builtin().then(reply); return true; }
  else if (m.t === 'poll') { RSPSync.poll().then(function (ch) { reply({ changed: ch }); }, function () { reply({ changed: false }); }); return true; }
});

// 3. Sincronização: publica 3 s depois de uma mudança local; busca a cada 5 min.
let publishTimer = null;
chrome.storage.onChanged.addListener(function (ch, area) {
  if (area !== 'local' || !ch.settingsTs) return;
  chrome.storage.local.get('settingsFrom').then(function (o) {
    if (o.settingsFrom !== 'local') return;
    clearTimeout(publishTimer);
    publishTimer = setTimeout(function () { RSPSync.publishState().catch(function () {}); }, 3000);
  });
});

function setup() {
  chrome.alarms.create('sync', { periodInMinutes: 5 });
  RSPSync.poll().catch(function () {});
}
chrome.runtime.onInstalled.addListener(setup);
chrome.runtime.onStartup.addListener(setup);
chrome.alarms.onAlarm.addListener(function (a) { if (a.name === 'sync') RSPSync.poll().catch(function () {}); });
