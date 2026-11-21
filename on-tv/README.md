# On-TV mode: the remote with no computer

Couch TV normally needs a computer on the network running the bridge. **On-TV mode puts the bridge
inside an app on the TV.** The phone remote is served by the TV itself, and the TV controls itself
through its own debugging port. Nothing else has to stay switched on.

It is a separate app (`dev.couchtv.ontv`, shown as **Couch TV** in the TV's app list). It installs
next to anything else, including the computer-bridge home screen, and it does not take over the
home button unless you switch that on.

## What works, compared with the computer bridge

| Feature | Computer bridge | On-TV mode | Notes for on-TV mode |
|---|---|---|---|
| Phone remote page | yes | yes | Served by the TV at `http://<tv-ip>:5400`. Same remote build as the computer bridge. |
| Pairing a phone | not needed (home network only) | **required** | A 6 digit code on the TV, once per phone. Every call needs the phone's token. |
| Open an app | yes | yes | Plain Android intents, even without ADB. |
| D-pad, back, home, playback keys | yes | yes, with ADB | Through the TV's own ADB (see below). Home works without ADB. |
| Volume | yes | yes | With ADB: key presses, so HDMI soundbars follow. Without ADB: the TV's own speaker only. |
| Type into a search box | yes | yes, with ADB | |
| Turn the TV off | yes | yes, with ADB | |
| **Turn the TV on** | yes (Wake-on-LAN) | **no** | Once the TV sleeps, this app sleeps too. See "Power on" below. |
| What is playing | yes | yes, with ADB | Same `dumpsys media_session` reading as the bridge. Apps that report no title show the app only. |
| Play a show by name | yes | yes | Same JustWatch lookup and deep-link table as the bridge, no key needed. |
| Keep watching | yes | yes, with one permission | What was started from the phone on this TV, and, once you allow it, what was watched in any app with the TV's own remote (title, episode and progress as far as the app says them). No imported history. See "Keep watching" below. |
| Sleep timer | yes | yes, with ADB | Pause, then off. |
| Screenshot (`/api/screenshot`) | yes | yes, with ADB | |
| Picks and taste profile | yes | no | Needs "ask". |
| Ask Claude | yes (computer's Claude Code) | **off by default** | Optional: your own Anthropic API key on the setup page. See below. |
| Picture mode, screens, Moonlight | yes | no | |
| Home screen (TV home page) | yes | yes, optional | Same page, served from the app. Replacing Google's home needs one ADB command, see below. |
| Live updates (`/api/events`) | yes | yes | Server-sent events, plus a WebSocket at `/api/ws`. |
| Game-day score bugs | no | yes, MLB and NBA | On the landscape home while your team plays. See "Score bugs" below. |

## Which TVs

- **Yes:** Android TV and Google TV (Chromecast with Google TV, Google TV Streamer, Sony, TCL,
  Hisense, Philips and other Google TV sets), Nvidia Shield, and Fire TV on Fire OS. Android 7.1 or newer.
- **No:** Samsung (Tizen), LG (webOS), Roku, Apple TV, Hisense VIDAA, and Fire TV sticks on Vega OS.
  These do not run Android apps. Use the computer bridge for those where it can reach them.

### The one setup step: let the TV control itself (ADB)

Opening apps works straight away. Keys, typing, "what's playing" and power off need the TV's
debugging switched on once. There are two ways. **On Android 13 and newer (most Google TVs), use
pairing (1).** Use port 5555 (2) on older TVs.

1. **Wireless debugging pairing** (Android 11 and newer, TV on Wi-Fi). The port is random and
   changes after every restart, so the app pairs with the TV once, then finds the port by itself
   and switches wireless debugging back on after a restart. With the TV remote:
   1. Settings, System, About (on Android TV 9 to 11: Settings, Device Preferences, About), press
      **Build** seven times (unlocks Developer options).
   2. Settings, System (or Device Preferences), **Developer options**, scroll to **Wireless debugging**, OK, switch it on.
      On "Allow wireless debugging on this network?" tick **Always allow on this network**, then
      **Allow**.
      **Careful:** in the Developer options list, **Revoke USB debugging authorizations** sits
      directly above Wireless debugging. Never press OK on it: it signs out every computer that
      uses ADB on the TV. Check what is highlighted before each OK.
   3. In Wireless debugging choose **Pair device with pairing code**. Leave that screen open.
   4. On the phone, open `http://<tv-ip>:5400/setup` and type the 6 digit code. The port is
      optional (the app finds it). The setup page shows "connected (wireless debugging)".

   **Wireless debugging needs Wi-Fi.** On Ethernet, Android refuses to turn it on; use 2, or put the
   TV on Wi-Fi.
2. **Network debugging on port 5555** (older Android TV, Fire TV, Nvidia Shield, and any TV where a
   computer has run `adb tcpip 5555`). Turn on network (or USB) debugging, open Couch TV, press
   **Connect ADB**, and on the TV tick **Always allow** and press **Allow**. **Some Android 13+
   Google TVs never show that question for a new key** (seen on one TV after its Google home screen
   had been switched off): the app gives up after 45 seconds and points you to pairing.

After the first connection the app gives itself two permissions over ADB, for itself only:
"draw over other apps" (so it can open apps from the background) and "write secure settings" (so
it can switch wireless debugging back on after a restart, the way Shizuku does).

## Install

1. **Download `couch-tv-ontv.apk` from the GitHub Releases page** (easiest), or build it from the
   repo root with `sh scripts/build-apk.sh` (JDK 21, the Android SDK and Node 20+, no Gradle
   needed; README, "Building the app"). It builds the phone remote first if `remote/dist` is
   missing, then copies the remote and the TV home page into the app, and leaves it at
   `dist/couch-tv-ontv.apk`.
2. Install from a computer once (developer options and debugging must be on). With network
   debugging on port 5555, the repo's own helper needs nothing else installed, and copies the app
   in small pieces (some TVs stall on a normal push):
   ```
   .venv/bin/python on-tv/scripts/real-tv.py --ip <tv-ip> install couch-tv-ontv.apk   # from the repo root, after README step 2
   ```
   If the TV only has **Wireless debugging** (many Android 13 and newer Google TVs), use Google's
   platform-tools once instead:
   ```
   adb pair <tv-ip>:<pairing port> <6 digit code>    # from "Pair device with pairing code"
   adb connect <tv-ip>:<port shown under Wireless debugging>
   adb install -r couch-tv-ontv.apk
   ```
   Or copy the APK to the TV with a file app and open it there.
3. On the TV, open **Couch TV**. It shows the address and a phone code.

## Pair a phone

1. On the phone, open the address the TV shows, for example `http://192.0.2.10:5400`.
2. Type the 6 digit code from the TV. (Tap "Show a code on the TV" if it has gone.)
3. That phone now has its own token, stored in the browser. Add the page to the home screen.

A code lasts 3 minutes, works once, and is thrown away after 5 wrong tries. **Forget phones** on
the TV, or on the setup page, signs every phone out.

## Use it as the home screen (optional)

Switch on **Use as home screen** in Couch TV. On a plain Android TV that is enough. On Google TV the
system launcher always wins the home button, so it also has to be switched off, from a computer:

```
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx   # Chromecast with Google TV and most Google TVs
adb shell pm enable com.google.android.apps.tv.launcherx                  # undo
```

Check the package name on your TV first (`adb shell pm list packages | grep launcher`). Some Google
Home features stop working while it is off. The app never does this by itself.

### Fire TV (Fire OS)

Step by step on a Fire TV Stick, with the things that will save you time: [docs/FIRE-TV-STICK.md](../docs/FIRE-TV-STICK.md).

Works on Fire TV sticks, Cubes and TVs that run **Fire OS** (6 on Android 7.1, 7 on Android 9, 8 on
Android 11). Fire OS 6 (Fire TV Stick 4K 1st gen and older sticks) needs a release newer than 0.3,
and network debugging on port 5555, because wireless pairing starts at Android 11. It does
**not** work on the newer sticks that run **Vega OS**, which cannot sideload apps at all.

Amazon's launcher cannot be switched off, and the Home key starts it by name, so no setting can
hand the Home key to another app. Instead, with **Use as home screen** on, Couch TV notices
Amazon's home coming on screen and puts its own home back on top. It does nothing on any other
kind of TV. Two things to know:

- **Amazon's home still flashes up for a moment** before Couch TV's comes back. Without root,
  that is as good as Fire OS allows.
- It needs two one-off grants, over ADB from a computer (they survive reboots, so the computer is
  only needed once):

```
adb shell pm grant dev.couchtv.ontv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set dev.couchtv.ontv SYSTEM_ALERT_WINDOW allow
```

The first lets the app switch on its own small accessibility watcher (it sees which window came
forward and reads nothing else) and switch it back on if an update turns it off. The second lets
the app come forward from the background. If you would rather not use an accessibility service,
grant usage access instead of the first line (`adb shell appops set dev.couchtv.ontv GET_USAGE_STATS allow`)
and the app polls the system's usage events instead. Where a Fire OS build still lets the TV use its
own ADB, the activity log is followed as a third option.

On a Fire TV the quick buttons also gain **Input** (the TV's own HDMI picker). Some Fire OS builds
block the TV's own ADB (seen on an Insignia F30 TV, Fire OS 7.6.8.5: adbd accepts `127.0.0.1:5555`
and hangs up before the handshake, while a computer on the network connects fine).

**Fire TV remote fallback.** On those TVs, pair the **Fire TV remote** on the setup page instead:
press **Show a PIN on the TV**, type the 4 digit PIN, press **Pair**. Couch TV then sends the D-pad,
OK, Back, Home, Menu, volume, typing and power off through Amazon's own remote service on the TV
(`com.amazon.tv.turnstile`, the one the Fire TV phone app uses: DIAL start on port 8009, HTTPS on
8080), on `127.0.0.1` only. Play, pause and skip go through Android's media keys and need no
pairing. "What's playing" and screenshots still need ADB. The service is undocumented, so an Amazon
update could change it. If it stops answering, restart the TV (or from a computer:
`adb shell am force-stop com.amazon.tv.turnstile`).

Confirmed on a **Fire TV Stick 4K (2nd gen, Fire OS 8.1, Android 11)** by
[killy10o10](https://github.com/Seagey/couch-tv/issues/3), who also wrote a step-by-step setup guide:
[FIRE-TV-STICK.md](https://github.com/killy10o10/couch-tv/blob/main/docs/FIRE-TV-STICK.md). Two tips
from that report:

- **After updating the app with `adb install -r`, restart the stick** if Home lands on Amazon's
  home. Fire OS can leave the watcher switched on but not connected until a restart.
- **Only one launcher can do this at a time.** If another launcher (Projectivy, for example) also
  has its Home override on, the two fight. Turn the other one's override off.

Ported from the Fire TV work in [dlandman27's fork](https://github.com/dlandman27/couch-tv). Thanks
to them.

The home's gear tile opens **Home settings** here too (language, weather place and units, look,
scenes, apps, people, quick buttons; README, "Settings on the TV"). They are kept in this app
(`/api/settings`, next to the setup page's own settings), and the people list is the same one the
setup page edits. One difference: this app has no file picker, so your own photos come from a web
address.

## Score bugs (optional)

While one of your teams is playing, the landscape home shows a small score bug at the top left
(teams and score; inning, bases and outs for baseball; quarter and clock for basketball), from 60
minutes before the start until 15 minutes after the end. OK on it opens your TV service's app for
the game. **MLB and the NBA.** MLB's scores come from MLB's own Stats API, which needs no key. The
NBA's own scoreboard refuses a plain request, so the NBA's come from ESPN's public scoreboard
JSON: no key either, but not an official feed, with no permission given for this use, and ESPN
may change or close it. [docs/SCORES.md](../docs/SCORES.md) has all of it.

The teams, and which channel each network is on your TV service, are set on the phone's setup page
(`http://<tv-ip>:5400/setup`, **Score bugs**). Left alone, the teams are the Dodgers and the
Clippers. Every 15 minutes the app looks at each team's schedule (about a kilobyte from MLB, 21 KB
from ESPN) and asks for more only while a game is near and the home screen is in front. To leave
ESPN out, take the NBA team out of the list; to switch it all off, save an empty list
(`"teams": []`).

## Keep watching (optional)

Out of the box the home's Keep watching row holds what was started through Couch TV's own search.
It can also hold what you watched by opening Netflix, Peacock or any other app yourself: the
title, the episode and how far in you are, as far as the app lets them be known. OK on a card
opens that app. [docs/KEEP-WATCHING.md](../docs/KEEP-WATCHING.md) has all of it, with what each
app has been seen to give so far. Apps differ a great deal: on a Fire TV, Peacock names only the
episode, by its name or, for a show that is on every day, by its date (the show is worked out
from the previews it plays, if you switch looking up on), and Hulu names nothing at all (it gets
one card, "Continue in Hulu").

It reads the few words each app publishes about what it is playing (its "media session"), and
Android only allows that to an app you have let in as a notification listener. Couch TV's listener
reads no notifications; it is only the way in. Nothing is listened to until you run this once from
a computer:

```
adb shell cmd notification allow_listener dev.couchtv.ontv/dev.couchtv.ontv.MediaAccess
```

(`disallow_listener` takes it back.) The watch log stays on the TV. One thing can send names out,
and it is off unless you switch it on: **Look up shows and pictures** on the setup page
(`http://<tv-ip>:5400/setup`, **Keep watching**) asks JustWatch for a picture when the app gave
none, and for the show when the app names only the episode; for an episode named only by its date
it asks TVmaze whether the show was on that day (TVmaze's data is CC BY-SA). Music, YouTube and
live TV apps are left out. It has run on one Fire TV so far (November 2026); what any other app
publishes is still
to be seen, and `http://<tv-ip>:5400/api/continue/log` on a paired phone shows it. OK on a card
opens the very episode only in an app that accepts links, and on a Fire TV Peacock does not.

## Weather

The weather comes from Open-Meteo (free, no key), for the place on the setup page, at most every
30 minutes. If Open-Meteo does not answer, the app asks the US National Weather Service
(`api.weather.gov`, also free with no key) instead: for a place in the United States only, and it
means that place's latitude and longitude then go to weather.gov as well. Anywhere else there is no
backup. The last reading stays on screen for up to two hours when nobody answers.
[docs/CONFIG.md](../docs/CONFIG.md), "The weather's backup", says what is asked and how the two
differ.

## Power on

The TV cannot wake itself: in standby the app is not running. Use the TV's own remote, HDMI-CEC
from another device, or Wake-on-LAN from anything that stays on (a phone shortcut, a router, a
Raspberry Pi running the computer bridge). On some TVs `POST /api/power {"state":"on"}` works for
a minute or so after "off", while the TV is still in light standby.

## Ask (optional, off by default)

On-TV mode has no Claude unless you add your own Anthropic API key on the setup page and switch
"ask" on. It uses Claude Haiku 5.5 with a few tools (play, open an app, press a key, what is on),
about a fifth of a US cent per ask at October 2026 prices. Make a key just for the TV and set a
monthly spend limit in the Anthropic Console: the key is stored on the TV, and anyone with ADB
access to the TV could read it. Without a key, "Ask for a show" answers that ask is off.

## Uninstall

```
adb uninstall dev.couchtv.ontv
```
or Settings, Apps, Couch TV, Uninstall. This removes the app, its tokens and its ADB key. If you
switched off Google's launcher, switch it back on first (see above). To also remove the app's
debugging key from the TV, use the revoke option for debugging keys in Developer options (it signs
out every computer, so reconnect yours afterwards).

## Troubleshooting

| What you see | Why, and what to do |
|---|---|
| Phone shows "Pair this phone" again | The token was cleared (new phone, private browsing, or Forget phones). Type a new code. |
| "Pressing keys needs ADB on the TV" | ADB is not connected. Open Couch TV and press Connect ADB, or pair wireless debugging on the setup page. |
| "Allow debugging?" never appears | Network debugging is off, or the TV suppresses the question (seen on an Android 14 Google TV). Use wireless debugging pairing instead. |
| Worked, then stopped after a restart | Port 5555 is often switched off by a restart. Pair wireless debugging once: the app then turns it back on and finds the new port after every restart. |
| Wireless debugging will not switch on | The TV is on Ethernet. Android only allows it on Wi-Fi. |
| The phone page does not load | Phone and TV must be on the same network. The address changes if the router gives the TV a new one; reserve it in the router. iPhone asks for Local Network access once. |
| Apps do not open from the phone | Android blocked an app start from the background. Connect ADB once: the app then has the permission it needs. |
| Home button still goes to Google's home | See "Use it as the home screen". |
| Server not answering after a TV update | Open Couch TV once; it also starts at boot. |

## How it works

- `BridgeService`: a foreground service that starts at boot and runs the HTTP and WebSocket
  server (NanoHTTPD) on port 5400.
- `Adb.kt`: ADB to 127.0.0.1 with libadb-android (plain TCP, TLS and pairing, mDNS discovery). The
  app's RSA key and certificate are made on first run and kept in its private storage.
- `Finder.kt` and `Apps.kt`: the computer bridge's JustWatch lookup, app table and deep-link rules.
- `web/shim.js`: added to the phone remote's page by the TV app. It pairs the phone and adds the
  token to every call, so the remote itself is unchanged and the same build works with both modes.
- `Scores.kt` and `ScoreFeeds.kt`: the score bugs. `Scores.kt` asks MLB's Stats API and ESPN's
  scoreboard (one `League` each) and keeps the time (the 15 minute look, readings only while the
  home asks, the logo cache);
  `ScoreFeeds.kt` is every rule about what an answer means (which games show and in what order,
  the channel for a game), with no network and no Android in it, so it is tested on a computer.
- `LinkRules.kt`: how a channel link is sent to a TV app (`/api/launch`): once when the app is
  already in front; otherwise once more a moment after it is playing, because an app brought
  forward by a link sometimes ignores it. With ADB it looks at the front app and the app's media
  session; without ADB it goes by its own home being in front and by sound starting on the TV.
  Also tested on a computer.
- `Watching.kt`, `WatchRules.kt` and `MediaAccess.kt`: Keep watching from other apps' media
  sessions. `MediaAccess` is a notification listener that reads no notifications: being allowed as
  one is what lets `Watching.kt` see the sessions. `Watching.kt` listens, keeps the apps' pictures
  and stores the log; `WatchRules.kt` is every rule (which apps, what counts as watched, season and
  episode from the words, the card's shape, which shows to try for an app that names only the
  episode, the one card for an app that names nothing), with no Android in it, so it is tested on
  a computer. `ShowRules.kt` reads JustWatch's answers about a show (its episode list, asked for
  without the offers, and the offers of one episode) and TVmaze's (whether a show was on on a
  day), tested the same way. `Tvmaze.kt` does that asking, and keeps the answers.
- `Weather.kt` and `WeatherRules.kt`: the weather. `Weather.kt` asks Open-Meteo, and the US
  National Weather Service when Open-Meteo does not answer; `WeatherRules.kt` turns either answer
  into the home's reading (the Weather Service's words into Open-Meteo's codes), tested on a
  computer.
- `web/setup.html`: the phone setup page (ADB, wireless debugging pairing, names, weather, ask,
  score bugs, keep watching).
- Endpoints are those in `bridge/API.md`, plus `/api/pair`, `/api/pair/start`, `/api/adb`,
  `/api/adb/connect`, `/api/adb/pair`, `/api/settings`, `/api/phones/forget` and `/api/ws`.

## Tests

`scripts/emulator-test.mjs` drives an Android TV emulator end to end (pairing, 401s, loopback ADB
with the real "Allow debugging?" dialog, keys, launch, deep link, volume, power, the phone page at
390x664). It refuses to run against anything that is not an emulator.

`tv-home/android/gradlew -p on-tv/android :app:testReleaseUnitTest` (from the repo folder, with
the same JDK and Android SDK as a build) runs the score bugs' rules on the computer, with no TV and
no emulator, against answers recorded from MLB's Stats API and from ESPN's scoreboard
(`android/app/src/test/`). The same run checks the rules for sending a channel link, the Keep
watching rules (against made-up sessions, and against the looks recorded from a Fire TV with
Peacock, Hulu and YouTube on it, and JustWatch's answers for the show in them) and the weather's
(against answers recorded from `api.weather.gov`).
