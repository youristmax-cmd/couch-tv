// The game-day score bugs of the solar home (home.mode "solar"): the Dodgers and the Clippers, or
// whichever teams home.scores.teams names. app.js starts it and hands it the keys while a bug has
// the focus (zone "sb"); everything else is here.
//
// No game in its window (an hour before the start until a quarter of an hour after the end):
// nothing on screen and nothing running, the home looks as it always did. A game comes into the
// window: its bug pops in at the top left, under the Spotify card while that shows small. Two games:
// two bugs, the one being played on top. OK on a selected bug opens the game (the pill under the bug
// says where; a game on a channel that is not in the channel table opens nothing, and the pill
// only says where it is on). OK held 1.2 s and let go: the bugs are put away, as the Spotify card
// is, and Up from the app bar brings them back. A game that comes up later shows by itself.
//
// The page never talks to a sports feed: it asks the bridge, GET /api/scores, once when the home
// opens, again when the home comes back in front, and whenever the TV app says a game has come up
// (scores.wake()). Only while the answer has games does it ask again by itself: every 15 s with a
// game being played, every 60 s otherwise. Logos come from the bridge too (it keeps its own copies);
// none are in this folder. A logo that is missing or does not load: the team's letters in the
// team's light colour, and a plain ball for the league.
//
// ?sb=mock shows a made-up game with no bridge. Other names: mlb (the same game), nba, pre, premlb,
// final, finalnba, extra (F/10), ot (F/OT), delayed, half, nochan (no channel known), link (a
// channel with a link), app (a game on a streaming app) and both (mlb,pre). Two names with a comma
// between them show two bugs. Keys in the mock: S a score changes, B a base changes (basketball: the
// clock runs down), O one more out, X the first game leaves, or comes back. &sblogo=1 fills the logo
// slots with made-up shapes, a wide one and a tall one, to see that any logo will fit; &sblogo=bad
// gives every logo an address that does not load.
(function () {
  "use strict";

  var Q = location.search;
  var MODE = (Q.match(/[?&]sb=([a-z0-9,]+)/) || [])[1] || "";
  var MOCK = !!MODE;
  var FAKE = MOCK ? (Q.match(/[?&]sblogo=([a-z0-9]+)/) || [])[1] || "" : "";
  var LIVE_MS = 15000;      // a game is being played: the bridge is asked this often
  var IDLE_MS = 60000;      // games showing, none being played: this often
  var STALE_MS = 120000;    // no answer for this long: the bugs go
  var RETRY = [5000, 15000, 45000];   // no answer and nothing showing: three more tries, then quiet
  var HOLD_MS = 1200;       // OK held this long, then let go: the bugs are put away (the card's own timings, nowplaying.js)
  var TAP_MS = 300;         // OK let go sooner than this is a press; later, the hold shows
  var GONE_MS = 520;        // a bug that left is taken away after this (the card's own wait)
  var KEEP_MS = 36 * 3600000;   // a game that was put away is remembered this long; by then its bug would be long gone
  var RING = 2 * Math.PI * 66;  // once round the basketball ring, in the design's pixels

  // A team's letters, shown when its logo is missing: the lightest colour of the team's own set,
  // white when the rest of the set is dark. By league and the feed's abbreviation (a few teams go
  // by two). Anything not here is white, and a "color" from the bridge wins.
  var COLORS = {
    "mlb:AZ": "#E3D4AD", "mlb:ARI": "#E3D4AD", "mlb:ATL": "#FFFFFF", "mlb:BAL": "#FF7A2F", "mlb:BOS": "#FFFFFF",
    "mlb:CHC": "#FFFFFF", "mlb:CWS": "#C4CED4", "mlb:CHW": "#C4CED4", "mlb:CIN": "#FFFFFF", "mlb:CLE": "#FFFFFF",
    "mlb:COL": "#C4CED4", "mlb:DET": "#FF6A3D", "mlb:HOU": "#F58233", "mlb:KC": "#7AB2DD", "mlb:KCR": "#7AB2DD",
    "mlb:LAA": "#FFFFFF", "mlb:LAD": "#FFFFFF", "mlb:LA": "#FFFFFF", "mlb:MIA": "#3DB5EA", "mlb:MIL": "#FFC52F",
    "mlb:MIN": "#FFFFFF", "mlb:NYM": "#FF7A3D", "mlb:NYY": "#FFFFFF", "mlb:ATH": "#EFB21E", "mlb:OAK": "#EFB21E",
    "mlb:PHI": "#FFFFFF", "mlb:PIT": "#FDB827", "mlb:SD": "#FFC425", "mlb:SDP": "#FFC425", "mlb:SF": "#FF7A45",
    "mlb:SFG": "#FF7A45", "mlb:SEA": "#C4CED4", "mlb:STL": "#FFFFFF", "mlb:TB": "#8FBCE6", "mlb:TBR": "#8FBCE6",
    "mlb:TEX": "#FFFFFF", "mlb:TOR": "#6CACE4", "mlb:WSH": "#FFFFFF", "mlb:WAS": "#FFFFFF", "mlb:WSN": "#FFFFFF",
    "nba:ATL": "#FDB927", "nba:BOS": "#FFFFFF", "nba:BKN": "#FFFFFF", "nba:BRK": "#FFFFFF", "nba:CHA": "#FFFFFF",
    "nba:CHO": "#FFFFFF", "nba:CHI": "#FFFFFF", "nba:CLE": "#FDBB30", "nba:DAL": "#B8C4CA", "nba:DEN": "#FEC524",
    "nba:DET": "#FFFFFF", "nba:GSW": "#FFC72C", "nba:GS": "#FFC72C", "nba:HOU": "#FFFFFF", "nba:IND": "#FDBB30",
    "nba:LAC": "#FFFFFF", "nba:LAL": "#FDB927", "nba:MEM": "#F5B112", "nba:MIA": "#F9A01B", "nba:MIL": "#EEE1C6",
    "nba:MIN": "#78BE20", "nba:NOP": "#C8A96A", "nba:NO": "#C8A96A", "nba:NYK": "#F58426", "nba:NY": "#F58426",
    "nba:OKC": "#FDBB30", "nba:ORL": "#C4CED4", "nba:PHI": "#FFFFFF", "nba:PHX": "#F9A01B", "nba:PHO": "#F9A01B",
    "nba:POR": "#FFFFFF", "nba:SAC": "#FFFFFF", "nba:SAS": "#C4CED4", "nba:SA": "#C4CED4", "nba:TOR": "#FFFFFF",
    "nba:UTA": "#FFF21F", "nba:UTAH": "#FFF21F", "nba:WAS": "#C4CED4", "nba:WSH": "#C4CED4"
  };
  // the TV app's name, for a preview: on the TV the name comes from the TV's own list of apps
  var APPS = { "com.att.tv": "DIRECTV" };

  // the league's stand-in: a plain ball
  var BALL = {
    mlb: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M6.2 5.2c2.4 2 2.4 11.6 0 13.6M17.8 5.2c-2.4 2-2.4 11.6 0 13.6"/></svg>',
    nba: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3v18M5.6 5.6c3 2.6 3 10.2 0 12.8M18.4 5.6c-3 2.6-3 10.2 0 12.8"/></svg>',
    any: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/></svg>'
  };
  var UP = '<svg viewBox="0 0 22 18"><path d="M11 0l11 18H0z"/></svg>';
  var DOWN = '<svg viewBox="0 0 22 18"><path d="M0 0h22L11 18z"/></svg>';
  // the fills of the bases: the top face and the sides of an empty one, the top face of a lit one
  var DEFS = '<svg aria-hidden="true"><defs>' +
    '<linearGradient id="sb-bt" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0.46"/><stop offset="0.55" stop-color="#fff" stop-opacity="0.14"/><stop offset="1" stop-color="#fff" stop-opacity="0.26"/></linearGradient>' +
    '<linearGradient id="sb-bs" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0.34"/><stop offset="1" stop-color="#fff" stop-opacity="0.08"/></linearGradient>' +
    '<linearGradient id="sb-by" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFF0A6"/><stop offset="0.45" stop-color="#FFCE2E"/><stop offset="1" stop-color="#F5A800"/></linearGradient>' +
    "</defs></svg>";
  // One base in the 148 x 80 diamond: its top face is a rhombus 56 x 32 around (cx, cy), with 9 px
  // of thickness under its two lower edges. Empty: clear glass with one facet line. Lit: gold.
  function base(n, cx, cy, lit) {
    var top = "M" + cx + " " + (cy - 16) + "l28 16-28 16-28-16z";
    var left = "M" + (cx - 28) + " " + cy + "l28 16v9l-28-16z", right = "M" + (cx + 28) + " " + cy + "l-28 16v9l28-16z";
    if (lit) return '<svg class="b' + n + ' lit" viewBox="0 0 148 80"><g stroke="#FFF1B8" stroke-width="1.4" stroke-linejoin="round">' +
      '<path d="' + left + '" fill="#E0A800"/><path d="' + right + '" fill="#C98F00"/><path d="' + top + '" fill="url(#sb-by)"/></g></svg>';
    return '<svg class="b' + n + ' dim" viewBox="0 0 148 80"><g stroke="rgba(255,255,255,0.95)" stroke-width="1.6" stroke-linejoin="round">' +
      '<path d="' + left + right + '" fill="url(#sb-bs)"/><path d="' + top + '" fill="url(#sb-bt)"/>' +
      '<path d="M' + cx + " " + (cy - 11.2) + 'l19.6 11.2-19.6 11.2-19.6-11.2z" fill="none" stroke="rgba(255,255,255,0.35)" stroke-width="1"/></g></svg>';
  }
  // the basketball ring: 63 fine ticks all the way round, and the arc (drawn from 12 o'clock, clockwise)
  var RING_SVG = '<svg class="sb-ring" viewBox="0 0 148 148"><g transform="rotate(-90 74 74)">' +
    '<circle class="tk" cx="74" cy="74" r="66" stroke-dasharray="2 ' + (RING / 63 - 2).toFixed(4) + '" stroke-dashoffset="1"/>' +
    '<circle class="arc" cx="74" cy="74" r="66" stroke-dasharray="0 ' + RING.toFixed(2) + '"/></g></svg>';
  // what the right panel holds: before the game; baseball being played; basketball being played; one word
  var PANEL = {
    pre: '<div class="sb-mid"><div class="sb-when"><span class="sb-time"></span><span class="sb-ap"></span></div><div class="sb-in"></div></div>',
    bases: '<div class="sb-inn"></div><div class="sb-bases">' + base(2, 74, 20, false) + base(3, 40, 50, false) + base(1, 108, 50, false) +
      base(2, 74, 20, true) + base(3, 40, 50, true) + base(1, 108, 50, true) + '</div><div class="sb-outs"><i></i><i></i><i></i></div>',
    clock: '<div class="sb-mid"><div class="sb-per"></div><div class="sb-clk"></div></div>',
    word: '<div class="sb-mid"><div class="sb-word"></div></div>'
  };

  var O = null;     // from app.js: api, t, kept, keep, now, clock24, open, appName, gone
  var E = null;     // the stack the bugs sit in, made when the first game shows
  // bugs: the ones on screen, top first (one that is leaving stays in the list until it is gone)
  var S = { bugs: [], focus: null, tvApp: "", any: false, next: null, hidden: [], timer: null, tick: null,
    away: false, asking: false, again: false, good: 0, fails: 0 };
  var H = { down: false, at: 0, vis: null, id: null };   // OK being held

  function now() { return window.performance && performance.now ? performance.now() : Date.now(); }
  // the clock on the wall: the home's own, which a preview can set (?t=)
  function wall() { var d = O && O.now ? O.now() : null; return d ? d.getTime() : Date.now(); }
  function t(k, v) { return O && O.t ? O.t(k, v) : k; }
  function esc(v) { return String(v == null ? "" : v).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function rem() { return innerWidth / 120; }
  function alive() { return S.bugs.filter(function (b) { return !b.leaving; }); }
  function find(id) { for (var i = 0; i < S.bugs.length; i++) if (S.bugs[i].id === id) return S.bugs[i]; return null; }
  function isLive(g) { return g.state === "live"; }

  // ---------------------------------------------------------------- ?sb=mock: made-up games
  var mock = MOCK ? (function () {
    function over(g, o) { for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) g[k] = o[k]; return g; }
    function team(abbr, score) { return { abbr: abbr, score: score, logo: null }; }
    function iso(ms) { return new Date(wall() + ms).toISOString(); }
    function mlb(o) {
      return over({ league: "mlb", league_name: "MLB", league_logo: null, state: "live", status: "In Progress", start: iso(-2 * 3600000),
        away: team("SD", 3), home: team("LA", 5), broadcasts: ["SportsNet LA"],
        channel: { name: "SportsNet LA", number: 690, link: "", app: null }, final_label: "",
        mlb: { inning: 7, half: "top", outs: 2, first: true, second: false, third: false } }, o);
    }
    function nba(o) {
      return over({ league: "nba", league_name: "NBA", league_logo: null, state: "live", status: "In Progress", start: iso(-90 * 60000),
        away: team("PHX", 78), home: team("LAC", 84), broadcasts: ["ClipperVision", "KTLA"],
        channel: null, final_label: "",
        nba: { period: "3RD", clock: "4:12", remaining: 0.35, halftime: false } }, o);
    }
    // 42 minutes from now, on the minute: "in 42 min" until the home's clock next turns over
    function soon() { return new Date(Math.floor(wall() / 60000) * 60000 + 42 * 60000).toISOString(); }
    var MAKE = {
      mlb: function () { return mlb({}); },
      nba: function () { return nba({}); },
      pre: function () { return nba({ state: "pre", status: "Scheduled", start: soon(), away: team("PHX", null), home: team("LAC", null), nba: null }); },
      premlb: function () { return mlb({ state: "pre", status: "Scheduled", start: soon(), away: team("SD", null), home: team("LA", null), mlb: null }); },
      final: function () { return mlb({ state: "final", status: "Final", final_label: "FINAL", mlb: { inning: 9, half: "top", outs: 3, first: false, second: false, third: false } }); },
      finalnba: function () { return nba({ state: "final", status: "Final", final_label: "FINAL", away: team("PHX", 104), home: team("LAC", 112), nba: { period: "4TH", clock: "0:00", remaining: 0, halftime: false } }); },
      extra: function () { return mlb({ state: "final", status: "Final", final_label: "F/10", away: team("SD", 5), home: team("LA", 6), mlb: { inning: 10, half: "bottom", outs: 1, first: false, second: false, third: false } }); },
      ot: function () { return nba({ state: "final", status: "Final", final_label: "F/OT", away: team("PHX", 118), home: team("LAC", 121), nba: { period: "OT", clock: "0:00", remaining: 0, halftime: false } }); },
      delayed: function () { return mlb({ state: "delayed", status: "Rain Delay", away: team("SD", 2), home: team("LA", 1), mlb: { inning: 5, half: "bottom", outs: 1, first: false, second: true, third: false } }); },
      half: function () { return nba({ status: "Halftime", away: team("PHX", 52), home: team("LAC", 49), nba: { period: "2ND", clock: "0:00", remaining: 0, halftime: true } }); },
      nochan: function () { return mlb({ broadcasts: ["A network not in the table"], channel: null }); },
      link: function () { return mlb({ channel: { name: "SportsNet LA", number: 690, link: "dtvnow://deeplink.directvnow.com/play/channel/MOCK/0000", app: null } }); },
      app: function () { return nba({ broadcasts: ["Prime Video"], channel: { name: "Prime Video", number: 0, link: "", app: "com.amazon.avod" } }); }
    };
    MAKE.mock = MAKE.mlb;
    var names = MODE === "both" ? ["mlb", "pre"] : MODE.split(",").slice(0, 2);
    var list = null, up = false, out = null, turn = false, nextBase = 0;
    function games() {
      if (!list) {
        list = [];
        names.forEach(function (n, i) { if (MAKE[n]) { var g = MAKE[n](); g.id = g.league + "-mock-" + n + (i ? "-2" : ""); list.push(g); } });
      }
      return list;
    }
    function state() { return { ok: true, tv_app: "com.att.tv", games: up ? games().filter(function (g) { return g !== out; }) : [] }; }
    // S, B, O and X on a keyboard. True when something changed.
    function key(k) {
      if (!up) return false;
      if (k === "x") { out = out ? null : games()[0] || null; return true; }
      var g = games().filter(function (x) { return isLive(x) && x !== out; })[0];
      if (!g) return false;
      if (k === "s") { turn = !turn; var tm = turn ? g.home : g.away; tm.score = (tm.score || 0) + (g.league === "nba" ? 2 : 1); return true; }
      if (k === "b" && g.mlb) { var n = ["first", "second", "third"][nextBase++ % 3]; g.mlb[n] = !g.mlb[n]; return true; }
      if (k === "b" && g.nba) {
        g.nba.remaining = g.nba.remaining > 0.08 ? g.nba.remaining - 0.07 : 1;
        var s = Math.round(g.nba.remaining * 720);
        g.nba.clock = Math.floor(s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
        return true;
      }
      if (k === "o" && g.mlb) {
        g.mlb.outs = (g.mlb.outs + 1) % 3;
        if (!g.mlb.outs) {   // three out: the other half of the inning, the bases empty
          if (g.mlb.half === "top") g.mlb.half = "bottom"; else { g.mlb.half = "top"; g.mlb.inning++; }
          g.mlb.first = g.mlb.second = g.mlb.third = false;
        }
        return true;
      }
      return false;
    }
    // the games "come into their window" a moment after the page opens, the way the TV app would
    // say so, so the pop-in shows
    setTimeout(function () { up = true; wake(); }, 1000);
    return { state: state, key: key };
  })() : null;

  // &sblogo=1: a made-up shape where a logo would be (nobody's logo), wide or tall, with a white
  // line right at its edge, so one that was cropped would show
  function fake(kind, colour) {
    var svg = kind === "wide"
      ? '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 100"><rect x="3" y="3" width="294" height="94" rx="22" fill="' + colour + '" stroke="#fff" stroke-width="6"/><circle cx="62" cy="50" r="24" fill="#fff"/><rect x="104" y="38" width="160" height="24" rx="12" fill="#fff" opacity="0.85"/></svg>'
      : '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 230"><path d="M3 3h94v140l-47 84L3 143z" fill="' + colour + '" stroke="#fff" stroke-width="6" stroke-linejoin="round"/><circle cx="50" cy="74" r="26" fill="#fff"/></svg>';
    return "data:image/svg+xml," + encodeURIComponent(svg);
  }
  function logoOf(url, kind, colour) {
    if (FAKE === "bad") return "/api/scores/logo/not-there.png";
    if (FAKE) return fake(kind, colour);
    return url || null;
  }

  // ---------------------------------------------------------------- a bug
  function build() {
    if (E) return;
    var root = document.createElement("div");
    root.className = "sbs gone";
    root.id = "sbs";
    root.setAttribute("aria-hidden", "true");
    root.innerHTML = DEFS;
    document.body.appendChild(root);
    E = { root: root };
  }
  // The body is the Spotify card's own glass (np-card and np-sheen, nowplaying.css); the tab is a
  // second piece of glass on its top edge. The veil and the pill for a held OK are the card's too.
  function make(g) {
    var el = document.createElement("div");
    el.className = "sb off nomorph " + (g.league === "nba" ? "nba" : "mlb");
    el.setAttribute("data-id", g.id);
    el.innerHTML =
      '<div class="sb-lift">' +
        '<div class="sb-tabg"></div>' +
        '<div class="np-card sb-body">' +
          '<div class="np-sheen"></div>' +
          '<div class="sb-row a"><div class="sb-mark"></div><div class="sb-score"></div></div>' +
          '<div class="sb-h"></div>' +
          '<div class="sb-row h"><div class="sb-mark"></div><div class="sb-score"></div></div>' +
          '<div class="sb-v"></div>' +
          '<div class="sb-side"></div>' +
          '<div class="np-veil"><div class="np-hold"><i></i><span></span></div></div>' +
        "</div>" +
        '<div class="sb-tab"><i></i><span></span></div>' +
      "</div>" +
      '<div class="sb-pill"><span></span></div>';
    var q = function (sel) { return el.querySelectorAll(sel); };
    return { id: g.id, g: g, el: el, leaving: false, kind: "", sh: {}, pics: {}, note: "",
      p: { body: q(".np-card")[0], icon: q(".sb-tab i")[0], name: q(".sb-tab span")[0], am: q(".sb-mark")[0], hm: q(".sb-mark")[1],
        as: q(".sb-score")[0], hs: q(".sb-score")[1], side: q(".sb-side")[0], hold: q(".np-hold span")[0], pill: q(".sb-pill span")[0] } };
  }

  // A logo: its slot is empty until it has loaded (the pop-in covers the wait). No address, or it
  // does not load: the stand-in. One that failed is tried again a minute or more later, when the
  // bridge is next asked, in case the bridge had not fetched it yet (five tries, then it is left).
  function picture(b, slot, box, url, alt) {
    var m = b.pics[slot] || (b.pics[slot] = { url: "", state: "", at: 0, tries: 0 });
    if (m.url === url) {
      if (m.state === "wait" && now() - m.at > 4000) { m.state = "slow"; alt(); }   // still not here: the stand-in, until it is
      if (m.state !== "bad" || m.tries >= 5 || now() - m.at < 60000) return;
    } else {
      m.url = url; m.tries = 0;
      if (!url) { m.state = "none"; alt(); return; }
      box.textContent = ""; box.style.color = "";
    }
    m.state = m.state === "bad" ? "again" : "wait"; m.at = now(); m.tries++;
    var img = new Image();
    img.alt = "";
    img.onload = function () { if (m.url !== url) return; m.state = "ok"; box.textContent = ""; box.style.color = ""; box.appendChild(img); };
    img.onerror = function () { if (m.url !== url) return; m.state = "bad"; m.at = now(); alt(); };
    img.src = url;
  }
  function team(b, slot, box, tm, league, kind) {
    var abbr = String((tm && tm.abbr) || ""), colour = (tm && tm.color) || COLORS[league + ":" + abbr.toUpperCase()] || "#fff";
    picture(b, slot, box, logoOf(tm && tm.logo, kind, kind === "wide" ? "#5c7fa6" : "#7a6fa8"), function () { box.textContent = abbr; box.style.color = colour; });
  }
  // a score: a number that changes while it is on screen grows and settles, once
  function score(el, s, first) {
    var was = el.textContent;
    if (s === was) return;
    el.textContent = s;
    if (first || !was || !s) return;
    el.classList.remove("bump");
    void el.offsetWidth;
    el.classList.add("bump");
  }
  // one word in place of the inning or the clock (FINAL, HALF, RAIN DELAY): the biggest of these
  // sizes at which it fits its line
  var SIZES = [2.125, 1.75, 1.375, 1.125];
  function fit(el, room) {
    for (var i = 0; i < SIZES.length; i++) {
      el.style.fontSize = SIZES[i] + "rem"; el.style.lineHeight = (SIZES[i] + 0.125) + "rem";
      if (el.offsetWidth <= room * rem()) return;
    }
  }

  // the right panel
  function side(b) {
    var g = b.g, st = g.state, m = g.mlb, n = g.nba, ring = g.league === "nba", on = st === "live" || st === "delayed";
    var kind = st === "pre" ? "pre" : g.league === "mlb" && on && m && m.inning >= 1 ? "bases" : ring && on && n && !n.halftime ? "clock" : "word";
    var box = b.p.side;
    // the ring counts a quarter down, so it is there only while one is being played: not before
    // the game, not at halftime, not once it is over
    if (b.kind !== kind) { b.kind = kind; b.sh = {}; box.innerHTML = (kind === "clock" ? RING_SVG : "") + PANEL[kind]; }
    var q = function (sel) { return box.querySelector(sel); };
    var set = function (sel, text) { var el = q(sel); if (el.textContent !== text) el.textContent = text; };
    if (kind === "pre") {
      // when it starts, by the home's own clock, and how long that is from now
      var d = new Date(g.start), ok = !isNaN(d.getTime()), h = d.getHours(), mi = d.getMinutes(), h24 = !!(O && O.clock24 && O.clock24());
      set(".sb-time", !ok ? "" : (h24 ? (h < 10 ? "0" : "") + h : h % 12 || 12) + ":" + (mi < 10 ? "0" : "") + mi);
      set(".sb-ap", !ok || h24 ? "" : t(h < 12 ? "am" : "pm"));
      var mins = Math.ceil((d.getTime() - wall()) / 60000);
      set(".sb-in", !ok ? "" : mins > 0 ? t("sb_in_min", { n: mins }) : t("sb_soon"));
    } else if (kind === "bases") {
      // a delay: the feed's word for it where the inning was; the bases and outs stay as they stand
      var ik = st === "delayed" ? "d" + (g.status || "") : (m.half === "bottom" ? "b" : "t") + m.inning, inn = q(".sb-inn");
      if (b.sh.inn !== ik) {
        b.sh.inn = ik;
        if (st === "delayed") { inn.innerHTML = '<span class="sb-word">' + esc(g.status || t("sb_delayed")) + "</span>"; fit(inn.firstChild, 10.25); }
        else inn.innerHTML = (m.half === "bottom" ? DOWN : UP) + esc(m.inning);
      }
      var bs = q(".sb-bases"), outs = q(".sb-outs").children;
      bs.classList.toggle("on1", !!m.first); bs.classList.toggle("on2", !!m.second); bs.classList.toggle("on3", !!m.third);
      for (var i = 0; i < outs.length; i++) outs[i].classList.toggle("on", i < (m.outs || 0));
    } else if (kind === "clock") {
      set(".sb-per", String(n.period || ""));
      var ck = q(".sb-clk"), ct = st === "delayed" ? "d" + (g.status || "") : "c" + (n.clock || "");
      if (b.sh.clk !== ct) {
        b.sh.clk = ct;
        ck.textContent = st === "delayed" ? g.status || t("sb_delayed") : n.clock || "";
        ck.className = st === "delayed" ? "sb-clk sb-word" : "sb-clk";
        ck.style.fontSize = ""; ck.style.lineHeight = "";
        if (st === "delayed") fit(ck, 6.75);
      }
    } else {
      var w = st === "final" ? g.final_label || t("sb_final") : ring && on && n && n.halftime ? t("sb_half") : g.status || "";
      if (b.sh.word !== w) { b.sh.word = w; var we = q(".sb-word"); we.textContent = w; fit(we, 10.25); }
    }
    // the solid arc: what is left of the period, clockwise from 12 o'clock
    var arc = kind === "clock" ? q(".arc") : null;
    if (arc) {
      var f = kind === "clock" ? Math.max(0, Math.min(1, +n.remaining || 0)) : 0, da = (RING * f).toFixed(1) + " " + RING.toFixed(2);
      if (b.sh.arc !== da) { b.sh.arc = da; arc.setAttribute("stroke-dasharray", da); arc.style.display = f > 0 ? "" : "none"; }
    }
  }

  // what OK opens: the channel's link, else the app the game is on, else (a channel with only its
  // number) the TV app, which the bridge names. A game on nothing in the channel table: nothing.
  function target(g) { var c = g.channel; return c ? c.link || c.app || (c.number > 0 ? S.tvApp : "") || "" : ""; }
  // where a game is on when OK cannot open it: the channel's name, else the first name the feed gives
  function where(g) { var c = g.channel, bs = g.broadcasts; return String((c && c.name) || (bs && bs.length ? bs[0] : "") || ""); }
  function channelText(g) {
    var c = g.channel, s = c ? String(c.name || "") : "";
    if (c && c.number > 0) s += (s ? " " : "") + c.number;
    return s;
  }
  function appName(pkg) { return (O && O.appName && O.appName(pkg)) || APPS[pkg] || t("sb_tv_app"); }
  // the pill under a selected bug: "OK to watch · SportsNet LA 690"; for a game OK cannot open,
  // only where it is on ("On ClipperVision")
  function pill(b) {
    var g = b.g, ch = channelText(g), tg = target(g), on = tg ? "" : where(g);
    var text = b.note || (tg ? (ch ? t("sb_watch", { channel: ch }) : t("sb_open", { app: appName(tg) })) : on ? t("sb_on", { name: on }) : "");
    if (b.p.pill.textContent !== text) b.p.pill.textContent = text;
  }
  // a short message on the pill in place of its words (which channel to go to, or what went wrong)
  function note(b, text, stay) {
    if (!text) return;
    clearTimeout(b.noteTimer);
    b.note = String(text);
    pill(b);
    if (!stay) b.noteTimer = setTimeout(function () { b.note = ""; pill(b); }, 2600);
  }
  function unnote(b) { clearTimeout(b.noteTimer); if (b.note) { b.note = ""; pill(b); } }

  function draw(b, first) {
    var g = b.g, p = b.p, st = g.state, states = ["pre", "live", "final", "delayed"];
    for (var i = 0; i < states.length; i++) b.el.classList.toggle(states[i], st === states[i]);
    var name = String(g.league_name || g.league || "").toUpperCase();
    if (p.name.textContent !== name) p.name.textContent = name;
    picture(b, "lg", p.icon, logoOf(g.league_logo, g.league === "nba" ? "tall" : "wide", "#6b7f96"), function () { p.icon.innerHTML = BALL[g.league] || BALL.any; });
    team(b, "a", p.am, g.away, g.league, "wide");
    team(b, "h", p.hm, g.home, g.league, "tall");
    // no scores before the game
    var as = st === "pre" || g.away.score == null ? "" : String(g.away.score), hs = st === "pre" || g.home.score == null ? "" : String(g.home.score);
    b.el.classList.toggle("d3", as.length > 2 || hs.length > 2);
    score(p.as, as, first);
    score(p.hs, hs, first);
    side(b);
    if (p.hold.textContent !== t("sb_hold")) p.hold.textContent = t("sb_hold");
    pill(b);
  }

  // ---------------------------------------------------------------- showing and leaving
  // the same corner as the card (nowplaying.js place()): two or more people have the top left, so
  // the bugs start under their switch
  function place() {
    var p = document.getElementById("people");
    var bt = p && p.children.length && p.offsetHeight ? p.getBoundingClientRect().bottom : 0;
    if (bt) E.root.style.setProperty("--np-top", (bt + rem()) + "px"); else E.root.style.removeProperty("--np-top");
  }
  // Which bug is selected, and where each one sits: the second under the first, and lower still
  // while the first is selected, so the first one's pill is in the clear. A bug under one that is
  // on its way out keeps its place until that one is gone, then moves up.
  function paint() {
    if (S.focus && !alive().some(function (b) { return b.id === S.focus; })) S.focus = null;
    for (var i = 0; i < S.bugs.length; i++) {
      var b = S.bugs[i], top = i ? S.bugs[0] : null, sel = b.id === S.focus;
      if (b.leaving) continue;
      b.el.classList.toggle("sel", sel);
      b.p.body.classList.toggle("focus", sel);   // app.js looks for .focus to know the focus is somewhere
      b.el.classList.toggle("s1", i > 0);
      b.el.classList.toggle("room", i > 0 && !top.leaving && top.id === S.focus && !!top.p.pill.textContent);
    }
  }
  // how: "out" (the game left its window) or "stop" (OK held)
  function leave(b, how) {
    if (b.leaving) return;
    b.leaving = true;
    var had = S.focus === b.id;
    if (H.id === b.id) holdOff();
    if (had) S.focus = null;
    clearTimeout(b.enterTimer); clearTimeout(b.noteTimer);
    b.el.classList.remove("entering", "sel", "press", "holding");
    b.p.body.classList.remove("focus");
    b.el.classList.add(how === "stop" ? "stopped" : "leaving");
    paint();
    if (had && O && O.gone) O.gone();              // the focus goes back to the app bar
    b.goneTimer = setTimeout(function () {
      if (b.el.parentNode) b.el.parentNode.removeChild(b.el);
      S.bugs = S.bugs.filter(function (x) { return x !== b; });
      if (!S.bugs.length) E.root.classList.add("gone");
      var l = S.next;
      S.next = null;
      if (l) sync(l); else paint();                // whoever is left moves up; a game that was waiting comes in
    }, GONE_MS);
  }
  // the games to show now, top first: the ones no longer there leave, new ones pop in, the rest are redrawn
  function sync(list) {
    var ids = list.map(function (g) { return g.id; }), i, b;
    for (i = 0; i < S.bugs.length; i++) if (!S.bugs[i].leaving && ids.indexOf(S.bugs[i].id) < 0) leave(S.bugs[i], "out");
    for (i = 0; i < list.length; i++) { b = find(list[i].id); if (b && !b.leaving) { b.g = list[i]; draw(b); } }
    // while one is on its way out, the rest wait: nothing moves into its place before it is gone
    if (S.bugs.some(function (x) { return x.leaving; })) { S.next = list; paint(); ticks(); return; }
    var fresh = [];
    if (list.length) {
      build();
      if (E.root.classList.contains("gone")) { place(); E.root.classList.remove("gone"); }
      S.bugs = list.map(function (g) {
        var x = find(g.id);
        if (!x) { x = make(g); fresh.push(x); E.root.appendChild(x.el); draw(x, true); }
        return x;
      });
    }
    paint();
    if (fresh.length) {
      void E.root.offsetWidth;                     // the starting state is in before it moves
      fresh.forEach(function (x) {
        x.el.classList.remove("off", "nomorph");
        x.el.classList.add("entering");
        x.enterTimer = setTimeout(function () { x.el.classList.remove("entering"); }, 700);
      });
    }
    ticks();
  }

  // "in 42 min": the minute turns over only while a game that has not started is on screen
  function ticks() {
    clearTimeout(S.tick); S.tick = null;
    if (S.away || document.hidden) return;
    var a = alive(), wait = 0;
    for (var i = 0; i < a.length; i++) if (a[i].g.state === "pre") {
      var left = new Date(a[i].g.start).getTime() - wall();
      if (left > 0 && (!wait || (left % 60000 || 60000) < wait)) wait = left % 60000 || 60000;
    }
    if (wait) S.tick = setTimeout(function () {
      var l = alive();
      for (var j = 0; j < l.length; j++) if (l[j].g.state === "pre") side(l[j]);
      ticks();
    }, wait + 80);
  }

  // ---------------------------------------------------------------- what the bridge says
  function isHidden(id) { return S.hidden.some(function (x) { return x.id === id; }); }
  function apply(d) {
    S.good = now(); S.fails = 0;
    S.last = d;
    show(d);
    plan();
  }
  // the answer's games on the screen, less the ones that were put away
  function show(d) {
    S.tvApp = d && typeof d.tv_app === "string" ? d.tv_app : "";
    var all = (d && Array.isArray(d.games) ? d.games : []).filter(function (g) { return g && g.id && g.away && g.home; });
    S.any = all.length > 0;
    var list = all.filter(function (g) { return !isHidden(g.id); });
    // a game being played goes on top (the bridge sends them that way already); never more than two
    list = list.filter(isLive).concat(list.filter(function (g) { return !isLive(g); })).slice(0, 2);
    sync(list);
  }
  // The only timer, and only while the bridge's answer has games: 15 s with one being played on
  // screen, else 60 s. No games: nothing is set, and the next question waits for wake() or resume.
  function plan() {
    clearTimeout(S.timer); S.timer = null;
    if (S.away || document.hidden || !S.any) return;
    S.timer = setTimeout(ask, alive().some(function (b) { return isLive(b.g); }) ? LIVE_MS : IDLE_MS);
  }
  function failed() {
    S.fails++;
    clearTimeout(S.timer); S.timer = null;
    if (S.away || document.hidden) return;
    if (S.any) {
      // games on screen: they stay as they are for up to two minutes without an answer, then go
      if (now() - S.good < STALE_MS) { plan(); return; }
      S.any = false; S.fails = 1; S.last = null;
      sync([]);
    }
    if (S.fails <= RETRY.length) S.timer = setTimeout(ask, RETRY[S.fails - 1]);
  }
  function ask() {
    clearTimeout(S.timer); S.timer = null;
    if (!O || S.away || document.hidden) return;   // another app is in front: nobody is asked
    if (MOCK) { apply(mock.state()); return; }
    if (S.asking) { S.again = true; return; }
    S.asking = true;
    var done = function () { S.asking = false; if (S.again) { S.again = false; ask(); } };
    O.api("GET", "/api/scores").then(apply, failed).then(done, done);
  }
  function wake() { if (O) ask(); }
  function quiet() { clearTimeout(S.timer); clearTimeout(S.tick); S.timer = null; S.tick = null; }

  // ---------------------------------------------------------------- the remote (app.js calls these)
  // OK on a selected bug. A channel with a link, or a game on a streaming app: that is opened. Else
  // the TV app, which opens where it was last, so the pill says which channel to go to.
  function act(b) {
    var g = b.g, c = g.channel, tg = target(g);
    if (!tg) return;
    var tune = c && !c.link && !c.app && c.number > 0 ? t("sb_channel", { n: c.number }) : "";
    var asked = O && O.open ? O.open(tg) : null;
    if (!asked) {
      // a preview has nothing to open: it says what it would have asked the bridge for
      try { console.log("[scores] OK would POST /api/launch " + JSON.stringify({ app: tg })); } catch (e) {}
      note(b, (tune ? tune + " · " : "") + tg);
      return;
    }
    if (tune) note(b, tune, true);                 // it stays until the home is back in front
    asked.then(null, function (e) { note(b, e && e.message); });
  }
  // OK held and let go: the bugs are put away, all that are showing, as the Spotify card is. Their
  // ids are kept, so they stay away over a reload, until Up from the app bar brings them back
  // (recall). A game that comes into its window later has another id and shows by itself.
  function closeAll() {
    var at = Date.now(), a = alive(), ids = a.map(function (x) { return x.id; });
    S.hidden = S.hidden.filter(function (x) { return ids.indexOf(x.id) < 0 && at - x.at < KEEP_MS; });
    a.forEach(function (x) { S.hidden.push({ id: x.id, at: at }); });
    if (!MOCK && O && O.keep) O.keep("tvhome.sb.hidden", S.hidden);   // a preview forgets when it is reloaded
    a.forEach(function (x) { leave(x, "stop"); });
  }
  // Up from the app bar with the bugs put away: they come back. False when there are none to bring.
  function recall() {
    var d = S.last, games = d && Array.isArray(d.games) ? d.games : [];
    var ids = games.map(function (g) { return g && g.id; }).filter(function (id) { return id && isHidden(id); });
    if (!O || S.away || !ids.length) return false;
    S.hidden = S.hidden.filter(function (x) { return ids.indexOf(x.id) < 0; });
    if (!MOCK && O.keep) O.keep("tvhome.sb.hidden", S.hidden);
    // any still on their way out are taken away at once, so the same games can come straight back in
    S.bugs.forEach(function (b) { if (b.leaving) { clearTimeout(b.goneTimer); if (b.el.parentNode) b.el.parentNode.removeChild(b.el); } });
    S.bugs = alive(); S.next = null;
    show(d);
    plan();
    return alive().length > 0;
  }
  // Up and down between two bugs. "up" and "down": the focus is leaving by the top or the bottom
  // (app.js says where it goes, and blur() follows).
  function move(dy) {
    var a = alive(), i = 0;
    if (!a.length) return "down";
    while (i < a.length - 1 && a[i].id !== S.focus) i++;
    if (i + dy < 0) return "up";
    if (i + dy >= a.length) return "down";
    S.focus = a[i + dy].id;
    paint();
    return "stay";
  }
  function holdOff() {
    clearTimeout(H.vis); H.vis = null;
    var b = H.id ? find(H.id) : null;
    if (b) b.el.classList.remove("press", "holding");
    H.down = false; H.id = null;
  }
  // OK goes down: nothing happens yet. Let go quickly and it is a press. Keep holding and the bug
  // dims while the card's pill fills; let go once it is full (1.2 s) and the bugs are put away. Letting
  // go on the way cancels. Both act when OK comes up, so one press never does both.
  function okDown() {
    var b = S.focus ? find(S.focus) : null;
    if (H.down || !b || b.leaving) return;
    H.down = true; H.at = now(); H.id = b.id;
    b.el.classList.add("press");                   // the fill starts now, out of sight until the veil shows
    H.vis = setTimeout(function () { b.el.classList.add("holding"); }, TAP_MS);
  }
  function okUp() {
    if (!H.down) return;
    var held = now() - H.at, b = find(H.id);
    holdOff();
    if (!b || b.leaving) return;
    if (held >= HOLD_MS) closeAll();
    else if (held < TAP_MS) act(b);
  }

  function start(opts) {
    O = opts || {};
    var h = !MOCK && O.kept ? O.kept("tvhome.sb.hidden") : null;
    S.hidden = Array.isArray(h) ? h.filter(function (x) { return x && x.id && Date.now() - x.at < KEEP_MS; }) : [];
    if (MOCK) document.addEventListener("keydown", function (e) {
      if (e.repeat || !e.key || (e.target && /^(INPUT|TEXTAREA)$/.test(e.target.tagName || ""))) return;
      if (mock.key(e.key.toLowerCase())) apply(mock.state());
    });
    document.addEventListener("visibilitychange", function () { if (document.hidden) quiet(); else ask(); });
    ask();
  }

  window.scores = {
    start: start,
    // the TV app: a game has come into its window (harmless at any other time)
    wake: wake,
    focusable: function () { return alive().length > 0; },
    // the bugs were put away with OK: bring them back (app.js, Up from the app bar)
    recall: recall,
    enter: function () { var a = alive(); S.focus = a.length ? a[0].id : null; paint(); },
    // the focus went somewhere else (a held OK is dropped with it: letting go then does nothing)
    blur: function () {
      var b = S.focus ? find(S.focus) : null;
      if (!b && !S.focus) return;
      if (H.down) holdOff();
      if (b) unnote(b);
      S.focus = null;
      paint();
    },
    paint: paint, move: move, okDown: okDown, okUp: okUp, okCancel: holdOff,
    // the home went behind another app, and came back (the launcher says so)
    away: function () { S.away = true; quiet(); holdOff(); },
    refresh: function () { S.away = false; S.bugs.forEach(unnote); if (O) ask(); },
    // the language changed
    words: function () { alive().forEach(function (b) { b.sh = {}; draw(b); }); paint(); }
  };
})();
