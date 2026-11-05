// Página de ajustes: mesmos campos do app + conexão com o app pelo ntfy.
(async function () {
  const $ = function (id) { return document.getElementById(id); };
  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function btn(text, cls, on) { const b = el('button', cls, text); b.onclick = on; return b; }

  // ---------- Campos simples (data-k) ----------
  async function fill() {
    const s = await RSP.settings();
    document.querySelectorAll('[data-k]').forEach(function (i) {
      const v = s[i.dataset.k];
      if (i.type === 'checkbox') i.checked = !!v;
      else if (i.type === 'time') i.value = RSP.fmtHm(v);
      else i.value = v;
    });
    renderSites(s);
  }
  document.querySelectorAll('[data-k]').forEach(function (i) {
    i.addEventListener('change', function () {
      let v;
      if (i.type === 'checkbox') v = i.checked;
      else if (i.type === 'time') { const p = i.value.split(':'); v = +p[0] * 60 + +p[1]; }
      else if (i.type === 'number') v = Math.max(+i.min, Math.min(+i.max, +i.value || 0));
      else v = i.value.trim();
      const patch = {}; patch[i.dataset.k] = v;
      RSP.saveSettings(patch);
    });
  });

  // ---------- Sites: limite, pausa e remover ----------
  async function renderSites(s) {
    const box = $('sites');
    box.replaceChildren();
    (await RSP.allSites(s)).forEach(function (x) {
      const r = el('div', 'row');
      const t = el('div', 't'); t.append(el('b', '', x.name), el('span', '', 'limite diário em minutos'));
      const lim = el('input'); lim.type = 'number'; lim.min = 5; lim.max = 180; lim.step = 5; lim.value = RSP.limit(s, x.id);
      lim.onchange = async function () {
        const cur = await RSP.settings();
        const limits = Object.assign({}, cur.limits); limits[x.id] = Math.max(5, Math.min(180, +lim.value || 20));
        RSP.saveSettings({ limits: limits });
      };
      const on = el('input'); on.type = 'checkbox'; on.className = 'switch'; on.title = 'Pausa ativa';
      on.checked = (s.noint || []).indexOf(x.id) < 0;
      on.onchange = async function () {
        const cur = await RSP.settings();
        const set = (cur.noint || []).filter(function (id) { return id !== x.id; });
        if (!on.checked) set.push(x.id);
        RSP.saveSettings({ noint: set });
      };
      r.append(t, lim, on);
      if (x.custom) r.append(btn('Remover', 'sec rm', async function () {
        const cur = await RSP.settings();
        await RSP.saveSettings({ custom: cur.custom.filter(function (c) { return c.id !== x.id; }) });
        fill();
      }));
      box.append(r);
    });
  }

  $('add').onclick = async function () {
    let u = $('newUrl').value.trim();
    if (!u) return;
    if (!/^https?:/.test(u)) u = 'https://' + u;
    const host = RSP.host(u);
    if (!host) return;
    const cur = await RSP.settings();
    if (cur.custom.some(function (c) { return RSP.host(c.url) === host; })) return;
    const name = $('newName').value.trim() || host;
    await RSP.saveSettings({ custom: cur.custom.concat([{ id: 'c_' + Date.now(), name: name, url: u }]) });
    $('newName').value = ''; $('newUrl').value = '';
    fill();
  };

  // ---------- Conexão com o app ----------
  async function renderSync() {
    const box = $('sync');
    box.replaceChildren();
    const c = await RSPSync.cfg();
    if (RSPSync.paired(c)) {
      box.append(el('b', '', 'Conectado'),
        el('p', 'mu', c.last ? 'Última sincronização: ' + new Date(c.last).toLocaleString('pt-BR') : 'Aguardando o app'),
        el('p', 'mu', 'Código desta conexão (para outro navegador ou para o app):'));
      const code = el('input'); code.id = 'code'; code.type = 'text'; code.readOnly = true; code.value = await RSPSync.code();
      code.onclick = function () { code.select(); };
      const row = el('div', 'inline');
      row.append(btn('Sincronizar agora', 'sec', async function () {
        await RSPSync.poll().catch(function () {}); await RSPSync.publishState().catch(function () {});
        fill(); renderSync();
      }), btn('Desconectar', 'sec', async function () { await RSPSync.unpair(); renderSync(); }));
      box.append(code, row);
      return;
    }
    box.append(el('b', '', 'Conectar ao app'),
      el('p', 'mu', 'Os ajustes passam pelo ntfy.sh, cifrados com uma chave que só os seus aparelhos têm. No app: Ajustes → Extensão do navegador → Conectar extensão → Gerar código.'));
    const input = el('input'); input.id = 'code'; input.type = 'text'; input.placeholder = 'Cole aqui o código RESPIRO1:…';
    const srv = el('input'); srv.type = 'url'; srv.value = RSPSync.server(c); srv.title = 'Servidor ntfy';
    srv.style.marginTop = '8px';
    const msg = el('p', 'mu');
    const row = el('div', 'inline');
    row.append(btn('Conectar', '', async function () {
      msg.textContent = 'Conectando…';
      try {
        if (!(await RSPSync.pair(input.value, srv.value.trim()))) { msg.textContent = 'Código inválido.'; return; }
      } catch (e) { msg.textContent = 'Não foi possível falar com o servidor ntfy.'; return; }
      fill(); renderSync();
    }), btn('Gerar código aqui', 'sec', async function () {
      const code = RSPSync.newCode();
      try { await RSPSync.pair(code, srv.value.trim()); } catch (e) {}
      renderSync();
    }));
    box.append(input, srv, row, msg);
  }

  chrome.storage.onChanged.addListener(function (ch) { if (ch.settingsTs && ch.settingsFrom && ch.settingsFrom.newValue === 'remote') fill(); });
  await fill();
  await renderSync();
})();
