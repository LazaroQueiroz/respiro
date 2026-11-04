// Filtros injetados em cada página das redes (WebActivity.inject).
// C = configuração do site (preenchida pelo app em __CFG__); B = ponte com o app (RespiroBridge).
// Roda de novo a cada navegação; a guarda abaixo evita instalar tudo duas vezes.
(function () {
  if (window.__rsp) { window.__rsp.update(); return; }
  if (document.querySelector('meta[name=rsp-home]')) return; // a página "Feed oculto" é nossa: nada a filtrar

  var C = __CFG__;
  var B = window.RespiroBridge;
  var feedRe = C.feed ? new RegExp(C.feed) : null;
  var blockRe = C.block ? new RegExp(C.block) : null;
  var reelRe = C.reel ? new RegExp(C.reel) : null;

  // 1. Esconde vídeos curtos (Reels/Shorts). O <style> resolve quase sempre; o laço é o plano B
  //    para sites cuja política de segurança (CSP) bloqueia estilos injetados.
  function hideShortVideos() {
    if (!C.hide) return;
    if (!document.getElementById('__rsp_css')) {
      var s = document.createElement('style');
      s.id = '__rsp_css';
      s.textContent = C.hide + '{display:none!important}';
      (document.head || document.documentElement).appendChild(s);
    }
    try {
      document.querySelectorAll(C.hide).forEach(function (n) { n.style.setProperty('display', 'none', 'important'); });
    } catch (e) {}
  }

  // 2. Esconde faixas fixas do tipo "Abrir no app" (procura o botão e sobe até o contêiner fixo).
  var NAG = /^(use the app|usar o app|abrir o app|abrir app|open app|open in app|abrir no app|abrir no aplicativo|get the app|baixar o app|open tiktok|abrir o tiktok|switch to the app)$/i;
  function hideAppBanners() {
    document.querySelectorAll('button,a[role=button],a[href*="app"]').forEach(function (b) {
      if (!NAG.test((b.textContent || '').trim())) return;
      for (var e = b, k = 0; k < 8 && e && e !== document.body; k++, e = e.parentElement) {
        var pos = getComputedStyle(e).position;
        if (pos === 'fixed' || pos === 'sticky') { e.style.setProperty('display', 'none', 'important'); return; }
      }
    });
  }

  // 3. Visualizador de vídeo curto: deixa ver o vídeo aberto (ex.: mandado no direct),
  //    mas não deixa deslizar para os próximos.
  var reelId = null, reelUrl = '';
  function guardReel(path) {
    var m = reelRe && path.match(reelRe);
    if (!m) { reelId = null; return true; }
    if (!reelId) { reelId = m[1]; reelUrl = location.href; if (B) B.reel(); return true; }
    if (m[1] !== reelId) { location.replace(reelUrl); return false; } // trocou de vídeo: volta ao original
    return true;
  }

  // Há um vídeo ocupando o centro da tela? (o direct do Instagram abre reels sem mudar a URL)
  function bigVideoOnScreen() {
    var H = innerHeight, v = document.querySelectorAll('video');
    for (var i = 0; i < v.length; i++) {
      var r = v[i].getBoundingClientRect();
      if (r.height > H * 0.45 && r.top < H * 0.5 && r.bottom > H * 0.5) return true;
    }
    return false;
  }
  function reelMode() { return !!C.reel && (!!reelId || bigVideoOnScreen()); }

  // Trava as listas roláveis que contêm vídeos (enquanto um reel está aberto).
  function lockScroll(on) {
    if (!on) {
      document.querySelectorAll('[data-rsp-lock]').forEach(function (e) {
        e.style.removeProperty('overflow-y'); e.removeAttribute('data-rsp-lock');
      });
      return;
    }
    document.querySelectorAll('video').forEach(function (v) {
      for (var e = v.parentElement; e && e !== document.body; e = e.parentElement) {
        if (/(auto|scroll)/.test(getComputedStyle(e).overflowY) && e.scrollHeight > e.clientHeight + 10) {
          e.style.setProperty('overflow-y', 'hidden', 'important');
          e.setAttribute('data-rsp-lock', '1');
        }
      }
    });
  }

  // 4. Passo principal: chamado a cada navegação ou mudança relevante da página.
  var lastPath = '';
  function update() {
    hideShortVideos();
    hideAppBanners();
    var path = location.pathname;
    if (C.yt) { // Shorts abrem como vídeo normal (sem a rolagem infinita)
      var s = path.match(/^\/shorts\/([\w-]+)/);
      if (s) { location.replace('/watch?v=' + s[1]); return; }
    }
    if (blockRe && blockRe.test(path)) { if (B) B.blocked(); location.replace(C.home); return; }
    if (!guardReel(path)) return;
    if (C.reel) lockScroll(reelMode());
    // Navegou (dentro do site) até o feed: o app troca pela página leve "Feed oculto".
    if (path !== lastPath && feedRe && feedRe.test(path) && B) B.home();
    lastPath = path;
  }
  window.__rsp = { update: update };

  // 5. Gestos no visualizador de vídeo: bloqueia o deslizar desde o 1º movimento
  //    (depois que a rolagem começa, o navegador não deixa mais cancelar).
  //    Registrado em window/captura para rodar antes do código do próprio site.
  var startY = 0, moved = false, active = false, lastSwipe = 0;
  function exempt(t) { // campos de texto e diálogos sem vídeo (ex.: comentários) continuam livres
    if (!t || !t.closest) return false;
    if (t.closest('textarea,input')) return true;
    var d = t.closest('[role=dialog]');
    return !!(d && !d.querySelector('video'));
  }
  function onStart(e) {
    if (e.type === 'pointerdown' && e.pointerType === 'touch' && active) return;
    startY = (e.touches ? e.touches[0] : e).clientY;
    moved = false;
    active = reelMode() && !exempt(e.target);
  }
  function onMove(e) {
    if (!active) return;
    if (Math.abs((e.touches ? e.touches[0] : e).clientY - startY) > 8) { moved = true; lastSwipe = Date.now(); }
    if (e.cancelable) e.preventDefault();
    e.stopImmediatePropagation();
  }
  function onEnd(e) {
    if (active && moved) { if (e.cancelable) e.preventDefault(); e.stopImmediatePropagation(); }
  }
  var opts = { capture: true, passive: false };
  window.addEventListener('touchstart', onStart, { capture: true, passive: true });
  window.addEventListener('pointerdown', onStart, { capture: true, passive: true });
  window.addEventListener('touchmove', onMove, opts);
  window.addEventListener('pointermove', onMove, opts);
  window.addEventListener('touchend', onEnd, opts);
  window.addEventListener('pointerup', onEnd, opts);
  window.addEventListener('wheel', function (e) {
    if (reelMode() && !exempt(e.target)) { if (e.cancelable) e.preventDefault(); e.stopImmediatePropagation(); }
  }, opts);
  // "Deslizar para cima para responder" abre o teclado: se o foco veio logo após um deslize, desfaz.
  window.addEventListener('focusin', function (e) {
    var t = e.target;
    if (Date.now() - lastSwipe < 900 && t && (t.isContentEditable || /^(TEXTAREA|INPUT)$/.test(t.tagName))) t.blur();
  }, true);

  // 6. Conta telas roladas (para o aviso "você já rolou N telas") e avisa o app a cada 3.
  var scrolled = 0, lastTop = new WeakMap();
  document.addEventListener('scroll', function (e) {
    var t = e.target && e.target.nodeType === 1 ? e.target : document.scrollingElement;
    if (!t) return;
    var prev = lastTop.get(t);
    if (prev !== undefined) scrolled += Math.abs(t.scrollTop - prev);
    lastTop.set(t, t.scrollTop);
    var screen = innerHeight || 600;
    if (scrolled >= screen * 3) { var n = Math.floor(scrolled / screen); scrolled -= n * screen; if (B) B.scr(n); }
  }, { capture: true, passive: true });

  // 7. Quando rodar update(): ao navegar (sites de página única usam pushState),
  //    quando a página muda (no máximo a cada 700 ms) e, por garantia, a cada 4 s.
  //    Com a página parada, não há trabalho.
  ['pushState', 'replaceState'].forEach(function (k) {
    var orig = history[k];
    history[k] = function () { var r = orig.apply(this, arguments); setTimeout(update, 0); return r; };
  });
  window.addEventListener('popstate', function () { setTimeout(update, 0); });
  var pending = false;
  new MutationObserver(function () {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; if (!document.hidden) update(); }, 700);
  }).observe(document.documentElement, { childList: true, subtree: true });
  setInterval(function () { if (!document.hidden) update(); }, 4000);
  update();
})();
