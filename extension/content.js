// Roda nas páginas das redes: aplica o filter.js compartilhado com o app e faz o papel do WebActivity
// (página "Feed oculto", aviso de rolagem, tons de cinza, tempo da sessão, fim do passe).
(async function () {
  if (window.__rspContent) return;
  window.__rspContent = true;

  const s = await RSP.settings();
  const site = RSP.siteFor(await RSP.allSites(s), location.hostname);
  if (!site || (s.noint || []).indexOf(site.id) >= 0) return;

  // ---------- Interface própria (Shadow DOM: o CSS do site não interfere) ----------
  const hostEl = document.createElement('div');
  hostEl.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;pointer-events:none';
  const root = hostEl.attachShadow({ mode: 'closed' });
  const css = document.createElement('style');
  css.textContent = `
    :host{--bg:#fff;--fg:#000;--mu:#86868b;--cd:#f5f5f7}
    @media (prefers-color-scheme:dark){:host{--bg:#000;--fg:#fff;--cd:#161617}}
    *{box-sizing:border-box;font-family:system-ui,-apple-system,Roboto,"Segoe UI",sans-serif}
    .full{position:fixed;inset:0;background:var(--bg);color:var(--fg);pointer-events:auto;overflow:auto;display:flex;justify-content:center}
    .full main{width:100%;max-width:440px;padding:56px 24px;display:flex;flex-direction:column;justify-content:center;min-height:100%}
    .k{font-size:12px;font-weight:600;letter-spacing:.07em;text-transform:uppercase;color:var(--mu)}
    h1{font-size:32px;font-weight:700;letter-spacing:-.02em;margin:6px 0 0}
    p{font-size:16px;color:var(--mu);margin:6px 0 0;line-height:1.4}
    input{width:100%;font:inherit;font-size:17px;padding:15px 16px;border-radius:16px;border:0;outline:0;background:var(--cd);color:var(--fg);margin-top:28px}
    nav{margin-top:12px;display:flex;flex-direction:column;gap:10px}
    a.l{display:block;padding:16px;border-radius:16px;background:var(--cd);color:var(--fg);text-decoration:none;font-size:17px;font-weight:500}
    button{font:inherit;font-size:16px;font-weight:600;border:0;border-radius:14px;padding:13px 16px;cursor:pointer;background:var(--fg);color:var(--bg)}
    button.sec{background:var(--cd);color:var(--fg)}
    button.txt{background:none;color:var(--mu);font-weight:400;font-size:14px;text-decoration:underline;margin-top:32px}
    .cue{position:fixed;left:14px;right:14px;bottom:14px;max-width:440px;margin:0 auto;background:var(--cd);color:var(--fg);border-radius:18px;padding:18px;pointer-events:auto;box-shadow:0 8px 30px rgba(0,0,0,.25)}
    .cue b{font-size:17px}.cue .r{display:flex;gap:10px;margin-top:14px}.cue .r button{flex:1}
    .toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:var(--fg);color:var(--bg);padding:12px 18px;border-radius:14px;font-size:14px;pointer-events:none;max-width:90%}
  `;
  root.append(css);
  (document.body || document.documentElement).append(hostEl);

  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function btn(text, cls, on) { const b = el('button', cls, text); b.onclick = on; return b; }
  function toast(text) {
    const t = el('div', 'toast', text); root.append(t);
    setTimeout(function () { t.remove(); }, 3500);
  }

  // ---------- Tons de cinza ----------
  if (s.gray) {
    const g = document.createElement('style');
    g.textContent = 'html{filter:grayscale(1)!important}';
    (document.head || document.documentElement).append(g);
  }

  // ---------- "Feed oculto" no lugar do feed ----------
  const feedRe = s.feed && site.feedRe ? new RegExp(site.feedRe) : null;
  let feedForced = false, home = null;
  function showHome() {
    if (!s.feed || feedForced || home) return;
    home = el('div', 'full');
    const m = el('main');
    m.append(el('div', 'k', site.name), el('h1', '', 'Feed oculto'), el('p', '', 'Vá direto ao que você veio fazer.'));
    if (site.search) {
      const f = el('form'); const i = el('input'); i.placeholder = 'Buscar em ' + site.name; i.type = 'search';
      f.onsubmit = function (e) { e.preventDefault(); if (i.value.trim()) location.href = site.search.replace('%s', encodeURIComponent(i.value.trim())); };
      f.append(i); m.append(f);
    }
    const nav = el('nav');
    (site.links || []).forEach(function (l) { const a = el('a', 'l', l[0]); a.href = new URL(l[1], location.origin).href; nav.append(a); });
    m.append(nav, btn('Mostrar o feed mesmo assim', 'txt', function () { feedForced = true; hideHome(); }));
    home.append(m);
    root.append(home);
    document.documentElement.style.setProperty('overflow', 'hidden', 'important');
  }
  function hideHome() { if (home) { home.remove(); home = null; document.documentElement.style.removeProperty('overflow'); } }
  setInterval(function () { if (home && !(feedRe && feedRe.test(location.pathname))) hideHome(); }, 1000);

  // ---------- Aviso de rolagem ----------
  let screens = 0, nextCue = s.cue, cue = null;
  function onScreens(n) {
    screens += n;
    if (s.cue <= 0 || screens < nextCue || cue) return;
    nextCue = screens + s.cue;
    cue = el('div', 'cue');
    const r = el('div', 'r');
    r.append(btn('Sair', '', exit), btn('Continuar', 'sec', function () { cue.remove(); cue = null; }));
    cue.append(el('b', '', 'Você já rolou ~' + screens + ' telas.'), el('p', '', 'Ainda está procurando algo específico?'), r);
    root.append(cue);
  }

  function exit() { chrome.runtime.sendMessage({ t: 'close' }); }

  // ---------- Filtros compartilhados com o app (filter.js) ----------
  const hide = [s.shorts && site.short, s.feed && site.feedCss].filter(Boolean).join(',');
  window.__RSP_CFG = {
    hide: hide,
    feed: s.feed ? site.feedRe || '' : '',
    block: s.shorts ? site.blockRe || '' : '',
    yt: s.shorts && site.id === 'youtube',
    reel: s.shorts ? site.reelRe || '' : '',
    home: site.url, name: site.name, search: site.search || '', links: site.links || [],
  };
  let reelToast = false;
  window.RespiroBridge = {
    scr: onScreens,
    home: showHome,
    feed: function () {},
    reel: function () { if (!reelToast) { reelToast = true; toast('Só este vídeo — deslizar para os próximos está bloqueado'); } },
    blocked: function () { toast('Vídeos curtos bloqueados pelo Respiro'); },
  };
  if (window.__rspStart) window.__rspStart();

  // ---------- Tempo de uso e fim do passe ----------
  const TICK = 15000;
  setInterval(async function () {
    if (document.visibilityState !== 'visible' || !document.hasFocus()) return;
    chrome.runtime.sendMessage({ t: 'tick', site: site.id, ms: TICK });
    const now = Date.now();
    const cur = await RSP.settings();
    const until = (await RSP.passes())[site.id] || 0;
    if (RSP.focusActive(cur) || until <= now) expired();
  }, TICK);

  let ended = null;
  function expired() {
    if (ended) return;
    ended = el('div', 'full');
    const m = el('main');
    m.append(el('div', 'k', site.name), el('h1', '', 'Seu tempo acabou'), el('p', '', 'Valeu a pena?'));
    const r = el('div'); r.style.cssText = 'display:flex;flex-direction:column;gap:10px;margin-top:28px';
    r.append(btn('Sair', '', exit), btn('Abrir de novo', 'sec', function () {
      location.href = location.href; // passa de novo pela pausa
    }));
    m.append(r);
    ended.append(m);
    root.append(ended);
    document.querySelectorAll('video,audio').forEach(function (v) { try { v.pause(); } catch (e) {} });
  }
})();
