package kira.phoneuse.runtime

/**
 * The computer-side phone window: the projected screen and nothing else. A frosted pill at the
 * bottom of the screen shows what the agent is doing (click, scroll, type, open ...); clicks also
 * move a mouse pointer to the exact spot and ripple there. Margins the window leaves around the
 * screen show a blurred extension of the screen instead of black. Clicking or dragging on the
 * screen takes over (recorded as teaching); during a takeover the
 * pill finishes it. Served by the phone-side server at /ui (`?lane=` picks a lane).
 */
internal object PhoneWindowPage {
    // Kotlin raw string: the page deliberately uses no dollar signs.
    val html: String = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Phone Use</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
  body { font: 500 13px/1 system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; }
  #backdrop { position: fixed; inset: -40px; width: calc(100% + 80px); height: calc(100% + 80px); object-fit: cover;
              filter: blur(28px) brightness(.75) saturate(1.2); pointer-events: none; }
  #stage { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; }
  #wrap { position: relative; height: 100%; aspect-ratio: var(--ar, 0.45); max-width: 100%; }
  #screen { width: 100%; height: 100%; display: block; object-fit: contain; user-select: none;
            -webkit-user-drag: none; touch-action: none; cursor: default; }
  #pointer { position: absolute; width: 22px; height: 22px; left: 0; top: 0; pointer-events: none; opacity: 0;
             transition: left .35s cubic-bezier(.2,.8,.2,1), top .35s cubic-bezier(.2,.8,.2,1), opacity .25s;
             filter: drop-shadow(0 1px 2px rgba(0,0,0,.55)); z-index: 3; }
  .ripple { position: absolute; width: 44px; height: 44px; margin: -22px 0 0 -22px; border-radius: 50%;
            border: 2px solid rgba(255,255,255,.9); background: rgba(255,255,255,.18); pointer-events: none;
            animation: ripple .6s ease-out forwards; z-index: 2; }
  @keyframes ripple { from { transform: scale(.3); opacity: 1; } to { transform: scale(1.4); opacity: 0; } }
  #pill { position: absolute; left: 50%; bottom: 18px; transform: translateX(-50%); z-index: 4;
          display: flex; align-items: center; gap: 8px; max-width: calc(100% - 32px); padding: 9px 16px;
          border-radius: 999px; color: #fff; white-space: nowrap;
          background: rgba(28,28,32,.38); border: 1px solid rgba(255,255,255,.22);
          -webkit-backdrop-filter: blur(18px) saturate(1.6); backdrop-filter: blur(18px) saturate(1.6);
          box-shadow: 0 6px 24px rgba(0,0,0,.28), inset 0 1px 0 rgba(255,255,255,.18);
          transition: opacity .3s, transform .3s; }
  #pill.hide { opacity: 0; transform: translate(-50%, 8px); }
  #pill.tap { cursor: pointer; }
  #pill .icon { width: 16px; height: 16px; flex: none; display: none; }
  #pill .icon.on { display: block; }
  #pill .icon svg { width: 16px; height: 16px; fill: none; stroke: #fff; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
  #pill .verb { font-weight: 650; letter-spacing: .2px; text-transform: lowercase; }
  #pill .detail { opacity: .82; overflow: hidden; text-overflow: ellipsis; max-width: 220px; }
  #pill .dot { width: 7px; height: 7px; border-radius: 50%; background: #ff453a; box-shadow: 0 0 6px #ff453a; display: none; }
  #pill.rec .dot { display: block; }
  #pill.err .verb { color: #ffb4ab; }
  #notificationProtection { position: fixed; right: 12px; top: 12px; z-index: 5; padding: 8px 12px;
    border-radius: 16px; border: 1px solid rgba(255,255,255,.3); color: #fff;
    background: rgba(28,28,32,.65); cursor: pointer; font: inherit; }
</style>
</head>
<body>
<img id="backdrop" alt="">
<button id="notificationProtection" type="button" aria-pressed="true" title="只控制通知遮挡检查，不改变手机免打扰">通知保护：开</button>
<div id="stage">
  <div id="wrap">
    <img id="screen" alt="" draggable="false">
    <svg id="pointer" viewBox="0 0 24 24"><path d="M4 2.5l15.5 10.2-6.6 1.3 3.9 7.3-2.9 1.5-3.9-7.4-4.6 4.6z" fill="#fff" stroke="#111" stroke-width="1.3" stroke-linejoin="round"/></svg>
    <div id="pill" class="hide"><span class="dot"></span><span class="icon"></span><span class="verb"></span><span class="detail"></span></div>
  </div>
</div>
<script>
(function () {
  var lane = new URLSearchParams(location.search).get('lane') || '';
  var windowKey = new URLSearchParams(location.search).get('window_key') || '';
  if (/^[a-f0-9]{16}$/.test(windowKey)) document.title = 'Phone Use [' + windowKey + ']';
  var q = lane ? ('lane=' + encodeURIComponent(lane) + '&') : '';
  var screen = document.getElementById('screen');
  var backdrop = document.getElementById('backdrop');
  var wrap = document.getElementById('wrap');
  var pointer = document.getElementById('pointer');
  var pill = document.getElementById('pill');
  var protection = document.getElementById('notificationProtection');
  var verbEl = pill.querySelector('.verb');
  var detailEl = pill.querySelector('.detail');
  var iconEl = pill.querySelector('.icon');
  // Line icons (24x24, stroked) for each action the pill names.
  var ICONS = {
    'click': '<path d="M9 11V5.5a1.5 1.5 0 0 1 3 0V10m0-1.5a1.5 1.5 0 0 1 3 0V11m0-1a1.5 1.5 0 0 1 3 0v4.5a6 6 0 0 1-6 6h-.5a6 6 0 0 1-4.9-2.6L4.3 14.6a1.5 1.5 0 0 1 2.4-1.8L9 15"/>',
    'long press': '<circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="7" opacity=".55"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2"/>',
    'scroll': '<path d="M12 3v18M7 8l5-5 5 5M7 16l5 5 5-5"/>',
    'type': '<rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/>',
    'clear': '<path d="M20 5H9l-6 7 6 7h11a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1zM12 9l6 6M18 9l-6 6"/>',
    'open': '<rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><path d="M17.2 14v6.5M14 17.2h6.5"/>',
    'back': '<path d="M15 5l-7 7 7 7"/>',
    'home': '<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
    'key': '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 15h10"/>',
    'drag': '<path d="M5 12h14M5 12l3-3M5 12l3 3M19 12l-3-3M19 12l-3 3"/><circle cx="12" cy="12" r="2"/>',
    'look': '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    'wait': '<path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/>',
    'switch screen': '<rect x="2" y="5" width="9" height="14" rx="1.5"/><rect x="13" y="5" width="9" height="14" rx="1.5"/><path d="M9 12h6M13 10l2 2-2 2"/>',
    '示教中': '<path d="M12 3a4 4 0 1 1 0 8 4 4 0 0 1 0-8zM4 21a8 8 0 0 1 16 0"/>',
    '录制中': '',
    '等待中': '<path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/>'
  };
  var state = { width: 1080, height: 2400, teaching: false };
  var lastSeq = 0, hideTimer = null, fitted = false, polling = false;

  function post(path, body) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json(); }).then(apply).catch(function () {});
  }

  // Size the window to the phone's own shape once, so there is no empty band around the screen.
  function fitWindow() {
    if (!state.width || !state.height) return;
    var ar = state.width / state.height;
    document.documentElement.style.setProperty('--ar', ar);
    if (fitted) return;
    fitted = true;
    try {
      var chromeW = window.outerWidth - window.innerWidth, chromeH = window.outerHeight - window.innerHeight;
      var h = Math.min(window.innerHeight, window.screen.availHeight - chromeH - 40);
      window.resizeTo(Math.round(h * ar) + chromeW, Math.round(h) + chromeH);
    } catch (e) {}
  }

  function showPill(verb, detail, sticky, err) {
    var icon = ICONS[verb];
    iconEl.innerHTML = icon ? '<svg viewBox="0 0 24 24">' + icon + '</svg>' : '';
    iconEl.className = icon ? 'icon on' : 'icon';
    verbEl.textContent = verb;
    detailEl.textContent = detail ? '· ' + detail : '';
    pill.classList.remove('hide');
    pill.classList.toggle('err', !!err);
    clearTimeout(hideTimer);
    if (!sticky) hideTimer = setTimeout(function () { if (!state.teaching && !state.recording) pill.classList.add('hide'); }, 2600);
  }

  // Where the picture really is inside the img box (object-fit: contain), relative to #wrap.
  function content() {
    var r = screen.getBoundingClientRect(), w0 = wrap.getBoundingClientRect(), ar = state.width / state.height;
    var w = r.width, h = r.height;
    if (w / h > ar) w = h * ar; else h = w / ar;
    return { left: r.left - w0.left + (r.width - w) / 2, top: r.top - w0.top + (r.height - h) / 2, width: w, height: h,
             absLeft: r.left + (r.width - w) / 2, absTop: r.top + (r.height - h) / 2 };
  }

  function pointAt(x, y) {
    var c = content();
    var px = c.left + x / state.width * c.width, py = c.top + y / state.height * c.height;
    pointer.style.opacity = '1';
    pointer.style.left = (px - 3) + 'px';
    pointer.style.top = (py - 2) + 'px';
    setTimeout(function () {
      var r = document.createElement('div');
      r.className = 'ripple'; r.style.left = px + 'px'; r.style.top = py + 'px';
      wrap.appendChild(r);
      setTimeout(function () { r.remove(); }, 650);
    }, 330);
    clearTimeout(pointAt.t);
    pointAt.t = setTimeout(function () { pointer.style.opacity = '0'; }, 2400);
  }

  protection.onclick = function (e) {
    e.stopPropagation();
    protection.disabled = true;
    post('/ui/notification-protection?' + q, { enabled: state.notification_protection === false })
      .finally(function () { protection.disabled = false; refresh(); });
  };

  function apply(s) {
    if (!s || s.ok === false) return;
    state = s;
    var protectedNow = s.notification_protection !== false;
    protection.textContent = '通知保护：' + (protectedNow ? '开' : '关');
    protection.setAttribute('aria-pressed', String(protectedNow));
    fitWindow();
    pill.classList.toggle('rec', !!s.recording);
    pill.classList.toggle('tap', !!s.teaching);
    if (s.teaching) {
      showPill('示教中', s.teaching_hint ? s.teaching_hint : '点这里完成', true);
    } else if (s.event && s.event.seq !== lastSeq) {
      var first = lastSeq === 0;
      lastSeq = s.event.seq;
      if (!first || Date.now() - s.event.at < 3000) {
        showPill(s.event.verb, s.event.detail, false, s.event.ok === false);
        if (s.event.x != null && s.event.y != null) pointAt(s.event.x, s.event.y);
      }
    } else if (!s.active) {
      showPill('等待中', '', true);
    } else if (s.recording && pill.classList.contains('hide')) {
      showPill('录制中', '', true);
    }
  }

  function refresh() {
    fetch('/ui/state?' + q + 'client=monitor&t=' + Date.now()).then(function (r) { return r.json(); }).then(apply).catch(function () {});
  }

  function startStream() {
    // Finite, bounded requests recover from a silent stalled MJPEG connection. Both the
    // screen and blurred margin use the same frame rather than competing capture requests.
    if (!polling) { polling = true; pollFrames(); }
  }

  function pollFrames() {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, 2500);
    fetch('/ui/frame.jpg?' + q + 't=' + Date.now(), { cache: 'no-store', signal: controller.signal })
      .then(function (r) { if (!r.ok || r.status === 204) throw new Error('frame unavailable'); return r.blob(); })
      .then(function (blob) {
        var next = URL.createObjectURL(blob), previous = pollFrames.url;
        screen.onload = function () {
          if (previous) URL.revokeObjectURL(previous);
          screen.dataset.frameAt = Date.now();
        };
        screen.src = next; backdrop.src = next; pollFrames.url = next;
      }).catch(function () { showPill('等待中', '画面重连中', true); })
      .finally(function () { clearTimeout(timer); setTimeout(pollFrames, document.hidden ? 1000 : 200); });
  }

  function toDisplay(clientX, clientY) {
    var c = content();
    var x = Math.round((clientX - c.absLeft) / c.width * state.width);
    var y = Math.round((clientY - c.absTop) / c.height * state.height);
    return { x: Math.max(0, Math.min(state.width - 1, x)), y: Math.max(0, Math.min(state.height - 1, y)) };
  }

  // Takeover: the user's own clicks and drags run on the phone and are recorded as teaching.
  var down = null;
  screen.addEventListener('pointerdown', function (e) {
    if (!screen.dataset.frameAt || Date.now() - Number(screen.dataset.frameAt) > 3000) {
      showPill('等待中', '画面过期，等待刷新', true); return;
    }
    e.preventDefault(); screen.setPointerCapture(e.pointerId);
    down = { x: e.clientX, y: e.clientY, t: Date.now() };
  });
  screen.addEventListener('pointerup', function (e) {
    if (!down) return;
    if (!screen.dataset.frameAt || Date.now() - Number(screen.dataset.frameAt) > 3000) { down = null; return; }
    var start = toDisplay(down.x, down.y), end = toDisplay(e.clientX, e.clientY);
    var moved = Math.abs(e.clientX - down.x) + Math.abs(e.clientY - down.y), elapsed = Date.now() - down.t;
    down = null;
    if (moved < 12) post('/ui/tap', { x: start.x, y: start.y });
    else post('/ui/swipe', { x1: start.x, y1: start.y, x2: end.x, y2: end.y, duration_ms: Math.max(120, Math.min(2000, elapsed)) });
  });
  pill.addEventListener('click', function () {
    if (state.teaching) post('/ui/takeover/finish');
    else if (!state.active) post('/ui/display/start');
  });

  refresh();
  setInterval(refresh, 400);
  startStream();
})();
</script>
</body>
</html>
"""
}
