# Keep watching, from what was really watched on the TV

The home's **Keep watching** row used to know only what was started through couch-tv's own search
("put on Bluey"). With the on-TV app (option C) it can also show what you watched by opening
Netflix, Hulu or any other app with the TV's own remote: the title, the episode when the app says
it, how far in you are, and the app's own picture.

Netflix and the others tell couch-tv nothing, and their own "continue watching" lists cannot be
read. What Android does offer, to an app the TV's owner has allowed, is each app's **media
session**: the few words an app publishes about what it is playing (the same ones a soundbar's
display or a car screen would show). The on-TV app listens to those and keeps its own short log.

**This has been built and tested on a computer only. It has not yet run on a TV**, so nobody
knows yet what each app really publishes: some may give a title and nothing else, some no session
at all. "Seeing what each app reports", below, is how to find out.

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
access); Fire TVs, as far as we know, do not. The command is Android's own. It is not in Android
7.1 as far as we know, so a Fire OS 6 stick may have no way to allow this; that has not been
tried.

Whether it is allowed shows on the phone's setup page (`http://<tv-ip>:5400/setup`, **Keep
watching**) and in `GET /api/continue` (`watching.access`). Taking the permission back stops the
listening at once; what was already logged stays until you forget it (below).

## What shows

- **Which apps.** Every app that publishes a media session, except couch-tv itself and the ones
  left out because they are not "shows": music and voice (Spotify, Amazon Music, Alexa's players),
  YouTube, and live TV (DirecTV, YouTube TV). The list is the `exclude` setting, below.
- **What counts as watched.** A title that has been in the playing state for 2 minutes in all.
  If the app says how long the thing is, it must also be at least 5 minutes long. That is what
  keeps out the trailers and previews an app plays by itself on its own home screen.
- **Finished.** Once you are 95% of the way through, the title leaves the row.
- **One card per app and title.** A new episode of the same series takes the place of the last
  one. Season and episode are read from the words the app publishes, in the forms "S1 E2",
  "S1:E2", "Season 1 Episode 2", "1x02" and "E3 · Name". When the app does not say, they are left
  empty rather than guessed.
- **How far in.** From the position the app last reported. An app that reports no position, or
  no length, gets a card with no progress bar.
- **How many.** The 20 newest in the row. 40 titles are kept, and one not played for 60 days is
  dropped.
- **For everyone.** The TV cannot tell who is holding the remote, so these cards show for every
  person on the home.
- **The picture.** The app's own, when its session carries one: the app keeps a small copy (JPEG,
  640 px wide at most). When the app publishes an `https` address for its picture instead, the
  home shows that. Otherwise the card has the title in words, unless you switch on looking titles
  up (below).
- **OK on a card** opens the app it was watched in, and the app's own "continue watching" takes it
  from there. couch-tv does not make up a link into an app from what a session says. (With looking
  up switched on, and the title found in that app, it opens the title itself.)

Cards from couch-tv's own search still show, after these. A title that is in both shows once.

## What stays on the TV

All of it, unless you switch on looking titles up. In the app's private storage:

| File | What |
|---|---|
| `watching.json` | The log: per title its app, episode, position, length, when it was last played. |
| `watching-art/` | The copies of the apps' own pictures. |
| `watching-seen.json` | The last 60 raw looks at media sessions, for `GET /api/continue/log`. |

Nothing here is sent to a computer, to the phone, or to anyone else, except as an answer to a
paired phone or the TV's own home asking for the row. Uninstalling the app removes it all.

**Forgetting.** The setup page's **Forget what was watched** empties the log, the pictures and the
raw looks. One card: `POST /api/continue/forget {"id": "w-..."}`.

## Looking titles up (off unless you switch it on)

Many apps publish a title and no picture. With `lookup` on, the TV asks JustWatch (the same
lookup couch-tv's search uses) for a poster and a backdrop for such a title.

- **What that sends:** the title's name, and your country code, to JustWatch
  (`apis.justwatch.com`), from the TV. Not the app, not the episode, not how far in you are, and
  nothing about who is watching. The pictures it finds are then loaded from JustWatch's picture
  server (`images.justwatch.com`) by whatever shows the row: the TV's home, or a phone. So it does
  tell JustWatch that someone at your internet address looked that title up, which is why it is
  off until you say so.
- Each title is asked for once. What is found is kept with the title; if nothing is found, or
  JustWatch does not answer, it is not tried again for a day.
- A match in the app the title was watched in is preferred. With such a match, OK on the card
  opens the title in that app the way couch-tv's search would, through the link JustWatch gives
  for it (each OK asks JustWatch again, with the title's name). Without one, or if that fails, OK
  opens the app as before. Only the apps couch-tv's search knows can be opened this way.
- Switching it on fills in the pictures for titles already in the log, with no restart. Switching
  it off stops the asking and stops the looked-up pictures being shown.
- A title whose app gave its own picture is never looked up.

JustWatch answers for the country set on the setup page (**Country**). If that is not where you
are, titles will not be found.

## Settings

On the phone's setup page (**Keep watching**), or `watching` in the on-TV app's
`GET|POST /api/settings` ([CONFIG.md](CONFIG.md), "Keep watching"):

| Key [default] | What it is |
|---|---|
| `lookup` [`false`] | Look up pictures (and a link) for titles whose app gives no picture. See above for what it sends. |
| `exclude` [the music, YouTube and live TV apps] | Android package names whose sessions are ignored. Sending a list replaces the whole list, so to add one app, send the old list with it added. Cards from an app you add stop showing. |

## Seeing what each app reports

`GET /api/continue/log` gives the last 60 raw looks, newest first: for each, the app, the playback
state, the position, **every metadata key the app published with its kind and value** (a picture
shows as its size only), and what the rules decided and why (`counted`, `not yet`, `too short`,
`finished`, `no title`, `excluded`). A phone that is paired with the TV can open
`http://<tv-ip>:5400/api/continue/log` in its browser. Play something for a few minutes in an app,
then look: if the app's line shows a title, it will work; if the app has no line at all, it
publishes no media session and cannot be followed. The shape is in
[bridge/API.md](../bridge/API.md).

That log holds the titles of what was watched, so treat it as private; it stays on the TV and is
emptied by **Forget what was watched**.

## Limits

- **An app that publishes no session, or no title, cannot be followed.** Nothing can be done
  about that from outside the app.
- **An app's words are taken as they are.** If an app puts the episode's name where the show's
  should be, the card says that. The raw looks show what it did.
- **Only while the on-TV app is running.** It starts when the TV does. If Android stops it while
  another app plays, that stretch is not counted.
- **Positions are the app's own claim**, read when it changes and once a minute while playing, so
  a card can be up to a minute behind.
- **Live TV, YouTube and music are left out on purpose.** Take a package out of `exclude` to try
  one.
- **The older computer bridge (option B) does not have this.** Its Keep watching is unchanged.
