// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Enhancements; every page works without this file (navigation falls back to nojs.css).
// No third-party code is loaded unless the visitor agrees to statistics, and only when a
// Google Tag Manager container is configured.
(function () {
  'use strict';
  var doc = document.documentElement;
  var de = doc.lang === 'de';
  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  };
  var focusable = 'a[href],button:not([disabled]),[tabindex]:not([tabindex="-1"])';

  // ---------- Navigation: dropdown panels (click, Enter, Space; Escape closes) ----------
  var triggers = Array.prototype.slice.call(document.querySelectorAll('.nav-trigger'));
  function closePanels(except) {
    triggers.forEach(function (t) { if (t !== except) t.setAttribute('aria-expanded', 'false'); });
  }
  triggers.forEach(function (t) {
    t.addEventListener('click', function () {
      var open = t.getAttribute('aria-expanded') !== 'true';
      closePanels(t);
      t.setAttribute('aria-expanded', String(open));
    });
  });
  document.addEventListener('click', function (e) {
    if (!e.target.closest || !e.target.closest('.nav-group')) {
      if (!isMobile()) closePanels(null);
    }
  });
  document.addEventListener('focusin', function (e) {
    if (!isMobile() && e.target.closest && !e.target.closest('.nav-group')) closePanels(null);
  });

  // ---------- Mobile menu: button, focus trap, Escape ----------
  var menuBtn = document.querySelector('.menu-button');
  var nav = document.getElementById('site-nav');
  var head = document.querySelector('.site-head');
  var mq = window.matchMedia('(max-width: 1179px)');
  function isMobile() { return mq.matches; }
  function setMenu(open, restoreFocus) {
    if (!menuBtn || !nav) return;
    menuBtn.setAttribute('aria-expanded', String(open));
    nav.classList.toggle('open', open);
    document.body.classList.toggle('menu-open', open);
    if (open) {
      var first = nav.querySelector(focusable);
      if (first) first.focus();
    } else if (restoreFocus) {
      menuBtn.focus();
    }
  }
  if (menuBtn && nav) {
    menuBtn.addEventListener('click', function () { setMenu(menuBtn.getAttribute('aria-expanded') !== 'true', true); });
    mq.addEventListener('change', function () { if (!isMobile()) setMenu(false, false); });
    head.addEventListener('keydown', function (e) {
      if (!nav.classList.contains('open') || e.key !== 'Tab') return;
      var items = [menuBtn].concat(Array.prototype.slice.call(nav.querySelectorAll(focusable)))
        .filter(function (el) { return el.offsetParent !== null; });
      var first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
  }
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    var openTrigger = triggers.filter(function (t) { return t.getAttribute('aria-expanded') === 'true'; })[0];
    if (openTrigger && !isMobile()) { closePanels(null); openTrigger.focus(); return; }
    if (nav && nav.classList.contains('open')) setMenu(false, true);
  });

  // ---------- Motion: allowed unless reduced motion is asked for; one shared pause switch ----------
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)');
  var listeners = [];
  var motion = {
    paused: function () { return store.get('vectolyn-motion') === 'paused'; },
    onChange: function (fn) { listeners.push(fn); }
  };
  function applyMotion() {
    doc.classList.toggle('motion', !reduce.matches);
    doc.classList.toggle('paused', motion.paused());
    document.querySelectorAll('[data-motion-toggle]').forEach(function (b) {
      b.hidden = reduce.matches || (b.hasAttribute('data-needs-3d') && !doc.classList.contains('has-3d'));
      b.textContent = b.getAttribute(motion.paused() ? 'data-label-play' : 'data-label-pause');
    });
    listeners.forEach(function (fn) { fn(motion.paused() || reduce.matches); });
  }
  document.querySelectorAll('[data-motion-toggle]').forEach(function (b) {
    b.addEventListener('click', function () {
      store.set('vectolyn-motion', motion.paused() ? 'running' : 'paused');
      applyMotion();
    });
  });
  reduce.addEventListener('change', applyMotion);
  applyMotion();

  // ---------- 3D hero scene: only on large, capable screens, after load, when idle ----------
  var sceneFig = document.querySelector('[data-scene]');
  function canRun3d() {
    if (!sceneFig || reduce.matches || !window.matchMedia('(min-width: 1000px)').matches) return false;
    var c = navigator.connection;
    if (c && (c.saveData || /2g/.test(c.effectiveType || ''))) return false;
    if ((navigator.hardwareConcurrency || 0) < 4 || (navigator.deviceMemory && navigator.deviceMemory < 4)) return false;
    try { var cv = document.createElement('canvas'); return !!(cv.getContext('webgl2') || cv.getContext('webgl')); } catch (e) { return false; }
  }
  if (canRun3d()) {
    var idle = window.requestIdleCallback || function (fn) { return setTimeout(fn, 1200); };
    window.addEventListener('load', function () {
      idle(function () {
        var src = document.querySelector('script[src$="site.js"]').src.replace(/site\.js$/, 'scene.js');
        import(src).then(function (m) {
          m.start(sceneFig, { paused: function () { return motion.paused(); }, onChange: motion.onChange });
          doc.classList.add('has-3d');
          applyMotion();
        }).catch(function () { /* the SVG stays */ });
      }, { timeout: 4000 });
    });
  }

  // ---------- Colour mode: follows the system until the visitor picks one ----------
  var themeBtn = document.querySelector('[data-theme-toggle]');
  var sysDark = window.matchMedia('(prefers-color-scheme: dark)');
  function effective() { return doc.getAttribute('data-theme') || (sysDark.matches ? 'dark' : 'light'); }
  function paintThemeBtn() {
    if (!themeBtn) return;
    var target = effective() === 'dark' ? 'light' : 'dark';
    themeBtn.hidden = false;
    themeBtn.setAttribute('data-target', target);
    themeBtn.querySelector('.theme-label').textContent = themeBtn.getAttribute('data-label-' + target);
  }
  if (themeBtn) {
    themeBtn.addEventListener('click', function () {
      var t = themeBtn.getAttribute('data-target');
      doc.setAttribute('data-theme', t);
      store.set('vectolyn-theme', t);
      paintThemeBtn();
    });
    sysDark.addEventListener('change', paintThemeBtn);
    paintThemeBtn();
  }

  // ---------- Scroll reveal: only for sections below the first screen, only with motion ----------
  if ('IntersectionObserver' in window) {
    var revealIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('revealed'); revealIO.unobserve(e.target); } });
    }, { rootMargin: '0px 0px -10% 0px' });
    document.querySelectorAll('main .band').forEach(function (el) {
      if (reduce.matches || motion.paused()) return;
      if (el.getBoundingClientRect().top > window.innerHeight) { el.classList.add('reveal'); revealIO.observe(el); }
    });
  }

  // ---------- Header: shrinks after the first scroll; thin scroll progress bar ----------
  var bar = document.querySelector('.progress span');
  var ticking = false;
  function onScroll() {
    ticking = false;
    var y = window.scrollY || 0;
    if (head) head.classList.toggle('scrolled', y > 24);
    if (bar) {
      var max = document.documentElement.scrollHeight - window.innerHeight;
      bar.style.setProperty('--p', max > 0 ? Math.min(1, y / max).toFixed(4) : '0');
    }
  }
  window.addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(onScroll); } }, { passive: true });
  onScroll();

  // ---------- Big numbers count up briefly once visible (only with motion); bars grow ----------
  if ('IntersectionObserver' in window) {
    var countIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        countIO.unobserve(e.target);
        var el = e.target;
        if (el.classList.contains('bar')) { el.classList.add('grown'); return; }
        var end = parseInt(el.getAttribute('data-count'), 10);
        if (!(end > 0) || reduce.matches || motion.paused()) return;
        var t0 = performance.now(), dur = 700;
        var step = function (now) {
          var k = Math.min(1, (now - t0) / dur);
          el.textContent = String(Math.max(1, Math.round(end * (1 - Math.pow(1 - k, 3)))));
          if (k < 1) requestAnimationFrame(step); else el.textContent = String(end);
        };
        requestAnimationFrame(step);
      });
    }, { threshold: 0.6 });
    document.querySelectorAll('[data-count],.bar').forEach(function (el) { countIO.observe(el); });
  }

  // ---------- Copy the server address ----------
  document.querySelectorAll('[data-copy]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var status = document.querySelector('[data-copy-status]');
      var label = btn.textContent;
      var show = function (msg) {
        btn.textContent = msg;
        if (status) status.textContent = msg;
        setTimeout(function () { btn.textContent = label; }, 2000);
      };
      if (!navigator.clipboard) { show(de ? 'Bitte von Hand kopieren' : 'Please copy by hand'); return; }
      navigator.clipboard.writeText(btn.getAttribute('data-copy')).then(
        function () { show(btn.getAttribute('data-copied')); },
        function () { show(de ? 'Kopieren nicht möglich' : 'Copy failed'); });
    });
  });

  // ---------- Live status of the test network (only with a configured status URL). Text only. ----------
  document.querySelectorAll('[data-status-url]').forEach(function (card) {
    var text = card.querySelector('[data-status-text]');
    var dot = card.querySelector('[data-status-dot]');
    var set = function (cls, msg) { dot.className = 'dot ' + cls; text.textContent = msg; };
    var ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 5000);
    fetch(card.getAttribute('data-status-url'), { cache: 'no-store', signal: ctrl ? ctrl.signal : undefined })
      .then(function (r) { if (!r.ok) throw new Error(String(r.status)); return r.json(); })
      .then(function (s) {
        clearTimeout(timer);
        if (!s || s.online !== true) { set('dot-off', de ? 'Gerade offline' : 'Offline right now'); return; }
        var n = Number.isInteger(s.players) ? s.players : null;
        set('dot-on', 'Online');
        var put = function (key, value) { var el = card.querySelector('[data-status-' + key + ']'); if (el && value !== null && value !== undefined && value !== '') el.textContent = String(value); };
        put('players', n === null ? null : (s.max ? n + ' / ' + s.max : n));
        put('uptime', typeof s.uptime30d === 'number' ? (Math.round(s.uptime30d * 10) / 10).toLocaleString(doc.lang) + ' %' : null);
        put('version', typeof s.version === 'string' ? s.version.slice(0, 40) : null);
        put('patch', typeof s.patch === 'string' ? s.patch.slice(0, 40) : null);
      })
      .catch(function () {
        clearTimeout(timer);
        set('dot-error', de ? 'Status nicht abrufbar' : 'Status unavailable');
      });
  });

  // ---------- Consent for statistics (Google Tag Manager), only when configured ----------
  var gtmId = doc.getAttribute('data-gtm');
  var banner = document.querySelector('[data-consent]');
  if (!gtmId || !banner) return;
  var KEY = 'vectolyn-consent';
  var loaded = false;
  var load = function () {
    if (loaded || !/^GTM-[A-Z0-9]+$/.test(gtmId)) return;
    loaded = true;
    window.dataLayer = window.dataLayer || [];
    window.dataLayer.push({ 'gtm.start': Date.now(), event: 'gtm.js' });
    var s = document.createElement('script');
    s.async = true;
    s.src = 'https://www.googletagmanager.com/gtm.js?id=' + encodeURIComponent(gtmId);
    document.head.appendChild(s);
  };
  var choice = store.get(KEY);
  if (choice === 'granted') load();
  else if (choice !== 'denied') banner.hidden = false;
  banner.querySelectorAll('[data-consent-choice]').forEach(function (b) {
    b.addEventListener('click', function () {
      var v = b.getAttribute('data-consent-choice');
      store.set(KEY, v);
      banner.hidden = true;
      if (v === 'granted') load();
      else if (loaded) window.location.reload();
    });
  });
  document.querySelectorAll('[data-consent-open]').forEach(function (b) {
    b.addEventListener('click', function () { banner.hidden = false; banner.querySelector('button').focus(); });
  });
}());
