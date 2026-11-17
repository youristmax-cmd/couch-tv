// The Spotify "now playing" card of the solar home (home.mode "solar"). app.js starts it, hands it the
// keys while it has the focus (zone "np") and tells it the time; everything else is here.
//
// Nothing playing: no card at all, the home looks as it always did. Playback starts (on any device
// of the account): the card pops in at the size last used, small at the top left or large in the
// middle (the big clock steps aside). Paused: it stays for 30 s with a play icon, then leaves.
// OK held 1.2 s on it: Spotify pauses and the card stays away until playback starts again, or
// until Up is pressed on the app bar, which brings the last song back, paused. Up brings the card
// up whenever Spotify is set up, even with nothing playing anywhere: then Play resumes if Spotify
// can, and opens the TV's Spotify app if there is nothing to resume.
//
// The page never talks to Spotify and never sees a token: it asks the bridge, GET /api/now-playing
// and POST /api/now-playing/{play,pause,next,previous}. ?np=mock plays a made-up playlist with no
// bridge and no account (P: play or pause "from the phone", S: shuffle on or off "in Spotify").
// A green shuffle mark shows beside the controls while shuffle is on in Spotify. It only shows:
// Spotify will not change shuffle from outside for a TV's own Spotify app (it accepts the request
// and ignores it), so there is no button. A title too long for its line slides along.
(function () {
  "use strict";

  var Q = location.search;
  var MOCK = /[?&]np=mock\b/.test(Q);
  // home.np_morph / ?npmorph=: how small and large change into each other. "layout" animates the
  // card's place and size; "flip" does the same with transforms only (for a TV that drops frames);
  // "fade" is a plain crossfade.
  var MORPH = (Q.match(/[?&]npmorph=(layout|flip|fade)/) || [])[1] || "";
  var PAUSE_MS = 30000;     // paused this long: the card leaves
  var HOLD_MS = 1200;       // OK held this long: stop and dismiss
  var TAP_MS = 300;         // OK let go sooner than this is a press; later, the hold shows
  var ORDER = ["prev", "play", "next", "size"];

  var ICON = {
    prev: '<svg viewBox="0 0 24 24"><rect x="5" y="5" width="2.6" height="14" rx="1.2"/><path d="M19 6.2v11.6a.7.7 0 0 1-1.09.58l-8.61-5.8a.7.7 0 0 1 0-1.16l8.61-5.8A.7.7 0 0 1 19 6.2z"/></svg>',
    next: '<svg viewBox="0 0 24 24"><rect x="16.4" y="5" width="2.6" height="14" rx="1.2"/><path d="M5 6.2v11.6a.7.7 0 0 0 1.09.58l8.61-5.8a.7.7 0 0 0 0-1.16l-8.61-5.8A.7.7 0 0 0 5 6.2z"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5.3v13.4a.7.7 0 0 0 1.07.6l10.5-6.7a.7.7 0 0 0 0-1.2L9.07 4.7A.7.7 0 0 0 8 5.3z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="6.2" y="5" width="4" height="14" rx="1.1"/><rect x="13.8" y="5" width="4" height="14" rx="1.1"/></svg>',
    grow: '<svg viewBox="0 0 24 24" class="line"><path d="M14 4.5h5.5V10M19.5 4.5 13.5 10.5M10 19.5H4.5V14M4.5 19.5l6-6"/></svg>',
    shrink: '<svg viewBox="0 0 24 24" class="line"><path d="M19.5 9.5H14V4M14 9.5l6-6M4.5 14.5H10V20M10 14.5l-6 6"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" class="line"><path d="M3 7h3.3a5 5 0 0 1 4 2l3.4 4.6a5 5 0 0 0 4 2H21M18 12.6l3 3-3 3M3 16.6h3.3a5 5 0 0 0 4-2l.5-.7M21 7.4h-3.3a5 5 0 0 0-4 2l-.5.7M18 4.4l3 3-3 3"/></svg>'
  };

  var O = null;     // from app.js: api, t, esc, kept, keep, launch, gone, big
  var E = null;     // the card's elements, made on first use
  var S = { on: false, leaving: false, size: "small", focus: null, data: null, base: 0, got: 0,
    pauseTimer: null, tickTimer: null, pollTimer: null, dismissed: false, dismissedAt: 0, sawStop: false, shown: "" };
  var H = { at: 0, timer: null, vis: null, done: false, down: false };   // OK being held

  function now() { return window.performance && performance.now ? performance.now() : Date.now(); }
  function t(k) { return O && O.t ? O.t(k) : k; }
  function esc(v) { return String(v == null ? "" : v).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function mmss(ms) { var s = Math.max(0, Math.floor(ms / 1000)); return Math.floor(s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60); }

  // ---------------------------------------------------------------- ?np=mock: a made-up playlist
  var mock = MOCK ? (function () {
    function art(a, b, c) {
      return "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 300"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
        '<stop offset="0" stop-color="' + a + '"/><stop offset="1" stop-color="' + b + '"/></linearGradient></defs><rect width="300" height="300" fill="url(#g)"/>' +
        '<circle cx="210" cy="96" r="58" fill="' + c + '" opacity="0.9"/><path d="M0 232l70-56 60 40 80-70 90 66v88H0z" fill="#0b1220" opacity="0.55"/></svg>');
    }
    var T = [
      { title: "Canyon Lights", artist: "The Ridgeline", dur: 204000, art: art("#ff9a5a", "#7b2ff7", "#ffe3a8") },
      { title: "Night Drive on the Avenue of Stars", artist: "Silver Arcade and the Long Way Home Orchestra", dur: 187000, art: art("#0f2a4a", "#1fb7a6", "#d7f5ff") },
      { title: "Golden Hour", artist: "Marlow & June", dur: 232000, art: art("#f6c453", "#e2553d", "#fff3c4") }
    ];
    var i = 0, playing = false, pos = 0, at = now(), shuffle = false;
    var IDLE = /[?&]npidle=1/.test(Q);   // Spotify set up, nothing playing anywhere, nothing to resume
    function step() {
      var n = now();
      if (playing) pos += n - at;
      at = n;
      while (pos >= T[i].dur) { pos -= T[i].dur; i = (i + 1) % T.length; }
    }
    function state() {
      step();
      if (IDLE) return { ok: true, configured: true, playing: false };
      return { ok: true, configured: true, playing: playing, shuffle: shuffle, progress_ms: Math.round(pos), age_ms: 0, device: { name: "Living room", type: "TV" },
        item: { id: "mock-" + i, title: T[i].title, artist: T[i].artist, art: T[i].art, duration_ms: T[i].dur } };
    }
    function cmd(a, body) {
      step();
      if (IDLE) throw new Error("Nothing is playing on Spotify right now");
      if (a === "shuffle") shuffle = !!(body && body.on);
      else if (a === "play") playing = true;
      else if (a === "pause") playing = false;
      else if (a === "next") { i = (i + 1) % T.length; pos = 0; playing = true; }
      else if (a === "previous") { if (pos < 3000) i = (i + T.length - 1) % T.length; pos = 0; playing = true; }
      return state();
    }
    // playback "starts" a moment after the page opens, so the pop-in shows
    if (!IDLE) setTimeout(function () { step(); playing = true; }, 1200);
    return { state: state, cmd: cmd };
  })() : null;

  // ---------------------------------------------------------------- the card
  function btn(name, cls, icon) { return '<div class="np-b ' + cls + '" data-b="' + name + '">' + ICON[icon] + "</div>"; }
  function build() {
    if (E) return;
    var root = document.createElement("div");
    root.className = "np gone off small";
    root.id = "np";
    root.setAttribute("aria-hidden", "true");
    root.innerHTML =
      '<div class="np-card">' +
        '<div class="np-sheen"></div>' +
        '<div class="np-art"><img alt=""><div class="np-tip"></div></div>' +
        '<div class="np-s">' +
          '<div class="np-text"><div class="np-title"><span></span></div><div class="np-artist"></div></div>' +
          btn("size", "np-size", "grow") +
          '<div class="np-bar"><i></i></div>' +
          '<div class="np-ctl">' + btn("prev", "np-skip", "prev") + btn("play", "np-play", "pause") + btn("next", "np-skip", "next") + "</div>" +
          '<div class="np-shuf">' + ICON.shuffle + "</div>" +
        "</div>" +
        '<div class="np-l">' +
          '<div class="np-clock"></div>' +
          btn("size", "np-size", "shrink") +
          '<div class="np-col">' +
            '<div class="np-text"><div class="np-title"><span></span></div><div class="np-artist"></div></div>' +
            '<div class="np-foot">' +
              '<div class="np-prog"><span class="np-el"></span><div class="np-bar"><i></i></div><span class="np-tot"></span></div>' +
              '<div class="np-ctl">' + btn("prev", "np-skip", "prev") + btn("play", "np-play", "pause") + btn("next", "np-skip", "next") +
                '<div class="np-shuf">' + ICON.shuffle + "</div></div>" +
            "</div>" +
          "</div>" +
        "</div>" +
        '<div class="np-veil"><div class="np-hold"><i></i><span></span></div></div>' +
      "</div>";
    document.body.appendChild(root);
    var q = function (sel) { return root.querySelectorAll(sel); };
    E = { root: root, card: q(".np-card")[0], art: q(".np-art")[0], img: q(".np-art img")[0], tip: q(".np-tip")[0],
      titles: q(".np-title"), artists: q(".np-artist"), bars: q(".np-bar i"), plays: q(".np-play"), shufs: q(".np-shuf"),
      el: q(".np-el")[0], tot: q(".np-tot")[0], clock: q(".np-clock")[0], hold: q(".np-hold span")[0],
      s: q(".np-s")[0], l: q(".np-l")[0] };
    E.img.onerror = function () { E.img.style.visibility = "hidden"; };
    E.img.onload = function () { E.img.style.visibility = ""; };
    words();
  }
  function words() {
    if (!E) return;
    E.tip.innerHTML = esc(t("np_open")).replace(/ (\S+)$/, " <b>$1</b>");   // "OK opens <b>Spotify</b>": the small card breaks it there
    E.hold.textContent = t("np_hold");
  }
  function part(size) { return size === "large" ? E.l : E.s; }
  function button(name) { return part(S.size).querySelector('[data-b="' + name + '"]'); }

  // the track, the icons and where it is up to
  function draw() {
    var d = S.data, it = d && d.item;
    if (!E || !it) return;
    var key = it.id + "|" + it.title + "|" + it.artist + "|" + it.art;
    if (key !== S.shown) {
      S.shown = key;
      for (var i = 0; i < E.titles.length; i++) { E.titles[i].firstChild.textContent = it.title || ""; E.artists[i].textContent = it.artist || ""; }
      slide(600);
      if (it.art) { if (E.img.getAttribute("src") !== it.art) E.img.src = it.art; } else { E.img.removeAttribute("src"); E.img.style.visibility = "hidden"; }
      E.tot.textContent = mmss(it.duration_ms || 0);
    }
    for (var s = 0; s < E.shufs.length; s++) E.shufs[s].classList.toggle("on", !!d.shuffle);
    var icon = d.playing ? ICON.pause : ICON.play;
    for (var p = 0; p < E.plays.length; p++) if (E.plays[p].getAttribute("data-i") !== (d.playing ? "1" : "0")) {
      E.plays[p].setAttribute("data-i", d.playing ? "1" : "0"); E.plays[p].innerHTML = icon;
    }
    tick();
  }
  // A title too long for its line: after a moment it slides along to its end, waits, comes back,
  // and does it again. Timers only (the slide itself is one CSS transition); nothing moves for a
  // title that fits, and nothing while the card is away.
  function slideStop() {
    clearTimeout(S.slideTimer);
    if (!E) return;
    for (var i = 0; i < E.titles.length; i++) {
      var sp = E.titles[i].firstChild;
      E.titles[i].classList.remove("run");
      sp.style.transition = "none"; sp.style.webkitTransform = ""; sp.style.transform = "";
    }
  }
  function slide(after) {
    slideStop();
    if (!E || !S.on || S.leaving) return;
    S.slideTimer = setTimeout(function go() {
      if (!S.on || S.leaving) return;
      var box = part(S.size).querySelector(".np-title"), sp = box.firstChild;
      var over = box.scrollWidth - box.clientWidth;
      if (over <= 3) return;                        // it fits
      var secs = Math.max(1.2, over / (innerWidth / 120 * 2.75));   // 44 px a second at 1080p
      box.classList.add("run");
      void sp.offsetWidth;
      sp.style.transition = "transform " + secs.toFixed(2) + "s linear";
      sp.style.webkitTransform = sp.style.transform = "translateX(" + (-over) + "px)";
      S.slideTimer = setTimeout(function () {
        sp.style.transition = "transform 600ms ease";
        sp.style.webkitTransform = sp.style.transform = "translateX(0)";
        S.slideTimer = setTimeout(function () {
          box.classList.remove("run"); sp.style.transition = "none"; sp.style.transform = "";
          S.slideTimer = setTimeout(go, 3000);
        }, 650);
      }, secs * 1000 + 1800);
    }, after == null ? 2000 : after + 2000);
  }

  // once a second while the card is up: the bar and the elapsed time, run on from the last reading
  function tick() {
    var d = S.data, it = d && d.item;
    if (!E || !it) return;
    var dur = it.duration_ms || 0;
    var pos = Math.max(0, Math.min(dur || Infinity, S.base + (d.playing ? now() - S.got : 0)));
    var f = dur ? Math.min(1, pos / dur) : 0;
    var w = "scaleX(" + f.toFixed(4) + ")";
    for (var i = 0; i < E.bars.length; i++) if (E.bars[i].style.transform !== w) { E.bars[i].style.webkitTransform = w; E.bars[i].style.transform = w; }
    var el = mmss(pos);
    if (E.el.textContent !== el) E.el.textContent = el;
  }

  // ---------------------------------------------------------------- showing and leaving
  function place() {
    // two or more people: their switch has the top left corner, so the small card sits under it
    var p = document.getElementById("people");
    var b = p && p.children.length && p.offsetHeight ? p.getBoundingClientRect().bottom : 0;
    if (b) E.root.style.setProperty("--np-top", (b + innerWidth / 120) + "px"); else E.root.style.removeProperty("--np-top");
  }
  function show() {
    build();
    if (S.on && !S.leaving) return;
    clearTimeout(S.goneTimer);
    S.on = true; S.leaving = false;
    place();
    E.root.className = "np off nomorph " + S.size;
    draw();
    void E.root.offsetWidth;                       // the starting state is in before it moves
    E.root.className = "np entering " + S.size;
    S.goneTimer = setTimeout(function () { if (E && S.on && !S.leaving) E.root.classList.remove("entering"); }, 700);
    big(S.size === "large");
    clearInterval(S.tickTimer);
    S.tickTimer = setInterval(tick, 1000);
    paint();
    slide(700);
  }
  // how: "out" (paused long enough, or the music ended) or "stop" (OK held)
  function hide(how) {
    if (!E || !S.on || S.leaving) return;
    S.leaving = true;
    clearTimeout(S.pauseTimer); S.pauseTimer = null;
    clearInterval(S.tickTimer);
    slideStop();
    var had = !!S.focus;
    S.focus = null;
    paint();                                       // no focus mark stays behind on a card that is gone
    E.root.classList.remove("press", "holding", "entering");
    E.root.classList.add(how === "stop" ? "stopped" : "leaving");
    big(false);
    if (had && O && O.gone) O.gone();              // the focus goes back to the app bar
    clearTimeout(S.goneTimer);
    S.goneTimer = setTimeout(function () {
      S.on = false; S.leaving = false;
      E.root.className = "np gone off " + S.size;
    }, 520);
  }
  // Paused: the card leaves after 30 s, unless someone is on it with the remote (then it waits)
  function armPause() {
    clearTimeout(S.pauseTimer);
    S.pauseTimer = setTimeout(function () {
      S.pauseTimer = null;
      if (S.focus || H.down) { armPause(); return; }
      hide("out");
    }, PAUSE_MS);
  }
  // Up from the app bar while the card is away (closed with a long OK, or gone after a pause): the
  // last song comes back, paused, with play in focus. False when there is no song to show.
  function recall() {
    if (S.on && !S.leaving) return true;
    if (!S.conf) return false;                     // Spotify is not set up here: up goes where it always went
    if (!S.data || !S.data.item) {
      // nothing playing anywhere: the last song seen, or the card with nothing on it yet
      var it = S.last || { id: "", title: "Spotify", artist: t("np_idle"), art: O && O.icon ? O.icon() : null, duration_ms: 0 };
      S.data = { configured: true, playing: false, progress_ms: 0, item: it };
      S.base = 0; S.got = now(); S.shown = "";
    }
    S.dismissed = false;
    show();
    if (!S.data.playing) armPause();
    return true;
  }
  function big(on) {
    document.documentElement.classList.toggle("np-big", !!on);
    if (O && O.big) O.big(!!on);
  }

  // small <-> large: one card changes place, size and corners; the album art moves with it
  function setSize(size) {
    if (size === S.size || !E) return;
    var from = S.size;
    S.size = size;
    if (O && O.keep) O.keep("tvhome.np.size", size);
    var mode = MORPH || (O && O.morph) || "layout";
    E.root.classList.remove("nomorph", "entering");
    if (mode === "flip") flip(from, size);
    else if (mode === "fade") {
      // the card dips out, changes size out of sight and comes back
      E.root.classList.add("fade", "swap");
      clearTimeout(S.flipTimer);
      S.flipTimer = setTimeout(function () {
        E.root.classList.remove("small", "large"); E.root.classList.add(S.size);
        void E.root.offsetWidth;
        E.root.classList.remove("swap");
        paint();
      }, 190);
    } else {
      E.root.classList.remove(from);
      E.root.classList.add(size);
    }
    big(size === "large" && S.on && !S.leaving);
    paint();
    slide(900);                                    // the other layout's title, once the card has settled
  }
  // The same change with transforms only (no layout while it moves): the card takes its new place at
  // once, is drawn at its old one, and eases home; so does the album art inside it.
  function flip(from, to) {
    var r = E.root, a = E.art;
    var tr = function (el, v) { el.style.webkitTransform = v; el.style.transform = v; };
    var rem = innerWidth / 120;
    clearTimeout(S.flipTimer);
    tr(r, "none"); tr(a, "none");
    var r0 = r.getBoundingClientRect(), a0 = a.getBoundingClientRect();
    r.style.transition = "none"; a.style.transition = "none"; E.card.style.transition = "none";
    r.classList.remove(from); r.classList.add(to);
    var r1 = r.getBoundingClientRect(), a1 = a.getBoundingClientRect();
    var sx = r0.width / r1.width, sy = r0.height / r1.height;
    tr(r, "translate(" + (r0.left - r1.left) + "px," + (r0.top - r1.top) + "px) scale(" + sx + "," + sy + ")");
    // the art inside the scaled card: where it was, in the card's own (new) coordinates
    var ax = ((a0.left - r0.left) / sx) - (a1.left - r1.left), ay = ((a0.top - r0.top) / sy) - (a1.top - r1.top);
    tr(a, "translate(" + ax + "px," + ay + "px) scale(" + (a0.width / a1.width / sx) + "," + (a0.height / a1.height / sy) + ")");
    E.card.style.borderRadius = ((from === "large" ? 3 : 2.25) * rem / Math.min(sx, sy)) + "px";
    void r.offsetWidth;
    var ease = "750ms cubic-bezier(0.32, 1.2, 0.36, 1)";
    r.style.transition = "transform " + ease; a.style.transition = "transform " + ease; E.card.style.transition = "border-radius " + ease;
    tr(r, "none"); tr(a, "none"); E.card.style.borderRadius = "";
    S.flipTimer = setTimeout(function () { r.style.transition = ""; a.style.transition = ""; E.card.style.transition = ""; }, 800);
  }

  // ---------------------------------------------------------------- what the bridge says
  function apply(d) {
    S.conf = !!d && d.configured === true;     // only a bridge that says Spotify is set up
    if (!S.conf) { S.data = null; S.live = false; if (S.on) hide("out"); return; }
    S.live = !!d.item;                             // Spotify itself has a song (playing, or paused and resumable)
    if (d.item && d.item.id !== (S.last && S.last.id)) {
      S.last = { id: d.item.id, title: d.item.title, artist: d.item.artist, art: d.item.art, duration_ms: d.item.duration_ms };
      if (O && O.keep && !MOCK) O.keep("tvhome.np.last", S.last);   // the card can come back with it after a restart
    }
    var playing = !!(d.playing && d.item);
    if (S.dismissed) {
      // held to stop: away until playback starts again (or the pause never took, ten seconds on)
      if (!playing) { S.sawStop = true; return; }
      if (!S.sawStop && now() - S.dismissedAt < 10000) return;
      S.dismissed = false;
    }
    if (!d.item) d.item = S.data && S.data.item;   // an advert or a gap between songs: the last song stays
    if (!d.item) return;
    S.data = d; S.base = (d.progress_ms || 0) + (d.age_ms || 0); S.got = now();
    if (playing) {
      clearTimeout(S.pauseTimer); S.pauseTimer = null;
      if (!S.on || S.leaving) show(); else draw();
    } else if (S.on && !S.leaving) {
      draw();
      if (!S.pauseTimer) armPause();
    }
  }
  function poll() {
    clearTimeout(S.pollTimer);
    var again = function () { S.pollTimer = setTimeout(poll, MOCK ? 1000 : S.on ? 2000 : 3000); };
    if (document.hidden || S.away) { again(); return; }   // another app is in front: nobody is asked
    if (MOCK) { apply(mock.state()); again(); return; }
    O.api("GET", "/api/now-playing").then(apply, function () {}).then(again);
  }
  function cmd(a, body) {
    var failed = function (e) {
      if (a === "play") {
        if (S.data) { S.data.playing = false; draw(); }
        // nothing to resume (Spotify has no device playing): the TV's own Spotify app, to pick something
        if (!S.live && O && O.launch && O.launch()) { note(t("np_opening")); return; }
      }
      note(e && e.message); poll();
    };
    if (MOCK) { try { apply(mock.cmd(a, body)); } catch (e) { failed(e); } return; }
    O.api("POST", "/api/now-playing/" + a, body || {}, 9000).then(apply, failed);
  }
  // a short message on the album art (Spotify refused, or the preview has no Spotify to open)
  function note(text) {
    if (!E || !text) return;
    E.tip.textContent = text;
    E.root.classList.add("note");
    clearTimeout(S.noteTimer);
    S.noteTimer = setTimeout(function () { E.root.classList.remove("note"); words(); }, 2600);
  }

  function act(name) {
    var d = S.data;
    if (!d || !S.on || S.leaving) return;
    if (name === "play") {
      // at once on screen; the bridge's answer follows
      var was = d.playing;
      S.base = Math.max(0, S.base + (was ? now() - S.got : 0)); S.got = now();
      d.playing = !was;
      if (was) { if (!S.pauseTimer) armPause(); }
      else { clearTimeout(S.pauseTimer); S.pauseTimer = null; }
      draw();
      cmd(was ? "pause" : "play");
    } else if (name === "prev") cmd("previous");
    else if (name === "next") cmd("next");
    else if (name === "size") setSize(S.size === "large" ? "small" : "large");
    else if (name === "card") { if (O && O.launch && !O.launch()) note(t("np_no_app")); }
  }

  // ---------------------------------------------------------------- the remote (app.js calls these)
  function paint() {
    if (!E) return;
    var olds = E.root.querySelectorAll(".np-b.focus");
    for (var i = 0; i < olds.length; i++) olds[i].classList.remove("focus");
    var card = S.focus === "card";
    E.root.classList.toggle("sel", card);
    E.card.classList.toggle("focus", card);        // app.js looks for .focus to know the focus is somewhere
    if (S.focus && !card) { var b = button(S.focus); if (b) b.classList.add("focus"); }
  }
  // Left and right: previous, play, next, size. Down: back to the app bar. Up: the whole card; up
  // again leaves by the top when there is somewhere to go (the buttons at the top right).
  function move(dx, dy, canUp) {
    if (!S.on || S.leaving) { S.focus = null; return "down"; }
    if (S.focus === "card") {
      if (dy > 0) S.focus = "play";
      else if (dy < 0 && canUp) { S.focus = null; paint(); return "up"; }
    } else if (dx) S.focus = ORDER[Math.max(0, Math.min(ORDER.length - 1, ORDER.indexOf(S.focus) + dx))];
    else if (dy > 0) { S.focus = null; paint(); return "down"; }
    else if (dy < 0) S.focus = "card";
    paint();
    return "stay";
  }
  function holdOff() {
    clearTimeout(H.timer); clearTimeout(H.vis); H.timer = null; H.vis = null;
    if (E) E.root.classList.remove("press", "holding");
  }
  // OK goes down: nothing happens yet. Let go quickly and it is a press; keep holding and the card
  // dims while a pill fills, and at 1.2 s Spotify pauses and the card leaves. Letting go on the way
  // cancels, and does nothing else.
  function okDown() {
    if (H.down || !S.on || S.leaving) return;
    H.down = true; H.done = false; H.at = now();
    E.root.classList.add("press");                 // the fill starts now, out of sight until the veil shows
    H.vis = setTimeout(function () { E.root.classList.add("holding"); }, TAP_MS);
    H.timer = setTimeout(function () {
      H.timer = null; H.done = true;
      S.dismissed = true; S.dismissedAt = now(); S.sawStop = false;
      if (S.data && S.data.playing) { S.data.playing = false; cmd("pause"); }
      hide("stop");
    }, HOLD_MS);
  }
  function okUp() {
    if (!H.down) return;
    var quick = now() - H.at < TAP_MS, done = H.done;
    H.down = false; H.done = false;
    holdOff();
    if (!done && quick && !H.noAct) act(S.focus);
    H.noAct = false;
  }
  function okCancel() { H.down = false; H.done = false; H.noAct = false; holdOff(); }

  // Browser previews only (a TV has no mouse): a click is OK on what is under it, and holding the
  // mouse button down is holding OK. The first click on the card itself only selects it.
  function mouse() {
    build();
    document.documentElement.classList.add("np-mouse");
    E.root.addEventListener("mousedown", function (e) {
      if (e.button || !S.on || S.leaving) return;
      e.preventDefault();
      var b = e.target.closest ? e.target.closest(".np-b") : null;
      var name = b ? b.getAttribute("data-b") : "card";
      H.noAct = name === "card" && S.focus !== "card";
      if (O && O.take) O.take();
      S.focus = name;
      paint();
      okDown();
    });
    document.addEventListener("mouseup", function () { if (H.down) okUp(); });
  }

  // play/pause, rewind and fast forward on the remote, whenever the card is up
  function mediaKey(e) {
    if (!S.on || S.leaving) return false;
    var k = e.key, c = e.keyCode;
    var a = k === "MediaPlayPause" || k === "MediaPlay" || k === "MediaPause" || c === 179 ? "play"
      : k === "MediaRewind" || k === "MediaTrackPrevious" || c === 227 || c === 177 ? "prev"
      : k === "MediaFastForward" || k === "MediaTrackNext" || c === 228 || c === 176 ? "next" : null;
    if (!a) return false;
    if (!e.repeat) act(a);
    return true;
  }

  function start(opts) {
    O = opts || {};
    words();   // the card may have been made already (for its clock), before the words were here
    var s = O.kept ? O.kept("tvhome.np.size") : null;
    S.size = s === "large" ? "large" : "small";
    var last = !MOCK && O.kept ? O.kept("tvhome.np.last") : null;
    if (last && last.title) S.last = last;
    if (MOCK) {
      var away = parseInt((Q.match(/[?&]npaway=(\d+)/) || [])[1], 10);
      if (away) PAUSE_MS = away * 1000;            // ?npaway=5: a paused card leaves after 5 s, for previews
      document.addEventListener("keydown", function (e) {
        if (e.repeat) return;
        if (e.key === "p" || e.key === "P") apply(mock.cmd(mock.state().playing ? "pause" : "play"));
        if (e.key === "s" || e.key === "S") apply(mock.cmd("shuffle", { on: !mock.state().shuffle }));
      });
    }
    if (!window.TVOS && /[?&](mock|preview|np=mock)\b/.test(Q)) mouse();
    document.addEventListener("visibilitychange", function () { if (!document.hidden) poll(); });
    poll();
  }

  window.nowPlaying = {
    start: start,
    focusable: function () { return S.on && !S.leaving; },
    recall: recall,
    enter: function () { S.focus = "play"; paint(); },
    blur: function () { if (S.focus) { S.focus = null; paint(); } },
    move: move, paint: paint, okDown: okDown, okUp: okUp, okCancel: okCancel, mediaKey: mediaKey,
    // the home went behind another app, and came back (the launcher says so)
    away: function () { S.away = true; },
    refresh: function () { S.away = false; if (O) poll(); },
    words: words,
    clock: function (html) { build(); if (E.clock.getAttribute("data-hm") !== html) { E.clock.setAttribute("data-hm", html); E.clock.innerHTML = html; } }
  };
})();
