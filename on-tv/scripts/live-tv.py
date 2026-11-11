#!/usr/bin/env python3
"""Live edits of the TV home in on-TV mode: send changed files from tv-home/web straight to the TV.

The on-TV app serves files sent here at /tv/ in place of the ones built into it, until cleared, and
reloads the home. No new app build, no ADB. Needs a phone token for the TV (pair once: the 6 digit
code on the Couch TV screen), kept in ~/.couchtv-token or passed with --token-file.

  python3 on-tv/scripts/live-tv.py --ip <tv address> sky.js app.css        send these, reload
  python3 on-tv/scripts/live-tv.py --ip <tv address> --changed             every file git sees as changed
  python3 on-tv/scripts/live-tv.py --ip <tv address> --list                what the TV is using instead
  python3 on-tv/scripts/live-tv.py --ip <tv address> --clear               back to the app's own files
  python3 on-tv/scripts/live-tv.py --ip <tv address> --pair 123456         pair this computer once
"""
import argparse, base64, json, os, subprocess, sys, urllib.request

WEB = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tv-home", "web"))
CHUNK = 45 * 1024   # raw bytes per call: base64 keeps it under the server's 64 KB body limit


def call(ip, token, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://{ip}:5400{path}", data=data, method=method,
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", required=True)
    ap.add_argument("--token-file", default=os.path.expanduser("~/.couchtv-token"))
    ap.add_argument("--changed", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--clear", action="store_true")
    ap.add_argument("--pair")
    ap.add_argument("files", nargs="*", help="paths relative to tv-home/web")
    a = ap.parse_args()

    if a.pair:
        r = call(a.ip, None, "POST", "/api/pair", {"pin": a.pair, "name": "live-tv.py"})
        if not r.get("token"): sys.exit("pairing refused: " + r.get("error", ""))
        old = os.umask(0o077)
        open(a.token_file, "w").write(r["token"])
        os.umask(old)
        print("paired; token saved in", a.token_file)
        return
    token = open(a.token_file).read().strip()
    if a.list:
        for f in call(a.ip, token, "GET", "/api/dev/tv-files").get("files", []): print(f)
        return
    if a.clear:
        call(a.ip, token, "POST", "/api/dev/tv-clear", {}); print("cleared; the home uses the app's own files")
        return
    files = list(a.files)
    if a.changed:
        out = subprocess.run(["git", "-C", WEB, "status", "--porcelain", "--", "."], capture_output=True, text=True).stdout
        for line in out.splitlines():
            p = os.path.relpath(os.path.join(subprocess.run(["git", "-C", WEB, "rev-parse", "--show-toplevel"],
                                capture_output=True, text=True).stdout.strip(), line[3:]), WEB)
            if os.path.isfile(os.path.join(WEB, p)): files.append(p)
    if not files: sys.exit("nothing to send")
    for spec in files:
        # "local=remote" sends a file under another name (local is relative to tv-home/web, or absolute)
        local, rel = spec.split("=", 1) if "=" in spec else (spec, spec)
        raw = open(os.path.join(WEB, local), "rb").read()
        for i in range(0, max(len(raw), 1), CHUNK):
            call(a.ip, token, "POST", "/api/dev/tv-file",
                 {"path": rel, "b64": base64.b64encode(raw[i:i + CHUNK]).decode(), "append": i > 0})
        print(f"sent {rel} ({len(raw)} bytes)")
    call(a.ip, token, "POST", "/api/dev/tv-reload", {})
    print("home reloaded")


if __name__ == "__main__":
    main()
