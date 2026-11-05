// Sincronização com o app pelo ntfy, cifrada de ponta a ponta. Mesmo formato do app (Sync.kt):
//  - código de conexão: RESPIRO1:<tópico 32 hex>:<chave AES-256 em base64url>
//  - mensagem: base64( IV de 12 bytes ‖ cifra AES-GCM ‖ tag de 16 bytes )
//  - conteúdo: {t:"state", ts, from, …ajustes} ou {t:"req", from}
var RSPSync = (function () {
  const PREFIX = 'RESPIRO1:';
  const ME = 'ext';
  const FIELDS = ['goal', 'gray', 'shorts', 'feed', 'cue', 'rest', 'restStart', 'restEnd', 'focusUntil', 'planIf', 'planThen', 'limits', 'hidden', 'noint', 'custom'];

  function b64(bytes) { let s = ''; bytes.forEach(function (b) { s += String.fromCharCode(b); }); return btoa(s); }
  function unb64(s) { const bin = atob(s); const a = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i); return a; }
  function b64url(bytes) { return b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }
  function unb64url(s) { s = s.replace(/-/g, '+').replace(/_/g, '/'); while (s.length % 4) s += '='; return unb64(s); }
  function rand(n) { return crypto.getRandomValues(new Uint8Array(n)); }

  async function cfg() { return (await chrome.storage.local.get('sync')).sync || null; }
  async function setCfg(patch) {
    const c = Object.assign((await cfg()) || {}, patch);
    await chrome.storage.local.set({ sync: c });
    return c;
  }
  function paired(c) { return !!(c && c.topic && c.key); }

  function newCode() {
    const topic = Array.from(rand(16)).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
    return PREFIX + topic + ':' + b64url(rand(32));
  }

  function parse(code) {
    code = (code || '').trim();
    if (code.indexOf(PREFIX) !== 0) return null;
    const p = code.slice(PREFIX.length).split(':');
    if (p.length !== 2 || !/^[a-f0-9]{32}$/.test(p[0])) return null;
    try { if (unb64url(p[1]).length !== 32) return null; } catch (e) { return null; }
    return { topic: p[0], key: p[1] };
  }

  async function aes(key) { return crypto.subtle.importKey('raw', unb64url(key), 'AES-GCM', false, ['encrypt', 'decrypt']); }

  async function encrypt(c, obj) {
    const iv = rand(12);
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, await aes(c.key), new TextEncoder().encode(JSON.stringify(obj))));
    const all = new Uint8Array(12 + ct.length); all.set(iv); all.set(ct, 12);
    return b64(all);
  }

  async function decrypt(c, s) {
    try {
      const all = unb64(s);
      const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: all.slice(0, 12) }, await aes(c.key), all.slice(12));
      return JSON.parse(new TextDecoder().decode(pt));
    } catch (e) { return null; }
  }

  function server(c) { return ((c && c.server) || 'https://ntfy.sh').replace(/\/+$/, ''); }

  async function publish(obj) {
    const c = await cfg();
    if (!paired(c)) return;
    await fetch(server(c) + '/' + c.topic, { method: 'POST', body: await encrypt(c, obj) });
  }

  async function state() {
    const s = await RSP.settings();
    const { settingsTs } = await chrome.storage.local.get('settingsTs');
    const o = { t: 'state', v: 1, ts: settingsTs || 0, from: ME };
    FIELDS.forEach(function (k) { o[k] = s[k]; });
    return o;
  }

  async function publishState() { await publish(await state()); }

  /** Busca as mensagens novas; aplica o estado mais novo e responde pedidos de estado. */
  async function poll() {
    let c = await cfg();
    if (!paired(c)) return false;
    const r = await fetch(server(c) + '/' + c.topic + '/json?poll=1&since=' + (c.since || 'all'));
    if (!r.ok) return false;
    let latest = null, asked = false, since = c.since || 'all';
    for (const line of (await r.text()).split('\n')) {
      if (!line.trim()) continue;
      let ev; try { ev = JSON.parse(line); } catch (e) { continue; }
      if (ev.event !== 'message') continue;
      since = ev.id;
      const m = await decrypt(c, ev.message || '');
      if (!m || m.from === ME) continue;
      if (m.t === 'state' && (!latest || m.ts > latest.ts)) latest = m;
      if (m.t === 'req') asked = true;
    }
    c = await setCfg({ since: since, last: Date.now() });
    if (asked) await publishState();
    const { settingsTs } = await chrome.storage.local.get('settingsTs');
    if (latest && latest.ts > (settingsTs || 0)) {
      const patch = {};
      FIELDS.forEach(function (k) { if (k in latest) patch[k] = latest[k]; });
      await RSP.saveSettings(patch, { remote: true, ts: latest.ts });
      return true;
    }
    return false;
  }

  /** Conecta com um código (gerado aqui ou no app). */
  async function pair(code, srv) {
    const p = parse(code);
    if (!p) return false;
    await setCfg({ topic: p.topic, key: p.key, since: 'all', last: 0, server: srv || server(await cfg()) });
    const { settingsTs } = await chrome.storage.local.get('settingsTs');
    if (!settingsTs) await chrome.storage.local.set({ settingsTs: 1 }); // nunca mudado aqui: o outro lado vence
    await publish({ t: 'req', from: ME });
    await publishState();
    await poll();
    return true;
  }

  async function unpair() {
    const c = await cfg();
    await chrome.storage.local.set({ sync: { server: server(c) } });
  }

  async function code() { const c = await cfg(); return paired(c) ? PREFIX + c.topic + ':' + c.key : ''; }

  return { newCode, parse, pair, unpair, poll, publishState, cfg, setCfg, paired, code, server, encrypt, decrypt };
})();
