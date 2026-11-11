import { useEffect, useRef, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { logoFor } from './appLogos.js';
import { Sheet, personColour } from './Sheets.jsx';
import { t, locale } from './i18n.js';

const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };

// Who is watching, as a list in a sheet. Opened from the avatar in the top bar and from the
// "Who's watching" chip in Ask. Both counts as one more option.
const whoOptions = (people) => [...(people || []).map((p) => ({ id: p.id, name: p.name, colour: personColour(p) })), { id: 'both', name: t('both') }];

export function WhoSheet({ open, onClose, people, who, setWho }) {
  const options = whoOptions(people);
  const pick = (id) => { tick(); setWho(id); onClose(); };
  return (
    <Sheet open={open} onClose={onClose} title={t('whos_watching')} className="who-sheet">
      <div role="radiogroup" aria-label={t('whos_watching')}>
        {options.map((o) => (
          <button key={o.id} role="radio" aria-checked={who === o.id} className={`who-row ${who === o.id ? 'on' : ''}`} onClick={() => pick(o.id)}>
            <span className="person-dot" style={{ background: o.colour || 'var(--card-2)' }}>{o.id === 'both' ? <Ic.People size={18} /> : o.name.slice(0, 1).toUpperCase()}</span>
            <span className="who-name">{o.name}</span>
            {who === o.id && <span className="who-tick" aria-hidden="true">&#10003;</span>}
          </button>
        ))}
      </div>
    </Sheet>
  );
}

export const whoName = (people, who) => whoOptions(people).find((o) => o.id === who)?.name;

// The top line: the TV's name with a status dot and "On · Netflix" under it, then who is
// watching, the TV menu and the power button.
export function TopBar({ people, tvName, who, onWho, tv, appInfo, reach, sleepLeft, onPower, onMore }) {
  const on = reach !== false && tv?.tv_connected && tv.power === 'on' && !tv.waking;
  const appName = tv?.app?.id === 'home' ? t('home') : (appInfo?.name || tv?.app?.name);
  const status = reach === false ? t('not_connected')
    : !tv ? t('connecting')
      : !tv.tv_connected ? t('tv_not_connected')
        : tv.waking ? t('waking')
          : tv.power === 'off' ? t('off')
            : appName ? `${t('on')} · ${appName}` : t('on');
  const dot = reach === false ? 'err' : on ? 'on' : tv?.waking ? 'waking' : 'idle';
  const showWho = (people || []).length > 0;
  const cur = whoOptions(people).find((o) => o.id === who);
  return (
    <header className="topbar">
      <div className="tvname">
        <span className={`status-dot ${dot}`} aria-hidden="true" />
        <span className="tvname-text">
          <b>{tvName || t('tv')}</b>
          <span>{status}{sleepLeft && <span className="sleep-left"> · <Ic.Moon size={12} /> {sleepLeft}</span>}</span>
        </span>
      </div>
      {showWho && cur && (
        <button className="round card who-btn" aria-haspopup="dialog" aria-label={t('whos_watching_change', { name: cur.name })}
          onClick={() => { tick(); onWho(); }}>
          <span className="person-dot who-dot" style={{ background: cur.colour || 'var(--card-2)' }}>{cur.id === 'both' ? <Ic.People size={16} /> : cur.name.slice(0, 1).toUpperCase()}</span>
        </button>
      )}
      <button className="round card" aria-label={t('more_controls')} onClick={() => { tick(); onMore(); }}><Ic.More size={20} /></button>
      <button className={`round card power ${tv?.power === 'on' ? 'is-on' : ''}`} aria-label={tv?.power === 'on' ? t('turn_off') : t('turn_on')}
        onClick={() => { tick(); onPower(); }}><Ic.Power size={20} /></button>
    </header>
  );
}

// The big clock, and the home's weather beside it (the same reading as the TV home). The clock
// follows the TV home's 12 or 24 hour setting; the weather hides when the TV home has it off or
// the bridge has no reading.
const WMO_KEY = { 0: 'wx_clear', 1: 'wx_mostly_clear', 2: 'wx_partly', 3: 'wx_cloudy', 45: 'wx_fog', 48: 'wx_fog',
  51: 'wx_drizzle', 53: 'wx_drizzle', 55: 'wx_drizzle', 56: 'wx_drizzle', 57: 'wx_drizzle', 61: 'wx_light_rain',
  63: 'wx_rain', 65: 'wx_heavy_rain', 66: 'wx_rain', 67: 'wx_rain', 80: 'wx_showers', 81: 'wx_showers',
  82: 'wx_heavy_showers', 95: 'wx_storms', 96: 'wx_storms', 99: 'wx_storms' };

function useMinute() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    let timer;
    const next = () => { setNow(new Date()); timer = setTimeout(next, 60000 - (Date.now() % 60000) + 50); };
    timer = setTimeout(next, 60000 - (Date.now() % 60000) + 50);
    const back = () => setNow(new Date());
    window.addEventListener('tv:resume', back);
    document.addEventListener('visibilitychange', back);
    return () => { clearTimeout(timer); window.removeEventListener('tv:resume', back); document.removeEventListener('visibilitychange', back); };
  }, []);
  return now;
}

export function ClockWeather({ settings, weather }) {
  const now = useMinute();
  const hour12 = settings?.clock === '12h' ? true : settings?.clock === '24h' ? false : undefined;
  let parts = [];
  try { parts = new Intl.DateTimeFormat(locale(), { hour: 'numeric', minute: '2-digit', hour12 }).formatToParts(now); } catch { /* old browser */ }
  const time = parts.filter((p) => p.type !== 'dayPeriod').map((p) => p.value).join('').trim()
    || now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const period = parts.find((p) => p.type === 'dayPeriod')?.value;
  const w = settings?.wx_show !== false && weather && weather.temp != null ? weather : null;
  const temp = w ? `${settings?.wx_unit === 'f' ? Math.round((w.temp * 9) / 5 + 32) : w.temp}°` : null;
  const text = w ? (WMO_KEY[w.code] ? t(WMO_KEY[w.code]) : w.text) : null;
  return (
    <section className="clock-row">
      <p className="clock"><time dateTime={now.toISOString()}>{time}</time>{period && <small>{period}</small>}</p>
      {w && (
        <p className="weather" aria-label={[temp, text, w.place].filter(Boolean).join(', ')}>
          <span className="weather-temp" aria-hidden="true">{temp}</span>
          {(text || w.place) && <span className="weather-sub" aria-hidden="true">{[text, w.place].filter(Boolean).join(' · ')}</span>}
        </p>
      )}
    </section>
  );
}

// "7 min in", never a bare 7:38 that reads as the time of day.
const minsIn = (ms) => (ms < 60000 ? t('just_started') : t('min_in', { n: Math.floor(ms / 60000) }));
const shortEp = (s) => [s.season && `S${s.season}`, s.episode && `E${s.episode}`].filter(Boolean).join(' ');

// Where the show is up to. The bridge reads the app's own position every few seconds; between reads
// the clock runs on here while it plays. The TV gives no length and cannot jump to a time, so the
// bar (read only) appears only when the bridge knows the episode and its length from JustWatch.
function usePosition(p) {
  const [now, setNow] = useState(Date.now());
  const playing = p?.state === 'playing';
  useEffect(() => {
    if (!playing) return undefined;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [playing]);
  if (!p || p.position_ms == null) return null;
  const since = playing && p.updated ? Math.max(0, now - new Date(p.updated).getTime()) : 0;
  return Math.min(p.duration_ms || Infinity, (p.position_ms || 0) + since);
}

// One card under the clock. While a show is on the TV it is "Now playing" (where it is up to, and
// the playback buttons behind the chevron); otherwise it is the newest "Keep watching" show for
// whoever is watching, with a round button that resumes it. Nothing to show, no card.
export function NowCard({ tv, appInfo, item, serviceName, onKey, onResume }) {
  const p = tv?.playing;
  const off = !tv || tv.power === 'off' || !tv.tv_connected;
  const [open, setOpen] = useState(false);
  const pos = usePosition(!off ? p : null);
  const key = (k) => () => { tick(); onKey(k); };
  const logo = logoFor(appInfo);

  if (p && !off && (p.title || appInfo)) {
    const art = p.poster ? <img src={p.poster} alt="" /> : logo
      ? <img className="art-logo" src={logo} alt="" />
      : <span className="art-app" style={{ background: appInfo?.colour }}>{appInfo?.name}</span>;
    const word = { paused: t('st_paused'), buffering: t('st_buffering'), stopped: t('st_stopped') }[p.state];
    const time = pos == null ? null : p.duration_ms
      ? t(p.duration_source === 'justwatch' ? 'about_left' : 'min_left', { n: Math.max(1, Math.round((p.duration_ms - pos) / 60000)) })
      : minsIn(pos);
    const sub = [shortEp(p) || p.episode_title || (p.title ? appInfo?.name : null), time, word].filter(Boolean).join(' · ');
    return (
      <section className={`now card ${open ? 'open' : ''}`} aria-label={t('now_on_tv')}>
        <div className="now-head">
          <span className="now-art">{art}</span>
          <span className="now-text">
            <span className="now-label">{t('now_playing')}</span>
            <span className="now-title">{p.title || appInfo?.name}</span>
            {sub && <span className="now-sub">{sub}</span>}
          </span>
          <button className="round card-2 now-chev-btn" aria-expanded={open} aria-label={open ? t('fewer_controls') : t('all_controls')}
            onClick={() => { tick(); setOpen(!open); }}><Ic.Chevron className="now-chev" size={20} /></button>
        </div>
        {pos != null && p.duration_ms > 0 && (
          <div className="scrub-track" role="progressbar" aria-label={t('progress')}
            aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round((pos / p.duration_ms) * 100)}>
            <div className="scrub-fill" style={{ width: `${(pos / p.duration_ms) * 100}%` }} />
          </div>
        )}
        {open && (
          <div className="transport">
            <button className="round card-2" aria-label={t('prev_ep')} onClick={key('previous')}><Ic.Prev /></button>
            <button className="round card-2" aria-label={t('back_10')} onClick={key('rewind')}><Ic.Replay /></button>
            <button className="round card-2" aria-label={t('fwd_10')} onClick={key('fast_forward')}><Ic.Forward /></button>
            <button className="round card-2" aria-label={t('next_ep')} onClick={key('next')}><Ic.Next /></button>
            <button className="round card-2" aria-label={t('subtitles')} onClick={key('captions')}><Ic.Captions /></button>
          </div>
        )}
      </section>
    );
  }

  if (!item) return null;
  const app = item.app?.name || (item.service ? serviceName(item.service) : null);
  const sub = [shortEp(item) || item.episode_title, app].filter(Boolean).join(' · ');
  return (
    <section className="now card" aria-label={t('keep_watching')}>
      <div className="now-head">
        <span className="now-art">{item.poster ? <img src={item.poster} alt="" /> : <span className="art-app">{item.title}</span>}</span>
        <span className="now-text">
          <span className="now-label">{t('keep_watching')}</span>
          <span className="now-title">{item.title}</span>
          {sub && <span className="now-sub">{sub}</span>}
        </span>
        <button className="round accent resume" aria-label={t('resume_title', { title: item.title })}
          onClick={() => { tick(); onResume(item); }}><Ic.Play size={20} /></button>
      </div>
    </section>
  );
}

// The first four apps, in the TV's own order, then All apps (the full list on Watch).
export function AppRow({ apps, onLaunch, onAll }) {
  if (!apps?.length) return null;
  return (
    <nav className="app-row" aria-label={t('apps')}>
      {apps.slice(0, 4).map((a) => (
        <button key={a.id} className="app-key card" aria-label={t('open_app', { app: a.name })} onClick={() => { tick(); onLaunch(a); }}>
          {logoFor(a) ? <img src={logoFor(a)} alt="" /> : <span className="app-word" style={a.colour ? { '--brand': a.colour } : undefined}>{a.name}</span>}
        </button>
      ))}
      <button className="app-key card all-apps" aria-label={t('all_apps')} onClick={() => { tick(); onAll(); }}><Ic.Apps size={22} /></button>
    </nav>
  );
}

// The round D-pad. It is also a swipe pad: one step per STEP points of travel, so a long drag
// scrolls a row; a quick flick sends one step. Only a tap inside the amber middle is OK (a long
// press there is a held OK); a tap anywhere else moves towards that side, and holding it keeps
// moving. So a stray tap near an arrow never selects something. One finger at a time: a second
// finger is ignored, so two thumbs never send a stray key. The arrows and OK are real buttons for
// the keyboard, VoiceOver and TalkBack; a click that follows a touch here is not sent twice.
const STEP = 56; const FLICK = 22; const HOLD_MS = 550; const OK_SHARE = 0.21;
const REPEAT_AFTER = 420; const REPEAT_EVERY = 160;

function zoneAt(r, x, y) {
  const dx = x - (r.left + r.width / 2); const dy = y - (r.top + r.height / 2);
  if (Math.hypot(dx, dy) <= r.width * OK_SHARE) return 'ok';
  return Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
}

const stopTimers = (s) => { if (s) { clearTimeout(s.timer); clearInterval(s.timer); } };

// `offState` is null when the TV can be driven, else 'off' | 'waking' | 'unreachable' | 'unpaired'.
export function Pad({ onKey, offState, wakeError, onWake, onRetry, tvName, onLivingRoom }) {
  const ref = useRef(null);
  const g = useRef(null);
  const lastPointer = useRef(0);
  const [lit, setLit] = useState(null);
  const [spot, setSpot] = useState(null);
  const flashT = useRef(null);
  useEffect(() => () => { stopTimers(g.current); clearTimeout(flashT.current); }, []);

  const flash = (dir) => { setLit(dir); clearTimeout(flashT.current); flashT.current = setTimeout(() => setLit(null), 140); };
  const send = (dir) => { onKey(dir); flash(dir); };

  if (offState) {
    let msg;
    if (offState === 'unpaired') {
      msg = (<>
        <p><b>{t('not_set_up_tv', { name: (tvName || t('this')).toLowerCase() })}</b> {t('not_set_up_tv_more')}</p>
        <button className="pill card-2" onClick={onLivingRoom}>{t('use_living_room')}</button>
      </>);
    } else if (offState === 'unreachable') {
      msg = (<>
        <p><b>{t('cant_reach')}</b> {t('home_wifi')}</p>
        <button className="pill card-2" onClick={() => { tick(); onRetry(); }}>{t('try_again')}</button>
      </>);
    } else {
      msg = (<>
        <button className="wake accent" disabled={offState === 'waking'} onClick={() => { tick(); onWake(); }}>
          {offState === 'waking' ? <><span className="spinner dark" /> {t('waking_tv')}</> : <><Ic.Power size={20} /> {t('turn_on_tv')}</>}
        </button>
        {wakeError && offState !== 'waking' && <p className="wake-err">{wakeError}</p>}
      </>);
    }
    return <div className="pad-zone"><div className="pad-msg card" aria-live="polite">{msg}</div></div>;
  }

  const down = (e) => {
    if (g.current || !e.isPrimary) return;
    ref.current.setPointerCapture(e.pointerId);
    const r = ref.current.getBoundingClientRect();
    const zone = zoneAt(r, e.clientX, e.clientY);
    const s = { id: e.pointerId, x: e.clientX, y: e.clientY, ax: e.clientX, ay: e.clientY, steps: 0, moved: false, held: false, zone };
    g.current = s;
    s.timer = zone === 'ok'
      ? setTimeout(() => { if (g.current === s && !s.moved) { s.held = true; onKey('ok', true); flash('ok'); } }, HOLD_MS)
      : setTimeout(() => {
        if (g.current !== s || s.moved) return;
        s.held = true; send(zone);
        s.timer = setInterval(() => { if (g.current === s && !s.moved) send(zone); else clearInterval(s.timer); }, REPEAT_EVERY);
      }, REPEAT_AFTER);
    setSpot({ x: e.clientX - r.left, y: e.clientY - r.top });
  };
  const move = (e) => {
    const s = g.current; if (!s || e.pointerId !== s.id) return;
    const r = ref.current.getBoundingClientRect();
    setSpot({ x: e.clientX - r.left, y: e.clientY - r.top });
    const dx = e.clientX - s.ax; const dy = e.clientY - s.ay;
    // Once the finger travels, it is a swipe: any hold or repeat stops, so directions never mix.
    if (!s.moved && Math.hypot(e.clientX - s.x, e.clientY - s.y) > 10) { s.moved = true; stopTimers(s); }
    if (!s.moved) return;
    if (Math.max(Math.abs(dx), Math.abs(dy)) >= STEP) {
      const dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
      send(dir); s.steps += 1;
      s.ax += DIRS[dir][0] * STEP; s.ay += DIRS[dir][1] * STEP;
      if (Math.abs(dx) > Math.abs(dy)) s.ay = e.clientY; else s.ax = e.clientX;
    }
  };
  const up = (e) => {
    const s = g.current; if (!s || e.pointerId !== s.id) return;
    g.current = null; setSpot(null);
    stopTimers(s);
    lastPointer.current = Date.now();
    const dx = e.clientX - s.x; const dy = e.clientY - s.y;
    if (s.held && !s.moved) { tick(); return; }
    if (!s.moved) { send(s.zone); tick(); return; }
    if (s.steps === 0 && Math.max(Math.abs(dx), Math.abs(dy)) >= FLICK) {
      send(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up'));
    }
    tick();
  };
  const cancel = (e) => { const s = g.current; if (!s || e.pointerId !== s.id) return; stopTimers(s); g.current = null; setSpot(null); lastPointer.current = Date.now(); };
  // Keyboard, VoiceOver and Switch Control press the buttons with a plain click.
  const click = (k) => () => { if (Date.now() - lastPointer.current > 600) { tick(); send(k); } };

  return (
    <div className="pad-zone">
      <div ref={ref} className="pad card" role="group" aria-label={t('pad_label')}
        onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={cancel}>
        {spot && <div className="spot" style={{ transform: `translate(${spot.x}px, ${spot.y}px)` }} />}
        {['up', 'left', 'right', 'down'].map((d) => (
          <button key={d} type="button" className={`pad-dir ${d} ${lit === d ? 'lit' : ''}`} aria-label={t(d)} onClick={click(d)}>
            <Ic.Chevron className="chev" size={22} strokeWidth="2.4" />
          </button>
        ))}
        <button type="button" className={`pad-ok accent ${lit === 'ok' ? 'lit' : ''}`} aria-label={t('select')} onClick={click('ok')}>OK</button>
      </div>
    </div>
  );
}

// A button that repeats while held (volume). A tap acts when the finger lifts, like any iOS button,
// and a hold starts repeating after a moment. If the finger slides more than 10 points first, it was
// a swipe between Remote and Watch, so nothing is sent and the page swipe takes over.
// Keyboard, VoiceOver and Switch Control send a plain click, which also works.
function useRepeat(fn) {
  const r = useRef({ timer: null, id: null });
  const lastPointer = useRef(0);
  const stop = () => { clearTimeout(r.current.timer); clearInterval(r.current.timer); r.current = { timer: null, id: null }; };
  useEffect(() => stop, []);
  return {
    onPointerDown: (e) => {
      stop();
      const s = { id: e.pointerId, x: e.clientX, y: e.clientY, fired: false, timer: null };
      r.current = s;
      s.timer = setTimeout(() => { s.fired = true; fn(); s.timer = setInterval(fn, 160); }, 380);
    },
    onPointerMove: (e) => {
      const s = r.current;
      if (s.id === e.pointerId && !s.fired && Math.hypot(e.clientX - s.x, e.clientY - s.y) > 10) stop();
    },
    onPointerUp: (e) => {
      const s = r.current;
      if (s.id !== e.pointerId) return;
      if (!s.fired) fn();
      stop(); tick();
      lastPointer.current = Date.now();
    },
    onPointerCancel: stop,
    onClick: () => { if (Date.now() - lastPointer.current > 600) { tick(); fn(); } },
  };
}

// Under the pad, in thumb reach: back, home, play or pause, the keyboard (Type on the TV), then
// the volume pill and mute.
export function Controls({ tv, onKey, onVolume, onType }) {
  const v = tv?.volume;
  const st = tv?.playing?.state;
  const upR = useRepeat(() => onVolume('up'));
  const downR = useRepeat(() => onVolume('down'));
  return (
    <div className="controls">
      <div className="key-row">
        <button className="key card" aria-label={t('back')} onClick={() => { tick(); onKey('back'); }}><Ic.Return /></button>
        <button className="key card" aria-label={t('home')} onClick={() => { tick(); onKey('home'); }}><Ic.Home /></button>
        <button className="key card" aria-label={st === 'playing' ? t('pause') : st === 'paused' ? t('play') : t('play_pause')}
          onClick={() => { tick(); onKey('play_pause'); }}>
          {st === 'playing' ? <Ic.Pause /> : st === 'paused' ? <Ic.Play /> : <Ic.PlayPause />}
        </button>
        <button className="key card" aria-label={t('type_on_tv')} aria-haspopup="dialog" onClick={onType}><Ic.Keyboard /></button>
      </div>
      <div className="vol-row">
        <div className="volume card" role="group" aria-label={t('volume')}>
          <button aria-label={t('vol_down')} {...downR}><Ic.Minus /></button>
          <span className="vol-mid" aria-live="polite">
            {v?.muted ? <Ic.Muted size={16} /> : <Ic.Speaker size={16} />}
            <span>{v?.muted ? t('muted') : v?.known ? v.level : t('vol_short')}</span>
          </span>
          <button aria-label={t('vol_up')} {...upR}><Ic.Plus /></button>
        </div>
        <button className={`key card mute ${v?.muted ? 'on' : ''}`} aria-pressed={Boolean(v?.muted)} aria-label={v?.muted ? t('unmute') : t('mute')}
          onClick={() => { tick(); onVolume('mute'); }}><Ic.Muted /></button>
      </div>
    </div>
  );
}

// The Ask bar at the bottom: looks like a field, opens the Ask screen with the keyboard up.
export function AskButton({ onAsk, quiet }) {
  return (
    <button className={`ask ${quiet ? 'quiet' : ''}`} onClick={onAsk} aria-label={t('ask_for_show')} aria-haspopup="dialog">
      <Ic.Sparkles size={20} className="ask-spark" />
      <span className="ask-hint">{t('ask_hint')}</span>
      <span className="ask-tag" aria-hidden="true">{t('ask_tag')}</span>
    </button>
  );
}
