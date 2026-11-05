// Pausa antes de abrir uma rede (equivalente ao InterceptActivity do app).
(async function () {
  const q = new URLSearchParams(location.search);
  const target = q.get('url') || '';
  const s = await RSP.settings();
  const site = (await RSP.allSites(s)).find(function (x) { return x.id === q.get('site'); });
  if (!site) { location.replace(target || 'about:blank'); return; }
  document.title = 'Respiro · ' + site.name;

  const $ = function (id) { return document.getElementById(id); };
  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function btn(text, cls, on) { const b = el('button', cls, text); b.onclick = on; return b; }

  let intention = null;

  function leave() {
    chrome.runtime.sendMessage({ t: 'gaveUp' });
    if (history.length > 1) history.back(); else chrome.runtime.sendMessage({ t: 'close' });
  }

  async function allow(min) {
    await RSP.setPass(site.id, Date.now() + min * RSP.MIN);
    location.replace(target);
  }

  /** Acima do limite: digitar a frase para continuar (Ui.confirmPhrase no app). */
  function confirmPhrase(title, onOk) {
    const c = $('choices');
    c.replaceChildren();
    c.append(el('h2', 'c', title), el('p', 'mu c', 'Para continuar, digite exatamente:'), el('p', 'c', '“' + RSP.PHRASE + '”'));
    const input = el('input'); input.type = 'text'; input.id = 'phrase'; input.placeholder = 'digite aqui'; input.autocomplete = 'off';
    const ok = btn('Continuar', '', onOk); ok.disabled = true; ok.style.marginTop = '14px';
    input.oninput = function () { ok.disabled = input.value.trim().toLowerCase() !== RSP.PHRASE; };
    c.append(input, ok, btn('Desistir', 'sec', leave));
    input.focus();
  }

  async function show() {
    const today = await RSP.today();
    const focus = RSP.focusActive(await RSP.settings());
    const opens = today.opens[site.id] || 0;
    const used = today.ms[site.id] || 0;
    const lim = RSP.limit(s, site.id) * RSP.MIN;
    const overLimit = used >= lim;
    const rest = RSP.restNow(s);
    const delay = focus ? 0 : rest ? 30 : Math.min(30, 5 + opens * 2);
    if (!focus) chrome.runtime.sendMessage({ t: 'open', site: site.id });

    $('name').textContent = site.name;
    $('sub').textContent = focus ? 'Modo foco'
      : (opens + 1) + 'ª vez hoje' + (rest ? ' · horário de descanso' : '') +
        (overLimit ? ' · ' + RSP.fmt(used) + ' hoje, limite ' + RSP.fmt(lim) : ' · ' + RSP.fmt(used) + ' de ' + RSP.fmt(lim));

    const box = $('breathBox'), c = $('choices');
    c.classList.add('hidden');
    box.classList.toggle('hidden', delay === 0);
    let inhale = true;
    const phase = setInterval(function () { inhale = !inhale; $('phase').textContent = inhale ? 'inspire' : 'expire'; }, 4000);

    let left = delay;
    (function tick() {
      if (left > 0) { $('count').textContent = left--; setTimeout(tick, 1000); return; }
      clearInterval(phase);
      $('breath').classList.add('small'); $('count').textContent = ''; $('phase').textContent = '';
      c.replaceChildren();
      if (focus) focusChoices(c); else openChoices(c, overLimit);
      c.classList.remove('hidden'); c.classList.add('fade');
    })();
  }

  function openChoices(c, overLimit) {
    if (s.planIf && s.planThen) {
      const card = el('div', 'card');
      card.append(el('div', 'k', 'Seu plano'), el('div', '', 'Se ' + s.planIf + ', então ' + s.planThen + '.'));
      card.firstChild.style.margin = '0 0 6px';
      c.append(card);
    }
    c.append(el('h2', '', 'O que você veio fazer?'));
    const hint = el('p', 'mu hidden', 'Percebido. Que tal 2 minutos sem tela? Beba água, alongue, olhe pela janela.');
    const mins = el('div', 'chips center');
    const chips = el('div', 'chips');
    RSP.INTENTIONS.forEach(function (t) {
      chips.append(btn(t, 'chip', function (e) {
        chips.querySelectorAll('.chip').forEach(function (x) { x.classList.remove('on'); });
        e.target.classList.add('on');
        intention = t;
        mins.querySelectorAll('button').forEach(function (b) { b.disabled = false; });
        hint.classList.toggle('hidden', RSP.IMPULSIVE.indexOf(t) < 0);
      }));
    });
    c.append(chips, hint);
    const no = btn('Não abrir agora', '', leave); no.style.marginTop = '28px';
    c.append(no, el('p', 'mu c', 'ou abrir por'));
    c.lastChild.style.marginTop = '18px';
    [5, 10, 15].forEach(function (m) {
      const b = btn(m + ' min', 'chip', function () {
        if (overLimit) confirmPhrase('Você passou do limite de hoje', function () { allow(m); }); else allow(m);
      });
      b.disabled = true;
      mins.append(b);
    });
    mins.style.marginTop = '8px';
    c.append(mins);
  }

  function focusChoices(c) {
    c.append(el('h2', 'c', 'Faltam ' + RSP.fmt(s.focusUntil - Date.now() + RSP.MIN - 1) + '.'),
      el('p', 'mu c', site.name + ' pode esperar. O que você estava fazendo antes?'));
    const back = btn('Voltar', '', leave); back.style.marginTop = '28px';
    c.append(back, btn('Encerrar o foco', 'txt', function () {
      confirmPhrase('Encerrar o foco?', async function () {
        s.focusUntil = 0;
        await RSP.saveSettings({ focusUntil: 0 });
        show();
      });
    }));
  }

  show();
})();
