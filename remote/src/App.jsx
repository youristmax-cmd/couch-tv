import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { api, subscribeNow, setTvId } from './api.js';
import { tick } from './haptics.js';
import * as Ic from './icons.jsx';
import { TopBar, ClockWeather, NowCard, AppRow, Pad, Controls, WhoSheet, SlotSheet, rowApps } from './Remote.jsx';
import { Watch, launchable } from './Watch.jsx';
import { AskSheet, MoreSheet, ArrangeSheet, PeopleSheet, Onboarding } from './Sheets.jsx';
import { usePullDown } from './gestures.js';
import { useScene } from './scene.js';
import { t, useLang, setTvLang } from './i18n.js';

const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

const SERVICES = {
  'apple-tv': 'Apple TV+', 'abc-iview': 'ABC iview', 'sbs-on-demand': 'SBS On Demand', 'disney-plus': 'Disney+',
  'prime-video': 'Prime Video', 'paramount-plus': 'Paramount+', 'foxtel-go': 'Foxtel Go', 'kayo': 'Kayo', 'binge': 'Binge', 'stan': 'Stan',
};

// The screen size, kept current through every way a phone can change it. Opening or closing the
// iPhone Duo often happens with the app in the background, and iOS does not always send a resize,
// so it is also re-read on matchMedia changes, the visual viewport, a ResizeObserver on the page,
// and whenever the app comes back to the front.
function useSize() {
  const get = () => ({ w: document.documentElement.clientWidth || window.innerWidth, h: window.innerHeight });
  const [s, set] = useState(get);
  useEffect(() => {
    let raf = 0;
    const f = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => { const n = get(); set((o) => (o.w === n.w && o.h === n.h ? o : n)); window.__tvMeasure?.(); });
    };
    const later = () => { f(); setTimeout(f, 300); };
    const mq = window.matchMedia?.('(min-width: 760px) and (min-height: 480px)');
    const vv = window.visualViewport;
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(f) : null;
    ro?.observe(document.documentElement);
    window.addEventListener('resize', f);
    window.addEventListener('orientationchange', later);
    window.addEventListener('pageshow', later);
    window.addEventListener('tv:resume', later);
    document.addEventListener('visibilitychange', later);
    vv?.addEventListener('resize', f);
    mq?.addEventListener?.('change', f);
    return () => {
      cancelAnimationFrame(raf); ro?.disconnect();
      window.removeEventListener('resize', f);
      window.removeEventListener('orientationchange', later);
      window.removeEventListener('pageshow', later);
      window.removeEventListener('tv:resume', later);
      document.removeEventListener('visibilitychange', later);
      vv?.removeEventListener('resize', f);
      mq?.removeEventListener?.('change', f);
    };
  }, []);
  return s;
}

// The ground is the TV home's landscape for this moment (scene.js), sharp, the same sky as the TV.
// Without one (a bridge with no TV home), the artwork of what is on the TV, blurred. Two layers
// crossfade when it changes.
function Backdrop({ image, colour, off, scene }) {
  const [layers, setLayers] = useState([{ key: 0, image, colour }]);
  useEffect(() => {
    let cancelled = false;
    const next = { key: Date.now(), image, colour };
    const swap = () => { if (!cancelled) setLayers((l) => [...l.slice(-1), next]); };
    if (image) { const im = new Image(); im.onload = swap; im.onerror = () => { next.image = null; swap(); }; im.src = image; } else swap();
    return () => { cancelled = true; };
  }, [image, colour]);
  return (
    <div className={`backdrop ${off ? 'off' : ''} ${scene ? 'is-scene' : ''}`} aria-hidden="true">
      {layers.map((l, i) => (
        <div key={l.key} className={`bd-layer ${i === layers.length - 1 ? 'top' : ''}`}
          style={l.image ? { backgroundImage: `url("${l.image}")` } : { background: `radial-gradient(120% 70% at 50% 18%, color-mix(in srgb, ${l.colour || '#2a2a30'} 55%, #000), #050506 75%)` }} />
      ))}
      <div className="bd-shade" />
    </div>
  );
}

// The on-screen keyboard's height, from the visual viewport. iOS keeps the page the same size and
// slides the keyboard over it, so bottom sheets are lifted by --kb. Android with
// interactive-widget=resizes-content shrinks the page instead, and --kb stays 0.
function useKeyboardInset() {
  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return undefined;
    const root = document.documentElement;
    // Android shrinks the page instead: a typing field in focus and a page much shorter than it
    // was at this width means the keyboard is up too.
    let tallest = { w: window.innerWidth, h: window.innerHeight };
    const f = () => {
      if (window.innerWidth !== tallest.w) tallest = { w: window.innerWidth, h: window.innerHeight };
      tallest.h = Math.max(tallest.h, window.innerHeight);
      const kb = Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop));
      const typing = document.activeElement?.tagName === 'INPUT';
      const shrunk = typing && window.innerHeight < tallest.h - 120;
      const open = kb > 80 || shrunk;
      root.style.setProperty('--kb', `${kb > 80 ? kb : 0}px`);
      root.classList.toggle('kb-open', open);
      // iOS sometimes scrolls the whole page up to show the field; the sheet is already lifted.
      if (open && window.scrollY) window.scrollTo(0, 0);
    };
    const later = () => setTimeout(f, 50);
    vv.addEventListener('resize', f); vv.addEventListener('scroll', f); window.addEventListener('resize', f);
    document.addEventListener('focusin', later); document.addEventListener('focusout', later); f();
    return () => {
      vv.removeEventListener('resize', f); vv.removeEventListener('scroll', f); window.removeEventListener('resize', f);
      document.removeEventListener('focusin', later); document.removeEventListener('focusout', later);
    };
  }, []);
}

export default function App() {
  useKeyboardInset();
  useLang();   // every word comes from i18n.js; a new language re-renders everything
  const [people, setPeople] = useState([{ id: 'person1', name: 'Person 1' }, { id: 'person2', name: 'Person 2' }]);
  // ask.enabled false in config.json hides Ask; people can be any number (none: everything shows as Both)
  const [askOn, setAskOn] = useState(true);
  // nobody set up: no picker, the remote shows everything
  useEffect(() => { if (!who && people.length === 0) setWho('both'); }, [people]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { api.config().then((c) => { if (Array.isArray(c.people)) setPeople(c.people); if (c.ask && c.ask.enabled === false) setAskOn(false); }).catch(() => {}); }, []);
  const [who, setWhoState] = useState(() => store.get('tv.who'));
  // Which TV this phone drives, remembered per phone. null is the bridge's default (living room).
  const [tvId, setTvIdState] = useState(() => { const id = store.get('tv.tv'); setTvId(id); return id; });
  const [tvs, setTvs] = useState([]);
  const [tv, setTv] = useState(null);
  const [reach, setReach] = useState(null);
  const [apps, setApps] = useState(null);
  const [recs, setRecs] = useState(null);
  const [cont, setCont] = useState(null);
  const [screens, setScreens] = useState([]);
  const [tab, setTab] = useState('remote');
  const [sheet, setSheet] = useState(null);
  // A sheet adds a history step, so the Android back button (or gesture) closes it instead of the app.
  useEffect(() => {
    const st = window.history.state;
    if (sheet && !st?.tvSheet) window.history.pushState({ ...(st || {}), tvSheet: true }, '');
    else if (!sheet && st?.tvSheet) window.history.back();
  }, [sheet]);
  useEffect(() => {
    const onPop = () => { if (!window.history.state?.tvSheet) setSheet(null); };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);
  const [toast, setToast] = useState(null);
  const [sleepError, setSleepError] = useState(null);
  const [, setNowTick] = useState(0);
  const askInput = useRef(null);
  const typeInput = useRef(null);
  const { w, h } = useSize();
  const wide = w >= 760 && h >= 480;
  const standalone = typeof navigator !== 'undefined' && (navigator.standalone || window.matchMedia('(display-mode: standalone)').matches);

  const say = useCallback((text, kind = 'info') => {
    setToast({ text, kind, id: Date.now() });
  }, []);
  useEffect(() => { if (!toast) return undefined; const t = setTimeout(() => setToast(null), toast.text.length > 40 ? 4500 : 2600); return () => clearTimeout(t); }, [toast]);

  useEffect(() => { setTv(null); return subscribeNow(setTv, setReach); }, [tvId]);
  useEffect(() => { api.tvs().then((d) => setTvs(d.tvs || [])).catch(() => {}); }, [reach, tvId]);
  // "Same as the TV": the language picked in the TV home's Home settings. The clock (12 or 24
  // hours) and the weather (on or off, °C or °F) follow those settings too.
  const [tvSettings, setTvSettings] = useState(null);
  useEffect(() => { api.settings().then((d) => { setTvLang(d.settings?.lang); setTvSettings(d.settings || null); }).catch(() => {}); }, [reach, tvId]);
  // The home's weather, the same reading the TV home shows. None (no internet, no place): hidden.
  const [weather, setWeather] = useState(null);
  useEffect(() => {
    if (reach === false) return undefined;
    const get = () => api.weather().then(setWeather).catch(() => {});
    get();
    const timer = setInterval(get, 30 * 60000);
    return () => clearInterval(timer);
  }, [reach, tvId]);
  useEffect(() => { api.apps().then((d) => setApps(d.apps)).catch(() => setApps((a) => a || false)); api.screens().then((d) => setScreens(d.screens)).catch(() => {}); }, [reach]);
  useEffect(() => { if (tab === 'watch' && apps === false) api.apps().then((d) => setApps(d.apps)).catch(() => {}); }, [tab]); // eslint-disable-line react-hooks/exhaustive-deps
  const whoNow = useRef(who);
  whoNow.current = who;
  const loadRecs = useCallback(() => {
    if (!who) return;
    const w = who;
    api.recs(w).then((d) => { if (whoNow.current === w) setRecs(d); }).catch(() => { if (whoNow.current === w) setRecs((r) => r || false); });
    api.continueList(w).then((d) => { if (whoNow.current === w) setCont(d.items); }).catch(() => { if (whoNow.current === w) setCont(undefined); });
  }, [who]);
  useEffect(() => { setRecs(null); setCont(null); loadRecs(); const t = setInterval(loadRecs, 10 * 60000); return () => clearInterval(t); }, [loadRecs]);
  useEffect(() => { if (reach && recs === false) loadRecs(); }, [reach]); // eslint-disable-line react-hooks/exhaustive-deps
  // Back from the lock screen: fetch the lists again, they may be hours old.
  useEffect(() => {
    const f = () => { loadRecs(); api.apps().then((d) => setApps(d.apps)).catch(() => {}); };
    window.addEventListener('tv:resume', f);
    return () => window.removeEventListener('tv:resume', f);
  }, [loadRecs]);
  useEffect(() => { const t = setInterval(() => setNowTick((n) => n + 1), 30000); return () => clearInterval(t); }, []);

  const setWho = (v) => { store.set('tv.who', v); setWhoState(v); };
  // Who is on the couch is shared with the bridge, so the phones and the TV home agree.
  // A tap on the switch tells the bridge; a change made elsewhere (TV, other phone) is followed here.
  const pickWho = (v) => {
    setWho(v);
    api.setWho(v).catch(() => { say(t('cant_tell_who'), 'err'); if (couchRef.current) setWho(couchRef.current); });
  };
  const couch = tv?.who;
  const couchRef = useRef(couch);
  couchRef.current = couch;
  useEffect(() => { if (couch && who && couch !== who) setWho(couch); }, [couch]); // eslint-disable-line react-hooks/exhaustive-deps

  const run = useCallback(async (p, okText) => {
    try { const r = await p; if (okText) say(typeof okText === 'function' ? okText(r) : okText); return r; } catch (e) {
      if (e.status === 0) setReach(false);
      say(e.message, 'err'); return null;
    }
  }, [say]);

  const appById = useMemo(() => Object.fromEntries((apps || []).map((a) => [a.id, a])), [apps]);
  const serviceName = (id) => appById[id]?.name || SERVICES[id] || id;

  const off = Boolean(tv) && (tv.power === 'off' || !tv.tv_connected);
  const thisTv = tvs.find((t) => t.id === (tvId || 'living-room'));
  const offState = reach === false ? 'unreachable' : thisTv && thisTv.paired === false ? 'unpaired'
    : !tv ? null : tv.waking ? 'waking' : off ? 'off' : null;
  const chooseTv = (id) => {
    const next = !id || id === 'living-room' ? null : id;
    store.set('tv.tv', next || ''); setTvId(next); setTvIdState(next); setWakeError(null);
  };
  const [wakeError, setWakeError] = useState(null);
  useEffect(() => { if (tv?.power === 'on') setWakeError(null); }, [tv?.power]);
  const onKey = (key, hold) => run(api.key(key, hold));
  const onVolume = (a) => run(api.volume(a));
  // A fully off TV leaves the Wi-Fi; the bridge tries Wake-on-LAN for up to 25 s, then gives up.
  const onWake = async () => {
    setWakeError(null);
    try { await api.power('on'); } catch (e) {
      if (e.status === 0) setReach(false);
      setWakeError(e.status === 503 ? t('didnt_wake') : e.message);
    }
  };
  const onRetry = () => api.now()
    .then((d) => { setTv(d); setReach(true); loadRecs(); api.apps().then((a) => setApps(a.apps)).catch(() => {}); })
    .catch(() => say(t('still_cant_reach'), 'err'));
  const onPower = () => (tv?.power === 'on' ? run(api.power('off')) : onWake());
  const onPlay = async (show) => {
    if (show.playable === false) { say(t('not_on_tv', { title: show.title, service: serviceName(show.service) })); return; }
    say(t('starting', { title: show.title }));
    const r = await run(show.id
      ? api.openContinue(show.id, who)
      : api.play({ title: show.title, season: show.season, episode: show.episode, service: show.service }, who));
    if (r) { say(r.method === 'deeplink' ? t('is_on', { title: r.title }) : r.note || t('opened', { app: r.app?.name })); loadRecs(); if (!wide) goTo('remote'); }
  };
  const onLaunch = (a) => run(api.launch(a.id), t('opening', { app: a.name })).then((r) => { if (r && !wide) goTo('remote'); });
  const onScreen = (s) => (s.ready ? run(api.openScreen(s.id), (r) => r.note || `Opening ${s.name}`) : say(s.note || t('not_set_up')));
  const onAsk = (text) => api.ask(text, who).then((r) => { loadRecs(); return r; });
  const onSleep = async (m) => {
    try { await api.setSleep(m); setSleepError(null); setTv((t) => t && ({ ...t, sleep: { ends: m ? new Date(Date.now() + m * 60000).toISOString() : null } })); say(m ? t('sleep_set', { n: m }) : t('sleep_off')); } catch (e) {
      setSleepError(e.status === 404 ? t('sleep_needs_update') : e.message);
    }
  };
  // Picture mode: only for TVs that have a "picture" entry in config.json.
  const [pictureInfo, setPictureInfo] = useState(null);
  useEffect(() => {
    setPictureInfo(null);
    if (sheet === 'more' && thisTv?.picture) api.getPicture().then(setPictureInfo).catch(() => {});
  }, [sheet, tvId]);
  const onPicture = async (mode) => {
    try { const r = await api.picture(mode); setPictureInfo((i) => ({ ...(i || {}), ...r })); setTv((t) => t && { ...t, picture: r.mode }); say(t('picture_now', { mode: pictureInfo?.modes?.find((m) => m.id === r.mode)?.name || r.mode })); } catch (e) { say(e.message, 'err'); }
  };
  // iOS only raises the keyboard if focus happens inside the tap itself, and a closed sheet is inert
  // (cannot take focus), so open it synchronously, then focus, all within the same tap.
  const openAsk = () => { tick(); flushSync(() => setSheet('ask')); askInput.current?.focus({ preventScroll: true }); };
  // The keyboard button: the TV menu with its "Type on the TV" field focused, the same way.
  const openType = () => { tick(); flushSync(() => setSheet('more')); typeInput.current?.focus({ preventScroll: true }); };

  const sleepEnds = tv?.sleep?.ends;
  const sleepLeft = sleepEnds ? `${Math.max(0, Math.round((new Date(sleepEnds) - Date.now()) / 60000))}m` : null;
  const p = tv?.playing;
  // Keep wearing the last show while an app or menu without artwork is up, so the remote never goes flat grey.
  const lastArt = useRef(null);
  const fresh = p?.backdrop || p?.poster || null;
  if (fresh) lastArt.current = fresh;
  const artTv = useRef(tvId);
  if (artTv.current !== tvId) { artTv.current = tvId; lastArt.current = null; }
  const appColour = appById[tv?.app?.id]?.colour;
  const dark = off || Boolean(offState);
  const artwork = dark ? null : (fresh || (appColour ? null : lastArt.current));
  const tint = dark ? '#101012' : (appColour || null);
  const scene = useScene(weather?.code);

  // Swipe down from the top (top bar, now playing, the Watch title) pulls the TV menu down with the finger.
  const moreSheet = useRef(null);
  const pull = usePullDown(moreSheet, () => { tick(); setSheet('more'); });

  // Remote and Watch are two pages side by side; the phone's own scrolling moves between them.
  const pager = useRef(null);
  // After the Duo closes (two panes back to one), or the width changes, put the pages back on the
  // tab that is showing, so the visible page and the active tab always agree.
  useEffect(() => {
    const el = pager.current; if (wide || !el) return;
    el.scrollLeft = tab === 'watch' ? el.clientWidth : 0;
  }, [wide, w]); // eslint-disable-line react-hooks/exhaustive-deps
  const goTo = (t) => {
    tick(); const el = pager.current;
    const still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (el) el.scrollTo({ left: t === 'watch' ? el.clientWidth : 0, behavior: still ? 'auto' : 'smooth' });
    setTab(t);
  };
  const onPage = (e) => {
    const el = e.currentTarget; const p = el.scrollLeft / Math.max(1, el.clientWidth);
    const t = p > 0.5 ? 'watch' : 'remote';
    if (t !== tab) setTab(t);
  };
  // There are no tabs: a swipe (or All apps) opens Watch, and Watch has a back button to come back.
  // All apps: the full list is the Apps section of Watch. One column: page over to it; two
  // columns: Watch is already beside the remote, so scroll it there.
  const onAllApps = () => {
    const still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const sec = document.getElementById('watch-apps');
    const box = sec?.closest('.watch');
    if (box) box.scrollTo({ top: box.scrollTop + sec.getBoundingClientRect().top - box.getBoundingClientRect().top - 8, behavior: still ? 'auto' : 'smooth' });
    if (!wide) goTo('watch');
  };
  // This phone's own app row (held tiles): a list of app ids, saved on the phone. null: the TV's order.
  const [row, setRow] = useState(() => { try { return JSON.parse(store.get('tv.row') || 'null'); } catch { return null; } });
  const [slot, setSlot] = useState(0);
  const saveRow = (r) => { setRow(r); store.set('tv.row', r ? JSON.stringify(r) : ''); };
  const pickSlot = (id) => {
    const ids = rowApps(launchable(apps), row).map((a) => a.id);
    const at = ids.indexOf(id);
    if (at >= 0) ids[at] = ids[slot];   // already in the row: the two swap
    ids[slot] = id;
    saveRow(ids); setSheet(null);
  };
  const appInfo = appById[tv?.app?.id];
  const nextUp = (cont === undefined ? recs?.continue : cont)?.[0] || null;

  const remote = (
    <div className="remote-col">
      <div className="pull-zone" {...pull}>
        <TopBar people={people} tvName={thisTv?.name} who={who} onWho={() => setSheet('who')} tv={tv} appInfo={appInfo} reach={reach} sleepLeft={sleepLeft} onMore={() => setSheet('more')} />
        <ClockWeather settings={tvSettings} weather={weather} />
        <NowCard tv={tv} appInfo={appInfo} item={nextUp} serviceName={serviceName} onKey={onKey} onResume={onPlay} />
      </div>
      <AppRow apps={launchable(apps)} row={row} onLaunch={onLaunch} onAll={onAllApps} onHold={(i) => { setSlot(i); setSheet('slot'); }} />
      <Pad onKey={onKey} offState={offState} wakeError={wakeError} onWake={onWake} onRetry={onRetry}
        tvName={thisTv?.name} onLivingRoom={() => { tick(); chooseTv(null); }} />
      <Controls tv={tv} onKey={onKey} onVolume={onVolume} onType={openType} onPower={onPower} onAsk={askOn ? openAsk : null} />
    </div>
  );
  const watch = <Watch pull={pull} people={people} who={who || people[0]?.id || 'both'} recs={recs} cont={cont === undefined ? (recs === false ? false : recs?.continue) : cont} apps={apps} screens={screens} serviceName={serviceName} onPlay={onPlay} onLaunch={onLaunch} onScreen={onScreen} wide={wide} onBack={() => goTo('remote')} />;

  return (
    <div className={`app ${wide ? 'is-wide' : ''} ${offState ? 'is-off' : ''}`}>
      <Backdrop image={scene || artwork} colour={tint} off={!scene && dark} scene={Boolean(scene)} />
      {reach === false && !wide && tab === 'watch' && (
        <div className="banner glass" role="status"><Ic.Wifi size={18} /><span><b>{t('cant_reach')}</b> {t('home_wifi')}</span>
          <button onClick={() => { tick(); onRetry(); }}>{t('try_again')}</button></div>
      )}
      {wide ? (
        <main className="split">{remote}<div className="watch-col">{watch}</div></main>
      ) : (
        <main className="single">
          <div ref={pager} className="pager" onScroll={onPage}>
            <section className="page" aria-label="Remote" inert={tab !== 'remote'}>{remote}</section>
            <section className="page watch-page" aria-label="Watch" inert={tab !== 'watch'}>{watch}</section>
          </div>
        </main>
      )}
      {toast && <div key={toast.id} className={`toast glass ${toast.kind}`} role="status">{toast.text}</div>}
      <AskSheet ref={askInput} open={sheet === 'ask'} onClose={() => setSheet(null)} recs={recs} onAsk={onAsk}
        playing={tv?.playing} serviceName={serviceName} onPlay={onPlay} scene={scene} />
      <SlotSheet open={sheet === 'slot'} onClose={() => setSheet(null)} apps={launchable(apps)} row={row} slot={slot}
        onPick={pickSlot} onReset={() => { saveRow(null); setSheet(null); }} />
      <WhoSheet open={sheet === 'who'} onClose={() => setSheet(null)} people={people} who={who} setWho={pickWho} />
      <MoreSheet ref={moreSheet} open={sheet === 'more'} tvs={tvs} tvId={tvId || 'living-room'} onTv={(id) => { tick(); chooseTv(id); }}
        picture={tv?.picture} pictureInfo={pictureInfo} onPicture={onPicture} onClose={() => setSheet(null)} sleepEnds={sleepEnds} sleepError={sleepError} onSleep={onSleep}
        onKey={onKey} typeRef={typeInput} onText={(text) => run(api.text(text), t('sent_to_tv'))} onArrange={() => setSheet('arrange')} onPeople={() => setSheet('people')} />
      <ArrangeSheet open={sheet === 'arrange'} onClose={() => setSheet(null)} tvName={thisTv?.name} />
      <PeopleSheet open={sheet === 'people'} onClose={() => setSheet(null)} onSaved={(list) => { setPeople(list); if (who && who !== 'both' && !list.some((p) => p.id === who)) setWho(list[0]?.id || 'both'); }} />
      {!who && people.length > 0 && <Onboarding people={people} standalone={standalone} onPick={pickWho} />}
    </div>
  );
}
