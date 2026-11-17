# Spotify "now playing" on the home screen

On the landscape home (`home.mode` "solar"), a glass card shows what Spotify is playing on your
account, on any device: your phone, a speaker, or the TV's own Spotify app. Nothing playing, no
card. It works with the on-TV app (option C).

- **Small or large.** It pops in at the size you last used: small at the top left, or large in
  the middle (the big clock steps aside). The round button on the card switches.
- **With the TV remote.** Up from the app bar lands on pause/play; left and right reach previous,
  next and the size button; down goes back to the apps. A green shuffle mark shows beside the
  controls while shuffle is on in Spotify. A title too long for its line slides along and back so
  all of it can be read. Up again selects the whole card, and OK
  there opens the TV's Spotify app. Hold OK for 1.2 seconds anywhere on the card to stop the music
  and put the card away until something plays again (or press Up on the app bar: the last song
  comes back, paused, ready to play). The remote's play/pause, rewind (previous)
  and fast-forward (next) keys work whenever the card is showing.
- **Paused.** The card stays for 30 seconds with a play button, then leaves. It comes back when
  the music does, or when you press Up on the app bar.
- **Nothing playing anywhere.** Up on the app bar still brings the card up, with the last song it
  saw. Play resumes it if Spotify can. With nothing to resume, it starts the TV's own Spotify
  (where it left off) when that is possible, and otherwise opens the TV's Spotify app so you can
  pick something.

## What it needs

- **Spotify Premium** on the account. Spotify's rules (2026): the owner of a developer app needs
  Premium for the app to work at all, and pause, play, next and previous only work for Premium.
- **Your own Spotify developer app.** It is free and takes five minutes. couch-tv has no keys of
  its own: yours stay on your computer and your TV.
- **A sign-in once every 6 months.** Spotify ends every sign-in 6 months after it was made. When
  that happens the card stops showing; run the sign-in again.

## 1. Make the developer app (once)

1. Open <https://developer.spotify.com/dashboard> and log in with the Spotify account that has
   Premium.
2. Press **Create app**.
3. **App name:** `Couch TV`. **App description:** anything, for example `Now playing on my TV`.
   **Website:** leave empty.
4. **Redirect URIs:** type exactly `http://127.0.0.1:8898/callback` and press **Add**. (Not
   `localhost`: Spotify no longer accepts that word.)
5. **Which API/SDKs are you planning to use?** Tick **Web API**.
6. Tick the box that agrees to Spotify's developer terms, and press **Save**.
7. On the app's page, open **Settings**. Copy the **Client ID**. Press **View client secret** and
   copy the **Client secret**.
8. Put both in the file `.env` in the couch-tv folder (make the file if it is not there; git
   ignores it):

   ```
   SPOTIFY_CLIENT_ID=<the client id>
   SPOTIFY_CLIENT_SECRET=<the client secret>
   ```

Already have a developer app? You can use it instead: its **Settings**, **Edit**, add the
Redirect URI above, **Save**. If saving fails, remove any old `http://localhost...` address from
the list first.

Someone else's Spotify account (not the one that owns the app)? Add their Spotify email under
**Settings**, **User Management** first, or Spotify answers 403. Up to 5 people.

## 2. Sign in (once, then every 6 months)

On the computer, in the couch-tv folder:

```sh
python3 scripts/spotify-auth.py
```

Your browser opens Spotify. Log in if it asks and press **Agree**. The script keeps the sign-in
in `.env` and says what is playing right now, as a check. It asks for three permissions: read
what is playing (`user-read-playback-state`, `user-read-currently-playing`) and control playback
(`user-modify-playback-state`). `python3 scripts/spotify-auth.py --check` asks again later
without a new sign-in.

## More than one person

The TV's own Spotify app is often logged in as someone other than the person who set this up, and
then the card cannot see what the TV itself plays. So sign in every Spotify account of the house:

1. In the developer app, **Settings**, **User Management**: add the other person's name and the
   email of their Spotify account (not needed for the account that owns the developer app).
2. Run `python3 scripts/spotify-auth.py` again. On Spotify's page press **Not you?**, log in as
   the other person, and press **Agree**.
3. Hand the keys to the TV again (below).

The card shows whichever account is playing; if two are, the one playing on a TV. The buttons
control the account that is showing. `python3 scripts/spotify-auth.py --check` lists every
signed-in account and what it is playing.

## 3. Give the keys to the TV

```sh
python3 scripts/spotify-auth.py --tv <tv address>
```

The first time, this computer has to be paired with the TV like a phone: add
`--pair <the 6 digit code on the TV's Couch TV screen>`. The TV keeps the three keys in the
app's private storage. The home page never sees them: it only asks the app "what is playing?".

To take it off again: remove the Couch TV app's access under **Apps** in your Spotify account
(spotify.com/account/apps), or delete the developer app.

## Try it without Spotify

Add `?np=mock` to a preview of the home (with `?mock=1`): a made-up playlist plays, with no
account and no bridge. `P` plays or pauses "from the phone". `&npaway=5` makes a paused card
leave after 5 seconds instead of 30.

## Good to know

- **How often Spotify is asked.** Every 3 seconds while music plays and every 10 seconds
  otherwise, and only while the home screen is in front. Spotify has an allowance per developer
  account that it does not publish. If it is used up, the card pauses its questions for as long
  as Spotify says and carries on by itself.
- **Shuffle is shown, not switched.** The green mark follows Spotify. Switch shuffle in the Spotify
  app itself: Spotify will not change it from outside for a TV's own Spotify app (it accepts the
  request and ignores it).
- **Not every device takes remote control.** Some speakers refuse pause and skip from outside;
  the card then shows Spotify's answer for a moment.
- **Podcasts** show with the show's name where the artist would be.
- **A slow TV.** If the card stutters when it changes size, set `home.np_morph` in `config.json`
  to `"flip"` (moves without re-laying-out) or `"fade"` (a plain crossfade).
- **The computer bridge (option B)** does not have this yet; there the card stays away.
