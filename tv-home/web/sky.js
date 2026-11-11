// The solar landscape (home.mode "solar", the default): one scene, lit by where the sun really is.
//
// Frames come from landscape/<name>/manifest.json, each lit for one solar altitude. The two frames
// either side of the sun's current altitude are the only images in the page; the upper one's opacity
// is how far between them the sun is, updated every 30 s with a 30 s linear transition, so the light
// changes continuously. Everything moves with transform and opacity only.
//
// On top: two drifting cloud plates (cirrus, valley haze), and below -6° a night sky that turns at the
// real 15° an hour, with twinkling, shooting stars, satellites, a plane now and then, a breathing
// Milky Way and a city that is never quite still. Random timing comes from a seeded generator that is
// reseeded on every load, so nothing visibly repeats.
//
// Coordinates: everything inside the stack is laid out in 1080p device pixels (1920x1080) and the
// stack is scaled once to the screen, so on the TV (960x540 CSS px at devicePixelRatio 2) one unit is
// one real pixel.
//
// Dev (browser only, or ?dev=1 on the TV): ?t=HH:MM sets the clock (in the home's time zone),
// ?alt=-10 sets the sun's altitude, ?pm=1 with ?alt picks the evening frames, ?night=1 forces night
// with a shooting star every 10 s, and a scrub bar sweeps 24 hours in 20 seconds.
(function () {
  "use strict";
  var Q = location.search;
  function arg(name) { var m = Q.match(new RegExp("[?&]" + name + "=([^&]*)")); return m ? decodeURIComponent(m[1]) : null; }

  var W = 1920, H = 1080;
  var RAD = Math.PI / 180;

  // ---------------------------------------------------------------- the sun (SunCalc, MIT, altitude only)
  function sunAltitude(ms, lat, lon) {
    var d = ms / 86400000 - 0.5 + 2440588 - 2451545;             // days since J2000
    var M = RAD * (357.5291 + 0.98560028 * d);                    // solar mean anomaly
    var C = RAD * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M));
    var L = M + C + RAD * 102.9372 + Math.PI;                     // ecliptic longitude
    var e = RAD * 23.4397;                                        // obliquity of the Earth
    var dec = Math.asin(Math.sin(e) * Math.sin(L));
    var ra = Math.atan2(Math.sin(L) * Math.cos(e), Math.cos(L));
    var Hh = RAD * (280.16 + 360.9856235 * d) - RAD * -lon - ra;  // hour angle
    var phi = RAD * lat;
    return Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(Hh)) / RAD;
  }

  // ---------------------------------------------------------------- random numbers
  function mulberry32(a) {
    return function () {
      a |= 0; a = a + 0x6D2B79F5 | 0;
      var t = Math.imul(a ^ a >>> 15, 1 | a);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  }
  // timing: a new seed every load. The star field itself: a fixed seed, so the sky is the same sky.
  var rnd = mulberry32((Date.now() ^ Math.floor(Math.random() * 4294967296)) >>> 0);
  function rr(a, b) { return a + (b - a) * rnd(); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function lerp(a, b, t) { return a + (b - a) * t; }

  // ---------------------------------------------------------------- time
  var LAT = 34.0522, LON = -118.2437, TZ = "America/Los_Angeles";
  var offset = 0;          // ?t=: how far the shown clock is from the real one
  var fixedAlt = null;     // ?alt=
  var fixedPm = false;
  var forceNight = false;  // ?night=1
  var scrubMs = null;      // the dev sweep: the time being shown, or null
  // ?speed=N (previews): the clock runs N times faster from the moment the page opened
  var SPEED = Math.max(1, parseFloat(arg("speed")) || 1), T0 = Date.now();
  var STILL = arg("still") === "1";   // previews made of stills: no zoom, clouds placed by the shown clock
  function nowMs() {
    if (scrubMs != null) return scrubMs;
    return (SPEED === 1 ? Date.now() : T0 + (Date.now() - T0) * SPEED) + offset;
  }

  // minutes ahead of UTC in a time zone at an instant
  function tzOffsetMin(tz, ms) {
    try {
      var p = {};
      new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", second: "2-digit" }).formatToParts(new Date(ms)).forEach(function (x) { p[x.type] = x.value; });
      return (Date.UTC(+p.year, p.month - 1, +p.day, +p.hour % 24, +p.minute, +p.second) - Math.floor(ms / 1000) * 1000) / 60000;
    } catch (e) { return -new Date(ms).getTimezoneOffset(); }
  }
  // today's HH:MM in the home's time zone, as an instant
  function instantFor(hh, mm) {
    var now = Date.now(), off = tzOffsetMin(TZ, now);
    var local = new Date(now + off * 60000);   // its UTC fields are the home's local date
    var guess = Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate(), hh, mm) - off * 60000;
    return Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate(), hh, mm) - tzOffsetMin(TZ, guess) * 60000;
  }

  function altAt(ms) { return sunAltitude(ms, LAT, LON); }
  function altitude() {
    if (fixedAlt != null) return fixedAlt;
    if (forceNight) return -25;
    return altAt(nowMs());
  }
  function morning() {
    if (fixedAlt != null) return !fixedPm;
    var t = nowMs();
    return altAt(t + 600000) >= altAt(t);
  }
  function phaseOf(alt) {
    if (alt < -18) return "night";
    if (alt < -12) return "astronomical twilight";
    if (alt < -6) return "nautical twilight";
    if (alt < 0) return "blue hour";
    if (alt < 6) return "sunrise/sunset";
    if (alt < 15) return "golden";
    if (alt < 30) return "morning/evening";
    return "day";
  }

  // ---------------------------------------------------------------- frames
  var BASE = "landscape/la-hills/";
  var LISTS = { am: [], pm: [] };
  // clouds by frame: opacity and tint (sunrise, mid-morning and dusk have painted clouds: none)
  var NIGHT_TINT = [150, 165, 200], COOL = [176, 190, 218], WARM = [255, 218, 178], WHITE = [255, 255, 255];
  var CLOUD = {
    "frame-01.jpg": { o: 0.35, c: NIGHT_TINT }, "frame-02.jpg": { o: 0.45, c: COOL }, "frame-03.jpg": { o: 0.45, c: COOL },
    "frame-04.jpg": { o: 0.45, c: COOL }, "frame-05.jpg": { o: 0, c: WARM }, "frame-06.jpg": { o: 0.75, c: WARM },
    "frame-07.jpg": { o: 0, c: WHITE }, "frame-08.jpg": { o: 1, c: WHITE }, "frame-10.jpg": { o: 0, c: WARM }
  };

  // manifest "align": [scale, x, y] that lines a frame up with the others (frames 06 and 07 came out wider)
  var ALIGN = {};
  function buildLists(man) {
    (man.frames || []).forEach(function (f) {
      if (Array.isArray(f.align) && f.align.length === 3) ALIGN[f.file] = f.align;
      // the drifting clouds' opacity and tint over this frame
      if (Array.isArray(f.clouds) && f.clouds.length === 2) CLOUD[f.file] = { o: +f.clouds[0], c: f.clouds[1] };
    });
    // small position corrections of the weather pictures (rain, showers ...)
    Object.keys(man.weather || {}).forEach(function (k) {
      var wa = (man.weather[k] || {}).align || {};
      Object.keys(wa).forEach(function (f) { ALIGN[f] = wa[f]; });
    });
    if (man.sun && man.sun.rise && man.sun.set) { SUN = { rise: +man.sun.rise[0], set: +man.sun.set[0] }; loadSun(man.sun.images); }
    if (man.milkyway) MILKY = man.milkyway;
    // manifest "city": false: no copied-city shimmer or window lights (frames that light their own city)
    if (man.city === false) CITY_ON = false;
    // manifest "smooth_pairs": neighbours blended gradually instead of a quick swap
    (man.smooth_pairs || []).forEach(function (p) { SMOOTH[p[0] + "|" + p[1]] = SMOOTH[p[1] + "|" + p[0]] = true; });
    // manifest "city_evening": a brighter-city version of the night frame until a local hour
    if (man.city_evening && man.city_evening.file) { CITY_EVE = man.city_evening; if (ALIGN[CITY_EVE.replaces]) ALIGN[CITY_EVE.file] = ALIGN[CITY_EVE.replaces]; }
    // manifest "dissolve_seconds": this landscape's handover length (home.dissolve_seconds wins)
    if (isFinite(+man.dissolve_seconds) && !DISSOLVE_SET) DISSOLVE = clamp(+man.dissolve_seconds, 5, 1800);
    // manifest "cloud_plates": which drifting plates to draw
    if (Array.isArray(man.cloud_plates)) PLATES = PLATES.filter(function (p) { return man.cloud_plates.indexOf(p.name) >= 0; });
    // manifest "weather": the overcast and rain pictures, and the weather's cloud plates
    if (man.weather) {
      WXSCENES = { overcast: man.weather.overcast, rain: man.weather.rain, partly: man.weather.partly, showers: man.weather.showers };
      var wl = man.weather.layers || {};
      [["partly", 2.2], ["showers", 2.8]].forEach(function (d) {
        if (!wl[d[0]]) return;
        var p = { name: d[0], src: wl[d[0]], speed: d[1], shaded: true, weather: true, scale: 0.6, top: 0.04, maxA: d[0] === "partly" ? 0.7 : 0.5 };
        PLATES.push(p); loadPlate(p);
      });
      ["overcast", "rain"].forEach(function (k) {
        var s = man.weather[k] || {};
        ["day", "dusk", "night"].forEach(function (st) { if (s[st]) CLOUD[s[st]] = CLOUD[s[st]] || { o: 0, c: st === "night" ? NIGHT_TINT : WHITE }; });
      });
    }
    // dev: ?align=frame-07.jpg:1.095:-50:0 tries another alignment for one frame
    var ov = (arg("align") || "").split(":");
    if (ov.length === 4) ALIGN[ov[0]] = [parseFloat(ov[1]), parseFloat(ov[2]), parseFloat(ov[3])];
    // the in-between pictures for each handover, both ways
    (man.transitions || []).forEach(function (t) {
      if (!t.between || t.between.length !== 2 || !t.a || !t.b) return;
      TRANS[t.between[0] + "|" + t.between[1]] = { from: t.a, to: t.b };
      TRANS[t.between[1] + "|" + t.between[0]] = { from: t.b, to: t.a };
    });
    var fr = (man.frames || []).map(function (f) { return { file: f.file, alt: f.altitude, period: f.period || "both" }; });
    var am = fr.filter(function (f) { return f.period !== "pm"; });
    var pm = fr.filter(function (f) { return f.period !== "am"; });
    // the evening shares the morning's frame where it has none of its own (frame-05 for 0°, frame-07 for 25°)
    am.forEach(function (f) { if (f.period === "am" && !pm.some(function (p) { return p.alt === f.alt; })) pm.push(f); });
    var by = function (a, b) { return a.alt - b.alt; };
    LISTS.am = am.sort(by); LISTS.pm = pm.sort(by);
  }

  // the two frames around an altitude, and how far between them
  function bracket(alt, list) {
    if (!list.length) return null;
    if (alt <= list[0].alt) return { lo: list[0], hi: list[0], t: 0, i: 0 };
    var last = list[list.length - 1];
    if (alt >= last.alt) return { lo: last, hi: last, t: 0, i: list.length - 1 };
    for (var i = 0; i < list.length - 1; i++) {
      if (alt >= list[i].alt && alt < list[i + 1].alt) {
        return { lo: list[i], hi: list[i + 1], t: (alt - list[i].alt) / (list[i + 1].alt - list[i].alt), i: i };
      }
    }
    return { lo: last, hi: last, t: 0, i: list.length - 1 };
  }

  // decoded images, ready to drop in without a hitch
  var cache = {};
  function ensure(file) {
    if (cache[file]) return cache[file];
    var img = new Image();
    img.className = "sky-img";
    img.alt = "";
    var al = ALIGN[file];
    if (al) { img.style.transformOrigin = "0 0"; img.style.transform = "translate(" + al[1] + "px," + al[2] + "px) scale(" + al[0] + ")"; }
    img.src = BASE + file;
    // decoded ahead where the browser can; on load either way, and never waiting more than 4 s
    // (seen on the Fire TV's WebView: a decode() that did not settle left the frames empty)
    var loaded = new Promise(function (ok) { img.onload = ok; img.onerror = ok; });
    var decoded = img.decode ? img.decode().catch(function () {}) : loaded;
    cache[file] = Promise.race([decoded, loaded.then(function () { return new Promise(function (ok) { setTimeout(ok, 400); }); }),
      new Promise(function (ok) { setTimeout(ok, 4000); })]).then(function () {
      // an aligned frame moved down or right leaves a sliver at the edge: fill it with the sky's colour
      if (al && (al[1] > 0 || al[2] > 0)) {
        try {
          var c = document.createElement("canvas"); c.width = c.height = 1;
          var g = c.getContext("2d"); g.drawImage(img, 0, 0, W, 8, 0, 0, 1, 1);
          var p = g.getImageData(0, 0, 1, 1).data;
          img.setAttribute("data-edge", "rgb(" + p[0] + "," + p[1] + "," + p[2] + ")");
        } catch (e) { /* the sliver stays dark */ }
      }
      return img;
    });
    return cache[file];
  }

  var slots = [], base = 0, seq = 0;
  function slotOp(s, v, instant) { fade(s.el, v, instant); }

  // ---------------------------------------------------------------- slow fades, in steps
  // No CSS transitions or animations run here: on this TV the page is drawn at 4K, and while any
  // runs the whole screen is composited 60 times a second (most of a CPU core). Instead every slow
  // change moves in small steps on a two-second tick (sky.js "the ticker"), and in between the TV
  // draws nothing. A change takes 30 s at most, as the transitions did.
  var fades = [];
  // secs: how long a full 0-to-1 change takes (30 s unless given); done(): called once it arrives
  function fade(e, v, instant, secs, done) {
    var f = e._fade;
    if (!f) { f = e._fade = { el: e, cur: parseFloat(e.style.opacity) || 0, goal: 0 }; fades.push(f); }
    f.goal = v; f.secs = secs || 30; f.done = done || null;
    if (instant || scrubbing) { f.cur = v; e.style.opacity = v.toFixed(4); }
    if (f.cur === f.goal && f.done) { var d = f.done; f.done = null; d(); }
  }
  function stepFades(dt) {
    fades.forEach(function (f) {
      if (f.cur === f.goal) return;
      var k = dt / f.secs, d = f.goal - f.cur;
      f.cur = Math.abs(d) <= k ? f.goal : f.cur + (d > 0 ? k : -k);
      f.el.style.opacity = f.cur.toFixed(4);
      if (f.cur === f.goal && f.done) { var cb = f.done; f.done = null; cb(); }
    });
  }
  function shown(e) { return !!(e && e._fade && (e._fade.cur > 0 || e._fade.goal > 0)); }
  function put(s, img) {
    if (s.el.firstChild !== img) { s.el.textContent = ""; s.el.appendChild(img); }
    s.el.style.backgroundColor = img.getAttribute("data-edge") || "";
  }
  function setBase(i) { base = i; slots[i].el.style.zIndex = 1; slots[1 - i].el.style.zIndex = 2; }

  // show lo with hi over it at opacity t. A frame both pairs share stays where it is, so a new pair
  // never shows a step: the shared frame is fully visible at the moment the pair changes.
  function showPair(lo, hi, t, instant) {
    var my = ++seq;
    Promise.all([ensure(lo), ensure(hi)]).then(function (imgs) {
      if (my !== seq) return;
      glass(imgs, lo, hi, lo === hi ? 0 : t);
      var b = slots[base], top = slots[1 - base];
      if (lo === hi) {
        // one frame (past either end): it is the base, the other slot clears. One image element can
        // only be in one slot, so this never puts it in both.
        var k = b.file === lo ? b : top.file === lo ? top : b;
        var o = k === b ? top : b;
        if (k !== b) { setBase(slots.indexOf(k)); slotOp(o, 0, true); }
        if (k.file !== lo) { k.file = lo; put(k, imgs[0]); }
        slotOp(k, 1, true);
        slotOp(o, 0, instant);
        if (instant) o.file = null;
        return;
      }
      if (b.file === lo && top.file === hi) { slotOp(top, t, instant); return; }
      var keepLo = b.file === lo ? b : top.file === lo ? top : null;
      var keepHi = b.file === hi ? b : top.file === hi ? top : null;
      if (keepLo && !instant) {
        // the lower frame stays (rising sun): it goes underneath, the new upper frame fades in on top
        var other = keepLo === b ? top : b;
        setBase(slots.indexOf(keepLo)); slotOp(keepLo, 1, true);
        other.file = hi; put(other, imgs[1]); slotOp(other, 0, true);
        slotOp(other, lo === hi ? 0 : t, false);
      } else if (keepHi && !instant && lo !== hi) {
        // the upper frame stays (setting sun): it goes on top at full, the new lower frame goes under it
        var under = keepHi === b ? top : b;
        setBase(slots.indexOf(under)); under.file = lo; put(under, imgs[0]); slotOp(under, 1, true);
        slotOp(keepHi, 1, true); slotOp(keepHi, t, false);
      } else {
        b.file = lo; put(b, imgs[0]); slotOp(b, 1, true);
        top.file = hi; put(top, imgs[1]); slotOp(top, lo === hi ? 0 : t, true);
      }
    });
  }

  // ---------------------------------------------------------------- one frame at a time (the default)
  // The frames were relit one by one by an image generator, so each is slightly redrawn: two of
  // them half mixed show a doubled ridge and city. So the screen shows one frame, the one nearest
  // the sun's height, and hands over to the next with a slow dissolve (DISSOLVE seconds) once the sun
  // is a little past halfway between them. The hysteresis (SWITCH) keeps a sun that hovers near the
  // middle, as at a winter noon, from going back and forth. home.blend "continuous" keeps the old
  // fully continuous mix.
  //
  // Each frame's fine detail (rocks, trees, buildings) is its own drawing, so even a short mix of two
  // frames is soft. Where the manifest lists a transition, the handover goes through two in-between
  // pictures that share the half-way light: A -> mid-a (the light moves, A's detail stays: sharp) ->
  // mid-b (only the detail changes, SWAP seconds) -> B (the light moves on, B's detail: sharp).
  var DISSOLVE = 180, SWAP = 4, SWITCH = 0.08, BLEND = "dissolve", DISSOLVE_SET = false, CITY_EVE = null, SMOOTH = {};
  // the evening version of the night frame (city lights still bright) from noon until CITY_EVE.until
  function evening(file) {
    if (!CITY_EVE || file !== CITY_EVE.replaces) return file;
    var ms = nowMs(), h = ((ms + tzOffsetMin(TZ, ms) * 60000) % 86400000) / 3600000;
    return h >= 12 && h < (+CITY_EVE.until || 22) ? CITY_EVE.file : file;
  }
  var shownFile = null, dissolving = null, TRANS = {};
  function pickFrame(br) {
    if (br.lo === br.hi) return br.lo.file;
    if (shownFile === br.lo.file) return br.t > 0.5 + SWITCH ? br.hi.file : br.lo.file;
    if (shownFile === br.hi.file) return br.t < 0.5 - SWITCH ? br.lo.file : br.hi.file;
    return br.t >= 0.5 ? br.hi.file : br.lo.file;
  }
  // the steps from one frame to another: [picture, seconds]
  function steps(from, to, secs) {
    if (secs) return [[to, secs]];
    var tr = TRANS[from + "|" + to];
    if (tr) return [[tr.from, (DISSOLVE - SWAP) / 2], [tr.to, SWAP], [to, (DISSOLVE - SWAP) / 2]];
    return [[to, DISSOLVE]];
  }
  // a picture fades in on top over secs; once in, it becomes the base and the one under it leaves
  function dissolveTo(file, secs, done) {
    var my = seq;
    ensure(file).then(function (img) {
      if (my !== seq) return;
      var b = slots[base], top = slots[1 - base];
      top.file = file; put(top, img); slotOp(top, 0, true);
      fade(top.el, 1, false, secs, function () {
        if (my !== seq) return;
        slotOp(b, 0, true); b.file = null; b.el.textContent = "";   // the old picture leaves the page
        setBase(slots.indexOf(top));
        done(img);
      });
    });
  }
  function showFrame(file, instant, secs) {
    if (!instant && (file === shownFile || (dissolving && dissolving.file === file))) return;
    var my = ++seq;
    var from = shownFile;
    ensure(file).then(function (img) {
      if (my !== seq) return;
      var b = slots[base], top = slots[1 - base];
      if (dissolving) {
        // a new target while still on the way: whatever is coming in becomes the base at once
        if (top.file) { slotOp(top, 1, true); slotOp(b, 0, true); b.el.textContent = ""; b.file = null; setBase(1 - base); }
        from = slots[base].file;
        dissolving = null;
      }
      b = slots[base]; top = slots[1 - base];
      if (instant || !from || b.file !== from) {
        b.file = file; put(b, img); slotOp(b, 1, true);
        slotOp(top, 0, true); top.file = null; top.el.textContent = "";
        shownFile = file; dissolving = null;
        glass([img, img], file, file, 0);
        return;
      }
      shownFile = file;
      dissolving = { file: file, from: from };
      var path = steps(from, file, secs);
      path.forEach(function (s) { ensure(s[0]); });   // decoded ahead
      (function next(i) {
        if (my !== seq) return;
        if (i >= path.length) { dissolving = null; return; }
        dissolveTo(path[i][0], path[i][1], function (im) {
          glass([im, im], path[i][0], path[i][0], 0);   // the glass clock follows each step
          next(i + 1);
        });
      })(0);
    });
  }

  // ---------------------------------------------------------------- the clock's glass
  // The glass clock (as in the scenes) is filled with a frosted copy of the picture behind it. The
  // scenes ship a -glass.jpg; here the light keeps changing, so the copy is made from the two frames
  // showing, mixed as they are on screen, blurred and lifted, small (it is blurred anyway).
  var glassCb = null, glassKey = "";
  function glass(imgs, lo, hi, t) {
    if (!glassCb) return;
    var key = lo + hi + Math.round(t * 40);
    if (key === glassKey) return;
    glassKey = key;
    try {
      var c = document.createElement("canvas"), GW = 384, GH = 216, k = GW / W;
      c.width = GW; c.height = GH;
      var g = c.getContext("2d");
      // frosted glass: the blurred landscape, lifted, under a milky white frost that is strong by
      // day (so the digits read as pale glass on a bright sky) and light at night
      var night = forceNight ? 1 : clamp(-altitude() / 6, 0, 1);
      var lift = 1.15 + 0.6 * night;
      g.filter = "blur(4px) brightness(" + lift.toFixed(2) + ") saturate(1.2)";
      var draw = function (img, file, a) {
        var al = ALIGN[file] || [1, 0, 0];
        g.globalAlpha = a;
        g.setTransform(al[0] * k, 0, 0, al[0] * k, al[1] * k, al[2] * k);
        g.drawImage(img, -8, -8, W + 16, H + 16);   // a little over the edges, so the blur has no dark rim
      };
      draw(imgs[0], lo, 1);
      if (lo !== hi && t > 0) draw(imgs[1], hi, t);
      g.setTransform(1, 0, 0, 1, 0, 0); g.filter = "none"; g.globalAlpha = 1;
      // the frost: brighter at the top of each digit, like light catching glass
      var fr = g.createLinearGradient(0, 0, 0, GH * 0.4);
      fr.addColorStop(0, "rgba(255,255,255," + (0.5 - 0.2 * night).toFixed(2) + ")");
      fr.addColorStop(1, "rgba(255,255,255," + (0.32 - 0.12 * night).toFixed(2) + ")");
      g.fillStyle = fr; g.fillRect(0, 0, GW, GH);
      // the glass's own colour where the clock sits, for the date line to match
      var px = g.getImageData(Math.round(GW * 0.3), Math.round(GH * 0.12), Math.round(GW * 0.4), Math.round(GH * 0.2)).data;
      var sum = [0, 0, 0], n = px.length / 4;
      for (var i = 0; i < px.length; i += 4) { sum[0] += px[i]; sum[1] += px[i + 1]; sum[2] += px[i + 2]; }
      var tint = "rgb(" + sum.map(function (v) { return Math.round(v / n); }).join(",") + ")";
      glassCb(c.toDataURL("image/jpeg", 0.85), tint);
    } catch (e) { /* no glass: the rim and sheen still draw the clock */ }
  }

  // ---------------------------------------------------------------- the sky mask (sky white, ridge black)
  // The effects are drawn into small canvases, not stacked full-screen page layers: the Fire TV's
  // WebView draws the page at 4K (devicePixelRatio 4), where every full-screen layer costs about 33 MB
  // of graphics memory, and a stack of them left parts of the page unpainted. A canvas costs only
  // its own pixels, whatever the screen.
  var CW = 960, CH = 540;   // the soft layers (clouds, the night dimming) at half size
  var maskCanvas = null, maskHalf = null, maskLum = null, MW4 = W / 4, MH4 = H / 4;
  function loadMask() {
    return new Promise(function (ok) {
      var img = new Image();
      img.onload = function () {
        try {
          var c = document.createElement("canvas"); c.width = W; c.height = H;
          var g = c.getContext("2d"); g.drawImage(img, 0, 0, W, H);
          var id = g.getImageData(0, 0, W, H), px = id.data;
          // a quarter-size copy for quick "is this sky?" checks
          maskLum = new Uint8Array(MW4 * MH4);
          for (var y = 0; y < MH4; y++) for (var x = 0; x < MW4; x++) maskLum[y * MW4 + x] = px[((y * 4 + 2) * W + x * 4 + 2) * 4];
          // canvas masks use alpha: brightness becomes alpha
          for (var i = 0; i < px.length; i += 4) { px[i + 3] = px[i]; px[i] = px[i + 1] = px[i + 2] = 255; }
          g.putImageData(id, 0, 0);
          maskCanvas = c;
          maskHalf = document.createElement("canvas"); maskHalf.width = CW; maskHalf.height = CH;
          maskHalf.getContext("2d").drawImage(c, 0, 0, CW, CH);
          ok();
        } catch (e) { ok(); }
      };
      img.onerror = function () { ok(); };
      img.src = BASE + "layers/sky-mask.png";
    });
  }
  // sky at (x, y), with a margin below so things stay clear of the ridge
  function isSky(x, y, margin) {
    if (!maskLum) return y < 560;
    var yy = y + (margin || 0);
    if (x < 0 || x >= W || y < 0 || yy >= H) return false;
    return maskLum[(yy >> 2) * MW4 + (x >> 2)] > 200 && maskLum[(y >> 2) * MW4 + (x >> 2)] > 200;
  }

  // ---------------------------------------------------------------- page
  var root, kb, layer = {};
  function el(tag, cls, parent) { var e = document.createElement(tag); if (cls) e.className = cls; (parent || kb).appendChild(e); return e; }
  function fit() {
    var s = window.innerWidth / W, h = window.innerHeight / H;
    // cover the screen if it is not 16:9
    var k = Math.max(s, h);
    root.firstChild.style.transform = "translate(" + ((window.innerWidth - W * k) / 2) + "px," + ((window.innerHeight - H * k) / 2) + "px) scale(" + k + ")";
  }

  function build() {
    root = document.getElementById("sky");
    var fitEl = el("div", "sky-fit", root);
    kb = el("div", "sky-kb", fitEl);
    var frames = el("div", "sky-frames");
    slots = [{ el: el("div", "sky-slot", frames), file: null }, { el: el("div", "sky-slot", frames), file: null }];
    setBase(0);
    // the sun: drawn, so it moves with the real sun and sinks behind the ridge (manifest "sun")
    layer.sun = canvas("sky-sun", CW, CH);
    // night: the photo's own sky dims a little, so the drawn stars (which move) carry it
    layer.dim = canvas("sky-dim", CW, CH);
    // the Milky Way band of frame-01, cut to the band, breathing
    layer.mw = el("div", "sky-mw");
    layer.mwC = canvas("sky-mw-c", MWR[2], MWR[3], layer.mw);
    layer.stars = el("div", "sky-stars");
    layer.spin = el("div", "sky-spin", layer.stars);
    layer.canvas = canvas("sky-canvas", W, H, layer.spin);
    layer.tw = el("div", "sky-tw", layer.spin);
    layer.fx = el("div", "sky-fx");
    // the city: frame-01's lights over themselves, cut to downtown
    layer.city = el("div", "sky-city");
    layer.cityImg = canvas("sky-city-c", CITYR[2], CITYR[3], layer.city);
    layer.win = el("div", "sky-win");
    // both cloud plates, drawn into one half-size canvas
    layer.clouds = canvas("sky-clouds", CW, CH);
    fit();
    window.addEventListener("resize", fit);
  }
  function canvas(cls, w, h, parent) { var c = el("canvas", cls, parent); c.width = w; c.height = h; return c; }

  // where the Milky Way band and downtown are in frame-01 (x, y, w, h in 1920x1080). The band's
  // stripe: angle of the gradient across it, and where along that gradient (0..1 over the whole
  // frame) it runs: centre, half width, soft edge. manifest "milkyway" overrides (a new night frame).
  var MWR = [600, 0, 1200, 620], CITYR = [1180, 640, 740, 380];
  var MILKY = { region: MWR, angle: 145, center: 0.475, half: 0.04, fade: 0.085 };
  var SUN = null;   // manifest "sun": where it rises and sets on screen (x, 1920 wide)
  // the dimming, the band and the city, made once from the mask and frame-01
  function prepare(f01) {
    try {
      var g = layer.dim.getContext("2d");
      g.fillStyle = "#03050c"; g.fillRect(0, 0, CW, CH);
      if (maskHalf) { g.globalCompositeOperation = "destination-in"; g.drawImage(maskHalf, 0, 0); g.globalCompositeOperation = "source-over"; }

      var MR = MILKY.region || MWR;
      layer.mwC.width = MR[2]; layer.mwC.height = MR[3];
      layer.mw.style.left = MR[0] + "px"; layer.mw.style.top = MR[1] + "px";
      layer.mw.style.width = layer.mwC.style.width = MR[2] + "px"; layer.mw.style.height = layer.mwC.style.height = MR[3] + "px";
      g = layer.mwC.getContext("2d");
      g.drawImage(f01, MR[0], MR[1], MR[2], MR[3], 0, 0, MR[2], MR[3]);
      // a soft stripe along the band
      var ux = Math.sin(MILKY.angle * RAD), uy = -Math.cos(MILKY.angle * RAD), L = Math.abs(W * ux) + Math.abs(H * uy);
      var gr = g.createLinearGradient(W / 2 - ux * L / 2 - MR[0], H / 2 - uy * L / 2 - MR[1], W / 2 + ux * L / 2 - MR[0], H / 2 + uy * L / 2 - MR[1]);
      var c0 = MILKY.center, hw = MILKY.half, fd = MILKY.fade;
      gr.addColorStop(0, "rgba(0,0,0,0)"); gr.addColorStop(clamp(c0 - hw - fd, 0, 1), "rgba(0,0,0,0)"); gr.addColorStop(clamp(c0 - hw, 0, 1), "#000");
      gr.addColorStop(clamp(c0 + hw, 0, 1), "#000"); gr.addColorStop(clamp(c0 + hw + fd, 0, 1), "rgba(0,0,0,0)"); gr.addColorStop(1, "rgba(0,0,0,0)");
      g.globalCompositeOperation = "destination-in"; g.fillStyle = gr; g.fillRect(0, 0, MR[2], MR[3]);
      if (maskCanvas) g.drawImage(maskCanvas, MR[0], MR[1], MR[2], MR[3], 0, 0, MR[2], MR[3]);
      g.globalCompositeOperation = "source-over";

      g = layer.cityImg.getContext("2d");
      g.drawImage(f01, CITYR[0], CITYR[1], CITYR[2], CITYR[3], 0, 0, CITYR[2], CITYR[3]);
      g.globalCompositeOperation = "destination-in";
      g.setTransform(1, 0, 0, 190 / 420, 1600 - CITYR[0], 830 - CITYR[1]);   // an ellipse 420 x 190 around downtown
      var rg = g.createRadialGradient(0, 0, 0, 0, 0, 420);
      rg.addColorStop(0, "#000"); rg.addColorStop(0.55, "#000"); rg.addColorStop(1, "rgba(0,0,0,0)");
      g.fillStyle = rg; g.fillRect(-3000, -3000, 6000, 6000);
      g.setTransform(1, 0, 0, 1, 0, 0); g.globalCompositeOperation = "source-over";
    } catch (e) { console.log("[home] sky layers: " + e); }
  }

  // ---------------------------------------------------------------- clouds by phase
  // cirrus 1.2 px/s and haze 0.4 px/s (at 1080p), left to right, each drawn twice side by side so the
  // seam never shows; clipped by the ridge; opacity and tint follow the light over 30 s.
  var CLOUDS_ON = true, CLOUD_K = 1;
  var CITY_ON = true;
  var PLATES = [{ name: "haze", speed: 0.4 }, { name: "cirrus", speed: 1.2 }];
  var cloudGoal = { o: 0, c: WHITE }, cloudNow = { o: 0, c: WHITE.slice() }, tinted = "", lastTick = 0, lastTint = 0;
  function cloudState(br) {
    var a = CLOUD[br.lo.file] || { o: 0, c: WHITE }, b = CLOUD[br.hi.file] || { o: 0, c: WHITE };
    var t = br.lo === br.hi ? 0 : br.t;
    var o = lerp(a.o, b.o, t);
    // a side with no clouds gives no colour: take the other side's
    var ca = a.o > 0 ? a.c : b.c, cb = b.o > 0 ? b.c : a.c;
    return { o: o, c: [0, 1, 2].map(function (i) { return Math.round(lerp(ca[i], cb[i], t)); }) };
  }
  function loadPlates() { return Promise.all(PLATES.map(loadPlate)); }
  function loadPlate(p) {
    p.phase = STILL ? 0 : rr(0, CW);
    return new Promise(function (ok) {
      var i = new Image();
      i.onload = function () { p.img = p.shaded ? seamless(i) : i; tinted = ""; ok(); };
      i.onerror = ok;
      i.src = BASE + (p.src || "layers/" + p.name + ".png");
    });
  }
  // A weather plate is tiled side by side, and its edges never quite meet (a straight line showed
  // where they joined). So its left and right 12% fade to nothing: clouds pass out of one tile and
  // into the next with no edge. Made at the half-size the clouds are drawn at.
  function seamless(img) {
    var F = Math.round(CW * 0.12);
    var out = document.createElement("canvas"); out.width = CW; out.height = CH;
    var g = out.getContext("2d");
    g.drawImage(img, 0, 0, CW, CH);
    var gr = g.createLinearGradient(0, 0, CW, 0);
    gr.addColorStop(0, "rgba(0,0,0,0)"); gr.addColorStop(F / CW, "#000");
    gr.addColorStop(1 - F / CW, "#000"); gr.addColorStop(1, "rgba(0,0,0,0)");
    g.globalCompositeOperation = "destination-in"; g.fillStyle = gr; g.fillRect(0, 0, CW, CH);
    return out;
  }
  // The plates in the current tint. Plain plates (cirrus) are white shapes: the tint becomes their
  // colour. Shaded plates (the weather's clouds) keep their shading: the tint multiplies it.
  function tintPlates(c) {
    var key = c.join(",");
    if (key === tinted) return;
    tinted = key;
    PLATES.forEach(function (p) {
      if (!p.img) return;
      var t = p.tinted || (p.tinted = document.createElement("canvas"));
      t.width = CW; t.height = CH;
      var g = t.getContext("2d");
      g.drawImage(p.img, 0, 0, CW, CH);
      if (p.shaded) {
        g.globalCompositeOperation = "multiply"; g.fillStyle = "rgb(" + key + ")"; g.fillRect(0, 0, CW, CH);
        g.globalCompositeOperation = "destination-in"; g.drawImage(p.img, 0, 0, CW, CH);
      } else {
        g.globalCompositeOperation = "source-in"; g.fillStyle = "rgb(" + key + ")"; g.fillRect(0, 0, CW, CH);
      }
    });
  }
  // how much of each weather plate is wanted now
  function plateGoal(p) {
    if (!p.weather) return 1;
    if (covered) return 0;   // overcast, rain, a rain spell: the picture has its own sky
    if (p.name === "partly") return WX === "partly" ? 1 : WX === "showers" ? 0.45 : 0;
    if (p.name === "showers") return WX === "showers" && !covered ? 1 : 0;   // a rain spell has its own clouds
    return 0;
  }
  function setClouds(st, instant) {
    cloudGoal = { o: CLOUDS_ON ? clamp(st.o * CLOUD_K, 0, 1) : 0, c: st.c };
    if (instant) { cloudNow = { o: cloudGoal.o, c: st.c.slice() }; tintPlates(cloudNow.c); }
    cloudTick();
  }
  function cloudTick() {
    var now = Date.now(), dt = lastTick ? Math.min(2, (now - lastTick) / 1000) : 0;
    lastTick = now;
    var step = scrubbing ? 1 : dt / 30;   // a full change takes 30 s
    cloudNow.o += clamp(cloudGoal.o - cloudNow.o, -step, step);
    if (scrubbing || now - lastTint > 5000) {   // the colour moves in small steps every 5 s
      lastTint = now;
      cloudNow.c = cloudNow.c.map(function (v, i) { var d = cloudGoal.c[i] - v; return Math.abs(d) < 3 || scrubbing ? cloudGoal.c[i] : Math.round(v + d / 3); });
      tintPlates(cloudNow.c);
    }
    // the weather's clouds come and go over a minute; brighter by day, faint and dark at night
    var any = cloudNow.o > 0.003, wxA = clamp((altitude() + 12) / 18, 0.3, 0.95);
    PLATES.forEach(function (p) {
      if (!p.weather) return;
      var goal = plateGoal(p);
      p.w = p.w == null || scrubbing || STILL ? goal : p.w + clamp(goal - p.w, -dt / 60, dt / 60);
      if (p.w > 0.003) any = true;
    });
    var cv = layer.clouds, g = cv.getContext("2d");
    if (!any) {
      if (cv.style.display !== "none") { g.clearRect(0, 0, CW, CH); cv.style.display = "none"; }
      return;
    }
    cv.style.display = "";
    g.clearRect(0, 0, CW, CH);
    var t = (STILL ? nowMs() : now) / 1000;   // ?still: the clouds follow the shown clock
    PLATES.forEach(function (p) {
      if (!p.tinted) return;
      var a = p.weather ? p.w * wxA * (p.maxA || 1) : cloudNow.o;
      if (a <= 0.003) return;
      g.globalAlpha = a;
      // weather plates are drawn smaller (distant clouds over the valley), tiled across
      var sc = p.scale || 1, pw = CW * sc, ph = CH * sc, py = (p.top || 0) * CH;
      var x = (p.phase + t * p.speed / 2) % pw;   // half size: half the speed in canvas pixels
      for (var xx = x - pw; xx < CW; xx += pw) g.drawImage(p.tinted, xx, py, pw, ph);
    });
    g.globalAlpha = 1;
    if (maskHalf) { g.globalCompositeOperation = "destination-in"; g.drawImage(maskHalf, 0, 0); g.globalCompositeOperation = "source-over"; }
  }

  // ---------------------------------------------------------------- the night sky
  var PIVOT = { x: -180, y: -360 };   // the celestial pole: off the top left, north
  var STARS = [];
  function makeStars() {
    var srnd = mulberry32(20261109);
    var R1 = 300, R2 = 2750;
    for (var i = 0; i < 4600; i++) {
      var r = Math.sqrt(srnd() * (R2 * R2 - R1 * R1) + R1 * R1), th = srnd() * Math.PI * 2;
      var b = Math.pow(srnd(), 3.2);           // most stars faint, a few bright
      var hue = srnd();
      STARS.push({
        r: r, th: th, b: b,
        a: 0.16 + 0.78 * b, s: b > 0.82 ? 2 : 1,
        col: hue < 0.12 ? "205,220,255" : hue > 0.93 ? "255,232,200" : "255,255,255",
        tw: srnd() < (0.06 + 0.3 * b)           // about 1 in 12 twinkle, brighter ones more often
      });
    }
  }
  var twEls = [];
  var spinDeg = 0, bakedAt = 0;
  // 15° an hour, the real rate. Counter-clockwise around the pole on screen.
  function skyAngle(ms) { return -((ms / 3600000) * 15) % 360; }
  function bake(ms) {
    var ang = skyAngle(ms) * RAD;
    var g = layer.canvas.getContext("2d");
    g.clearRect(0, 0, W, H);
    var used = 0;
    for (var i = 0; i < STARS.length; i++) {
      var st = STARS[i], th = st.th + ang;
      var x = PIVOT.x + st.r * Math.cos(th), y = PIVOT.y + st.r * Math.sin(th);
      if (x < -4 || x > W + 4 || y < -4 || y > H) { if (st.el) st.el.style.display = "none"; continue; }
      var xi = Math.round(x), yi = Math.round(y);
      if (!isSky(clamp(xi, 0, W - 1), clamp(yi, 0, H - 1), 10)) { if (st.el) st.el.style.display = "none"; continue; }
      if (st.tw) { twinkler(st, xi, yi); used++; continue; }
      g.fillStyle = "rgba(" + st.col + "," + st.a.toFixed(3) + ")";
      g.fillRect(xi, yi, st.s, st.s);
    }
    bakedAt = ms;
    layer.spin.style.transform = "rotate(0deg)";   // the ticker turns it on from here
  }
  function twinkler(st, x, y) {
    if (!st.el) {
      st.el = el("i", "sky-twi", layer.tw);
      st.el.style.width = st.el.style.height = (st.s + (st.b > 0.6 ? 1 : 0)) + "px";
      st.el.style.background = "rgb(" + st.col + ")";
      // brighter stars twinkle more; each on its own slow sine, 3 to 8 s
      var amp = 0.25 + 0.55 * st.b;
      st.hi = Math.min(1, st.a + 0.1); st.lo = Math.max(0.05, st.hi - amp);
      st.period = rr(3000, 8000); st.phase = rnd();
      st.el.style.opacity = st.hi;
      twEls.push(st);
    }
    st.el.style.display = "";
    st.el.style.transform = "translate(" + x + "px," + y + "px)";
  }
  function twinkleStep(now) {
    for (var i = 0; i < twEls.length; i++) {
      var st = twEls[i];
      if (st.el.style.display === "none") continue;
      var s = 0.5 + 0.5 * Math.sin(2 * Math.PI * (now / st.period + st.phase));
      st.el.style.opacity = (st.lo + (st.hi - st.lo) * s).toFixed(3);
    }
  }

  // ---------------------------------------------------------------- shooting stars, satellites, planes
  var flying = { star: false, craft: false };
  var timers = {};
  var SKY_FLOOR = 620;            // the horizon, roughly
  function later(name, ms, fn) { clearTimeout(timers[name]); timers[name] = setTimeout(fn, ms); }
  function nightOn() { return !covered && (forceNight || altitude() < -6); }

  function shootingStar() {
    if (flying.star || !nightOn()) return;
    for (var tries = 0; tries < 14; tries++) {
      var x = rr(80, W - 80), y = rr(20, SKY_FLOOR * 0.6);
      if (!isSky(Math.round(x), Math.round(y), 8)) continue;
      var radial = Math.atan2(y - PIVOT.y, x - PIVOT.x);         // away from the pole
      var dir = radial + rr(-20, 20) * RAD;
      if (Math.sin(dir) < 0.15) continue;                         // down and away
      var len = rr(80, 200), dist = len * rr(1.5, 2.4);
      var ex = x + Math.cos(dir) * dist, ey = y + Math.sin(dir) * dist;
      var mx = (x + ex) / 2, my = (y + ey) / 2;
      if (!isSky(Math.round(ex), Math.round(ey), 12) || !isSky(Math.round(mx), Math.round(my), 12)) continue;
      fireStar(x, y, dir, len, dist, rnd() < 0.2);
      return;
    }
  }
  function fireStar(x, y, dir, len, dist, bright) {
    flying.star = true;
    var o = el("div", "sky-ss" + (bright ? " bright" : ""), layer.fx);
    o.style.transform = "translate(" + x.toFixed(1) + "px," + y.toFixed(1) + "px) rotate(" + (dir / RAD).toFixed(2) + "deg)";
    var t = el("i", "sky-ss-trail", o);
    t.style.width = len.toFixed(0) + "px";
    // Moved frame by frame (requestAnimationFrame), not with element.animate(): a Web Animation
    // whose finish never came left a star "in flight" for good, and no more were sent.
    var dur = rr(400, 800), linger = bright ? 1500 : 0, t0 = 0;
    var done = function () { if (o.parentNode) o.remove(); flying.star = false; };
    var safety = setTimeout(done, dur + linger + 1000);   // whatever happens, it ends
    var raf = window.requestAnimationFrame || function (fn) { return setTimeout(function () { fn(Date.now()); }, 33); };
    (function frame(now) {
      if (!o.parentNode) return;
      if (!t0) t0 = now;
      var e = now - t0, u = Math.min(1, e / dur), x, sc, op;
      if (u < 0.12) { x = u / 0.12 * dist * 0.12; sc = 0.25 + (0.7 - 0.25) * u / 0.12; op = u / 0.12; }
      else if (u < 0.8) { x = dist * 0.12 + (u - 0.12) / 0.68 * dist * 0.68; sc = 0.7 + 0.3 * (u - 0.12) / 0.68; op = 1; }
      else { x = dist * 0.8 + (u - 0.8) / 0.2 * dist * 0.2; sc = 1; op = 1 - (u - 0.8) / 0.2 * (bright ? 0.1 : 1); }
      // a bright one leaves its trail hanging for a moment
      if (e > dur) op = bright ? 0.9 * Math.max(0, 1 - (e - dur) / linger) : 0;
      t.style.transform = "translateX(" + (x - len).toFixed(1) + "px) scaleX(" + sc.toFixed(3) + ")";
      t.style.opacity = op.toFixed(3);
      if (e >= dur + linger) { clearTimeout(safety); done(); return; }
      raf(frame);
    })(0);
  }
  function scheduleStars() {
    later("star", forceNight && arg("night") ? 10000 : rr(120, 360) * 1000, function () { shootingStar(); scheduleStars(); });
  }

  function satellite() {
    if (!nightOn()) return;
    if (flying.craft) { later("sat-retry", 20000, satellite); return; }
    flying.craft = true;
    var ltr = rnd() < 0.5, y0 = rr(50, 400), y1 = clamp(y0 + rr(-90, 90), 40, 430);
    var x0 = ltr ? -10 : W + 10, x1 = ltr ? W + 10 : -10;
    var d = el("i", "sky-sat", layer.fx);
    fly(d, rr(80, 120) * 1000, function (u) {
      return { x: lerp(x0, x1, u), y: lerp(y0, y1, u), o: 0.85 * clamp(Math.min(u, 1 - u) / 0.05, 0, 1) };
    }, null);
  }
  // a dot crossing the sky, moved 15 times a second while it flies (smooth enough for a point of
  // light, and the screen rests again once it has gone)
  function fly(e, dur, at, each) {
    var t0 = Date.now();
    var timer = setInterval(function () {
      var u = (Date.now() - t0) / dur;
      if (u >= 1 || !e.parentNode) { clearInterval(timer); e.remove(); flying.craft = false; return; }
      var p = at(u);
      e.style.transform = "translate(" + p.x.toFixed(1) + "px," + p.y.toFixed(1) + "px)";
      e.style.opacity = p.o.toFixed(3);
      if (each) each(u);
    }, 66);
  }
  function airplane() {
    if (!nightOn()) return;
    if (flying.craft) { later("plane", 15000, airplane); return; }
    flying.craft = true;
    var ltr = rnd() < 0.5, y0 = rr(70, 300), y1 = clamp(y0 + rr(-60, 60), 60, 330), bend = rr(-60, 60);
    var x0 = ltr ? -20 : W + 20, x1 = ltr ? W + 20 : -20;
    var p = el("div", "sky-plane", layer.fx);
    var red = el("i", "sky-plane-red", p);
    var t0 = Date.now();
    fly(p, rr(42, 48) * 1000, function (u) {
      // a gentle curve, fading in and out at the edges
      return { x: lerp(x0, x1, u), y: lerp(y0, y1, u) + bend * 4 * u * (1 - u), o: 0.95 * clamp(Math.min(u, 1 - u) / 0.04, 0, 1) };
    }, function () {
      red.style.opacity = ((Date.now() - t0) % 1000) < 150 ? "1" : "0";   // the red light blinks once a second
    });
  }
  function schedulePlanes() {
    later("plane", rr(4, 9) * 60000, function () { airplane(); schedulePlanes(); });
  }
  // two to four satellites a night, at random times across what is left of it
  function planSatellites() {
    for (var k in timers) if (k.indexOf("sat") === 0) clearTimeout(timers[k]);
    var now = nowMs(), step = 600000, left = 0, past = 0;
    if (forceNight) left = 30 * 60000, past = 0;
    else {
      while (left < 18 * 3600000 && altAt(now + left) < -6) left += step;
      while (past < 18 * 3600000 && altAt(now - past) < -6) past += step;
    }
    var n = Math.round(Math.floor(rr(2, 5)) * (left / Math.max(left + past, 1)));
    if (forceNight) n = 3;
    for (var i = 0; i < n; i++) later("sat" + i, rr(0, left), satellite);
  }

  // the city: a little shimmer, and a few windows that go on and off
  var WINDOWS = [[1530, 762], [1532, 786], [1619, 746], [1621, 771], [1573, 762], [1651, 771], [1681, 763],
    [1597, 776], [1729, 826], [1594, 792]];
  var winEls = [];
  function city() {
    WINDOWS.forEach(function (p) {
      var w = el("i", "sky-window", layer.win);
      w.style.transform = "translate(" + p[0] + "px," + p[1] + "px)";
      w.style.opacity = rnd() < 0.6 ? "1" : "0";
      winEls.push(w);
    });
    (function toggle() {
      var w = winEls[Math.floor(rnd() * winEls.length)];
      w.style.opacity = w.style.opacity === "1" ? "0" : "1";
      setTimeout(toggle, rr(20, 60) * 1000);
    })();
  }

  // ---------------------------------------------------------------- the sun
  // Low in the sky only (above ~22° it is out of the top of the picture). In the morning it rises at
  // SUN.rise and climbs up and to the left; in the evening it comes down to the right of SUN.set and
  // sinks behind the far-left mountains: the sky mask hides the part below the skyline. Warmer and
  // redder near the horizon. KX/KY: screen px per degree of altitude.
  var KX = 14, KY = 24, sunDrawn = "", SUNIMG = {};
  function loadSun(images) {
    ["low", "day"].forEach(function (k) {
      if (!images || !images[k]) return;
      var i = new Image();
      i.onload = function () { SUNIMG[k] = i; sunDrawn = ""; drawSun(); };
      i.src = BASE + images[k];
    });
  }
  function skylineY(x) {
    if (!maskLum) return 560;
    var c = clamp(Math.round(x / 4), 0, MW4 - 1);
    for (var y = 0; y < MH4; y++) if (maskLum[y * MW4 + c] <= 127) return y * 4;
    return H;
  }
  function drawSun() {
    if (!SUN || !layer.sun) return;
    var alt = altitude(), am = morning();
    var vis = alt > -1.5 && alt < 24 && !forceNight && !SUN.hidden;
    var x0 = am ? SUN.rise : SUN.set, x = x0 + (am ? -1 : 1) * Math.max(0, alt) * KX;
    var y = skylineY(x0) - alt * KY;
    var key = vis ? Math.round(x) + "," + Math.round(y) : "off";
    if (arg("debug")) console.log("[sun] alt " + alt.toFixed(1) + " am " + am + " vis " + vis + " at " + key);
    if (key === sunDrawn) return;
    sunDrawn = key;
    var g = layer.sun.getContext("2d");
    g.clearRect(0, 0, CW, CH);
    if (!vis) { layer.sun.style.display = "none"; return; }
    layer.sun.style.display = "";
    var low = clamp(1 - alt / 10, 0, 1);           // 1 at the horizon, 0 from 10° up
    var hx = x / 2, hy = y / 2;                      // half-size canvas
    if (SUNIMG.low && SUNIMG.day) {
      // photographed suns on black (the canvas is screen-blended, so black adds nothing): the
      // low, orange one near the horizon, the white day one higher up, cross-faded in between.
      // Sizes put each disc at about 50 px across on a 1080p screen.
      var lowSz = 125, daySz = 55;                   // half-size px for the whole image
      g.globalAlpha = 1 - clamp((alt - 3) / 9, 0, 1);
      if (g.globalAlpha > 0) g.drawImage(SUNIMG.low, hx - lowSz, hy - lowSz, lowSz * 2, lowSz * 2);
      g.globalAlpha = clamp((alt - 3) / 9, 0, 1);
      if (g.globalAlpha > 0) g.drawImage(SUNIMG.day, hx - daySz, hy - daySz, daySz * 2, daySz * 2);
      g.globalAlpha = 1;
      if (maskHalf) { g.globalCompositeOperation = "destination-in"; g.drawImage(maskHalf, 0, 0); g.globalCompositeOperation = "source-over"; }
      return;
    }
    // the glow: wide and warm
    var glow = g.createRadialGradient(hx, hy, 0, hx, hy, 120 + 60 * low);
    glow.addColorStop(0, "rgba(255," + Math.round(236 - 80 * low) + "," + Math.round(200 - 140 * low) + "," + (0.55 + 0.15 * low).toFixed(2) + ")");
    glow.addColorStop(0.25, "rgba(255," + Math.round(214 - 90 * low) + "," + Math.round(160 - 120 * low) + ",0.22)");
    glow.addColorStop(1, "rgba(255,160,80,0)");
    g.fillStyle = glow; g.fillRect(0, 0, CW, CH);
    // the disc
    var disc = g.createRadialGradient(hx, hy, 0, hx, hy, 12);
    disc.addColorStop(0, "rgba(255,252,238,1)");
    disc.addColorStop(0.75, "rgba(255," + Math.round(246 - 70 * low) + "," + Math.round(214 - 130 * low) + ",1)");
    disc.addColorStop(1, "rgba(255," + Math.round(220 - 80 * low) + "," + Math.round(160 - 110 * low) + ",0)");
    g.fillStyle = disc; g.beginPath(); g.arc(hx, hy, 12, 0, Math.PI * 2); g.fill();
    // behind the mountains
    if (maskHalf) { g.globalCompositeOperation = "destination-in"; g.drawImage(maskHalf, 0, 0); g.globalCompositeOperation = "source-over"; }
  }

  // ---------------------------------------------------------------- the ticker
  // Twice a second, in the same frame: the twinkling and the clouds. Every two seconds: the fades,
  // the Ken Burns, the turning sky, the Milky Way breathing and the city's shimmer. All of them move
  // so slowly that steps this size cannot be seen, and between steps the screen rests.
  var tickN = 0, lastSlow = 0, shimmer = 0.96;
  function tick() {
    var now = Date.now();
    tickN++;
    if (shown(layer.stars)) twinkleStep(now);
    cloudTick();
    // fast-forward (?speed): everything follows the faster clock, on every tick
    if (SPEED > 1) { update(false); if (window.tv && window.tv.tick) window.tv.tick(); }
    if (now - lastSlow < (SPEED > 1 ? 0 : 2000) && !scrubbing) return;
    var dt = lastSlow ? (now - lastSlow) / 1000 : 0;
    lastSlow = now;
    stepFades(dt * SPEED);
    drawSun();
    // Ken Burns: 1.00 to 1.04 and back, ten minutes each way
    var kbScale = STILL ? 1 : 1 + 0.02 * (1 - Math.cos(2 * Math.PI * (now % 1200000) / 1200000));
    kb.style.transform = "scale(" + kbScale.toFixed(5) + ")";
    if (shown(layer.stars) && bakedAt) {
      layer.spin.style.transform = "rotate(" + (skyAngle(nowMs()) - skyAngle(bakedAt)).toFixed(4) + "deg)";
    }
    if (shown(layer.mw)) layer.mwC.style.opacity = (0.925 + 0.075 * Math.cos(2 * Math.PI * (now % 180000) / 180000)).toFixed(3);
    if (shown(layer.city)) {
      shimmer = clamp(shimmer + rr(-0.012, 0.012), 0.92, 1);
      layer.cityImg.style.opacity = shimmer.toFixed(3);
    }
  }

  // ---------------------------------------------------------------- the weather
  // From the live report the home already fetches (Open-Meteo WMO code, every 30 minutes; app.js
  // calls Sky.setWeather). clear: the landscape as it is. partly: drifting cumulus over it, sun
  // still out. overcast and rain: the weather's own pictures (day, dusk, night by the sun's height),
  // no sun, no stars. showers: shower clouds drifting by, and now and then a rain spell (the rain
  // pictures fade in for about 12 minutes). ?wx=clear|partly|overcast|rain|showers for previews.
  var WX = arg("wx") || "clear", WXSCENES = null, wxStage = null, wxMode = "", wxCycleFade = 0, wxLastStage = null;
  function wxKind(code) {
    code = +code;
    if (!isFinite(code) || code < 0) return null;
    if (code <= 1) return "clear";
    if (code === 2) return "partly";
    if (code === 3 || code === 45 || code === 48) return "overcast";
    if (code >= 80 && code <= 82) return "showers";
    return "rain";   // drizzle, rain, freezing rain, snow, storms
  }
  function setWeather(code) {
    var k = arg("wx") || wxKind(code);
    if (!k || k === WX) return;
    WX = k;
    if (LISTS.am.length) update(false);
  }
  // A shower spell: in about 4 of every 10 half-hours, from minute 6 to 18. Fixed by the clock.
  function showerSpell() {
    var t = nowMs(), blk = Math.floor(t / 1800000), m = (t % 1800000) / 60000;
    var h = Math.sin(blk * 12.9898 + 78.233) * 43758.5453; h -= Math.floor(h);
    return h < 0.2 && m >= 6 && m < 18;
  }
  // the overcast or rain picture for the sun's height (1° of hysteresis between stages), or null
  function wxScene(alt) {
    // partly cloudy (and showers between spells): its own pictures when the landscape has them,
    // else the clear frames. No cut-out clouds: they always looked pasted on.
    // showers with their own pictures never borrow the rain picture (Max: no rain spells)
    var spell = WX === "showers" && !(WXSCENES && WXSCENES.showers) && showerSpell();
    var set = WX === "overcast" ? "overcast" : (WX === "rain" || spell) ? "rain"
      : WX === "showers" && WXSCENES && WXSCENES.showers ? "showers"
      : (WX === "partly" || WX === "showers") && WXSCENES && WXSCENES.partly ? "partly" : null;
    if (!set || !WXSCENES || !WXSCENES[set]) { wxStage = null; return null; }
    var st = alt > 4 ? "day" : alt > -6 ? "dusk" : "night";
    if (wxStage === "day" && alt > 3) st = "day";
    else if (wxStage === "dusk" && alt > -7 && alt < 5) st = "dusk";
    else if (wxStage === "night" && alt < -5) st = "night";
    wxStage = st;
    var pic = WXSCENES[set][st];
    // several versions of one picture (showers): the clouds move by slowly cross-fading from one
    // version to the next, a new one every cycle_minutes, fixed by the clock
    if (Array.isArray(pic)) {
      var cyc = (+WXSCENES[set].cycle_minutes || 8) * 60000;
      pic = pic.length ? pic[Math.floor(nowMs() / cyc) % pic.length] : null;
      wxCycleFade = +WXSCENES[set].cycle_fade_seconds || 180;
    } else wxCycleFade = 0;
    return pic || null;
  }

  // ---------------------------------------------------------------- the update
  var wasNight = null, lastFrames = "", scrubbing = false, covered = false;
  function update(instant) {
    var alt = altitude(), am = morning();
    var br = bracket(alt, am ? LISTS.am : LISTS.pm);
    if (!br) return;
    // dev: ?pair=a.jpg,b.jpg,0.5 shows b over a at that opacity (to check any moment of a handover)
    var pr = (arg("pair") || "").split(",");
    // (the sky's other layers still follow ?alt=, so night shows its stars)
    var scene = wxScene(alt);
    covered = !!scene;
    // weather coming or going: a 2-minute fade (a shower spell, a front moving in)
    var mode = scene ? WX + (WX === "showers" ? "-spell" : "") : "clear";
    var wxFade = wxMode && mode !== wxMode ? 120 : null;
    // next version of the same picture (showers' moving clouds): its own short cross-fade
    if (!wxFade && scene && wxCycleFade && wxStage === wxLastStage && scene !== shownFile) wxFade = wxCycleFade;
    wxMode = mode; wxLastStage = wxStage;
    if (pr.length === 3) showPair(pr[0], pr[1], parseFloat(pr[2]), true);
    else if (scene) showFrame(scene, instant, wxFade);
    else if (wxFade) showFrame(evening(pickFrame(br)), instant, wxFade);
    else if (BLEND === "continuous" || scrubbing) showPair(br.lo.file, br.hi.file, br.t, instant);
    else if (br.lo !== br.hi && SMOOTH[br.lo.file + "|" + br.hi.file]) {
      // a pair whose light is far apart (afternoon blue -> evening gold): a gradual blend over the
      // middle 80% of the sun's way between them, eased at both ends, instead of a 10-minute swap
      var u = clamp((br.t - 0.1) / 0.8, 0, 1), e = u * u * (3 - 2 * u);
      var lo = evening(br.lo.file), hi = evening(br.hi.file);
      showPair(lo, hi, e, instant || scrubbing);
      shownFile = e < 0.5 ? lo : hi; dissolving = null;
    }
    else showFrame(evening(pickFrame(br)), instant);
    // the next frame along the way the sun is going, decoded ahead
    var list = am ? LISTS.am : LISTS.pm, next = am ? list[br.i + 2] || list[br.i + 1] : list[br.i - 1];
    if (next) ensure(next.file);
    var cs = cloudState(br);
    if (WX !== "clear") cs.o = 0;     // the cirrus is clear-sky only; the weather has its own clouds
    setClouds(cs, instant);
    if (SUN) SUN.hidden = covered;

    var night = forceNight ? 1 : clamp(-alt / 6, 0, 1);         // fades in over the blue hour
    var milky = forceNight ? 1 : clamp((-alt - 12) / 6, 0, 1);  // only in the night frames
    if (covered) { night = 0; milky = 0; }                      // no stars under cloud
    // for the page: how far into the night it is (the clock's white rim fades out with it)
    document.documentElement.style.setProperty("--sky-night", night.toFixed(3));
    if (instant || scrubbing) drawSun();
    fade(layer.dim, 0.42 * night, instant);
    fade(layer.mw, milky, instant);
    fade(layer.stars, night, instant);
    fade(layer.fx, night, instant);
    fade(layer.city, CITY_ON ? 0.5 * night : 0, instant);
    fade(layer.win, CITY_ON ? night : 0, instant);

    var ms = nowMs();
    if (night > 0) {
      if (scrubbing || instant || Math.abs(ms - bakedAt) > 59000) bake(ms);
    }
    var isNight = nightOn();
    if (isNight !== wasNight) {
      wasNight = isNight;
      if (isNight) { planSatellites(); scheduleStars(); schedulePlanes(); }
    }
    lastFrames = br.lo.file + (br.lo === br.hi ? "" : " → " + br.hi.file + " " + Math.round(br.t * 100) + "%");
    dev(alt, am);
  }

  // ---------------------------------------------------------------- dev tools
  var devEl = null;
  function devOn() { return !arg("shot") && (!window.TVOS || !!arg("dev")); }
  function dev(alt, am) {
    if (!devEl) return;
    var d = new Date(nowMs());
    devEl.querySelector(".sky-dev-info").textContent =
      d.toLocaleTimeString("en-US", { timeZone: TZ, hour: "2-digit", minute: "2-digit" }) + "  sun " + alt.toFixed(1) + "° " +
      (am ? "AM" : "PM") + "  " + phaseOf(alt) + "  " + lastFrames + "   " + window.innerWidth + "px @" + (window.devicePixelRatio || 1) + "x";
    if (!scrubbing) {
      var m = Math.round(((nowMs() + tzOffsetMin(TZ, nowMs()) * 60000) % 86400000) / 60000);
      devEl.querySelector("input").value = m;
    }
  }
  function buildDev() {
    if (!devOn()) return;
    devEl = document.createElement("div");
    devEl.className = "sky-dev";
    devEl.innerHTML = '<span class="sky-dev-info"></span><input type="range" min="0" max="1439" step="1" aria-label="Time of day">' +
      '<button type="button">Sweep 24 h</button>';
    document.body.appendChild(devEl);
    var range = devEl.querySelector("input"), btn = devEl.querySelector("button"), sweep = null;
    var dayStart = function () { var n = Date.now(); return n - ((n + tzOffsetMin(TZ, n) * 60000) % 86400000); };
    range.addEventListener("input", function () {
      if (sweep) { clearInterval(sweep); sweep = null; btn.textContent = "Sweep 24 h"; }
      fixedAlt = null; forceNight = false;
      offset = dayStart() + range.value * 60000 - Date.now();
      scrubbing = true; root.classList.add("scrub"); update(true); scrubbing = false; root.classList.remove("scrub");
      if (window.tv && window.tv.tick) window.tv.tick();
    });
    btn.addEventListener("click", function () {
      if (sweep) { clearInterval(sweep); sweep = null; scrubMs = null; scrubbing = false; root.classList.remove("scrub"); btn.textContent = "Sweep 24 h"; update(true); return; }
      fixedAlt = null; forceNight = false;
      Object.keys(LISTS.am.concat(LISTS.pm).reduce(function (o, f) { o[f.file] = 1; return o; }, {})).forEach(ensure);
      var t0 = Date.now(), from = dayStart();
      scrubbing = true; root.classList.add("scrub"); btn.textContent = "Stop";
      sweep = setInterval(function () {
        scrubMs = from + ((Date.now() - t0) % 20000) / 20000 * 86400000;
        update(false);
        if (window.tv && window.tv.tick) window.tv.tick();
      }, 100);
    });
  }

  // ---------------------------------------------------------------- start
  var started = false;
  function start(opts) {
    if (started) return; started = true;
    opts = opts || {};
    var w = opts.weather || {};
    var lat = parseFloat(w.lat != null ? w.lat : w.latitude), lon = parseFloat(w.lon != null ? w.lon : w.longitude);
    if (isFinite(lat) && isFinite(lon)) { LAT = lat; LON = lon; }
    if (w.timezone && w.timezone !== "auto") TZ = w.timezone;
    if (opts.landscape) BASE = "landscape/" + String(opts.landscape).replace(/[^a-z0-9-]/g, "") + "/";
    var cl = opts.clouds || {};
    CLOUDS_ON = cl.enabled !== false;
    CLOUD_K = isFinite(parseFloat(cl.opacity)) ? clamp(parseFloat(cl.opacity), 0, 2) : 1;

    var t = arg("t");
    if (t && /^\d{1,2}:\d{2}$/.test(t)) offset = instantFor(+t.split(":")[0], +t.split(":")[1]) - Date.now();
    if (arg("alt") != null && isFinite(parseFloat(arg("alt")))) { fixedAlt = parseFloat(arg("alt")); fixedPm = arg("pm") === "1"; }
    if (arg("night") === "1") forceNight = true;
    // home.blend: "dissolve" (default) or "continuous"; ?blend= and ?dissolve=SECONDS for previews
    BLEND = (arg("blend") || opts.blend || "dissolve") === "continuous" ? "continuous" : "dissolve";
    var ds = parseFloat(arg("dissolve") || opts.dissolve_seconds);
    if (isFinite(ds) && ds > 0) { DISSOLVE = clamp(ds, 5, 1800); DISSOLVE_SET = true; }
    var sw = parseFloat(arg("swap") || opts.swap_seconds);
    if (isFinite(sw) && sw > 0) SWAP = clamp(sw, 0.5, DISSOLVE / 2);

    console.log("[home] innerWidth=" + window.innerWidth + " innerHeight=" + window.innerHeight +
      " devicePixelRatio=" + (window.devicePixelRatio || 1) + " screen=" + screen.width + "x" + screen.height);

    build();
    makeStars();
    Promise.all([
      fetch(BASE + "manifest.json").then(function (r) { return r.json(); }),
      loadMask(),
      loadPlates()
    ]).then(function (res) {
      buildLists(res[0]);
      ensure("frame-01.jpg").then(prepare);
      if (CITY_ON) city();
      setInterval(tick, 500);
      buildDev();
      update(true);
      // fade the whole landscape in once the first frames are decoded
      setTimeout(function () { root.classList.add("on"); }, 60);
      setInterval(function () { if (!scrubMs) update(false); }, 30000);
      // dev: ?alt=10&altto=25&altafter=3000 moves the sun after a moment, to watch a handover
      var to = parseFloat(arg("altto"));
      if (fixedAlt != null && isFinite(to)) setTimeout(function () { fixedAlt = to; update(false); }, parseFloat(arg("altafter")) || 3000);
    }).catch(function (e) { console.log("[home] landscape failed: " + e); });
  }

  window.Sky = {
    start: start,
    now: function () { return new Date(nowMs()); },
    // the live weather report's WMO code (app.js, every 30 minutes)
    setWeather: setWeather,
    weather: function () { return WX; },
    // fn(dataUrl): the frosted copy for the glass clock, whenever the light changes
    onGlass: function (fn) { glassCb = fn; },
    altitude: altitude,
    phase: function () { return phaseOf(altitude()); }
  };
})();
