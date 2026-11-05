// Base comum da extensão: ajustes, sites, estatísticas do dia e utilidades.
// Carregado no background, nas páginas (pausa, popup, opções) e nos content scripts.
var RSP = (function () {
  // Mesmos padrões do app (Prefs em Core.kt).
  const DEFAULTS = {
    goal: 60, gray: true, shorts: true, feed: true, cue: 30,
    rest: false, restStart: 23 * 60, restEnd: 7 * 60,
    focusUntil: 0, planIf: '', planThen: '',
    limits: {}, hidden: [], noint: [], custom: [],
  };
  const MIN = 60000;
  const INTENTIONS = ['Responder alguém', 'Postar algo', 'Buscar algo', 'Trabalho/estudo', 'Tédio', 'Hábito', 'Ansiedade/fuga'];
  const IMPULSIVE = ['Tédio', 'Hábito', 'Ansiedade/fuga'];
  const PHRASE = 'escolho gastar mais tempo aqui';

  let builtinCache = null;
  async function builtin() {
    if (builtinCache) return builtinCache;
    // Nas páginas das redes (content script) o arquivo da extensão não é acessível: pede ao background.
    const inPage = !/^(chrome|moz)-extension:$/.test(location.protocol) && typeof importScripts === 'undefined';
    builtinCache = inPage
      ? await chrome.runtime.sendMessage({ t: 'sites' })
      : (await (await fetch(chrome.runtime.getURL('sites.json'))).json()).sites;
    return builtinCache;
  }

  async function settings() {
    const { settings } = await chrome.storage.local.get('settings');
    return Object.assign({}, DEFAULTS, settings || {});
  }

  /** Grava ajustes. `remote`: vieram da sincronização (não republica). */
  async function saveSettings(patch, opts) {
    opts = opts || {};
    const s = Object.assign(await settings(), patch);
    await chrome.storage.local.set({ settings: s, settingsTs: opts.ts || Date.now(), settingsFrom: opts.remote ? 'remote' : 'local' });
    return s;
  }

  function host(u) {
    try { return new URL(u.indexOf('://') > 0 ? u : 'https://' + u).hostname.replace(/^www\./, '').replace(/^m\./, ''); }
    catch (e) { return ''; }
  }

  async function allSites(s) {
    s = s || await settings();
    const custom = (s.custom || []).map(function (c) {
      return { id: c.id, name: c.name, url: c.url, own: [host(c.url)], hosts: [host(c.url)], custom: true };
    });
    return (await builtin()).concat(custom);
  }

  /** Site aberto num endereço: só pelos domínios próprios (como Sites.forBrowserHost no app). */
  function siteFor(sites, hostname) {
    const h = (hostname || '').toLowerCase().replace(/^www\./, '');
    return sites.find(function (s) {
      const own = s.own && s.own.length ? s.own : [host(s.url)];
      return own.some(function (d) { return h === d || h.endsWith('.' + d); });
    }) || null;
  }

  function limit(s, id) { return (s.limits && s.limits[id]) || 20; }
  function focusActive(s) { return s.focusUntil > Date.now(); }
  function minuteOfDay(t) { const d = new Date(t || Date.now()); return d.getHours() * 60 + d.getMinutes(); }
  function restNow(s) {
    if (!s.rest) return false;
    const m = minuteOfDay();
    return s.restStart <= s.restEnd ? (m >= s.restStart && m < s.restEnd) : (m >= s.restStart || m < s.restEnd);
  }

  function pad(n) { return String(n).padStart(2, '0'); }
  function dayKey(t) { const d = new Date(t || Date.now()); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }

  /**
   * Estatísticas de hoje: { opens: {site: n}, ms: {site: ms}, local: {site: ms}, remote: ms, gaveUp: n }.
   * `ms` soma o tempo medido aqui com o dos outros aparelhos conectados (celular); `local` é só daqui.
   */
  async function today() {
    const o = await chrome.storage.local.get(['stats', 'remoteUsage']);
    const day = dayKey();
    const d = (o.stats || {})[day] || {};
    const local = d.ms || {};
    const ms = Object.assign({}, local);
    let remote = 0;
    const r = (o.remoteUsage || {})[day] || {};
    Object.keys(r).forEach(function (dev) {
      Object.keys(r[dev]).forEach(function (k) { ms[k] = (ms[k] || 0) + r[dev][k]; remote += r[dev][k]; });
    });
    return { opens: d.opens || {}, ms: ms, local: local, remote: remote, gaveUp: d.gaveUp || 0 };
  }

  async function passes() { return (await chrome.storage.local.get('passes')).passes || {}; }
  async function setPass(id, until) {
    const p = await passes(); p[id] = until;
    await chrome.storage.local.set({ passes: p });
  }

  function fmt(ms) {
    const m = Math.floor(ms / MIN);
    if (m < 60) return m + ' min';
    return m % 60 === 0 ? (m / 60) + 'h' : Math.floor(m / 60) + 'h ' + (m % 60) + 'min';
  }
  function fmtHm(min) { return String(Math.floor(min / 60)).padStart(2, '0') + ':' + String(min % 60).padStart(2, '0'); }

  return {
    DEFAULTS, MIN, INTENTIONS, IMPULSIVE, PHRASE,
    builtin, settings, saveSettings, allSites, siteFor, host, limit, focusActive, restNow,
    dayKey, today, passes, setPass, fmt, fmtHm,
  };
})();
