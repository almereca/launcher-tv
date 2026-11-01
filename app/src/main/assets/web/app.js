/* App du téléphone pour le launcher TV. Aucune dépendance. */
(() => {
  'use strict';

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const SID = document.body.dataset.sid || '';
  let S = null; // état renvoyé par la TV

  // L'adresse /s/<session> permet de garder la connexion une fois l'app ajoutée à l'écran d'accueil.
  if (SID && location.pathname.indexOf('/s/') !== 0) {
    history.replaceState(null, '', '/s/' + SID + location.search + location.hash);
  }

  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* navigation privée */ } }
  };

  function post(url, data) {
    const body = new URLSearchParams(data || {}).toString();
    return fetch(url, {
      method: 'POST', keepalive: true, credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body
    }).then(r => r.ok ? r.json().catch(() => ({})) : Promise.reject(r.status));
  }

  let toastTimer;
  function toast(msg) {
    if (!msg) return;
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
  }
  const say = r => toast(r && r.msg);
  const fail = () => toast("La TV ne répond pas. Est-elle allumée ?");

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  const buzz = () => { if (navigator.vibrate) navigator.vibrate(8); };

  function refresh() {
    return fetch('/api/state', { credentials: 'same-origin', cache: 'no-store' })
      .then(r => r.json())
      .then(s => { S = s; renderAll(); $('#tvStatus').textContent = 'Salon · connectée'; })
      .catch(() => { $('#tvStatus').textContent = 'Salon · hors ligne'; });
  }

  /* ================= Onglets et pages ================= */
  const TABS = ['remote', 'search', 'settings'];
  let page = null; // sous-page ouverte dans Réglages

  function go(tab) {
    $$('.view').forEach(v => { v.hidden = v.id !== 'v-' + tab; });
    $$('.tabbar button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
    page = null;
    store.set('tab', tab);
    window.scrollTo(0, 0);
    if (tab === 'search' && findInput.value.trim().length < 2) loadDiscover();
    refresh();
  }
  function openPage(name) {
    $$('.view').forEach(v => { v.hidden = v.id !== 'p-' + name; });
    page = name;
    window.scrollTo(0, 0);
    refresh();
  }
  $$('.tabbar button').forEach(b => b.addEventListener('click', () => go(b.dataset.tab)));
  $$('[data-page]').forEach(b => b.addEventListener('click', () => openPage(b.dataset.page)));
  $$('[data-back]').forEach(b => b.addEventListener('click', () => go('settings')));

  /* ================= Feuilles du bas ================= */
  let openSheet = null;
  function sheet(id) {
    closeSheet();
    openSheet = $(id);
    openSheet.hidden = false;
    $('#scrim').hidden = false;
  }
  function closeSheet() {
    if (openSheet) openSheet.hidden = true;
    openSheet = null;
    $('#scrim').hidden = true;
  }
  $('#scrim').addEventListener('click', closeSheet);
  // Glisser la feuille vers le bas pour la fermer.
  $$('.sheet').forEach(sh => {
    let y0 = null;
    sh.addEventListener('touchstart', e => { if (sh.scrollTop <= 0) y0 = e.touches[0].clientY; }, { passive: true });
    sh.addEventListener('touchmove', e => { if (y0 != null && e.touches[0].clientY - y0 > 80) { y0 = null; closeSheet(); } }, { passive: true });
    sh.addEventListener('touchend', () => { y0 = null; });
  });

  /* ================= Télécommande ================= */
  const key = k => post('/key', { k }).catch(fail);

  $$('[data-k]').forEach(b => {
    let delay, rep;
    const stop = () => { clearTimeout(delay); clearInterval(rep); b.classList.remove('pressed'); };
    b.addEventListener('pointerdown', e => {
      e.preventDefault();
      b.classList.add('pressed');
      buzz();
      key(b.dataset.k);
      if (b.hasAttribute('data-repeat')) {
        delay = setTimeout(() => { rep = setInterval(() => key(b.dataset.k), 160); }, 420);
      }
    });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(t => b.addEventListener(t, stop));
    b.addEventListener('contextmenu', e => e.preventDefault());
  });

  // Pavé tactile : chaque glissé de STEP pixels envoie une flèche ; une touche courte envoie OK
  // (ou la flèche correspondante si on touche près d'un bord).
  const pad = $('#pad'), dot = $('#padDot');
  const STEP = 46;
  let st = null;
  function hit(dir) {
    const c = $('.chev.' + dir, pad);
    c.classList.add('hit');
    setTimeout(() => c.classList.remove('hit'), 160);
  }
  function moveDot(e) {
    const r = pad.getBoundingClientRect();
    dot.style.left = (e.clientX - r.left) + 'px';
    dot.style.top = (e.clientY - r.top) + 'px';
  }
  pad.addEventListener('pointerdown', e => {
    pad.setPointerCapture(e.pointerId);
    st = { x: e.clientX, y: e.clientY, lx: e.clientX, ly: e.clientY, t: Date.now(), moved: false };
    pad.classList.add('touching', 'used');
    moveDot(e);
  });
  pad.addEventListener('pointermove', e => {
    if (!st) return;
    moveDot(e);
    const dx = e.clientX - st.lx, dy = e.clientY - st.ly;
    if (Math.abs(dx) >= STEP && Math.abs(dx) > Math.abs(dy)) {
      const d = dx > 0 ? 'right' : 'left';
      key(d); hit(d); buzz();
      st.lx = e.clientX; st.ly = e.clientY; st.moved = true;
    } else if (Math.abs(dy) >= STEP) {
      const d = dy > 0 ? 'down' : 'up';
      key(d); hit(d); buzz();
      st.lx = e.clientX; st.ly = e.clientY; st.moved = true;
    }
  });
  function endPad(e) {
    if (!st) return;
    const dist = Math.hypot(e.clientX - st.x, e.clientY - st.y);
    if (!st.moved && dist < 14 && Date.now() - st.t < 500) {
      const r = pad.getBoundingClientRect();
      const fx = (e.clientX - r.left) / r.width - 0.5, fy = (e.clientY - r.top) / r.height - 0.5;
      if (Math.max(Math.abs(fx), Math.abs(fy)) > 0.34) {
        const d = Math.abs(fx) > Math.abs(fy) ? (fx > 0 ? 'right' : 'left') : (fy > 0 ? 'down' : 'up');
        key(d); hit(d);
      } else {
        key('ok');
        pad.classList.remove('tap'); void pad.offsetWidth; pad.classList.add('tap');
      }
      buzz();
    }
    st = null;
    pad.classList.remove('touching');
  }
  pad.addEventListener('pointerup', endPad);
  pad.addEventListener('pointercancel', () => { st = null; pad.classList.remove('touching'); });

  /* ================= Écrire sur la TV (sur l'écran télécommande) ================= */
  const typeInput = $('#typeInput');
  let typing, typeOpened = false;
  typeInput.addEventListener('focus', () => {
    // Ouvre la recherche de ce qui est affiché : celle du launcher, ou celle de l'app ouverte.
    if (!typeOpened) { typeOpened = true; post('/appsearch').catch(fail); }
  });
  typeInput.addEventListener('blur', () => { setTimeout(() => { typeOpened = false; }, 400); });
  typeInput.addEventListener('input', () => {
    clearTimeout(typing);
    typing = setTimeout(() => post('/type', { q: typeInput.value }).catch(fail), 220);
  });
  $('#typeForm').addEventListener('submit', e => {
    e.preventDefault();
    clearTimeout(typing);
    post('/go', { q: typeInput.value }).catch(fail);
    typeInput.blur();
    typeInput.value = '';
  });
  function renderTypeTarget() {
    $('#typeTarget').textContent = S && S.app ? S.app : '';
  }

  /* ================= Recherche sur le téléphone ================= */
  const findInput = $('#findInput');
  let findTimer, findSeq = 0, found = [];
  function find(qv) {
    const my = ++findSeq;
    const box = $('#findResults');
    if (qv.trim().length < 2) { box.innerHTML = ''; $('#findHint').hidden = true; $('#discover').hidden = false; loadDiscover(); return; }
    $('#discover').hidden = true;
    box.innerHTML = '<span class="spinner"></span>';
    post('/find', { q: qv }).then(r => {
      if (my !== findSeq) return;
      found = (r && r.results) || [];
      $('#findHint').hidden = found.length > 0;
      if (!found.length) { box.innerHTML = '<p class="empty">Aucun résultat.</p>'; return; }
      const groups = [['app', 'Apps'], ['movie', 'Films et séries'], ['channel', 'Chaînes YouTube'], ['video', 'Vidéos YouTube']];
      box.innerHTML = groups.map(([k, name]) => {
        const items = found.map((x, i) => [x, i]).filter(([x]) => x.kind === k);
        if (!items.length) return '';
        return `<h3>${name}</h3>` + items.map(([x, i]) => resultRow(x, i)).join('');
      }).join('');
      $$('[data-res]', box).forEach(b => b.addEventListener('click', e => {
        const item = found[+b.dataset.res];
        if (e.target.closest('.quick')) { act('pin', item); return; }
        showItem(item);
      }));
    }).catch(() => { if (my === findSeq) { box.innerHTML = ''; fail(); } });
  }
  function resultRow(x, i) {
    let pic;
    if (x.kind === 'movie') pic = x.poster ? `<img class="poster" src="${esc(x.poster)}" alt="" loading="lazy">` : '<span class="ph"></span>';
    else if (x.kind === 'channel') pic = x.thumb ? `<img class="round" src="${esc(x.thumb)}" alt="" loading="lazy">` : '<span class="ph"></span>';
    else if (x.kind === 'video') pic = x.thumb ? `<img src="${esc(x.thumb)}" alt="" loading="lazy">` : '<span class="ph"></span>';
    else pic = `<img class="round" src="/appicon/${encodeURIComponent(x.pkg)}.png" alt="" onerror="this.style.visibility='hidden'">`;
    return `<button class="res" data-res="${i}">${pic}<span class="txt"><b>${esc(x.title)}</b><small>${esc(x.subtitle || '')}</small></span>
      <span class="quick" aria-label="Ajouter aux favoris"><svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/></svg></span></button>`;
  }
  /* À regarder */
  let disc = null, discLoading = false;
  function loadDiscover(fresh, force) {
    const box = $('#discover');
    if (disc && !fresh && !force) return;
    if (discLoading) return;
    discLoading = true;
    if (!disc) box.innerHTML = '<h2 class="disc-head">À regarder</h2><span class="spinner"></span>';
    post('/discover', fresh ? { fresh: '1' } : {}).then(r => {
      disc = (r && r.sections) || [];
      renderDiscover();
    }).catch(() => { box.innerHTML = ''; }).finally(() => { discLoading = false; });
  }
  // Pictos maison pour les contenus signalés (pas les pictogrammes officiels).
  const PICTO = { Violence: '👊', Peur: '😱', Sexe: '💋', Drogues: '💊', Langage: '🤬', Discrimination: '✋' };
  const ageClass = n => n == null ? '' : n <= 6 ? 'a-ok' : n <= 12 ? 'a-mid' : 'a-high';
  function renderRating(x) {
    const box = $('#isRating');
    if (!x.age) { box.innerHTML = ''; return; }
    box.innerHTML = `<span class="age ${ageClass(x.ageNum)}">${esc(x.age)}</span>` +
      (x.descriptors || []).map(d => `<span class="chip">${PICTO[d] || '•'} ${esc(d)}</span>`).join('');
  }

  function renderDiscover() {
    const box = $('#discover');
    if (!disc.length) {
      box.innerHTML = '<h2 class="disc-head">À regarder</h2><p class="empty">Pas d\'idées pour l\'instant. La TV a-t-elle accès à TMDB ?</p>';
      return;
    }
    box.innerHTML = '<h2 class="disc-head">À regarder</h2>' + disc.map((sec, si) => `
      <section class="shelf">
        <h3>${esc(sec.title)}<small>${esc(sec.subtitle || '')}</small></h3>
        <div class="shelf-row">${sec.items.map((x, i) => `
          <button class="tile${x.wide ? ' wide' : ''}" data-s="${si}" data-i="${i}">
            <span class="art">
              ${x.poster ? `<img src="${esc(x.poster)}" alt="" loading="lazy" onerror="this.remove()">` : ''}
              ${x.age ? `<span class="age ${ageClass(x.ageNum)}">${esc(x.age === 'Tous publics' ? 'TP' : x.age)}</span>` : ''}
              ${x.descriptors && x.descriptors.length ? `<span class="pictos">${x.descriptors.map(d => `<i title="${esc(d)}">${PICTO[d] || '•'}</i>`).join('')}</span>` : ''}
              ${x.progress ? `<span class="prog"><span style="width:${Math.round(x.progress * 100)}%"></span></span>` : ''}
            </span>
            <b>${esc(x.title)}</b><small>${esc(x.kind === 'next' ? x.subtitle : (x.watch || ''))}</small>
          </button>`).join('')}</div>
      </section>`).join('') +
      '<p class="fine disc-reset"><button class="linkish" id="tasteReset">Oublier mes réponses aux cartes</button></p>';
    $('#tasteReset').addEventListener('click', () => {
      if (!confirm('Effacer « Ma liste » et toutes tes réponses aux cartes ?')) return;
      post('/taste', { action: 'reset' }).then(r => { say(r); loadDiscover(false, true); }).catch(fail);
    });
    $$('.tile', box).forEach(b => b.addEventListener('click', () => {
      const x = disc[+b.dataset.s].items[+b.dataset.i];
      if (x.kind === 'next') showItem(x); else openDeck(+b.dataset.s, +b.dataset.i);
    }));
  }

  /* ---------- Cartes à swiper ---------- */
  let deck = [], deckPos = 0, deckChanged = false;
  const TASTE_MSG = { want: 'Ajouté à Ma liste', no: 'Noté, on oublie', seen: 'Noté comme déjà vu',
    liked: 'Noté, on t\'en proposera d\'autres', nogenre: 'Ok, moins de titres de ce genre' };
  function openDeck(si, i) {
    const seen = new Set();
    deck = [];
    disc.forEach((sec, k) => {
      if (k < si) return;
      sec.items.forEach((x, j) => {
        if (x.kind === 'next' || (k === si && j < i)) return;
        const key = x.type + ':' + x.id;
        if (seen.has(key)) return;
        seen.add(key);
        deck.push(x);
      });
    });
    deckPos = 0;
    deckChanged = false;
    $('#deck').hidden = false;
    document.body.classList.add('deck-open');
    renderDeck();
  }
  function closeDeck() {
    $('#deck').hidden = true;
    document.body.classList.remove('deck-open');
    if (deckChanged) loadDiscover(false, true);
  }
  $('#deckClose').addEventListener('click', closeDeck);
  function cardHtml(x, top) {
    const img = x.poster || x.backdrop;
    return `<div class="dcard${top ? ' top' : ''}">
      ${img ? `<img src="${esc(img)}" alt="">` : ''}
      <span class="stamp yes">ÇA ME TENTE</span><span class="stamp no">NON MERCI</span>
      <div class="dinfo">
        <div class="rating">${x.age ? `<span class="age ${ageClass(x.ageNum)}">${esc(x.age)}</span>` : ''}${(x.descriptors || []).map(d => `<span class="chip">${PICTO[d] || '•'} ${esc(d)}</span>`).join('')}</div>
        <h2>${esc(x.title)}</h2><p class="dsub">${esc(x.subtitle || '')}</p>
        ${x.overview ? `<p class="dov">${esc(x.overview)}</p>` : ''}
      </div></div>`;
  }
  function renderDeck() {
    const stack = $('#deckStack');
    const x = deck[deckPos];
    if (!x) {
      stack.innerHTML = '<div class="deck-end"><p class="big">🍿</p><b>C\'est tout pour l\'instant</b><small>Tes réponses rendent « À regarder » plus malin.</small></div>';
      $('#deckCount').textContent = '';
      return;
    }
    $('#deckCount').textContent = (deckPos + 1) + ' / ' + deck.length;
    stack.innerHTML = (deck[deckPos + 1] ? cardHtml(deck[deckPos + 1], false) : '') + cardHtml(x, true);
    if (!x.age && x.id) {
      post('/rating', { type: x.type, id: x.id }).then(r => {
        if (r && r.age && deck[deckPos] === x) { Object.assign(x, { age: r.age, ageNum: r.ageNum, descriptors: r.descriptors || [] }); renderDeck(); }
      }).catch(() => {});
    }
    dragCard($('.dcard.top', stack));
  }
  function dragCard(card) {
    let x0 = null, dx = 0;
    const set = () => {
      card.style.transform = `translateX(${dx}px) rotate(${dx / 18}deg)`;
      $('.stamp.yes', card).style.opacity = Math.max(0, Math.min(1, dx / 90));
      $('.stamp.no', card).style.opacity = Math.max(0, Math.min(1, -dx / 90));
    };
    card.addEventListener('pointerdown', e => { x0 = e.clientX; dx = 0; card.setPointerCapture(e.pointerId); card.style.transition = 'none'; });
    card.addEventListener('pointermove', e => { if (x0 == null) return; dx = e.clientX - x0; set(); });
    const end = () => {
      if (x0 == null) return;
      x0 = null;
      card.style.transition = '';
      if (Math.abs(dx) > 100) decide(dx > 0 ? 'want' : 'no');
      else { dx = 0; set(); }
    };
    card.addEventListener('pointerup', end);
    card.addEventListener('pointercancel', end);
  }
  function fling(dir) {
    const card = $('.dcard.top');
    if (!card) return;
    card.style.transition = 'transform .32s ease, opacity .32s ease';
    card.style.transform = dir === 'up' ? 'translateY(-120%) scale(.9)' : `translateX(${dir === 'right' ? 140 : -140}%) rotate(${dir === 'right' ? 18 : -18}deg)`;
    card.style.opacity = '0';
  }
  function decide(state) {
    const x = deck[deckPos];
    if (!x) return;
    buzz();
    if (state === 'watch' || state === 'trailer' || state === 'pin') {
      act(state, x);
      if (state === 'watch') closeDeck();
      if (state === 'pin') {
        const b = $('#deck [data-t="pin"]');
        b.classList.add('done');
        setTimeout(() => b.classList.remove('done'), 900);
      }
      return;
    }
    post('/taste', { item: JSON.stringify(x), state }).then(r => toast((r && r.msg) || TASTE_MSG[state])).catch(fail);
    deckChanged = true;
    fling(state === 'want' || state === 'liked' ? 'right' : state === 'no' || state === 'nogenre' ? 'left' : 'up');
    setTimeout(() => { deckPos++; renderDeck(); }, 260);
  }
  $$('#deck [data-t]').forEach(b => b.addEventListener('click', () => decide(b.dataset.t)));

  findInput.addEventListener('input', () => { clearTimeout(findTimer); findTimer = setTimeout(() => find(findInput.value), 500); });
  $('#findForm').addEventListener('submit', e => { e.preventDefault(); clearTimeout(findTimer); find(findInput.value); findInput.blur(); });

  const ICON = {
    play: '<svg viewBox="0 0 24 24"><path d="M7 5l12 7-12 7z" class="fill"/></svg>',
    film: '<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M10 9l5 3-5 3z" class="fill"/></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/></svg>',
    user: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/></svg>'
  };
  function showItem(x) {
    const img = $('#isImg');
    img.className = '';
    if (x.kind === 'movie') { img.src = x.poster || x.backdrop || ''; img.className = 'poster'; }
    else if (x.kind === 'next') { img.src = x.poster || ''; img.className = 'wide'; }
    else if (x.kind === 'app') { img.src = '/appicon/' + encodeURIComponent(x.pkg) + '.png'; img.className = 'round'; }
    else { img.src = x.thumb || ''; if (x.kind === 'channel') img.className = 'round'; }
    $('#isTitle').textContent = x.title;
    $('#isSub').textContent = x.subtitle || '';
    $('#isText').textContent = x.kind === 'movie'
      ? (x.overview || '') + (x.providers && x.providers.length ? '\n\nSur ' + x.providers.slice(0, 3).join(', ') : '')
      : '';
    renderRating(x);
    if (x.kind === 'movie' && !x.age && x.id) {
      post('/rating', { type: x.type, id: x.id }).then(r => {
        if (r && r.age) { Object.assign(x, { age: r.age, ageNum: r.ageNum, descriptors: r.descriptors || [] }); renderRating(x); }
      }).catch(() => {});
    }
    const acts = [];
    if (x.kind === 'next') {
      acts.push(['watch', 'Reprendre sur la TV', ICON.play, 'main']);
      acts.push(['pin', 'Ajouter aux favoris', ICON.star, 'fav']);
    } else if (x.kind === 'movie') {
      acts.push(['watch', x.watch ? 'Regarder sur ' + x.watch : 'Chercher sur la TV', ICON.play, 'main']);
      acts.push(['trailer', 'Bande-annonce', ICON.film, '']);
      acts.push(['pin', 'Ajouter aux favoris', ICON.star, 'fav']);
    } else if (x.kind === 'video') {
      acts.push(['watch', 'Regarder sur la TV', ICON.play, 'main']);
      acts.push(['pin', 'Ajouter la vidéo aux favoris', ICON.star, 'fav']);
      acts.push(['pinchannel', 'Ajouter la chaîne aux favoris', ICON.user, 'fav']);
    } else if (x.kind === 'channel') {
      acts.push(['watch', 'Ouvrir la chaîne sur la TV', ICON.play, 'main']);
      acts.push(['pin', 'Ajouter aux favoris', ICON.star, 'fav']);
    } else {
      acts.push(['watch', 'Ouvrir sur la TV', ICON.play, 'main']);
      acts.push(['pin', 'Ajouter aux favoris', ICON.star, 'fav']);
    }
    $('#isActions').innerHTML = acts.map(([a, t, ic, cls]) => `<button class="${cls}" data-act="${a}">${ic}${esc(t)}</button>`).join('');
    $$('[data-act]', $('#isActions')).forEach(b => b.addEventListener('click', () => {
      act(b.dataset.act, x);
      closeSheet();
    }));
    sheet('#itemSheet');
  }
  function act(action, item) {
    buzz();
    return post('/act', { action, item: JSON.stringify(item) }).then(r => { say(r); refresh(); }).catch(fail);
  }

  /* ================= Raccourcis d'apps ================= */
  const icon = pkg => `<img src="/appicon/${encodeURIComponent(pkg)}.png" alt="" onerror="this.style.visibility='hidden'">`;
  function renderShortcuts() {
    const apps = S.remoteApps || [];
    const row = $('#shortcuts');
    row.innerHTML = apps.map(a => `<button class="sc" data-pkg="${esc(a.pkg)}">${icon(a.pkg)}<span>${esc(a.label)}</span></button>`).join('') +
      `<button class="sc add" id="scEdit" aria-label="Choisir les raccourcis"><svg viewBox="0 0 24 24"><path d="${apps.length ? 'M4 7h10M4 12h16M4 17h10M18 4v6' : 'M12 6v12M6 12h12'}"/></svg>${apps.length ? '' : '<span>Ajouter une app</span>'}</button>`;
    $$('.sc[data-pkg]', row).forEach(b => b.addEventListener('click', () => {
      buzz(); b.classList.add('pressed'); setTimeout(() => b.classList.remove('pressed'), 180);
      post('/launch', { pkg: b.dataset.pkg }).then(say).catch(fail);
    }));
    $('#scEdit').addEventListener('click', () => { renderPick(); sheet('#appsSheet'); });
  }
  $('#scFromApps').addEventListener('click', () => { renderPick(); sheet('#appsSheet'); });
  function renderPick() {
    const chosen = (S.remoteApps || []).map(a => a.pkg);
    const all = S.menu.map(m => ({ pkg: m.pkg, label: m.label })).concat(S.others).filter(a => a.pkg !== 'launcher.next');
    const list = $('#appsPick');
    list.innerHTML = all.map(a => `
      <button class="item app-pick ${chosen.includes(a.pkg) ? 'on' : ''}" data-pkg="${esc(a.pkg)}">
        <img class="app-icon" src="/appicon/${encodeURIComponent(a.pkg)}.png" alt="" onerror="this.style.visibility='hidden'">
        <div class="txt"><b>${esc(a.label)}</b></div>
        <span class="check" aria-hidden="true"></span>
      </button>`).join('');
    $$('.app-pick', list).forEach(b => b.addEventListener('click', () => {
      const pkg = b.dataset.pkg;
      const cur = (S.remoteApps || []).filter(a => a.pkg !== pkg);
      if (!b.classList.contains('on')) cur.push(all.find(a => a.pkg === pkg));
      S.remoteApps = cur;
      b.classList.toggle('on');
      renderShortcuts();
      post('/remote/apps', { pkgs: cur.map(a => a.pkg).join(',') }).catch(fail);
    }));
  }

  /* ================= Minuteur ================= */
  $('#timerBtn').addEventListener('click', () => { renderTimer(); sheet('#timerSheet'); });
  function startTimer(sec, label) {
    post('/timer', { seconds: sec, label: label || '' }).then(r => { say(r); refresh(); closeSheet(); }).catch(fail);
  }
  $$('#timerChips button').forEach(b => b.addEventListener('click', () => startTimer(b.dataset.sec, $('#timerLabel').value)));
  $('#timerGo').addEventListener('click', () => {
    const m = parseInt($('#timerMin').value, 10);
    if (!m || m < 1) { toast('Indique une durée en minutes'); return; }
    startTimer(m * 60, $('#timerLabel').value);
  });
  $('#timerStop').addEventListener('click', () => post('/timer', { seconds: 0 }).then(r => { say(r); refresh(); }).catch(fail));
  function mmss(ms) {
    const t = Math.max(0, Math.ceil(ms / 1000));
    const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), sec = t % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(sec).padStart(2, '0');
  }
  function renderTimer() {
    const t = S && S.timer;
    const on = t && t.running && t.endsAt > Date.now();
    $('#timerNow').hidden = !on;
    $('#timerBadge').hidden = !on;
    if (on) {
      const left = t.endsAt - Date.now();
      $('#timerLeft').textContent = mmss(left);
      $('#timerBadge').textContent = Math.max(1, Math.ceil(left / 60000)) + "'";
    }
  }
  setInterval(renderTimer, 1000);

  /* ================= À suivre ================= */
  $('#pinForm').addEventListener('submit', e => {
    e.preventDefault();
    const btn = $('#pinForm .primary');
    btn.disabled = true;
    post('/pin/add', { link: $('#link').value, title: $('#title').value })
      .then(r => { say(r); $('#link').value = ''; $('#title').value = ''; refresh(); })
      .catch(fail)
      .finally(() => { btn.disabled = false; });
  });

  function renderPins() {
    const list = $('#pinList');
    if (!S.pins.length) {
      list.innerHTML = '<p class="empty">Rien d\'épinglé pour l\'instant.</p>';
      return;
    }
    list.innerHTML = S.pins.map((p, i) => `
      <div class="item">
        ${p.image && p.image.indexOf('http') === 0 ? `<img class="thumb" src="${esc(p.image)}" alt="">` : '<span class="thumb"></span>'}
        <div class="txt"><b>${esc(p.title)}</b><small>${esc(p.subtitle)}</small></div>
        <button class="mini" data-move="-1" data-id="${esc(p.id)}" aria-label="Avancer" ${i === 0 ? 'disabled style="opacity:.25"' : ''}>
          <svg viewBox="0 0 24 24"><path d="M7 14l5-5 5 5"/></svg></button>
        <button class="mini" data-del="${esc(p.id)}" aria-label="Retirer">
          <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
      </div>`).join('');
    $$('[data-del]', list).forEach(b => b.addEventListener('click', () =>
      post('/pin/del', { id: b.dataset.del }).then(r => { say(r); refresh(); }).catch(fail)));
    $$('[data-move]', list).forEach(b => b.addEventListener('click', () =>
      post('/pin/move', { id: b.dataset.id, delta: b.dataset.move }).then(refresh).catch(fail)));
  }

  /* ================= Enfants ================= */
  function hm(ms) {
    const d = new Date(ms);
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function renderKids() {
    const k = S.kids;
    const box = $('#kidsStatus');
    let title = 'Aucune extinction prévue', sub = 'Choisis une durée ou une heure ci-dessous.', active = false, cancel = '';
    if (k.end) {
      title = 'Extinction à la fin du contenu'; sub = 'La TV s\'éteint quand l\'épisode ou le film se termine.'; active = true;
    }
    if (k.next) {
      const mins = Math.max(1, Math.round((k.next - Date.now()) / 60000));
      title = 'Extinction à ' + hm(k.next);
      sub = 'Dans ' + (mins < 60 ? mins + ' min' : Math.floor(mins / 60) + ' h ' + String(mins % 60).padStart(2, '0')) +
        (k.daily ? ' · tous les jours à ' + k.daily : '');
      active = true;
      cancel = k.at ? '<button class="ghost" data-kids="cancel">Annuler</button>' :
        (k.daily ? '<button class="ghost" data-kids="canceldaily">Supprimer</button>' : '');
    }
    if (k.locked) { title = 'TV verrouillée'; sub = 'Le code est demandé à l\'écran.'; active = true; cancel = '<button class="ghost" data-kids="unlock">Déverrouiller</button>'; }
    box.innerHTML = `
      <span class="moon ${active ? '' : 'idle'}"><svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/></svg></span>
      <div><b>${esc(title)}</b><small>${esc(sub)}</small></div>${cancel}`;
    $$('[data-kids]', box).forEach(b => b.addEventListener('click', () => kids({ action: b.dataset.kids })));

    $('#sleepEnd').checked = !!k.end;
    $('#endNote').textContent = k.endAvailable
      ? 'La TV s\'éteint quand l\'épisode ou le film se termine.'
      : 'Nécessite l\'accès aux notifications, accordé par le script d\'installation.';
    if (k.daily) { $('#sleepDaily').checked = true; $('#sleepTime').value = k.daily; }
    $('#lockOn').checked = !!k.lock;
    $('#codeBox').hidden = !k.lock;
    $('#codeValue').textContent = k.code ? k.code.split('').join('') : '••••';
  }
  const DEFI_TYPES = [['calc', 'Calculs', '7 + 5 pour la petite, 63 ÷ 7 pour le grand'],
    ['units', 'Grandeurs (niveau 8 ans)', '3 m = ? cm, 2 h = ? min, 5 € = ? centimes'],
    ['nlword', 'Mots en néerlandais', '🐶 le chien → de h·o·n·d, lettre par lettre'],
    ['nlorder', 'Phrases en néerlandais', 'Ik eet graag frietjes, dans l\'ordre'],
    ['frorder', 'Phrases en français', 'Le dragon mange des frites, dans l\'ordre']];
  const DEFI_OPS = [['plus', '+'], ['minus', '−'], ['times', '×'], ['divide', '÷']];
  function renderDefis() {
    const d = S.defis;
    if (!d) { $('#defisCard').hidden = true; return; }
    $('#defisOn').checked = !!d.on;
    $('#defisBox').hidden = !d.on;
    $('#defisNote').textContent = d.on && d.next
      ? 'Prochain défi vers ' + hm(d.next) + ' si une app est ouverte.'
      : 'La TV fait une pause et pose une petite question. On répond avec la télécommande.';
    const seg = (id, val) => $$(`#${id} button`).forEach(b => b.classList.toggle('on', b.dataset.v === String(val)));
    seg('defisEvery', d.every); seg('defisLevel', d.level); seg('defisCount', d.count);
    if (document.activeElement !== $('#defisEveryIn')) $('#defisEveryIn').value = d.every;
    if (document.activeElement !== $('#defisCountIn')) $('#defisCountIn').value = d.count;
    $('#defisBig').hidden = d.level === '6';
    const ops = d.ops || DEFI_OPS.map(o => o[0]);
    $('#defisOps').innerHTML = DEFI_OPS.map(([k, sym]) => `<button class="op ${ops.includes(k) ? 'on' : ''}" data-op="${k}">${sym}</button>`).join('');
    $$('#defisOps [data-op]').forEach(b => b.addEventListener('click', () => {
      const next = ops.includes(b.dataset.op) ? ops.filter(o => o !== b.dataset.op) : ops.concat(b.dataset.op);
      if (!next.length) { toast('Garde au moins une opération'); return; }
      defis({ ops: next.join(',') });
    }));
    $('#defisTypes').innerHTML = DEFI_TYPES.map(([k, name, ex]) => `
      <label class="item switch-row"><span><b>${name}</b><small>${esc(ex)}</small></span>
      <input type="checkbox" class="switch" data-type="${k}" ${d.types.includes(k) ? 'checked' : ''}></label>`).join('');
    $$('#defisTypes [data-type]').forEach(c => c.addEventListener('change', () => {
      const types = $$('#defisTypes [data-type]').filter(x => x.checked).map(x => x.dataset.type);
      if (!types.length) { c.checked = true; toast('Garde au moins un type de question'); return; }
      defis({ types: types.join(',') });
    }));
    $('#defisSkip').hidden = !d.running;
  }
  function defis(data) {
    return post('/defis', data).then(r => { say(r); return refresh(); }).catch(fail);
  }
  $('#defisOn').addEventListener('change', e => defis({ on: e.target.checked ? '1' : '0' }));
  [['defisEvery', 'every'], ['defisLevel', 'level'], ['defisCount', 'count']].forEach(([id, key]) =>
    $$(`#${id} button`).forEach(b => b.addEventListener('click', () => defis({ [key]: b.dataset.v }))));
  $('#defisEveryIn').addEventListener('change', e => { const v = parseInt(e.target.value, 10); if (v >= 1 && v <= 240) defis({ every: v }); else toast('Entre 1 et 240 minutes'); });
  $('#defisCountIn').addEventListener('change', e => { const v = parseInt(e.target.value, 10); if (v >= 1 && v <= 10) defis({ count: v }); else toast('Entre 1 et 10 questions'); });
  $('#defisNow').addEventListener('click', () => defis({ action: 'now' }));
  $('#defisSkip').addEventListener('click', () => defis({ action: 'skip' }));

  function kids(data) {
    return post('/kids', data).then(r => { say(r); return refresh(); }).catch(fail);
  }
  $$('#sleepChips button').forEach(b => b.addEventListener('click', () => kids({ action: 'in', minutes: b.dataset.min })));
  $('#sleepAtBtn').addEventListener('click', () =>
    kids({ action: 'at', time: $('#sleepTime').value, daily: $('#sleepDaily').checked ? '1' : '0' }));
  $('#sleepEnd').addEventListener('change', e => kids({ action: 'end', on: e.target.checked ? '1' : '0' }));
  $('#lockOn').addEventListener('change', e => kids({ action: 'lock', on: e.target.checked ? '1' : '0' }));
  $('#newCodeBtn').addEventListener('click', () => kids({ action: 'newcode' }));
  $('#lockNowBtn').addEventListener('click', () => kids({ action: 'locknow' }));

  /* ================= Réglages : apparence ================= */
  $$('#themeSeg button').forEach(b => b.addEventListener('click', () =>
    post('/theme', { mode: b.dataset.theme }).then(refresh).catch(fail)));

  function renderLook() {
    $$('#themeSeg button').forEach(b => b.classList.toggle('on', b.dataset.theme === S.theme));
    const tiles = S.wallpapers.map(w =>
      `<button class="wp ${S.wallpaper === w.id ? 'on' : ''}" data-wp="${w.id}"><img src="/wp/${w.id}.webp" alt=""><span>${esc(w.name)}</span></button>`);
    if (S.photo) {
      tiles.push(`<button class="wp ${S.wallpaper === 'photo' ? 'on' : ''}" data-wp="photo"><img src="/photo.jpg?v=${S.photoVersion}" alt=""><span>Ma photo</span></button>`);
    }
    $('#wpGrid').innerHTML = tiles.join('');
    $$('[data-wp]').forEach(b => b.addEventListener('click', () => {
      $$('[data-wp]').forEach(x => x.classList.toggle('on', x === b));
      post('/wallpaper/set', { id: b.dataset.wp }).then(say).catch(fail);
    }));
    dim.value = S.dim;
    dimv.textContent = S.dim + ' %';
    $('#unsplashOff').hidden = !!S.unsplash;
    $('#unsplashForm').hidden = !S.unsplash;
    $('#creditNow').textContent = S.wallpaper === 'photo' && S.credit ? 'Fond actuel · ' + S.credit : '';
  }

  /* ================= Réglages : accueil ================= */
  function sw(checked, attrs) {
    return `<input type="checkbox" class="switch" ${checked ? 'checked' : ''} ${attrs}>`;
  }
  function renderHome() {
    $$('#daysSeg button').forEach(b => b.classList.toggle('on', +b.dataset.days === (S.agendaDays || 1)));
    $('#widgetList').innerHTML = S.home.map(w =>
      `<label class="row-line"><span class="grow">${esc(w.name)}</span>${sw(w.on, 'data-w="' + w.id + '"')}</label>`).join('');
    $$('[data-w]').forEach(i => i.addEventListener('change', () =>
      post('/home', { id: i.dataset.w, on: i.checked ? '1' : '0' }).catch(fail)));

    $('#noteList').innerHTML = S.notes.length ? S.notes.map((n, i) => `
      <div class="note-item">
        <textarea data-note="${i}" maxlength="280" placeholder="Ex. Judo à 17h, penser au sac">${esc(n.text)}</textarea>
        <div class="side">
          ${sw(n.shown, 'data-note-shown="' + i + '" aria-label="Afficher sur la TV"')}
          <button class="mini" data-note-del="${i}" aria-label="Supprimer"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
        </div>
      </div>`).join('') : '<p class="fine">Aucune note. Elles s\'affichent sur l\'accueil, sous la météo.</p>';
    let noteTimer;
    $$('[data-note]').forEach(t => t.addEventListener('input', () => {
      S.notes[+t.dataset.note].text = t.value;
      clearTimeout(noteTimer);
      noteTimer = setTimeout(saveNotes, 600);
    }));
    $$('[data-note-shown]').forEach(c => c.addEventListener('change', () => { S.notes[+c.dataset.noteShown].shown = c.checked; saveNotes(); }));
    $$('[data-note-del]').forEach(b => b.addEventListener('click', () => { S.notes.splice(+b.dataset.noteDel, 1); saveNotes(); renderHome(); }));

    $('#calList').innerHTML = S.calendars.map((c, i) => `
      <div class="row-line">
        <div class="grow"><b>${esc(c.name)}</b><small>${esc(c.url.replace(/^(https?|webcal):\/\//, '').slice(0, 48))}…</small></div>
        ${sw(c.shown, 'data-cal-shown="' + i + '" aria-label="Afficher sur la TV"')}
        <button class="mini" data-cal-del="${i}" aria-label="Supprimer"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
      </div>`).join('');
    $$('[data-cal-shown]').forEach(c => c.addEventListener('change', () => { S.calendars[+c.dataset.calShown].shown = c.checked; saveCals(); }));
    $$('[data-cal-del]').forEach(b => b.addEventListener('click', () => { S.calendars.splice(+b.dataset.calDel, 1); saveCals(); renderHome(); }));
  }
  $$('#daysSeg button').forEach(b => b.addEventListener('click', () => {
    $$('#daysSeg button').forEach(x => x.classList.toggle('on', x === b));
    post('/agenda/days', { days: b.dataset.days }).catch(fail);
  }));
  const saveNotes = () => post('/notes', { data: JSON.stringify(S.notes) }).catch(fail);
  const saveCals = () => post('/calendars', { data: JSON.stringify(S.calendars) }).catch(fail);
  $('#addNote').addEventListener('click', () => {
    S.notes.unshift({ id: '', text: '', shown: true });
    renderHome();
    const t = $('[data-note="0"]');
    if (t) t.focus();
  });
  $('#calForm').addEventListener('submit', e => {
    e.preventDefault();
    S.calendars.push({ id: '', name: $('#calName').value.trim() || 'Agenda', url: $('#calUrl').value.trim(), shown: true });
    $('#calName').value = ''; $('#calUrl').value = '';
    saveCals().then(() => { toast('Agenda ajouté'); refresh(); });
  });

  // Recadrage d'une photo : le téléphone envoie directement une image 1920 x 1080.
  const cv = $('#cv'), c = cv.getContext('2d'), W = cv.width, H = cv.height;
  const zoom = $('#zoom'), dim = $('#dim'), dimv = $('#dimv'), guides = $('#guides');
  let img = null, base = 1, s = 1, ox = 0, oy = 0, drag = null;
  let credit = null; // { author, download } si la photo vient d'Unsplash
  // Zones de l'écran TV (fractions de l'écran) : apps, heure et météo, rangée, boutons.
  const zones = [[0.0375, 0.052, 0.2, 0.72, 'Apps'], [0.78, 0.052, 0.178, 0.2, 'Heure · météo'],
    [0.245, 0.53, 0.71, 0.26, 'À suivre'], [0.70, 0.885, 0.258, 0.063, 'Boutons']];
  function clamp() {
    const w = img.width * base * s, h = img.height * base * s;
    ox = Math.min(0, Math.max(W - w, ox));
    oy = Math.min(0, Math.max(H - h, oy));
  }
  function draw() {
    if (!img) return;
    clamp();
    c.clearRect(0, 0, W, H);
    c.drawImage(img, ox, oy, img.width * base * s, img.height * base * s);
    const dark = S ? S.dark : true, a = dim.value / 100;
    c.fillStyle = dark ? 'rgba(0,0,0,' + a + ')' : 'rgba(255,255,255,' + a + ')';
    c.fillRect(0, 0, W, H);
    const g = c.createLinearGradient(0, 0, W, 0);
    const tint = dark ? '0,0,0' : '255,255,255';
    g.addColorStop(0, 'rgba(' + tint + ',.65)'); g.addColorStop(0.32, 'rgba(' + tint + ',.3)'); g.addColorStop(1, 'rgba(' + tint + ',0)');
    c.fillStyle = g; c.fillRect(0, 0, W, H);
    if (guides.checked) {
      c.setLineDash([10, 7]); c.lineWidth = 2; c.font = '600 18px -apple-system, system-ui, sans-serif';
      for (const z of zones) {
        const x = z[0] * W, y = z[1] * H, w = z[2] * W, h = z[3] * H;
        c.fillStyle = 'rgba(255,255,255,.12)'; c.fillRect(x, y, w, h);
        c.strokeStyle = 'rgba(255,255,255,.9)'; c.strokeRect(x, y, w, h);
        c.fillStyle = '#fff'; c.fillText(z[4], x + 10, y + 26);
      }
      c.setLineDash([]);
    }
  }
  function openEditor(src, cross, onFail) {
    const im = new Image();
    if (cross) im.crossOrigin = 'anonymous';
    im.onload = () => {
      img = im; base = Math.max(W / im.width, H / im.height); s = 1; zoom.value = 1;
      ox = (W - im.width * base) / 2; oy = (H - im.height * base) / 2;
      $('#editor').hidden = false; draw();
      $('#editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
    };
    im.onerror = () => { if (onFail) onFail(); toast('Impossible de charger cette photo.'); };
    im.src = src;
  }
  $('#file').addEventListener('change', () => {
    const f = $('#file').files[0];
    if (!f) return;
    credit = null;
    openEditor(URL.createObjectURL(f), false);
  });

  // Recherche de photos Unsplash (la clé reste sur la TV).
  $('#unsplashForm').addEventListener('submit', e => {
    e.preventDefault();
    const term = $('#uq').value.trim();
    if (!term) return;
    $('#uq').blur();
    $('#ugrid').innerHTML = '<p class="fine">Recherche…</p>';
    post('/unsplash', { q: term }).then(r => {
      const res = (r && r.results) || [];
      if (!res.length) { $('#ugrid').innerHTML = '<p class="fine">Aucune photo trouvée.</p>'; return; }
      $('#ugrid').innerHTML = res.map((p, i) =>
        `<button data-u="${i}" style="background:${esc(p.color || '#222')}" aria-label="Photo de ${esc(p.author)}"><img src="${esc(p.thumb)}" alt="" loading="lazy"></button>`).join('');
      $$('[data-u]').forEach(b => b.addEventListener('click', () => {
        const p = res[+b.dataset.u];
        b.classList.add('loading');
        credit = { author: p.author, download: p.download };
        openEditor(p.full, true, () => b.classList.remove('loading'));
        setTimeout(() => b.classList.remove('loading'), 1500);
      }));
    }).catch(fail);
  });
  const ratio = () => W / cv.clientWidth;
  cv.addEventListener('pointerdown', e => { if (!img) return; drag = { x: e.clientX, y: e.clientY, ox, oy }; cv.setPointerCapture(e.pointerId); });
  cv.addEventListener('pointermove', e => {
    if (!drag) return;
    ox = drag.ox + (e.clientX - drag.x) * ratio(); oy = drag.oy + (e.clientY - drag.y) * ratio(); draw();
  });
  cv.addEventListener('pointerup', () => { drag = null; });
  cv.addEventListener('pointercancel', () => { drag = null; });
  zoom.addEventListener('input', () => {
    if (!img) return;
    const cx = (W / 2 - ox) / (base * s), cy = (H / 2 - oy) / (base * s);
    s = +zoom.value; ox = W / 2 - cx * base * s; oy = H / 2 - cy * base * s; draw();
  });
  dim.addEventListener('input', () => { dimv.textContent = dim.value + ' %'; draw(); });
  dim.addEventListener('change', () => { if (!img && S && S.wallpaper === 'photo') post('/wallpaper/dim', { dim: dim.value }).catch(fail); });
  guides.addEventListener('change', draw);
  $('#sendPhoto').addEventListener('click', () => {
    if (!img) return;
    const btn = $('#sendPhoto');
    const out = document.createElement('canvas');
    out.width = 1920; out.height = 1080;
    const r = 1920 / W;
    out.getContext('2d').drawImage(img, ox * r, oy * r, img.width * base * s * r, img.height * base * s * r);
    btn.disabled = true; btn.textContent = 'Envoi…';
    out.toBlob(b => {
      const fd = new FormData();
      fd.append('dim', dim.value);
      if (credit) { fd.append('author', credit.author || ''); fd.append('download', credit.download || ''); }
      fd.append('photo', b, 'fond.jpg');
      fetch('/wallpaper', { method: 'POST', body: fd, credentials: 'same-origin' })
        .then(r => r.json()).then(r => { say(r); $('#editor').hidden = true; img = null; refresh(); })
        .catch(fail)
        .finally(() => { btn.disabled = false; btn.textContent = 'Mettre en fond d\'écran'; });
    }, 'image/jpeg', 0.88);
  });

  /* ================= Réglages : apps ================= */
  const NEXT = 'launcher.next';
  const appIcon = pkg => pkg === NEXT
    ? '<span class="app-icon next-icon"><svg viewBox="0 0 24 24"><path d="M6 5l8 7-8 7z" class="fill"/><path d="M18 5v14"/></svg></span>'
    : `<img class="app-icon" src="/appicon/${encodeURIComponent(pkg)}.png" alt="" onerror="this.style.visibility='hidden'">`;
  function saveMenu() {
    return post('/menu', { data: JSON.stringify(S.menu.map(m => ({ pkg: m.pkg, name: m.label !== m.original ? m.label : '' }))) })
      .catch(fail);
  }
  function renderApps() {
    const list = $('#menuList');
    list.innerHTML = S.menu.length ? S.menu.map(m => `
      <div class="item app" data-pkg="${esc(m.pkg)}">
        <span class="grip" aria-label="Déplacer">⠿</span>
        ${appIcon(m.pkg)}
        <div class="txt"><b class="rename">${esc(m.label)}</b>${m.label !== m.original ? `<small>${esc(m.original)}</small>` : ''}</div>
        <button class="mini" data-hide aria-label="Masquer"><svg viewBox="0 0 24 24"><path d="M6 12h12"/></svg></button>
      </div>`).join('') : '<p class="empty">Aucune app dans la colonne.</p>';

    const others = $('#otherList');
    others.innerHTML = S.others.length ? S.others.map(o => `
      <div class="item" data-pkg="${esc(o.pkg)}">
        ${appIcon(o.pkg)}
        <div class="txt"><b>${esc(o.label)}</b></div>
        <button class="mini add" data-show aria-label="Afficher"><svg viewBox="0 0 24 24"><path d="M12 6v12M6 12h12"/></svg></button>
      </div>`).join('') : '<p class="empty">Toutes les apps sont affichées.</p>';

    const find = el => S.menu.find(m => m.pkg === el.closest('[data-pkg]').dataset.pkg);
    $$('[data-hide]', list).forEach(b => b.addEventListener('click', () => {
      const m = find(b);
      S.menu = S.menu.filter(x => x !== m);
      S.others.unshift({ pkg: m.pkg, label: m.original });
      renderApps(); saveMenu();
    }));
    $$('[data-show]', others).forEach(b => b.addEventListener('click', () => {
      const pkg = b.closest('[data-pkg]').dataset.pkg;
      const o = S.others.find(x => x.pkg === pkg);
      S.others = S.others.filter(x => x !== o);
      S.menu.push({ pkg, label: o.label, original: o.label });
      renderApps(); saveMenu();
    }));
    $$('.rename', list).forEach(el => el.addEventListener('click', () => rename(el, find(el))));
    sortable(list);
  }

  function rename(el, m) {
    const input = document.createElement('input');
    input.value = m.label;
    input.maxLength = 40;
    input.setAttribute('aria-label', 'Nom affiché sur la TV');
    el.replaceWith(input);
    input.focus();
    input.select();
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      m.label = input.value.trim() || m.original;
      renderApps(); saveMenu();
    };
    input.addEventListener('blur', finish);
    input.addEventListener('keydown', e => { if (e.key === 'Enter') input.blur(); if (e.key === 'Escape') { input.value = m.label; input.blur(); } });
  }

  // Glisser-déposer au doigt (iPhone compris) avec la poignée ⠿.
  function sortable(list) {
    $$('.grip', list).forEach(grip => {
      grip.addEventListener('pointerdown', e => {
        e.preventDefault();
        const item = grip.closest('.item');
        item.classList.add('dragging');
        buzz();
        const move = ev => {
          const y = ev.clientY;
          const sibs = $$('.item.app:not(.dragging)', list);
          let before = null;
          for (const sib of sibs) {
            const r = sib.getBoundingClientRect();
            if (y < r.top + r.height / 2) { before = sib; break; }
          }
          if (before) { if (item.nextElementSibling !== before) list.insertBefore(item, before); }
          else if (list.lastElementChild !== item) list.appendChild(item);
        };
        // Écoute sur toute la page : déplacer l'élément dans la liste ferait perdre le suivi du doigt.
        const up = () => {
          document.removeEventListener('pointermove', move);
          document.removeEventListener('pointerup', up);
          document.removeEventListener('pointercancel', up);
          item.classList.remove('dragging');
          const order = $$('.item.app', list).map(el => el.dataset.pkg);
          S.menu.sort((a, b) => order.indexOf(a.pkg) - order.indexOf(b.pkg));
          saveMenu();
        };
        document.addEventListener('pointermove', move);
        document.addEventListener('pointerup', up);
        document.addEventListener('pointercancel', up);
      });
    });
  }

  /* ================= Rendu ================= */
  function renderMenu() {
    $('#mPins').textContent = S.pins.length ? S.pins.length + ' dans la rangée À suivre' : 'Aucun pour l\'instant';
    const k = S.kids;
    $('#mKids').textContent = k.locked ? 'TV verrouillée' : k.next ? 'Extinction à ' + hm(k.next) : k.end ? 'Extinction en fin de contenu' : 'Extinction, code';
  }
  /* ================= Clés API et guide de démarrage ================= */
  const KEYS = {
    tmdb: {
      name: 'TMDB', need: 'Indispensable',
      why: 'Les films et les séries : la recherche, « À regarder », l\'âge des titres, et surtout sur quelle plateforme c\'est disponible en Belgique.',
      steps: [
        ['Crée un compte gratuit sur TMDB.', 'https://www.themoviedb.org/signup', 'Créer un compte'],
        ['Va dans Paramètres > API et demande une clé « Developer ». Pour l\'usage, indique quelque chose comme « Accueil TV perso ».', 'https://www.themoviedb.org/settings/api', 'Ouvrir la page API'],
        ['Copie la « Clé d\'API » (ou le long « Jeton d\'accès en lecture », les deux marchent) et colle-la ci-dessous.']
      ]
    },
    youtube: {
      name: 'YouTube', need: 'Facultatif',
      why: 'Chercher des vidéos et des chaînes YouTube depuis le téléphone, et lancer les bandes-annonces.',
      steps: [
        ['Ouvre la console Google Cloud avec ton compte Google et crée un projet (le nom n\'a pas d\'importance).', 'https://console.cloud.google.com/projectcreate', 'Créer un projet'],
        ['Active « YouTube Data API v3 » pour ce projet.', 'https://console.cloud.google.com/apis/library/youtube.googleapis.com', 'Activer l\'API'],
        ['Dans Identifiants, choisis Créer des identifiants > Clé API, copie-la et colle-la ci-dessous.', 'https://console.cloud.google.com/apis/credentials', 'Ouvrir Identifiants']
      ]
    },
    unsplash: {
      name: 'Unsplash', need: 'Facultatif',
      why: 'De belles photos libres de droits en fond d\'écran, avec le crédit du photographe.',
      steps: [
        ['Inscris-toi comme développeur Unsplash (c\'est gratuit).', 'https://unsplash.com/developers', 'Ouvrir Unsplash Developers'],
        ['Crée une nouvelle application et accepte les conditions.', 'https://unsplash.com/oauth/applications/new', 'Nouvelle application'],
        ['Copie l\'« Access Key » et colle-la ci-dessous. Surtout pas la « Secret Key », garde-la pour toi.']
      ]
    }
  };
  function keyBlock(name) {
    const k = KEYS[name];
    const st = (S && S.keys && S.keys[name]) || {};
    return `<div class="card key-card" data-key="${name}">
      <div class="key-head"><h2>${k.name}</h2>
        <span class="key-state ${st.set ? 'on' : ''}">${st.set ? 'Active ••••' + esc(st.end || '') : k.need}</span></div>
      <p class="fine">${esc(k.why)}</p>
      <ol class="key-steps">${k.steps.map(([t, url, lbl]) => `<li>${esc(t)}${url ? ` <a href="${url}" target="_blank" rel="noopener">${esc(lbl)} ↗</a>` : ''}</li>`).join('')}</ol>
      <div class="key-row"><input class="text-in" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="${st.set ? 'Coller une nouvelle clé' : 'Colle ta clé ici'}" aria-label="Clé ${k.name}">
        <button class="primary small" data-test>Tester</button></div>
      <p class="key-msg" hidden></p>
      ${st.set ? '<button class="linkish" data-clear>Retirer cette clé</button>' : ''}
    </div>`;
  }
  function bindKeyBlocks(root, after) {
    $$('.key-card', root).forEach(card => {
      const name = card.dataset.key;
      const input = $('input', card), msg = $('.key-msg', card), btn = $('[data-test]', card);
      btn.addEventListener('click', () => {
        const v = input.value.trim();
        if (!v) { input.focus(); return; }
        btn.disabled = true; btn.textContent = 'Test…';
        post('/keys', { [name]: v }).then(r => {
          const res = r && r.results && r.results[name];
          msg.hidden = false;
          if (res === 'ok') {
            msg.className = 'key-msg ok'; msg.textContent = '✓ Clé ' + KEYS[name].name + ' enregistrée sur la TV.';
            input.value = '';
            return refresh().then(() => after && after(name));
          }
          msg.className = 'key-msg bad'; msg.textContent = res || 'Réponse inattendue de la TV.';
        }).catch(fail).finally(() => { btn.disabled = false; btn.textContent = 'Tester'; });
      });
      const clear = $('[data-clear]', card);
      if (clear) clear.addEventListener('click', () => {
        if (!confirm('Retirer la clé ' + KEYS[name].name + ' de la TV ?')) return;
        post('/keys', { clear: name }).then(() => refresh()).catch(fail);
      });
    });
  }
  function renderKeys() {
    if (!S.keys) return;
    const n = ['tmdb', 'youtube', 'unsplash'].filter(k => S.keys[k] && S.keys[k].set).length;
    $('#mKeys').textContent = S.keys.tmdb && S.keys.tmdb.set ? n + ' sur 3 actives' : 'TMDB manquante';
    const list = $('#keysList');
    if (list.contains(document.activeElement)) return; // pas pendant la saisie
    list.innerHTML = ['tmdb', 'youtube', 'unsplash'].map(keyBlock).join('');
    bindKeyBlocks(list);
    if (!onbShown && !(S.keys.tmdb && S.keys.tmdb.set) && store.get('onb') !== 'done') openOnb();
  }

  let onbShown = false, onbStep = 0;
  const ONB = ['welcome', 'tmdb', 'youtube', 'unsplash', 'done'];
  function openOnb() {
    onbShown = true; onbStep = 0;
    $('#onb').hidden = false;
    document.body.classList.add('deck-open');
    renderOnb();
  }
  function closeOnb() {
    $('#onb').hidden = true;
    document.body.classList.remove('deck-open');
    store.set('onb', 'done');
  }
  function renderOnb() {
    const step = ONB[onbStep], body = $('#onbBody');
    $('#onbDots').innerHTML = ONB.map((_, i) => `<i class="${i === onbStep ? 'on' : ''}"></i>`).join('');
    $('#onbPrev').style.visibility = onbStep ? 'visible' : 'hidden';
    $('#onbNext').textContent = step === 'done' ? 'C\'est parti' : step === 'welcome' ? 'On y va' : (S.keys && S.keys[step] && S.keys[step].set ? 'Suivant' : (step === 'tmdb' ? 'Passer pour l\'instant' : 'Passer'));
    if (step === 'welcome') {
      body.innerHTML = `<p class="onb-emoji">📺</p><h1>Bienvenue sur ton accueil TV</h1>
        <p>Pour chercher les films, savoir où ils sont disponibles en Belgique et afficher de belles photos, la TV a besoin de quelques <b>clés gratuites</b>.</p>
        <p class="fine">Compte 5 à 10 minutes. Seule TMDB est indispensable, les autres peuvent attendre. Tes clés restent sur ta TV.</p>`;
    } else if (step === 'done') {
      body.innerHTML = `<p class="onb-emoji">🎉</p><h1>C'est prêt</h1>
        <p>Astuce : ajoute cette page à l'écran d'accueil de ton téléphone (Partager, puis « Sur l'écran d'accueil ») pour l'avoir comme une app.</p>
        <p class="fine">Tu retrouves tes clés dans Réglages > Clés API.</p>`;
    } else {
      body.innerHTML = keyBlock(step);
      bindKeyBlocks(body, () => renderOnb());
    }
  }
  $('#onbNext').addEventListener('click', () => { if (onbStep >= ONB.length - 1) closeOnb(); else { onbStep++; renderOnb(); } });
  $('#onbPrev').addEventListener('click', () => { if (onbStep > 0) { onbStep--; renderOnb(); } });
  $('#onbSkip').addEventListener('click', closeOnb);
  $('#onbAgain').addEventListener('click', openOnb);

  function renderVO() {
    const on = S.trailerVO !== false;
    $$('#voSeg button').forEach(b => b.classList.toggle('on', (b.dataset.vo === '1') === on));
  }
  $$('#voSeg button').forEach(b => b.addEventListener('click', () =>
    post('/trailer/vo', { on: b.dataset.vo }).then(r => { say(r); refresh(); }).catch(fail)));

  function renderAll() {
    if (!S) return;
    renderVO();
    renderKeys();
    renderTypeTarget();
    renderShortcuts();
    renderTimer();
    renderMenu();
    renderPins();
    renderKids();
    renderDefis();
    renderLook();
    renderApps();
    // Les notes en cours de saisie ne doivent pas être écrasées par un rafraîchissement.
    if (!document.activeElement || !document.activeElement.matches('textarea, #calName, #calUrl')) renderHome();
  }

  const start = store.get('tab');
  go(TABS.indexOf(start) >= 0 ? start : 'remote');
  refresh();
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
})();
