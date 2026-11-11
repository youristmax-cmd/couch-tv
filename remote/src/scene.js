// The TV home's landscape picture for right now, so the phone wears the same sky as the TV.
// It picks the frame the same way tv-home/web/sky.js does: the sun's height at the home (from the
// home's place in /tv/config.js), morning or evening frames, the brighter-city evening version of
// the night frame, and the weather's own pictures (overcast, rain, partly cloudy, showers) from
// the same forecast code the TV uses. The pictures are the TV home's own files under /tv/, so
// nothing new is stored on the phone. No place, no landscape (a bridge without the TV home, or an
// old app): null, and the remote keeps its plain ground.
import { useEffect, useState } from 'react';

const RAD = Math.PI / 180;
const SWITCH = 0.08;

// SunCalc (MIT), altitude only, as in sky.js
function sunAltitude(ms, lat, lon) {
  const d = ms / 86400000 - 0.5 + 2440588 - 2451545;
  const M = RAD * (357.5291 + 0.98560028 * d);
  const C = RAD * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M));
  const L = M + C + RAD * 102.9372 + Math.PI;
  const e = RAD * 23.4397;
  const dec = Math.asin(Math.sin(e) * Math.sin(L));
  const ra = Math.atan2(Math.sin(L) * Math.cos(e), Math.cos(L));
  const H = RAD * (280.16 + 360.9856235 * d) - RAD * -lon - ra;
  const phi = RAD * lat;
  return Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(H)) / RAD;
}

// The hour (with minutes as a fraction) at the home, in its own time zone
function homeHour(tz, ms) {
  try {
    const p = {};
    new Intl.DateTimeFormat('en-US', { timeZone: tz || undefined, hourCycle: 'h23', hour: '2-digit', minute: '2-digit' })
      .formatToParts(new Date(ms)).forEach((x) => { p[x.type] = x.value; });
    return (+p.hour % 24) + +p.minute / 60;
  } catch {
    const d = new Date(ms); return d.getHours() + d.getMinutes() / 60;
  }
}

// Open-Meteo's weather code, sorted the way the TV home sorts it
function wxKind(code) {
  if (code == null || !Number.isFinite(+code) || +code < 0) return 'clear';
  const c = +code;
  if (c <= 1) return 'clear';
  if (c === 2) return 'partly';
  if (c === 3 || c === 45 || c === 48) return 'overcast';
  if (c >= 80 && c <= 82) return 'showers';
  return 'rain';
}

export function pickScene(man, { lat, lon, tz, code, now }) {
  const alt = sunAltitude(now, lat, lon);
  const morning = sunAltitude(now + 600000, lat, lon) >= alt;

  // the weather's own pictures, by the sun's height (day, dusk, night)
  const W = man.weather || {};
  const k = wxKind(code);
  const set = k === 'overcast' ? 'overcast' : k === 'rain' ? 'rain'
    : k === 'showers' && W.showers ? 'showers'
      : (k === 'partly' || k === 'showers') && W.partly ? 'partly' : null;
  if (set && W[set]) {
    // day above 4°, dusk above -6°, with the TV's 1° of hold: rising it moves on 1° later (dusk at
    // -5°, day at 5°), setting it holds the higher stage down to 3° and -7°
    const stage = morning ? (alt >= 5 ? 'day' : alt >= -5 ? 'dusk' : 'night') : (alt > 3 ? 'day' : alt > -7 ? 'dusk' : 'night');
    let pic = W[set][stage];
    if (Array.isArray(pic)) pic = pic.length ? pic[Math.floor(now / ((+W[set].cycle_minutes || 8) * 60000)) % pic.length] : null;
    if (pic) return pic;
  }

  // the clear frames: the morning or evening list, the frame nearest the sun's height
  const fr = (man.frames || []).map((f) => ({ file: f.file, alt: f.altitude, period: f.period || 'both' }));
  const am = fr.filter((f) => f.period !== 'pm');
  const pm = fr.filter((f) => f.period !== 'am');
  am.forEach((f) => { if (f.period === 'am' && !pm.some((p) => p.alt === f.alt)) pm.push(f); });
  const list = (morning ? am : pm).sort((a, b) => a.alt - b.alt);
  if (!list.length) return null;
  // The TV keeps the frame it shows until the sun is 8% past the middle (sky.js SWITCH), so it
  // changes a little after halfway: rising (morning) to the higher frame once past 58%, setting
  // (evening) to the lower one once below 42%. The phone switches at the same minute.
  let file = list[list.length - 1].file;
  if (alt <= list[0].alt) file = list[0].file;
  else {
    for (let i = 0; i < list.length - 1; i += 1) {
      if (alt >= list[i].alt && alt < list[i + 1].alt) {
        const t = (alt - list[i].alt) / (list[i + 1].alt - list[i].alt);
        file = (morning ? t > 0.5 + SWITCH : t >= 0.5 - SWITCH) ? list[i + 1].file : list[i].file;
        break;
      }
    }
  }
  // the evening version of the night frame (city lights still bright) from noon until "until"
  const eve = man.city_evening;
  if (eve?.file && file === eve.replaces) {
    const h = homeHour(tz, now);
    if (h >= 12 && h < (+eve.until || 22)) file = eve.file;
  }
  return file;
}

// window.COUCH_CONFIG = {...}; as the TV app serves it
async function loadConfig() {
  const r = await fetch('/tv/config.js', { cache: 'no-store' });
  if (!r.ok) throw new Error('no config');
  const s = await r.text();
  return JSON.parse(s.slice(s.indexOf('=') + 1).trim().replace(/;\s*$/, ''));
}

// The picture's address, or null. Re-picked every minute and when the phone comes back.
export function useScene(code) {
  const [base, setBase] = useState(null);
  const [, setTick] = useState(0);
  useEffect(() => {
    let off = false;
    (async () => {
      try {
        const cfg = await loadConfig();
        const w = cfg?.weather || {};
        const lat = parseFloat(w.lat ?? w.latitude); const lon = parseFloat(w.lon ?? w.longitude);
        if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
        const name = String(cfg?.home?.landscape || 'la-hills-v2').replace(/[^a-z0-9-]/g, '');
        const dir = `/tv/landscape/${name}/`;
        const r = await fetch(`${dir}manifest.json`, { cache: 'no-store' });
        if (!r.ok) return;
        const man = await r.json();
        if (!off) setBase({ man, lat, lon, tz: w.timezone && w.timezone !== 'auto' ? w.timezone : null, dir });
      } catch { /* no TV home here: the plain ground */ }
    })();
    return () => { off = true; };
  }, []);
  useEffect(() => {
    const bump = () => setTick((n) => n + 1);
    const timer = setInterval(bump, 60000);
    window.addEventListener('tv:resume', bump);
    document.addEventListener('visibilitychange', bump);
    return () => { clearInterval(timer); window.removeEventListener('tv:resume', bump); document.removeEventListener('visibilitychange', bump); };
  }, []);
  if (!base) return null;
  const file = pickScene(base.man, { ...base, code, now: Date.now() });
  return file ? base.dir + file : null;
}
