// Popup: tempo de hoje por rede (contado nesta extensão), meta e modo foco.
(async function () {
  const $ = function (id) { return document.getElementById(id); };
  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }

  async function render() {
    const s = await RSP.settings();
    const sites = await RSP.allSites(s);
    const t = await RSP.today();
    const total = Object.values(t.ms).reduce(function (a, b) { return a + b; }, 0);
    $('total').textContent = RSP.fmt(total);
    $('bar').style.width = Math.min(100, total / (s.goal * RSP.MIN) * 100) + '%';
    $('goal').textContent = 'Meta: ' + RSP.fmt(s.goal * RSP.MIN) + (t.gaveUp ? ' · desistiu de abrir ' + t.gaveUp + 'x' : '');

    const list = $('sites');
    list.replaceChildren();
    sites.filter(function (x) { return t.ms[x.id] || t.opens[x.id]; })
      .sort(function (a, b) { return (t.ms[b.id] || 0) - (t.ms[a.id] || 0); })
      .forEach(function (x) {
        const r = el('div', 'row');
        const n = el('div', 't'); n.append(el('b', '', x.name), el('span', '', (t.opens[x.id] || 0) + ' aberturas'));
        r.append(n, el('div', 'v', RSP.fmt(t.ms[x.id] || 0) + ' de ' + RSP.limit(s, x.id) + ' min'));
        list.append(r);
      });
    list.classList.toggle('hidden', !list.children.length);

    const f = $('focus');
    f.replaceChildren();
    if (RSP.focusActive(s)) {
      f.append(el('p', 'mu', 'Ativo até ' + new Date(s.focusUntil).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) + '. Encerre pela pausa de qualquer rede.'));
    } else {
      const c = el('div', 'chips'); c.style.marginTop = '0';
      [25, 50, 90].forEach(function (m) {
        const b = el('button', 'chip', m + ' min');
        b.onclick = async function () { await RSP.saveSettings({ focusUntil: Date.now() + m * RSP.MIN }); render(); };
        c.append(b);
      });
      f.append(c);
    }

    const { sync } = await chrome.storage.local.get('sync');
    $('sync').textContent = sync && sync.topic
      ? 'Conectado ao app' + (sync.last ? ' · sincronizado ' + new Date(sync.last).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '')
      : 'Não conectado ao app';
  }

  $('opts').onclick = function () { chrome.runtime.openOptionsPage(); };
  await render();
  chrome.runtime.sendMessage({ t: 'poll' }, function (r) { if (r && r.changed) render(); });
})();
