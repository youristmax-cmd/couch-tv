#!/usr/bin/env python3
"""Sign couch-tv in to Spotify for the home screen's "now playing" card.

Run it on a computer with a browser. It needs the two keys of your own Spotify developer app
(docs/SPOTIFY.md says how to make one) in the repo's .env file, which git ignores:

    SPOTIFY_CLIENT_ID=...
    SPOTIFY_CLIENT_SECRET=...

  python3 scripts/spotify-auth.py                  sign one Spotify account in (opens the browser), keep
                                                   the result in .env, and say what is playing as a check
  python3 scripts/spotify-auth.py --no-browser     the same, but only print the address to open
  python3 scripts/spotify-auth.py --check          no sign-in: say what each signed-in account is playing
  python3 scripts/spotify-auth.py --tv <tv address>     hand the keys in .env to the TV (on-TV app).
        Needs this computer paired with the TV once: add --pair <the 6 digit code on the TV's
        Couch TV screen>, or have a token already in ~/.couchtv-token.

More than one person? Run the sign-in once per Spotify account (on Spotify's page, "Not you?"
switches account). The card shows whichever account is playing. Signing the same account in again
renews it. Each account other than the developer app's owner must first be added, by its Spotify
email, under User Management in the developer app.

Spotify sends the browser back to http://127.0.0.1:8898/callback, which must be listed, exactly so,
under Redirect URIs in the developer app. A sign-in lasts 6 months (Spotify's rule); after that,
run this again. Nothing secret is printed, and nothing is sent anywhere except to Spotify and,
with --tv, to your own TV on your own network. Python 3.8 or newer, no packages.
"""
import argparse, base64, http.server, json, os, secrets, sys, time, urllib.error, urllib.parse, urllib.request, webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = os.path.join(ROOT, ".env")
PORT = 8898
REDIRECT = "http://127.0.0.1:%d/callback" % PORT
SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state"


def read_env():
    out = {}
    if os.path.exists(ENV):
        for line in open(ENV, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def write_env(values):
    """Set these keys in .env, keeping every other line as it is. The file is for the owner only."""
    lines = open(ENV, encoding="utf-8").read().splitlines() if os.path.exists(ENV) else []
    left = dict(values)
    for i, line in enumerate(lines):
        k = line.split("=", 1)[0].strip()
        if k in left:
            lines[i] = "%s=%s" % (k, left.pop(k))
    lines += ["%s=%s" % kv for kv in left.items()]
    old = os.umask(0o077)
    try:
        with open(ENV, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    finally:
        os.umask(old)
    os.chmod(ENV, 0o600)


# One sign-in per Spotify account: the first in SPOTIFY_REFRESH_TOKEN, the next in
# SPOTIFY_REFRESH_TOKEN_2, and so on, each with _AUTHORIZED_AT, _ACCOUNT (the name) and _ACCOUNT_ID.
def key(base, n):
    return base if n == 1 else "%s_%d" % (base, n)


def slots(env):
    out = []
    for n in range(1, 10):
        if env.get(key("SPOTIFY_REFRESH_TOKEN", n)):
            out.append({"n": n, "refresh": env[key("SPOTIFY_REFRESH_TOKEN", n)], "at": int(env.get(key("SPOTIFY_AUTHORIZED_AT", n)) or 0),
                        "name": env.get(key("SPOTIFY_ACCOUNT", n)) or "account %d" % n, "id": env.get(key("SPOTIFY_ACCOUNT_ID", n)) or ""})
    return out


def call(method, url, token=None, form=None, basic=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if basic:
        headers["Authorization"] = "Basic " + basic
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode(); headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read(); st = r.status
    except urllib.error.HTTPError as e:
        body = e.read(); st = e.code
    try:
        return st, (json.loads(body) if body else {})
    except ValueError:
        return st, {}


def basic_of(env):
    return base64.b64encode(("%s:%s" % (env["SPOTIFY_CLIENT_ID"], env["SPOTIFY_CLIENT_SECRET"])).encode()).decode()


def need_keys(env):
    if not env.get("SPOTIFY_CLIENT_ID") or not env.get("SPOTIFY_CLIENT_SECRET"):
        sys.exit("Put SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET in %s first (docs/SPOTIFY.md says where they come from)." % ENV)


def sign_in(env, no_browser=False):
    """Authorization Code flow: the browser asks you, Spotify sends a code back here, the code buys the tokens."""
    state = secrets.token_urlsafe(16)
    got = {}

    class Back(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            q = urllib.parse.parse_qs(u.query)
            if u.path != "/callback":
                self.send_response(404); self.end_headers(); return
            ok = q.get("state", [""])[0] == state and "code" in q
            got["code"] = q.get("code", [None])[0] if ok else None
            got["error"] = q.get("error", ["the answer did not match this sign-in"])[0] if not ok else None
            text = "Couch TV is signed in to Spotify. You can close this tab." if ok else "Spotify did not sign in: %s. Go back to the terminal." % got["error"]
            body = ("<!doctype html><meta charset=utf-8><title>Couch TV</title><body style='font:20px system-ui;margin:4rem'>%s</body>" % text).encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)

    try:
        srv = http.server.HTTPServer(("127.0.0.1", PORT), Back)
    except OSError:
        sys.exit("Port %d on this computer is busy. Close what is using it and run this again." % PORT)
    # show_dialog: Spotify always shows who is signing in, with "Not you?" to switch to another account
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": env["SPOTIFY_CLIENT_ID"], "scope": SCOPES, "redirect_uri": REDIRECT, "state": state, "show_dialog": "true"})
    if no_browser:
        print("Open this in a browser on this computer, check the account it names (Not you? switches), then press Agree:\n  " + url, flush=True)
    else:
        print("Opening Spotify in your browser. Check the account it names (Not you? switches), then press Agree.")
        print("If no browser opens, paste this into one:\n  " + url, flush=True)
        webbrowser.open(url)
    srv.timeout = 1
    deadline = time.time() + 600
    while "code" not in got and time.time() < deadline:
        srv.handle_request()
    srv.server_close()
    if not got.get("code"):
        sys.exit("Spotify did not sign in: %s." % (got.get("error") or "nobody answered within 10 minutes"))
    st, j = call("POST", "https://accounts.spotify.com/api/token", basic=basic_of(env),
                 form={"grant_type": "authorization_code", "code": got["code"], "redirect_uri": REDIRECT})
    if st != 200 or not j.get("refresh_token"):
        sys.exit("Spotify refused the sign-in (HTTP %s: %s). Check the client secret in .env and the Redirect URI in the developer app." % (st, j.get("error_description") or j.get("error") or "no reason given"))
    # whose sign-in is it? The same account again renews its place; a new account takes the next one.
    st2, me = call("GET", "https://api.spotify.com/v1/me", token=j["access_token"])
    if st2 == 403:
        sys.exit("Spotify signed in, but this account may not use the developer app yet (403). In the developer app, User Management, add this account's Spotify email, then run this again.")
    uid = me.get("id") or ""
    name = me.get("display_name") or uid or "Spotify account"
    have = slots(env)
    same = [s for s in have if uid and s["id"] == uid]
    n = same[0]["n"] if same else next(i for i in range(1, 10) if i not in [s["n"] for s in have])
    write_env({key("SPOTIFY_REFRESH_TOKEN", n): j["refresh_token"], key("SPOTIFY_AUTHORIZED_AT", n): str(int(time.time() * 1000)),
               key("SPOTIFY_ACCOUNT", n): name, key("SPOTIFY_ACCOUNT_ID", n): uid})
    print("Signed in as %s (%s). Kept in .env (git ignores it); it lasts 6 months." % (name, "renewed" if same else "account %d" % n))
    return name, j["access_token"]


def access_token(env, slot):
    st, j = call("POST", "https://accounts.spotify.com/api/token", basic=basic_of(env), form={"grant_type": "refresh_token", "refresh_token": slot["refresh"]})
    if st == 400 and j.get("error") == "invalid_grant":
        print("%s: the sign-in has run out (it lasts 6 months) or was removed in the Spotify account. Sign that account in again." % slot["name"])
        return None
    if st != 200:
        print("%s: Spotify did not accept the keys (HTTP %s)." % (slot["name"], st))
        return None
    if j.get("refresh_token") and j["refresh_token"] != slot["refresh"]:
        write_env({key("SPOTIFY_REFRESH_TOKEN", slot["n"]): j["refresh_token"]})   # Spotify handed out a new one: that is the one to keep
    return j["access_token"]


def name_them(env):
    """A sign-in kept by an earlier version of this script has no name yet: ask Spotify whose it is."""
    changed = False
    for s in slots(env):
        if s["id"]:
            continue
        tok = access_token(env, s)
        st, me = call("GET", "https://api.spotify.com/v1/me", token=tok) if tok else (0, {})
        if st == 200 and me.get("id"):
            write_env({key("SPOTIFY_ACCOUNT", s["n"]): me.get("display_name") or me["id"], key("SPOTIFY_ACCOUNT_ID", s["n"]): me["id"]})
            changed = True
    return read_env() if changed else env


def check(name, token):
    """What the card will see for one account: one look at its playback, on any device."""
    st, j = call("GET", "https://api.spotify.com/v1/me/player?additional_types=episode", token=token)
    if st == 204 or (st == 200 and not j):
        print("%s: Spotify answers. Nothing is playing on this account right now." % name)
    elif st == 200:
        it = j.get("item") or {}
        who = ", ".join(a.get("name", "") for a in it.get("artists", [])) or (it.get("show") or {}).get("name", "")
        print("%s: Spotify answers. %s: %s by %s, on %s." % (name, "Playing" if j.get("is_playing") else "Paused", it.get("name", "(no title)"), who or "?", (j.get("device") or {}).get("name", "?")))
    elif st == 403:
        print("%s: Spotify refused (403). In the developer app, User Management, add this account's Spotify email. The app's owner also needs Spotify Premium." % name)
    elif st == 429:
        print("%s: Spotify says too many requests for now (429). The sign-in itself worked; try --check later." % name)
    else:
        print("%s: Spotify answered HTTP %s to the playback question." % (name, st))


def tv_call(tv, path, body, token=None):
    req = urllib.request.Request("http://%s:5400%s" % (tv, path), data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except ValueError:
            return e.code, {}
    except OSError as e:
        sys.exit("Could not reach the TV at %s (%s). Is it on, and on the same Wi-Fi?" % (tv, e.__class__.__name__))


def to_tv(env, tv, pair, token_file):
    have = slots(env)
    if not have:
        sys.exit("Not signed in yet. Run this without --tv first.")
    if pair:
        st, j = tv_call(tv, "/api/pair", {"pin": pair, "name": "spotify-auth.py"})
        if not j.get("token"):
            sys.exit("The TV refused that code: %s" % j.get("error", "HTTP %s" % st))
        old = os.umask(0o077)
        try:
            open(token_file, "w").write(j["token"])
        finally:
            os.umask(old)
    if not os.path.exists(token_file):
        sys.exit("This computer is not paired with the TV yet. Add --pair <the 6 digit code on the TV's Couch TV screen>.")
    # "accounts" for an app that knows several; the first one also the old way, for an app that knows one
    st, j = tv_call(tv, "/api/spotify/setup", {"client_id": env["SPOTIFY_CLIENT_ID"], "client_secret": env["SPOTIFY_CLIENT_SECRET"],
                                                "accounts": [{"refresh_token": s["refresh"], "authorized_at": s["at"]} for s in have],
                                                "refresh_token": have[0]["refresh"], "authorized_at": have[0]["at"]},
                    open(token_file).read().strip())
    if st == 404:
        sys.exit("The Couch TV app on this TV is older than the Spotify card. Update the app, then run this again.")
    if st == 401:
        sys.exit("The TV no longer knows this computer. Add --pair <the 6 digit code on the TV's Couch TV screen>.")
    if st != 200 or not j.get("configured"):
        sys.exit("The TV did not take the keys: %s" % j.get("error", "HTTP %s" % st))
    names = [a.get("name") for a in j.get("accounts", [])]
    if names:
        print("The TV has the keys for: %s. The card shows whichever of them is playing." % ", ".join(names))
    else:
        print("The TV has the keys (its app is an older one that knows a single account: %s). Update the app for more than one." % have[0]["name"])


def main():
    ap = argparse.ArgumentParser(description="Sign couch-tv in to Spotify for the now-playing card.")
    ap.add_argument("--check", action="store_true", help="only say what each signed-in account is playing (no sign-in)")
    ap.add_argument("--tv", metavar="ADDRESS", help="hand the keys in .env to the on-TV app at this address")
    ap.add_argument("--pair", metavar="CODE", help="with --tv: pair this computer first, with the 6 digit code on the TV")
    ap.add_argument("--no-browser", action="store_true", help="do not open a browser: print the address to open instead")
    ap.add_argument("--token-file", default=os.path.expanduser("~/.couchtv-token"), help=argparse.SUPPRESS)
    a = ap.parse_args()
    env = read_env()
    need_keys(env)
    env = name_them(env)
    if a.tv:
        to_tv(env, a.tv, a.pair, a.token_file)
    elif a.check:
        have = slots(env)
        if not have:
            sys.exit("Not signed in yet. Run this without --check first.")
        for s in have:
            tok = access_token(env, s)
            if tok:
                check(s["name"], tok)
    else:
        name, tok = sign_in(env, a.no_browser)
        check(name, tok)


if __name__ == "__main__":
    main()
