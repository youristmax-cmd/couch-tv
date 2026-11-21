# Keep watching, from what was really watched on the TV

The home's **Keep watching** row used to know only what was started through couch-tv's own search
("put on Bluey"). With the on-TV app (option C) it can also show what you watched by opening
Netflix, Peacock or any other app with the TV's own remote: the title, the episode, how far in you
are and a picture, as far as the app lets any of that be known.

Netflix and the others tell couch-tv nothing, and their own "continue watching" lists cannot be
read. What Android does offer, to an app the TV's owner has allowed, is each app's **media
session**: the few words an app publishes about what it is playing (the same ones a soundbar's
display or a car screen would show). The on-TV app listens to those and keeps its own short log.

How much an app publishes is up to the app, and it varies a great deal. What has been seen so
far:

## What each app has been seen to report

**On one TV** (a Fire TV on Fire OS 7), **16 to 20 November 2026.** Other TVs, other versions of
these apps and every app not listed here are still to be seen; "Seeing what each app reports",
below, is how.

| App | What its session says | What the row makes of it |
|---|---|---|
| Peacock (`com.peacock.peacockfiretv`) | The **episode's own name** as the title, how long it is and how far in. No show name, no season or episode number, no picture, no id. For a show that is on every day, the episode's name is **the date it was first on** ("November 10, 2030" in that form), and nothing else. It plays previews, on a show's page and on its own home screen: mostly titled with the show's name and "(Trailer)" after it, sometimes with the name alone, each a minute or two long. Now and then, while an episode plays, the session is empty for a moment. | Without looking up: a card with the episode's name (or the date) and the app's icon. With looking up on: the show (worked out from the previews, below), its season and episode and JustWatch's pictures. **OK opens Peacock, not the episode**: Peacock's Fire TV app accepts no links at all. |
| Hulu (`com.hulu.plus`) | **Nothing readable.** Four keys (title, artist, album, album art) with nothing in them, and besides that only "playing" and a position. Its screen has no readable text either. | One card for the app itself ("Continue in Hulu"). OK opens Hulu. Nothing more is possible from outside the app. |
| YouTube (`com.amazon.firetv.youtube`) | An empty title while playing. | Left out (it is on the `exclude` list). |

## What it needs: one permission, given once

Android only lets an app see other apps' media sessions if it is allowed as a "notification
listener". So the app declares one, called `MediaAccess`, and **it reads no notifications**: it
has no code that looks at one. It exists only because that is the door Android put in front of
media sessions.

Nothing is listened to until you allow it. From a computer, once (it survives restarts and
updates):

```
adb shell cmd notification allow_listener dev.couchtv.ontv/dev.couchtv.ontv.MediaAccess
```

To take it back:

```
adb shell cmd notification disallow_listener dev.couchtv.ontv/dev.couchtv.ontv.MediaAccess
```

Some Google TVs also have a screen for it (Settings, Apps, Special app access, Notification
access); Fire TVs, as far as we know, do not. The command is Android's own. On the Fire TV above
(Android 9) it took effect at once. It is not in Android 7.1 as far as we know, so a Fire OS 6
stick may have no way to allow this; that has not been tried.

Whether it is allowed shows on the phone's setup page (`http://<tv-ip>:5400/setup`, **Keep
watching**) and in `GET /api/continue` (`watching.access`). Taking the permission back stops the
listening at once; what was already logged stays until you forget it (below).

## What shows

- **Which apps.** Every app that publishes a media session, except couch-tv itself and the ones
  left out because they are not "shows": music and voice (Spotify, Amazon Music, Alexa's players),
  YouTube, and live TV (DirecTV, YouTube TV). The list is the `exclude` setting, below.
- **What counts as watched.** A title that has been in the playing state for 2 minutes in all.
  If the app says how long the thing is, it must also be at least 5 minutes long. That is what
  keeps out the trailers and previews an app plays by itself on its own home screen. A title that
  says it is a preview (the show's name with "(Trailer)" after it) is never a card, however long
  it plays.
- **Finished.** Once you are 95% of the way through, the title leaves the row.
- **One card per app and title.** A new episode of the same series takes the place of the last
  one. Season and episode are read from the words the app publishes, in the forms "S1 E2",
  "S1:E2", "Season 1 Episode 2", "1x02" and "E3 · Name". When the app does not say, they are left
  empty rather than guessed.
- **An app that names only the episode** (Peacock). On its own the card can only say the
  episode's name, or its date for a show that is on every day. With looking titles up switched on
  (below), the show is worked out and the card becomes the show's: its name, the season and
  episode, and its picture, with the episode's name (or the date) underneath. The next episode of
  the same show then takes that card over.
- **An app that plays and names nothing** (Hulu). After the same 2 minutes of playing it gets one
  card for the app itself, "Continue in Hulu", with the app's icon. It goes as soon as that app
  has a card with a real title.
- **How far in.** From the position the app last reported. An app that reports no position, or
  no length, gets a card with no progress bar.
- **How many.** The 20 newest in the row. 40 titles are kept, and one not played for 60 days is
  dropped.
- **For everyone.** The TV cannot tell who is holding the remote, so these cards show for every
  person on the home.
- **The picture.** The app's own, when its session carries one: the app keeps a small copy (JPEG,
  640 px wide at most). When the app publishes an `https` address for its picture instead, the
  home shows that. With looking up on, JustWatch's picture. With none of those, the app's icon.
- **OK on a card** opens the app it was watched in, and the app's own "continue watching" takes it
  from there. It opens the title itself only when all of this holds: looking up is on, JustWatch
  has a link for the title in that app, and the app on your TV says it accepts that link (Android
  is asked before anything is sent). A link the app does not accept is not sent at all, to that
  app, a browser or anything else. couch-tv never makes up a link from what a session says.
  **On a Fire TV, OK on a Peacock card opens Peacock, not the episode:** the Fire TV app accepts
  no links (seen 16 November 2026; Fire TV apps are opened on a title by Amazon's own home screen,
  in a way no other app can use).

Cards from couch-tv's own search still show, after these. A title that is in both shows once.

## What stays on the TV

All of it, unless you switch on looking titles up. In the app's private storage:

| File | What |
|---|---|
| `watching.json` | The log: per title its app, episode, position, length, when it was last played; and the names of previews seen in the last week. |
| `watching-art/` | The copies of the apps' own pictures. |
| `watching-seen.json` | The last 60 raw looks at media sessions, for `GET /api/continue/log`. |
| `watching-shows.json` | Only with looking up on: JustWatch's episode lists for the shows that were looked into (8 at most, each for a day), and which show it gave for each preview's name (for a week). |
| `watching-tvmaze.json` | Only with looking up on: which show TVmaze gave for a name (for a week), and whether a show was on on a date that was asked about. |

Nothing here is sent to a computer, to the phone, or to anyone else, except as an answer to a
paired phone or the TV's own home asking for the row. Uninstalling the app removes it all.

**Forgetting.** The setup page's **Forget what was watched** empties all five. One card:
`POST /api/continue/forget {"id": "w-..."}`.

## Looking titles up (off unless you switch it on)

With `lookup` on, the TV asks JustWatch (the same lookup couch-tv's search uses) about what was
watched, and for one thing TVmaze. Two things come of it:

**A picture.** Many apps publish a title and no picture. Such a title is looked up under its own
name for a poster and a backdrop. Only the very title counts: the name must be the same (capitals
and punctuation aside, not merely alike), and when both lengths are known, what is found must be
within a fifth of the length the app gave. A 21 minute episode is not a 2 hour film of the same
name. A title that is a date is never looked up this way: whatever is found under a date is
something else.

**The show, when the app names only the episode.** An episode's name alone, or its date, is not
enough to find it. So the shows it may belong to are tried, in this order:

1. The previews the same app played in the half hour before the episode came up, the newest
   first, three at most. On Peacock the show's page plays one, named after the show, seconds
   before the episode starts.
2. The other previews that app has played in the last week, the newest first, eight at one go. A
   show's name does not only pass by just before. A preview is anything the app named that was
   under 5 minutes long, with "(Trailer)" after the name or without.
3. The shows already worked out in that app. For an episode's name: the next episode of something
   you are watching (only the three episodes after the last one watched count, because a name
   alone proves little). For a date: a show already found by a date, which is on every day,
   whatever the date.

A name alone never makes the card. Each show tried has to be confirmed:

- **An episode's name:** JustWatch is searched for the show and its episode list is read, and the
  list is searched for an episode with the same name (capitals and punctuation aside) whose
  length agrees with what the app said, within 3 minutes. If a name is used for several episodes
  of the show (a look back at the season, once a season), the card says the show and the
  episode's name and leaves the season and episode out rather than guess. For a show from the
  second group, whose preview only passed by some time that week, more is asked: exactly one
  episode of that name, with both lengths known and agreeing.
- **A date:** JustWatch lists a daily show's episodes under other names (the guests'), so a date
  cannot be found there. **TVmaze** (`api.tvmaze.com`) is asked instead whether that show was on
  on that day. It must have been, the length must be an episode's (from half the slot TVmaze
  gives, since there are no adverts, to a little over it), and unless the preview came just
  before the title, the show must be one that is on at least four days a week: any weekly show
  was on on some date. The season and episode numbers on the card are then TVmaze's; the show's
  name and pictures are JustWatch's as for any card.

These are tried before the title's own name, because an episode's name is often also the name of
some film.

A title that is already in the log without its show is worked out later in one of these ways:
when a preview with a name that is new comes up in that app (even days later: the title is then
asked about again at once, not once a day); from the raw looks still kept on the TV, if a
preview's line is among them when the app is updated; or, for the next episode of a show already
worked out, by itself. When the app is updated, a picture found by an older and looser rule is
dropped, and the title is looked up afresh.

- **What that sends,** from the TV, with nothing about the app, how far in you are, or who is
  watching:
  - to JustWatch (`apis.justwatch.com`), with your country code: the title's name; the names of
    the previews tried for it (shows whose pages you had open or that the app's home played, not
    only the one you watched); and, for an episode list, JustWatch's own number for the show;
  - to TVmaze (`api.tvmaze.com`), only for a title that is a date: the names of the shows tried
    for it, and then TVmaze's number for the show with the date.

  The pictures found are then loaded from JustWatch's picture server (`images.justwatch.com`) by
  whatever shows the row: the TV's home, or a phone. So this does tell both services what someone
  at your internet address looked up, which is why it is off until you say so.
- **TVmaze's data is licensed CC BY-SA** (tvmaze.com/api: free to use for any purpose with TVmaze
  credited as the source, and share-alike). What is taken from it is whether a show was on on a
  day, and that episode's season and number. Source: TVmaze.
- **How much.** A JustWatch search is up to about 60 KB (5 KB as sent); a preview's name is
  searched for once a week. A show's episode list is asked for without the offers, once a day at
  most for a show: 36 KB (7 KB as sent) for a show of 17 seasons and 381 episodes, where the same
  list with offers was 2.3 MB, and 321 KB for a daily show of nearly 3,000 episodes. A list over
  512 KB is not read.
  OK on a card of a worked-out episode asks for that one episode's offers, 14 KB. TVmaze's
  answers are 1.5 KB for a show (once a week for a name) and under 1 KB for a day (once for a
  show and a day).
- A show that was tried and did not fit, or could not be asked about, is not tried again for a
  day. What is found is kept with the title.
- A match in the app the title was watched in is preferred.
- Switching it on works on the titles already in the log, with no restart. Switching it off stops
  the asking and stops the looked-up pictures being shown; a show's name already worked out
  stays.
- A title whose app gave its own picture is not looked up for one.

JustWatch answers for the country set on the setup page (**Country**). If that is not where you
are, titles will not be found.

## Settings

On the phone's setup page (**Keep watching**), or `watching` in the on-TV app's
`GET|POST /api/settings` ([CONFIG.md](CONFIG.md), "Keep watching"):

| Key [default] | What it is |
|---|---|
| `lookup` [`false`] | Ask JustWatch (and, for a title that is a date, TVmaze) about what was watched: pictures for titles whose app gives none, and the show when an app names only the episode. See above for what it sends. |
| `exclude` [the music, YouTube and live TV apps] | Android package names whose sessions are ignored. Sending a list replaces the whole list, so to add one app, send the old list with it added. Cards from an app you add stop showing. |

## Seeing what each app reports

`GET /api/continue/log` gives the last 60 raw looks, newest first. For each: the app, the playback
state, the position, **every metadata key the app published, with the Java class of its value**
(`"null"` for a key with nothing in it, which is how Hulu's case was settled), the value itself
for words and numbers (a picture shows as its size only), what else the session offers (its
queue's name and first items, the names and kinds of its extras, whether it has a screen of its
own), and what the rules decided and why (`counted`, `not yet`, `too short`, `preview`,
`finished`, `no title`, `app`, `excluded`). A phone that is paired with the TV can open
`http://<tv-ip>:5400/api/continue/log` in its browser. Play something for a few minutes in an app,
then look: if the app's line shows a title, it will work; if the app has no line at all, it
publishes no media session and cannot be followed. The shape is in
[bridge/API.md](../bridge/API.md).

That log holds the titles of what was watched, so treat it as private; it stays on the TV and is
emptied by **Forget what was watched**.

## Limits

- **An app that publishes no session cannot be followed at all**, and one that publishes no
  title gets only the card for the app. Nothing can be done about that from outside the app.
- **An app's words are taken as they are.** Where the show cannot be worked out, a card says the
  episode's name. The raw looks show what the app did.
- **Working out the show rests on the previews**, so far seen in Peacock only. An episode started
  straight from the app's own "continue watching" row has no preview before it. It stays under
  the episode's name (or its date) until that show's preview passes by in the app, or unless it
  is the next episode of a show already worked out.
- **A title that is a date is taken for a daily show's episode.** A film whose whole name is a
  date would stay a card with that date and no picture.
- **Every preview's name is a name to try**, so with looking up on, the names of shows the app
  merely advertised on its home screen are sent to JustWatch too (and to TVmaze, when a title
  that is a date is waiting for its show).
- **Opening the very episode needs the app to accept links**, and on a Fire TV many do not.
- **Only while the on-TV app is running.** It starts when the TV does. If Android stops it while
  another app plays, that stretch is not counted.
- **Positions are the app's own claim**, read when it changes and once a minute while playing, so
  a card can be up to a minute behind.
- **Live TV, YouTube and music are left out on purpose.** Take a package out of `exclude` to try
  one.
- **The older computer bridge (option B) does not have this.** Its Keep watching is unchanged.
