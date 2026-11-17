# Game-day score bugs on the home screen

On the landscape home (`home.mode` "solar"), a small glass "score bug" shows at the top left while
one of your teams is playing: both teams, the score, and for baseball the inning, the bases and the
outs. No game, nothing on screen. It works with the on-TV app (option C) only, for now.

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

| League | | Where the scores come from |
|---|---|---|
| MLB | Yes | MLB's own Stats API, `statsapi.mlb.com`. No key, no account. |
| NBA | **Not yet** | Nowhere. See below. |

**Why there is no NBA.** In November 2026 the NBA's public scoreboard file
(`cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_00.json`) answered a plain request,
with no key, with "Access Denied" (HTTP 403). couch-tv does not dress its requests up as a browser
to get past that, and does not use someone else's copy of the scores instead. So a team set to
`"league": "nba"` shows no game, and `GET /api/scores` says why in `problem`. The NBA's terms of use
(nba.com/termsofuse, "NBA Statistics") would also need reading first: they ask for "a prominent
attribution to NBA.com", allow "private, non-commercial purposes", and rule out use "in connection
with any product or service that presents a live, near-live, or other real-time or archived
play-by-play account or depiction of any NBA game". The code keeps the leagues apart
(`Scores.kt`), so another league can be added later without touching the rest.

## What MLB is asked, and how often

Everything is a plain `GET` with no key and no cookie, from the TV app only. The home page never
asks a sports site anything: it asks the app (`GET /api/scores`).

| What | Address | How big | How often |
|---|---|---|---|
| Team list ("LAD" is team 119) | `/api/v1/teams?sportId=1&fields=...` | about 1 KB | once a day at most |
| The team's games, yesterday to tomorrow | `/api/v1/schedule?sportId=1&teamId=...&hydrate=broadcasts(all),linescore,team&fields=...` | about 1.3 KB a game (next to nothing in the off-season) | every 15 minutes, always |
| One game: status, inning, outs, runners, runs | `/api/v1.1/game/<id>/feed/live?fields=...` | about 0.5 KB | every 60 s before the game, every 15 s during it, and only while the home screen is in front |
| Logos | `www.mlbstatic.com/team-logos/team-cap-on-dark/<team id>.svg`, `.../league-on-dark/1.svg` | about 1 KB each (SVG) | once each |

The 15 minute look at the schedule is the only work while no game is near. `fields=` asks MLB for
only the parts used, which is why the answers are small enough for a TV.

**Logos** are MLB's own pictures (the ones drawn for a dark background), fetched by the TV app the
first time they are needed into its cache folder and served to the home page from there
(`/api/scores/logo/...`). They are never part of couch-tv: not in the app, not in the repository.
Until a logo has arrived, and if it never does, the bug shows the team's letters instead.

**If MLB does not answer:** the bug keeps its last reading for 2 minutes, then goes until a reading
works again. The app tries again at a slowing pace (5 minutes apart at most).

**MLB's terms.** The API's answers point to this notice
(<http://gdx.mlb.com/components/copyright.txt>): "Only individual, non-commercial, non-bulk use of
the Materials is permitted and any other use of the Materials is prohibited without prior written
authorization from MLBAM." One household's TV asking for its own team's game a few times a minute
is that kind of use. Anything else (a bar, a business, collecting the data) is not, and is yours to
clear with MLB.

## Settings

Three settings, all optional. In `config.json` terms they are `home.scores.teams`,
`home.scores.channels` and `home.scores.tv_app` ([CONFIG.md](CONFIG.md)); the on-TV app keeps them
itself, and you change them on the phone's setup page (`http://<tv address>:5400/setup`, **Score
bugs**), which shows them as JSON:

```json
{
  "teams": [ { "league": "mlb", "team": "LAD" } ],
  "channels": {
    "SportsNet LA": { "number": 690, "link": "", "aliases": ["SNLA", "Spectrum SportsNet LA"] },
    "FOX": { "number": 11, "link": "" },
    "FS1": { "number": 219, "link": "" },
    "ESPN": { "number": 206, "link": "" },
    "TBS": { "number": 247, "link": "" },
    "MLB Network": { "number": 213, "link": "", "aliases": ["MLBN"] },
    "Apple TV": { "app": "<the Apple TV app's package on your TV>" },
    "NBC": { "number": 4, "link": "", "aliases": ["Peacock"] }
  },
  "tv_app": "com.att.tv"
}
```

(The numbers above are examples. Use the ones on your own TV service.)

- **`teams`**: a league and the team's abbreviation as MLB writes it (`LAD`, `SF`, `NYY`, `AZ`,
  `ATH`, `CWS`, `KC`, `SD`, `TB`, `WSH`...). Left out: the Dodgers and the Clippers (the Clippers
  show nothing until there is an NBA feed). Up to 8. An empty list (`"teams": []`) switches the
  score bugs off: nothing is asked of anyone.
- **`channels`**: one entry per network, by the name the feed uses. For each: `number` (the
  channel number on your TV service, shown on the bug's pill), `link` (a link that opens your TV
  app on that channel, if your app has such links), `aliases` (other names for the same network)
  and `app` (an Android package to open instead, for a game that is only on a streaming service
  such as Apple TV). Everything is optional: `0`, `""` or left out means not filled in.
- **`tv_app`**: the Android package of your TV service's app, opened when a channel has no link
  [`com.att.tv`, DirecTV on a Fire TV].

**How a game finds its channel.** MLB lists each game's TV broadcasts by name. A name matches a
table entry when it is the same as the entry's name or one of its aliases, ignoring capitals,
spaces and punctuation. The feed's own short call sign matches too (`SNLA`, `FS1`, `MLBN`), and
so does one half of a shared name (`TBS/HBO MAX` matches `TBS`; `FOX / FOX ONE` matches `FOX`) and
a name with a remark in brackets (`TBS (out-of-market only)` matches `TBS`). When more than one
matches, a channel you have filled in wins over one that is only named; after that your team's own
channel, then a national one, and a broadcast marked "out-of-market only" after those. No match:
OK just opens the TV app.

**The names MLB used for Dodgers games in 2026**, to save you looking: `SportsNet LA` (call sign
`SNLA`, 167 games), and nationally `FOX / FOX ONE`, `FS1 / FOX ONE`, `Fox Deportes / FOX ONE`,
`ESPN/ESPN App`, `ESPN Unlimited`, `TBS/HBO MAX`, `TBS (out-of-market only)`, `MLB Network`,
`MLBN`, `MLBN (out-of-market only)`, `NBC/Peacock` and `Apple TV`. Another team's own channel
(`NBCS BA`, `YES`...) is listed too; it only matters if you put it in the table. For another team,
`GET /api/scores` shows the names of the game on now in `broadcasts`.

## Good to know

- **A game that was already over** when the TV app first heard of it (the TV was off, or the app
  had just started) does not show: nobody knows when it ended. The final score stays its 15
  minutes when the app saw the game end.
- **Spring training and the postseason** show like any other game. Spring games often have no TV
  broadcast listed, so OK just opens the TV app.
- **A postponed game** is listed twice by MLB, once as postponed and again on the day it is
  played. The two are kept apart, so hiding the "Postponed" bug does not hide the game itself.
- **A start time not set yet** (MLB marks it "TBD"): no bug until MLB gives a time or the game
  starts.
- **The computer bridge (option B)** does not have this yet; there nothing shows.
- **Try it without a game:** add `?sb=mock` to a preview of the home (with `?mock=1`): made-up
  games, no bridge. `?sb=mlb`, `nba`, `pre`, `final` or `both` picks what to show.
