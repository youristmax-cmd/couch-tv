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

**On one TV** (a Fire TV on Fire OS 7), **16 November 2026.** Other TVs, other versions of these
apps and every app not listed here are still to be seen; "Seeing what each app reports", below,
is how.

| App | What its session says | What the row makes of it |
|---|---|---|
| Peacock (`com.peacock.peacockfiretv`) | The **episode's own name** as the title, how long it is and how far in. No show name, no season or episode number, no picture, no id. On a show's page it plays a preview whose title is the show's name with "(Trailer)" after it. Now and then, while an episode plays, the session is empty for a moment. | Without looking up: a card with the episode's name and the app's icon. With looking up on: the show (worked out from the preview just before, below), its season and episode and JustWatch's pictures. **OK opens Peacock, not the episode**: Peacock's Fire TV app accepts no links at all. |
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
  says it is a preview (the show's name with "(Trailer)" after it) is never a card, however long it plays.
- **Finished.** Once you are 95% of the way through, the title leaves the row.
- **One card per app and title.** A new episode of the same series takes the place of the last
  one. Season and episode are read from the words the app publishes, in the forms "S1 E2",
  "S1:E2", "Season 1 Episode 2", "1x02" and "E3 · Name". When the app does not say, they are left
  empty rather than guessed.
- **An app that names only the episode** (Peacock). On its own the card can only say the
  episode's name. With looking titles up switched on (below), the show is worked out and the card
  becomes the show's: its name, the season and episode, and its picture, with the episode's name
  underneath. The next episode of the same show then takes that card over.
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
| `watching-shows.json` | Only with looking up on: JustWatch's episode lists for the shows that were looked into (8 at most, each for a day). |

Nothing here is sent to a computer, to the phone, or to anyone else, except as an answer to a
paired phone or the TV's own home asking for the row. Uninstalling the app removes it all.

**Forgetting.** The setup page's **Forget what was watched** empties all four. One card:
`POST /api/continue/forget {"id": "w-..."}`.

## Looking titles up (off unless you switch it on)

With `lookup` on, the TV asks JustWatch (the same lookup couch-tv's search uses) about what was
watched. Two things come of it:

**A picture.** Many apps publish a title and no picture. Such a title is looked up under its own
name for a poster and a backdrop.

**The show, when the app names only the episode.** An episode's name alone is not enough to find
it. So the shows it may belong to are tried, in this order:

1. The previews the same app played in the half hour before the episode came up, the newest
   first, three at most. On Peacock the show's page plays one, named after the show, seconds
   before the episode starts.
2. The shows already worked out in that app, for the next episode of something you are watching.
   Here only the three episodes after the last one watched count, because a name alone proves
   little (many shows have an episode with the same plain name).

For each, the show is searched for and its episode list is read, and the list is searched for an
episode with the same name (capitals and punctuation aside) whose length agrees with what the app
said, within 3 minutes. A fit makes the card the show's. If a name is used for several episodes
of the show (a look back at the season, once a season), the card says the show and the episode's name and
leaves the season and episode out rather than guess. These are tried before the title's own name,
because an episode's name is often also the name of some film.

A title that is already in the log under its episode's name is worked out later in one of three
ways: from the raw looks still kept on the TV, if the preview's line is among them (that is how a
title logged before any of this was built finds its show when the app is updated); when its
show's page is next opened and it is played again; or, for the next episode of a show already
worked out, by itself.

- **What that sends,** from the TV to JustWatch (`apis.justwatch.com`), with your country code:
  the title's name; the names of the previews tried for it (shows whose pages you had open, not
  only the one you played); and, for an episode list, JustWatch's own number for the show. Not
  the app, not how far in you are, and nothing about who is watching. The pictures it finds are
  then loaded from JustWatch's picture server (`images.justwatch.com`) by whatever shows the row:
  the TV's home, or a phone. So it does tell JustWatch what someone at your internet address
  looked up, which is why it is off until you say so.
- **How much.** A search is up to about 60 KB (5 KB as sent). A show's episode list is asked for
  without the offers, once a day at most for a show: 36 KB (7 KB as sent) for a show of 17
  seasons and 381 episodes, where the same list with offers was 2.3 MB. A list over 512 KB is not
  read. OK on a card of a worked-out episode asks for that one episode's offers, 14 KB.
- Each thing is asked once. What is found is kept with the title; what was not found, or could
  not be asked, is not tried again for a day.
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
| `lookup` [`false`] | Ask JustWatch about what was watched: pictures for titles whose app gives none, and the show when an app names only the episode. See above for what it sends. |
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
- **Working out the show rests on the preview**, so far seen in Peacock only. An episode started
  straight from the app's own "continue watching" row, with no show page in between, has no
  preview before it: it stays under the episode's name unless it is the next episode of a show
  already worked out.
- **Opening the very episode needs the app to accept links**, and on a Fire TV many do not.
- **Only while the on-TV app is running.** It starts when the TV does. If Android stops it while
  another app plays, that stretch is not counted.
- **Positions are the app's own claim**, read when it changes and once a minute while playing, so
  a card can be up to a minute behind.
- **Live TV, YouTube and music are left out on purpose.** Take a package out of `exclude` to try
  one.
- **The older computer bridge (option B) does not have this.** Its Keep watching is unchanged.
