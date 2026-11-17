# Game-day score bugs on the home screen

On the landscape home (`home.mode` "solar"), a small glass "score bug" shows at the top left while
one of your teams is playing: both teams and the score, with the inning, the bases and the outs
for baseball, and the quarter and the clock for basketball. No game, nothing on screen. It works
with the on-TV app (option C) only, for now.

- **When it shows.** From 60 minutes before the scheduled start (with the start time) until 15
  minutes after the game ends (with the final score).
- **Two games at once** stack, the one being played on top. Never more than two.
- **With the TV remote.** Up from the app bar selects the bug. OK opens your TV service's app
  (DirecTV by default) for the game: on the game's channel if you have given that channel a link,
  otherwise the app opens and the bug tells you the channel number. Hold OK for 1.2 seconds to put
  that game's bug away; it comes back for the team's next game.
- **Delays.** A rain delay shows "Delayed" where the inning would be. A game that is postponed or
  cancelled shows that word from 60 minutes before the time it was to start until 15 minutes after
  it, then goes. A suspended game goes after 15 minutes. A game that still has not started 3 hours
  after its time, or has been stopped for 3 hours, goes too.

## Which leagues

| League | Where the scores come from | |
|---|---|---|
| MLB | MLB's own Stats API, `statsapi.mlb.com` | The league's own feed. No key, no account. Its notice allows this kind of use (below). |
| NBA | ESPN's public scoreboard JSON, `site.api.espn.com` | **Not an official feed.** No key, no account, and no permission either. Read the next paragraph. |

**The NBA scores are ESPN's, and nobody has said you may use them.** The NBA's own scoreboard file
(`cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_00.json`) answers a plain request,
with no key, with "Access Denied" (HTTP 403, November 2026), and couch-tv does not dress its
requests up as a browser to get past that. So the NBA side asks ESPN instead: the scoreboard JSON
ESPN serves at an address anyone can open. ESPN does not document it and does not offer it to the
public as a service, and no permission for this use has been given: none is published, and none
was asked for. ESPN can change it, limit it or close it on any day, and then the NBA bugs stop
until this is rewritten. The household that set this up chose it knowing that. couch-tv asks it
plainly, as itself, a few small requests at a time (below), and if ESPN ever refuses a plain
request, that is the end of it. If you would rather not, leave the NBA team out of `teams`: then
ESPN is never asked anything.

## What is asked, and how often

Everything is a plain `GET` with no key and no cookie, from the TV app only. The home page never
asks a sports site anything: it asks the app (`GET /api/scores`).

**MLB** (`https://statsapi.mlb.com/api`):

| What | Address | How big | How often |
|---|---|---|---|
| Team list ("LAD" is team 119) | `/v1/teams?sportId=1&fields=...` | about 1 KB | once a day at most |
| The team's games, yesterday to tomorrow | `/v1/schedule?sportId=1&teamId=...&hydrate=broadcasts(all),linescore,team&fields=...` | about 1.3 KB a game (next to nothing in the off-season) | every 15 minutes, always |
| One game: status, inning, outs, runners, runs | `/v1.1/game/<id>/feed/live?fields=...` | about 0.5 KB | every 60 s before the game, every 15 s during it, and only while the home screen is in front |
| Logos | `www.mlbstatic.com/team-logos/team-cap-on-dark/<team id>.svg`, `.../league-on-dark/1.svg` | about 1 KB each (SVG) | once each |

**NBA** (`https://site.api.espn.com/apis/site/v2/sports/basketball/nba`):

| What | Address | How big | How often |
|---|---|---|---|
| The team: its id and its next game | `/teams/<abbreviation>` (`/teams/lac`) | 21 KB (2.6 KB as sent) | every 15 minutes, always |
| One game: status, period, clock, scores, broadcasts | `/scoreboard/<event id>` | 5 to 23 KB (1.2 to 3.7 KB as sent) | every 60 s before the game and every 15 s during it, and only while the home screen is in front; and once with each 15 minute look from 60 minutes before the start until 30 minutes after the end |
| Where the league's logo is | `/scoreboard?limit=1` | 17 KB (3 KB as sent) | once a week |
| Logos | the addresses ESPN's answers give, on `a.espncdn.com`, asked at 200 px (the league's at 100 px) through ESPN's own resizer | 3 to 17 KB each (PNG) | once each |

The 15 minute look is the only work while no game is near. For MLB, `fields=` asks for only the
parts used. ESPN has no such thing, so its answers are bigger; the app never asks it for the whole
league's day (up to 240 KB), a team's season (900 KB) or a game's full summary (260 KB).

**Why the NBA game is asked for twice over.** ESPN's team entry names the team's next game only
until about tip-off; from then on it names the game after. So the app remembers a game once it has
seen it there (in its own files, so a TV switched on in the middle of a game still knows which
one) and from then on asks for that game by its own id.

**Logos** are the leagues' and ESPN's own pictures (the ones drawn for a dark background), fetched
by the TV app the first time they are needed into its cache folder and served to the home page
from there (`/api/scores/logo/...`). They are never part of couch-tv: not in the app, not in the
repository. Until a logo has arrived, and if it never does, the bug shows the team's letters
instead.

**If a feed does not answer:** the bug keeps its last reading for 2 minutes, then goes until a
reading works again. The app tries again at a slowing pace (5 minutes apart at most).

**MLB's terms.** The API's answers point to this notice
(<http://gdx.mlb.com/components/copyright.txt>): "Only individual, non-commercial, non-bulk use of
the Materials is permitted and any other use of the Materials is prohibited without prior written
authorization from MLBAM." One household's TV asking for its own team's game a few times a minute
is that kind of use. Anything else (a bar, a business, collecting the data) is not, and is yours to
clear with MLB.

**ESPN's terms.** There are none for this: see "Which leagues" above. The same common sense
applies with more reason: one household, its own team, nothing kept, nothing passed on.

## Settings

Three settings, all optional. In `config.json` terms they are `home.scores.teams`,
`home.scores.channels` and `home.scores.tv_app` ([CONFIG.md](CONFIG.md)); the on-TV app keeps them
itself, and you change them on the phone's setup page (`http://<tv address>:5400/setup`, **Score
bugs**), which shows them as JSON:

```json
{
  "teams": [ { "league": "mlb", "team": "LAD" }, { "league": "nba", "team": "LAC" } ],
  "channels": {
    "SportsNet LA": { "number": 690, "link": "", "aliases": ["SNLA", "Spectrum SportsNet LA"] },
    "FOX": { "number": 11, "link": "" },
    "FS1": { "number": 219, "link": "" },
    "ESPN": { "number": 206, "link": "" },
    "TBS": { "number": 247, "link": "" },
    "MLB Network": { "number": 213, "link": "", "aliases": ["MLBN"] },
    "Apple TV": { "app": "<the Apple TV app's package on your TV>" },
    "NBC": { "number": 4, "link": "", "aliases": ["Peacock"] },
    "KTLA": { "number": 5, "link": "" },
    "NBA TV": { "number": 216, "link": "" },
    "ClipperVision": { "app": "<the ClipperVision app's package on your TV, if it has one>" },
    "Prime Video": { "app": "<the Prime Video app's package on your TV>" }
  },
  "tv_app": "com.att.tv"
}
```

(The numbers above are examples. Use the ones on your own TV service.)

- **`teams`**: a league (`mlb` or `nba`) and the team's abbreviation as the feed writes it. MLB:
  `LAD`, `SF`, `NYY`, `AZ`, `ATH`, `CWS`, `KC`, `SD`, `TB`, `WSH`... NBA, in ESPN's forms: `LAC`,
  `LAL`, `BOS`, and the short ones `GS`, `NY`, `SA`, `NO`, `UTAH`, `WSH`. Left out: the Dodgers and
  the Clippers. Up to 8. An empty list (`"teams": []`) switches the score bugs off: nothing is
  asked of anyone.
- **`channels`**: one entry per network, by the name the feed uses. For each: `number` (the
  channel number on your TV service, shown on the bug's pill), `link` (a link that opens your TV
  app on that channel, if your app has such links), `aliases` (other names for the same network)
  and `app` (an Android package to open instead, for a game that is only on a streaming service
  such as Apple TV or Prime Video). Everything is optional: `0`, `""` or left out means not filled
  in.
- **`tv_app`**: the Android package of your TV service's app, opened when a channel has no link
  [`com.att.tv`, DirecTV on a Fire TV].

**How a game finds its channel.** The feed lists each game's broadcasts by name. A name matches a
table entry when it is the same as the entry's name or one of its aliases, ignoring capitals,
spaces and punctuation. MLB's own short call sign matches too (`SNLA`, `FS1`, `MLBN`), and so does
one half of a shared name (`TBS/HBO MAX` matches `TBS`; `FOX / FOX ONE` matches `FOX`) and a name
with a remark in brackets (`TBS (out-of-market only)` matches `TBS`). When more than one matches, a
channel you have filled in wins over one that is only named; after that your team's own channel,
then a national one, then one for people out of town ("out-of-market only", NBA League Pass), then
the other team's. No match: OK just opens the TV app.

**The names MLB used for Dodgers games in 2026**, to save you looking: `SportsNet LA` (call sign
`SNLA`, 167 games), and nationally `FOX / FOX ONE`, `FS1 / FOX ONE`, `Fox Deportes / FOX ONE`,
`ESPN/ESPN App`, `ESPN Unlimited`, `TBS/HBO MAX`, `TBS (out-of-market only)`, `MLB Network`,
`MLBN`, `MLBN (out-of-market only)`, `NBC/Peacock` and `Apple TV`. Another team's own channel
(`NBCS BA`, `YES`...) is listed too; it only matters if you put it in the table.

**The names ESPN lists for the Clippers' 2026-27 games** (80 games, read in November 2026):
`ClipperVision` (streaming, 76 games: the Clippers' own), `KTLA` (TV, 10 games), and nationally
`NBA TV` (3), `NBC` (3), `ESPN` (2), `NBCSN` (1) and `Prime Video` (1, streaming only), with
`NBA League Pass` on some. The other team's own channel is listed too (`Spectrum Sports Net` for
the Lakers, `KJZZ-TV` and `Jazz+` for Utah, `DAZN MIN`, `Suns Live`...). ESPN lists streaming
services beside TV channels and does not say which channel number anything is on.

For another team, `GET /api/scores` shows the names of the game on now in `broadcasts`.

## Channel links

A channel's `link` is what makes OK tune the game instead of only opening the TV app. What follows
was seen on one Fire TV with DirecTV in November 2026; another TV app will have its own links, or
none.

- **The form.** DirecTV (`com.att.tv`) takes
  `dtvnow://deeplink.directvnow.com/play/channel/<call sign>/<resource id>`. Only the id matters:
  it is DirecTV's own for that channel, and a link with a nonsense call sign tuned just the same.
- **DirecTV in front:** a link tunes every time, in about 6 seconds.
- **DirecTV not in front**, which is how it is when you press OK on a score bug (it is waiting
  behind the home, or not running at all): the link brings it forward, playing about 3 seconds
  later from the background and about 15 from a cold start. But it sometimes ignores the link
  and shows the channel it had before. That was seen from the background (after DirecTV had sat
  there for several minutes) as well as from cold, so nothing about the start tells whether the
  link was taken.
- **So the link is sent twice.** The same link sent again once DirecTV is playing tunes it, and
  when the first one had worked, a second one left it on the same channel. couch-tv sends the
  second one 1.5 seconds after DirecTV is playing. How it knows depends on the TV:
  - **With ADB on the TV** (on-tv/README.md): it sees which app is in front and waits until
    DirecTV says it is playing (it looks every 2 seconds; after 25 it sends anyway). A link sent
    while DirecTV is already in front goes once.
  - **Without ADB** (a Fire TV that refuses the TV its own ADB): it cannot see which app is in
    front, so it goes by what it does know. A link sent from the home (OK on a score bug) gets
    the second send; one sent while the home is not in front (from the phone, over whatever is
    showing) goes once. And "playing" is sound: if the TV was silent when you pressed OK, it
    listens once a second, for 30 seconds at most, and the second send follows the first sound.
  - Either way the second send is dropped if you have come back to the home, or asked for another
    game, in the meantime.
- **What the sound cannot tell** (without ADB), so the link stays sent once: something was already
  playing on the TV when you pressed OK (music from the Spotify app, say), or no sound came within
  30 seconds. If you then land on the wrong channel, go back to the home and press OK on the bug
  again.
- **A wrong id fails silently.** A link whose id your account cannot tune (seen with ids copied
  from someone else's list) does nothing you can see: DirecTV stays on what it was playing and
  says nothing. couch-tv cannot tell either. With ADB it only knows the app came to the front,
  without ADB not even that, so it will not warn you.
- **Check a link by hand before you put it in the table.** With DirecTV open on the TV and a
  computer that has ADB to it:

  ```sh
  adb shell am start -a android.intent.action.VIEW -n com.att.tv/com.clientapp.MainActivity \
    -d 'dtvnow://deeplink.directvnow.com/play/channel/<call sign>/<resource id>'
  ```

  The channel should change within a few seconds. If nothing changes, the id is not one your
  account can tune.

## Good to know

- **A game that was already over** when the TV app first heard of it (the TV was off, or the app
  had just started) does not show: nobody knows when it ended. The final score stays its 15
  minutes when the app saw the game end.
- **An NBA game the app never heard of before it began** does not show at all. It hears of a game
  from ESPN's team entry, which names it from the moment the team's previous game starts until
  this one does. A TV app that was not running at any time in between (unplugged for days, or
  installed during the game) misses that one game and has the next.
- **Basketball's clock** is the feed's: `6:21`, and tenths inside the last minute (`45.3`). The
  ring on the bug is how much of the period is left: 12 minutes for a quarter, 5 for an overtime.
  After overtime the final reads `F/OT`, `F/2OT`...
- **Spring training, the preseason and the postseason** show like any other game. Spring games
  often have no TV broadcast listed, so OK just opens the TV app.
- **A postponed game** is listed twice by MLB, once as postponed and again on the day it is
  played. The two are kept apart, so hiding the "Postponed" bug does not hide the game itself.
- **A start time not set yet**: no bug until the feed gives a time or the game starts.
- **The computer bridge (option B)** does not have this yet; there nothing shows.
- **Try it without a game:** add `?sb=mock` to a preview of the home (with `?mock=1`): made-up
  games, no bridge. `?sb=mlb`, `nba`, `pre`, `final` or `both` picks what to show.
