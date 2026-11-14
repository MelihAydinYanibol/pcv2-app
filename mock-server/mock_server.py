"""
PCV2 mock server - a stand-in for serverside/secondary_api.py (+ auth.py)
==========================================================================

Lets you try every feature of the Android app (or the web dashboard) without
a Windows PC running the engines. It speaks the same HTTP API as the real
secondary API:

* password sign-in (``POST /login`` -> ``pcv2_session`` cookie), ``/logout``,
  ``Authorization: Bearer <DASHBOARD_API_TOKEN>``, lockout after 5 wrong
  passwords, ``401 {"auth": "required"}`` for expired sessions
* ``GET /api/dashboard/data`` batch endpoint, every proxied ``/api/*`` endpoint
  of the primary API (limits, exceptions, usage, night lockdown, config,
  status) with the same validation and messages as ``clientside/api.py``
* the offline behaviour: when the simulated kid's PC is "off", reads come from
  the cache (``cached: true``) and writes are queued (``202 queued``); queued
  changes sync automatically when it comes back, or with ``/api/server/sync``
* ``/api/server/status``, ``/api/server/queue``, cancel / clear / sync

On top of that it simulates a running PC (usage counts up every second for the
"running" apps) and has a control panel at ``/mock`` to switch the PC on/off,
start/stop apps, add latency, make the server fail, expire sessions and load
scenarios (time up, lockdown now, empty config, ...).

Run it:

    pip install -r requirements.txt
    python mock_server.py                       # http://0.0.0.0:5035, password "parent1234"
    python mock_server.py --port 8080 --password secret --token my-api-token
    python mock_server.py --web-dir ../../PCV2/serverside   # also serve the real web dashboard

In the Android emulator the host machine is ``http://10.0.2.2:5035``; on a phone
use the computer's LAN address (``http://192.168.x.y:5035``).
"""

import argparse
import copy
import hashlib
import hmac
import json
import os
import random
import secrets
import threading
import time
from datetime import date, datetime, timedelta, timezone
from urllib.parse import quote, unquote

from flask import Flask, Response, jsonify, redirect, request

DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
UNCONFIGURED_LIMIT = 24 * 60 * 60 - 1
COOKIE_NAME = "pcv2_session"
FREE_ATTEMPTS = 5
BASE_LOCKOUT = 30
MAX_LOCKOUT = 15 * 60

app = Flask(__name__)
LOCK = threading.RLock()

CONFIG = {
    "password": "parent1234",
    "api_token": None,
    "auth_disabled": False,
    "sync_interval": 10,
    "web_dir": None,
}

# The simulated kid's computer (what clientside/*.json would hold)
PRIMARY = {"limits": {}, "usage": {}, "exceptions": {}, "night_lockdown": {}}
# Simulation knobs, changed from the /mock control panel
SIM = {
    "online": True,
    "running": {"OVERALL"},
    "latency_ms": 0,
    "fail": "none",          # none | server_error | primary_reject
    "ticking": True,
    "speed": 1,              # simulated seconds of usage per real second
    "last_online": time.time(),
}
QUEUE = []                    # {"id", "method", "endpoint", "data", "status", "created_at"}
CACHE = {}                    # endpoint -> {"data": response json, "cached_at": epoch}
SESSIONS = {}                 # sha256(token) -> expires_at
FAILURES = {}                 # ip -> [count, last, locked_until]
COUNTERS = {"queue_id": 0}


def _sha(v):
    return hashlib.sha256(v.encode("utf-8")).hexdigest()


def today_str():
    return datetime.now().strftime("%Y-%m-%d")


def default_night_lockdown():
    return {
        "enabled": False,
        "per_day": False,
        "all_days": {"blocked": [], "whitelist": []},
        "days": {d: {"blocked": [], "whitelist": []} for d in DAYS},
    }


# ============================================================================
# SEED DATA
# ============================================================================

def seed(kind="default"):
    """(Re)build the simulated PC's data."""
    with LOCK:
        QUEUE.clear()
        CACHE.clear()
        PRIMARY["limits"] = {}
        PRIMARY["usage"] = {}
        PRIMARY["exceptions"] = {}
        PRIMARY["night_lockdown"] = default_night_lockdown()
        SIM["online"] = True
        SIM["running"] = {"OVERALL"}
        if kind == "empty":
            refresh_cache()
            return

        weekdays = {d: (3 * 3600 if i < 5 else 5 * 3600) for i, d in enumerate(DAYS)}
        PRIMARY["limits"] = {
            "OVERALL": weekdays,
            "chrome.exe": {d: (5400 if i < 5 else 3 * 3600) for i, d in enumerate(DAYS)},
            "discord.exe": {**{d: 3600 for d in DAYS}, "Sunday": None},
            "minecraft.exe": {"Monday": 0, "Tuesday": 1800, "Wednesday": 0, "Thursday": 1800,
                              "Friday": 3600, "Saturday": 7200, "Sunday": 7200},
            "RobloxPlayerBeta.exe": {d: 2700 for d in DAYS},
        }

        rnd = random.Random(42)
        today = date.today()
        for back in range(8, 0, -1):
            d = (today - timedelta(days=back)).isoformat()
            chrome = rnd.randint(1200, 5400)
            discord = rnd.randint(300, 3600)
            mc = rnd.choice([0, 0, rnd.randint(600, 3600)])
            notepad = rnd.randint(0, 900)
            PRIMARY["usage"][d] = {
                "OVERALL": chrome + discord + mc + notepad + rnd.randint(600, 2400),
                "chrome.exe": chrome,
                "discord.exe": discord,
                "minecraft.exe": mc,
                "notepad.exe": notepad,
            }
        # Today: some time already used
        PRIMARY["usage"][today.isoformat()] = {
            "OVERALL": 4210,
            "chrome.exe": 2330,
            "discord.exe": 3480,
            "explorer.exe": 640,
            "Spotify.exe": 1210,
        }
        SIM["running"] = {"OVERALL", "chrome.exe"}

        PRIMARY["exceptions"] = {
            today.isoformat(): {
                "OVERALL": [[1800, "Homework done"]],
                "discord.exe": [[900, None], [-300, "Late to dinner"]],
            },
            (today - timedelta(days=1)).isoformat(): {
                "chrome.exe": [["7200", "School project"]],
            },
            (today + timedelta(days=1)).isoformat(): {
                "OVERALL": [[3600, "Birthday"]],
            },
        }

        PRIMARY["night_lockdown"] = {
            "enabled": True,
            "per_day": False,
            "all_days": {"blocked": [["22:30", "07:00"]], "whitelist": [["23:00", "23:15"]]},
            "days": {d: {"blocked": [["22:00" if i < 4 else "23:30", "07:00"]], "whitelist": []}
                     for i, d in enumerate(DAYS)},
        }
        refresh_cache()


# ============================================================================
# LIMIT MATHS (same as serverside/widget.py / client_engine.check_exception)
# ============================================================================

def _normalize_entries(exc):
    if not isinstance(exc, list):
        return []
    if len(exc) == 2 and isinstance(exc[0], (int, float)) and not isinstance(exc[0], bool):
        return [exc]
    return [e for e in exc if isinstance(e, (list, tuple)) and e]


def effective_limit(name, day, limits, exceptions):
    configured = (limits.get(name) or {}).get(day)
    unlimited = configured is None
    try:
        effective = UNCONFIGURED_LIMIT if unlimited else int(configured)
    except (TypeError, ValueError):
        effective, unlimited = UNCONFIGURED_LIMIT, True
    for entry in _normalize_entries((exceptions or {}).get(name)):
        t = entry[0]
        if type(t) is int:
            effective += t
        else:
            try:
                effective, unlimited = int(t), False
            except (TypeError, ValueError):
                pass
    return effective, unlimited


def _to_min(v):
    try:
        h, m = str(v).split(":")
        h, m = int(h), int(m)
        if 0 <= h <= 23 and 0 <= m <= 59:
            return h * 60 + m
    except Exception:
        pass
    return None


def lockdown_active(nl, now=None):
    if not isinstance(nl, dict) or not nl.get("enabled"):
        return False
    now = now or datetime.now()
    sch = (nl.get("days") or {}).get(DAYS[now.weekday()]) if nl.get("per_day") else nl.get("all_days")
    sch = sch or {}
    mins = now.hour * 60 + now.minute

    def hit(ws):
        for w in ws or []:
            if isinstance(w, (list, tuple)) and len(w) == 2:
                s, e = _to_min(w[0]), _to_min(w[1])
                if s is None or e is None:
                    continue
                if (s <= mins <= e) if s <= e else (mins >= s or mins <= e):
                    return True
        return False

    return hit(sch.get("blocked")) and not hit(sch.get("whitelist"))


# ============================================================================
# PRIMARY API (mirrors clientside/api.py)
# ============================================================================

def _ok(data=None, message=None, code=200):
    body = {"status": "success"}
    if message:
        body["message"] = message
    if data is not None or message is None:
        body["data"] = data
    return code, body


def _err(message, code):
    return code, {"status": "error", "message": message}


def _valid_time(v):
    return _to_min(v) is not None


def _normalize_schedule(s):
    if not isinstance(s, dict):
        return False, "Schedule must be an object with 'blocked' and 'whitelist'"
    out = {}
    for key in ("blocked", "whitelist"):
        ws = s.get(key, [])
        if not isinstance(ws, list):
            return False, "Time windows must be a list"
        norm = []
        for w in ws:
            if not isinstance(w, (list, tuple)) or len(w) != 2:
                return False, "Each window must be a [start, end] pair"
            if not _valid_time(w[0]) or not _valid_time(w[1]):
                return False, f"Invalid time value in window {list(w)}. Use HH:MM (24h)"
            norm.append([w[0], w[1]])
        out[key] = norm
    return True, out


def _valid_date(d):
    try:
        datetime.strptime(d, "%Y-%m-%d")
        return True
    except (TypeError, ValueError):
        return False


def primary(method, endpoint, data=None):
    """Handle one primary-API request. Returns (status_code, json_body)."""
    parts = [unquote(p) for p in endpoint.strip("/").split("/")]
    head = parts[0] if parts else ""
    P = PRIMARY
    with LOCK:
        # ---------------------------------------------------------- limits
        if head == "limits":
            limits = P["limits"]
            if method == "GET" and len(parts) == 1:
                return _ok(copy.deepcopy(limits))
            if method == "GET" and len(parts) == 2:
                if parts[1] not in limits:
                    return _err(f"Application '{parts[1]}' not found", 404)
                return _ok(copy.deepcopy(limits[parts[1]]))
            if method == "POST" and len(parts) == 1:
                if not data or "app_name" not in data or "limits" not in data:
                    return _err("Missing 'app_name' or 'limits'", 400)
                name = data["app_name"]
                if name in limits:
                    return _err(f"Application '{name}' already exists", 409)
                limits[name] = data["limits"]
                return _ok(limits[name], f"Limit for '{name}' created", 201)
            if method == "PUT" and len(parts) == 2:
                if not data or "limits" not in data:
                    return _err("Missing 'limits'", 400)
                if parts[1] not in limits:
                    return _err(f"Application '{parts[1]}' not found", 404)
                limits[parts[1]] = data["limits"]
                return _ok(limits[parts[1]], f"Limits for '{parts[1]}' updated")
            if method == "PUT" and len(parts) == 3:
                if not data or "seconds" not in data:
                    return _err("Missing 'seconds'", 400)
                if parts[2] not in DAYS:
                    return _err(f"Invalid day: {parts[2]}", 400)
                if parts[1] not in limits:
                    return _err(f"Application '{parts[1]}' not found", 404)
                limits[parts[1]][parts[2]] = data["seconds"]
                return 200, {"status": "success", "message": f"Limit for '{parts[1]}' on {parts[2]} updated to {data['seconds']} seconds"}
            if method == "DELETE" and len(parts) == 2:
                if parts[1] not in limits:
                    return _err(f"Application '{parts[1]}' not found", 404)
                del limits[parts[1]]
                SIM["running"].discard(parts[1])
                return 200, {"status": "success", "message": f"Application '{parts[1]}' removed"}

        # ---------------------------------------------------------- exceptions
        if head == "exceptions":
            exc = P["exceptions"]
            if method == "GET" and len(parts) == 1:
                return _ok(copy.deepcopy(exc))
            if method == "GET" and len(parts) == 2:
                day = exc.get(parts[1])
                if not day:
                    return _ok({})
                return _ok({a: _normalize_entries(v) for a, v in copy.deepcopy(day).items()})
            if method == "GET" and len(parts) == 3:
                if parts[2] not in exc.get(parts[1], {}):
                    return _err(f"No exception found for {parts[2]} on {parts[1]}", 404)
                return _ok(copy.deepcopy(exc[parts[1]][parts[2]]))
            if method == "POST" and len(parts) == 1:
                required = ["date", "app_name", "exception_time"]
                if not data or not all(f in data for f in required):
                    return _err(f"Missing required fields: {required}", 400)
                if not _valid_date(data["date"]):
                    return _err("Invalid date format. Use YYYY-MM-DD", 400)
                day = exc.setdefault(data["date"], {})
                day[data["app_name"]] = _normalize_entries(day.get(data["app_name"], []))
                entry = [data["exception_time"], data.get("reason")]
                day[data["app_name"]].append(entry)
                return _ok(entry, f"Exception created for {data['app_name']} on {data['date']}", 201)
            if method == "DELETE" and len(parts) in (3, 4):
                d, name = parts[1], parts[2]
                if name not in exc.get(d, {}):
                    return _err(f"Exception not found for {name} on {d}", 404)
                if len(parts) == 3:
                    del exc[d][name]
                    msg = f"Exception deleted for {name} on {d}"
                else:
                    lst = _normalize_entries(exc[d][name])
                    try:
                        idx = int(parts[3])
                    except ValueError:
                        return _err("Endpoint not found", 404)
                    if idx < 0 or idx >= len(lst):
                        return _err("Invalid exception index", 400)
                    lst.pop(idx)
                    exc[d][name] = lst
                    if not lst:
                        del exc[d][name]
                    msg = f"Exception transaction deleted for {name} on {d}"
                if not exc[d]:
                    del exc[d]
                return 200, {"status": "success", "message": msg}

        # ---------------------------------------------------------- night lockdown
        if head == "nightlockdown" and len(parts) == 1:
            if method == "GET":
                return _ok(copy.deepcopy(P["night_lockdown"]))
            if method == "PUT":
                if not data or not isinstance(data, dict):
                    return _err("No data provided", 400)
                cfg = default_night_lockdown()
                cfg["enabled"] = bool(data.get("enabled", False))
                cfg["per_day"] = bool(data.get("per_day", False))
                ok, val = _normalize_schedule(data.get("all_days", {"blocked": [], "whitelist": []}))
                if not ok:
                    return _err(f"all_days: {val}", 400)
                cfg["all_days"] = val
                for d in DAYS:
                    ok, val = _normalize_schedule((data.get("days") or {}).get(d, {"blocked": [], "whitelist": []}))
                    if not ok:
                        return _err(f"{d}: {val}", 400)
                    cfg["days"][d] = val
                P["night_lockdown"] = cfg
                return _ok(copy.deepcopy(cfg), "Night lockdown configuration updated")

        # ---------------------------------------------------------- usage
        if head == "usage":
            usage = P["usage"]
            if method == "GET" and len(parts) == 1:
                return _ok(copy.deepcopy(usage))
            if method == "GET" and len(parts) == 2:
                return _ok(copy.deepcopy(usage.get(parts[1], {})))
            if method == "GET" and len(parts) == 3:
                return _ok(usage.get(parts[1], {}).get(parts[2], 0))
            if method == "PUT" and len(parts) == 3:
                if not data or "seconds" not in data:
                    return _err("Missing 'seconds'", 400)
                if not _valid_date(parts[1]):
                    return _err("Invalid date format. Use YYYY-MM-DD", 400)
                usage.setdefault(parts[1], {})[parts[2]] = data["seconds"]
                return _ok(data["seconds"], f"Usage for {parts[2]} on {parts[1]} updated")

        # ---------------------------------------------------------- status / config
        if head == "status" and method == "GET" and len(parts) == 1:
            t = today_str()
            return _ok({
                "current_date": t,
                "apps_monitored": len(P["limits"]),
                "total_apps_today": len(P["usage"].get(t, {})),
                "exceptions_today": len(P["exceptions"].get(t, {})),
            })
        if head == "config" and len(parts) == 1:
            if method == "GET":
                return _ok({"limits": copy.deepcopy(P["limits"]), "exceptions": copy.deepcopy(P["exceptions"]),
                            "night_lockdown": copy.deepcopy(P["night_lockdown"])})
            if method == "POST":
                if not data:
                    return _err("No data provided", 400)
                if "limits" in data:
                    P["limits"] = data["limits"]
                if "exceptions" in data:
                    P["exceptions"] = data["exceptions"]
                if "night_lockdown" in data:
                    P["night_lockdown"] = data["night_lockdown"]
                return 200, {"status": "success", "message": "Configuration uploaded successfully"}

    return _err("Endpoint not found", 404)


# ============================================================================
# SECONDARY API BEHAVIOUR (proxy, cache, queue)
# ============================================================================

def primary_alive():
    return SIM["online"] and SIM["fail"] != "primary_reject"


def cache_put(endpoint, body):
    CACHE[endpoint] = {"data": copy.deepcopy(body), "cached_at": time.time()}


def refresh_cache():
    t = today_str()
    for ep in ("/limits", "/exceptions", f"/exceptions/{t}", f"/usage/{t}", "/config", "/status", "/nightlockdown", "/usage"):
        code, body = primary("GET", ep)
        if code == 200:
            cache_put(ep, body)


def proxy(method, endpoint, data=None, queue_writes=True):
    if SIM["fail"] == "primary_reject":
        return {"status": "error", "message": "Primary API rejected the server token (check PRIMARY_API_TOKEN)"}, 502
    if primary_alive():
        code, body = primary(method, endpoint, data)
        if code < 400:
            if method == "GET":
                cache_put(endpoint, body)
            return body, 200
        return body, code
    if method != "GET" and queue_writes:
        with LOCK:
            COUNTERS["queue_id"] += 1
            QUEUE.append({"id": COUNTERS["queue_id"], "method": method, "endpoint": endpoint,
                          "data": copy.deepcopy(data), "status": "pending", "created_at": time.time()})
        return {"status": "queued", "message": "Primary API unavailable. Request queued for later processing.",
                "primary_status": "offline"}, 202
    if method == "GET":
        cached = CACHE.get(endpoint)
        if cached:
            return {"status": "success", "data": cached["data"].get("data", {}), "cached": True,
                    "message": "Data from local cache (primary API offline)"}, 200
        return {"status": "error", "message": "Primary API unavailable. No cached data available.", "data": {}}, 503
    return {"status": "error", "message": "Primary API unavailable"}, 503


def pending_queue():
    return [q for q in QUEUE if q["status"] == "pending"]


def sync_queue():
    synced = failed = 0
    if not primary_alive():
        return synced, failed
    with LOCK:
        for item in pending_queue():
            code, _ = primary(item["method"], item["endpoint"], item["data"])
            if code < 400:
                item["status"] = "synced"
                synced += 1
            else:
                item["retries"] = item.get("retries", 0) + 1
                if item["retries"] > 3:
                    item["status"] = "failed"
                failed += 1
    return synced, failed


def iso_utc(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def dashboard_payload():
    t = today_str()
    endpoint_map = {
        "limits": "/limits",
        "status": "/status",
        "today_exceptions": f"/exceptions/{t}",
        "today_usage": f"/usage/{t}",
        "config": "/config",
        "night_lockdown": "/nightlockdown",
    }
    out, cached_keys = {}, []
    live = primary_alive()
    with LOCK:
        for key, ep in endpoint_map.items():
            if live:
                code, body = primary("GET", ep)
                if code == 200:
                    cache_put(ep, body)
                    out[key] = body.get("data", {})
                    continue
            cached = CACHE.get(ep)
            out[key] = cached["data"].get("data", {}) if cached else {}
            cached_keys.append(key)
        times = [CACHE[endpoint_map[k]]["cached_at"] for k in cached_keys if endpoint_map[k] in CACHE]
        out["server"] = {
            "primary_api": "online" if live else "offline",
            "timestamp": datetime.now().isoformat(),
            "server_date": t,
            "cached_keys": cached_keys,
            "last_synced": iso_utc(min(times)) if times else None,
        }
        out["queue"] = [{"id": q["id"], "method": q["method"], "endpoint": q["endpoint"], "data": q["data"]}
                        for q in pending_queue()]
    return out


# ============================================================================
# AUTH (mirrors serverside/auth.py)
# ============================================================================

def client_ip():
    return request.remote_addr or "?"


def lang():
    return "tr" if request.cookies.get("pcv2_lang") == "tr" else "en"


def authenticated():
    if CONFIG["auth_disabled"]:
        return True
    header = request.headers.get("Authorization", "")
    if CONFIG["api_token"] and header.startswith("Bearer "):
        if hmac.compare_digest(_sha(header[7:].strip()), _sha(CONFIG["api_token"])):
            return True
    token = request.cookies.get(COOKIE_NAME)
    if token:
        exp = SESSIONS.get(_sha(token))
        if exp and exp > time.time():
            return True
    return False


LOGIN_FALLBACK = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in · Parental Controls (mock)</title>
<style>body{font-family:system-ui,sans-serif;background:#f4f5f8;display:grid;place-items:center;min-height:100vh;margin:0}
form{background:#fff;padding:28px;border-radius:18px;box-shadow:0 8px 30px rgba(0,0,0,.08);width:min(360px,90vw)}
input{width:100%;padding:11px;margin:10px 0;border:1px solid #e3e6ec;border-radius:9px;box-sizing:border-box}
button{width:100%;padding:11px;border:0;border-radius:9px;background:#4f5bd5;color:#fff;font-weight:600}
.error{display:none;color:#d64545;background:#fdeaea;padding:9px 12px;border-radius:9px}.error.show{display:block}</style></head>
<body><form method="post"><h2>Parental Controls</h2><p>Mock server sign-in</p>
<div class="error {{ERROR_CLASS}}" role="alert">{{ERROR}}</div>
<input type="password" name="password" placeholder="Password" autofocus><button type="submit">Sign in</button></form></body></html>"""


def login_page(error="", status=200):
    html = LOGIN_FALLBACK
    if CONFIG["web_dir"]:
        path = os.path.join(CONFIG["web_dir"], "login.html")
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                html = f.read()
    html = html.replace("{{ERROR}}", error).replace("{{ERROR_CLASS}}", "show" if error else "")
    return Response(html, status=status, mimetype="text/html")


def safe_next(v):
    if not v or not v.startswith("/") or v.startswith("//") or "\\" in v:
        return "/"
    return v


@app.route("/login", methods=["GET", "POST"])
def login():
    if CONFIG["auth_disabled"]:
        return redirect("/", code=303)
    if request.method == "GET":
        if authenticated():
            return redirect(safe_next(request.args.get("next")), code=303)
        return login_page()
    ip = client_ip()
    entry = FAILURES.get(ip)
    if entry and entry[2] > time.time():
        wait = int(entry[2] - time.time() + 0.999)
        m, s = divmod(wait, 60)
        if lang() == "tr":
            left = f"{m} dk {s:02d} sn" if m else f"{s} sn"
            return login_page(f"Çok fazla yanlış deneme. {left} sonra tekrar deneyin.", 429)
        left = f"{m}m {s:02d}s" if m else f"{s}s"
        return login_page(f"Too many wrong attempts. Try again in {left}.", 429)
    pw = request.form.get("password", "")
    if not pw or not hmac.compare_digest(_sha(pw), _sha(CONFIG["password"])):
        entry = FAILURES.get(ip) or [0, 0, 0]
        entry[0] += 1
        entry[1] = time.time()
        if entry[0] >= FREE_ATTEMPTS:
            entry[2] = time.time() + min(MAX_LOCKOUT, BASE_LOCKOUT * 2 ** (entry[0] - FREE_ATTEMPTS))
        FAILURES[ip] = entry
        return login_page("Yanlış şifre." if lang() == "tr" else "Wrong password.", 401)
    FAILURES.pop(ip, None)
    token = secrets.token_urlsafe(32)
    SESSIONS[_sha(token)] = time.time() + 30 * 86400
    resp = redirect(safe_next(request.args.get("next") or request.form.get("next")), code=303)
    resp.set_cookie(COOKIE_NAME, token, max_age=30 * 86400, httponly=True, samesite="Strict", path="/")
    return resp


@app.route("/logout", methods=["POST"])
def logout():
    token = request.cookies.get(COOKIE_NAME)
    if token:
        SESSIONS.pop(_sha(token), None)
    resp = redirect("/login", code=303)
    resp.set_cookie(COOKIE_NAME, "", max_age=0, path="/")
    return resp


@app.before_request
def guard():
    path = request.path
    if path.startswith("/mock") or path in ("/login", "/favicon.ico"):
        return None
    if path.startswith("/api/") and SIM["latency_ms"]:
        time.sleep(SIM["latency_ms"] / 1000.0)
    if not authenticated():
        if path.startswith("/api/"):
            return jsonify({"status": "error", "message": "Sign-in required", "auth": "required"}), 401
        target = request.full_path.rstrip("?") if request.method == "GET" else "/"
        return redirect("/login?next=" + quote(target, safe="/"), code=303)
    if path.startswith("/api/") and SIM["fail"] == "server_error":
        return jsonify({"status": "error", "message": "Internal server error"}), 500
    return None


@app.after_request
def headers(resp):
    resp.headers["Cache-Control"] = "no-store"
    return resp


# ============================================================================
# ROUTES
# ============================================================================

@app.route("/api/auth/status")
def auth_status():
    return jsonify({"status": "success", "data": {"authenticated": True, "auth_disabled": CONFIG["auth_disabled"]}})


@app.route("/api/dashboard/data")
def dashboard_data():
    return jsonify({"status": "success", "data": dashboard_payload()})


@app.route("/api/server/status")
def server_status():
    stats = {}
    for q in QUEUE:
        stats[q["status"]] = stats.get(q["status"], 0) + 1
    return jsonify({"status": "success", "data": {
        "server_status": "online", "primary_api": "online" if primary_alive() else "offline",
        "queue": stats, "timestamp": datetime.now().isoformat()}})


@app.route("/api/server/queue")
def server_queue():
    items = [{"id": q["id"], "method": q["method"], "endpoint": q["endpoint"], "data": q["data"]} for q in pending_queue()]
    return jsonify({"status": "success", "data": {"count": len(items), "items": items}})


@app.route("/api/server/queue/<int:item_id>", methods=["DELETE"])
def cancel_queue_item(item_id):
    with LOCK:
        for q in QUEUE:
            if q["id"] == item_id:
                if q["status"] != "pending":
                    return jsonify({"status": "error", "message": "Queue item was already processed"}), 409
                QUEUE.remove(q)
                return jsonify({"status": "success", "message": f"Queue item {item_id} cancelled"})
    return jsonify({"status": "error", "message": "Queue item not found or already processed"}), 404


@app.route("/api/server/queue/clear", methods=["POST"])
def clear_queue():
    with LOCK:
        QUEUE.clear()
    return jsonify({"status": "success", "message": "Queue cleared"})


@app.route("/api/server/sync", methods=["POST"])
def manual_sync():
    synced, failed = sync_queue()
    return jsonify({"status": "success", "data": {"synced": synced, "failed": failed}})


@app.route("/api/<path:rest>", methods=["GET", "POST", "PUT", "DELETE"])
def api_proxy(rest):
    # Same as secondary_api.py: the (already decoded) path is passed on as-is
    endpoint = "/" + rest
    data = request.get_json(silent=True) if request.method in ("POST", "PUT") else None
    body, code = proxy(request.method, endpoint, data)
    return jsonify(body), code


@app.route("/")
@app.route("/dashboard")
def dashboard_page():
    if CONFIG["web_dir"] and os.path.exists(os.path.join(CONFIG["web_dir"], "dashboard.html")):
        with open(os.path.join(CONFIG["web_dir"], "dashboard.html"), "r", encoding="utf-8") as f:
            return Response(f.read(), mimetype="text/html")
    return redirect("/mock", code=303)


@app.errorhandler(404)
def not_found(_e):
    return jsonify({"status": "error", "message": "Endpoint not found"}), 404


# ============================================================================
# MOCK CONTROL
# ============================================================================

def mock_state():
    t = today_str()
    with LOCK:
        return {
            "online": SIM["online"],
            "running": sorted(SIM["running"]),
            "latency_ms": SIM["latency_ms"],
            "fail": SIM["fail"],
            "ticking": SIM["ticking"],
            "speed": SIM["speed"],
            "lockdown_active": lockdown_active(PRIMARY["night_lockdown"]),
            "apps": sorted(set(PRIMARY["limits"]) | set(PRIMARY["usage"].get(t, {})) | {"OVERALL"}),
            "today_usage": PRIMARY["usage"].get(t, {}),
            "queue": len(pending_queue()),
            "sessions": len(SESSIONS),
            "auth_disabled": CONFIG["auth_disabled"],
        }


@app.route("/mock/state")
def mock_state_route():
    return jsonify(mock_state())


@app.route("/mock/control", methods=["POST"])
def mock_control():
    """{"action": ..., ...}  — see the buttons on /mock."""
    body = request.get_json(silent=True) or {}
    action = body.get("action")
    t = today_str()
    with LOCK:
        if action == "online":
            SIM["online"] = bool(body.get("value", True))
            if SIM["online"]:
                SIM["last_online"] = time.time()
                sync_queue()
                refresh_cache()
        elif action == "run":
            app_name = body.get("app") or "OVERALL"
            if body.get("value", True):
                SIM["running"].add(app_name)
            else:
                SIM["running"].discard(app_name)
        elif action == "latency":
            SIM["latency_ms"] = max(0, min(30000, int(body.get("value", 0))))
        elif action == "fail":
            SIM["fail"] = body.get("value", "none") if body.get("value") in ("none", "server_error", "primary_reject") else "none"
        elif action == "ticking":
            SIM["ticking"] = bool(body.get("value", True))
        elif action == "speed":
            SIM["speed"] = max(1, min(600, int(body.get("value", 1))))
        elif action == "add_usage":
            app_name = body.get("app") or "OVERALL"
            day = PRIMARY["usage"].setdefault(t, {})
            day[app_name] = max(0, int(day.get(app_name, 0)) + int(body.get("value", 600)))
            if app_name != "OVERALL":
                day["OVERALL"] = max(0, int(day.get("OVERALL", 0)) + int(body.get("value", 600)))
        elif action == "expire_sessions":
            SESSIONS.clear()
        elif action == "unlock_login":
            FAILURES.clear()
        elif action == "auth_disabled":
            CONFIG["auth_disabled"] = bool(body.get("value", False))
        elif action == "reset":
            seed("default")
        elif action == "empty":
            seed("empty")
        elif action == "time_up":
            day = PRIMARY["usage"].setdefault(t, {})
            lim, unlimited = effective_limit("OVERALL", DAYS[datetime.now().weekday()], PRIMARY["limits"], PRIMARY["exceptions"].get(t))
            day["OVERALL"] = lim - 60 if not unlimited else day.get("OVERALL", 0)
        elif action == "lockdown_now":
            now = datetime.now()
            start = (now - timedelta(minutes=5)).strftime("%H:%M")
            end = (now + timedelta(minutes=45)).strftime("%H:%M")
            PRIMARY["night_lockdown"] = {**default_night_lockdown(), "enabled": True,
                                         "all_days": {"blocked": [[start, end]], "whitelist": []}}
        elif action == "lockdown_soon":
            now = datetime.now()
            start = (now + timedelta(minutes=10)).strftime("%H:%M")
            end = (now + timedelta(minutes=70)).strftime("%H:%M")
            PRIMARY["night_lockdown"] = {**default_night_lockdown(), "enabled": True,
                                         "all_days": {"blocked": [[start, end]], "whitelist": []}}
        elif action == "queue_samples":
            was = SIM["online"]
            SIM["online"] = False
            proxy("POST", "/exceptions", {"date": t, "app_name": "OVERALL", "exception_time": 900, "reason": "Queued sample"})
            proxy("PUT", "/limits/discord.exe", {"limits": {d: 5400 for d in DAYS}})
            proxy("DELETE", "/limits/minecraft.exe")
            SIM["online"] = was
        else:
            return jsonify({"status": "error", "message": f"Unknown action {action!r}"}), 400
    return jsonify({"status": "success", "data": mock_state()})


MOCK_PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PCV2 mock control</title>
<style>
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f4f5f8;color:#1b1f29;margin:0;padding:16px}
@media (prefers-color-scheme:dark){body{background:#0f1117;color:#e8eaf0}.card{background:#171a22!important;border-color:#2a2f3b!important}button{background:#1d212b!important;color:#e8eaf0!important;border-color:#2a2f3b!important}button.on{background:#7c86f0!important;color:#0f1117!important}}
h1{font-size:1.3rem;margin:0 0 4px}p{margin:0 0 14px;color:#5b6272}
.grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));max-width:1100px}
.card{background:#fff;border:1px solid #e3e6ec;border-radius:14px;padding:14px}
.card h2{font-size:.78rem;text-transform:uppercase;letter-spacing:.06em;color:#8a91a1;margin:0 0 10px}
button{border:1px solid #e3e6ec;background:#fff;border-radius:9px;padding:8px 12px;font-weight:600;cursor:pointer;margin:0 6px 6px 0}
button.on{background:#4f5bd5;color:#fff;border-color:#4f5bd5}
code{font-size:.85rem}.kv{display:grid;grid-template-columns:auto 1fr;gap:4px 12px;font-size:.9rem}
</style></head><body>
<h1>PCV2 mock server</h1>
<p>Simulated kid's PC for testing the Android app and the web dashboard. API: <code id="base"></code></p>
<div class="grid">
 <div class="card"><h2>Kid's PC</h2><div id="online"></div><div class="kv" id="info"></div></div>
 <div class="card"><h2>Running apps (usage counts up)</h2><div id="apps"></div>
   <div><button data-a="add_usage" data-app="OVERALL" data-v="600">OVERALL +10m</button><button data-a="add_usage" data-app="chrome.exe" data-v="900">chrome +15m</button></div>
   <div id="speed"></div></div>
 <div class="card"><h2>Scenarios</h2>
   <button data-a="reset">Reset sample data</button><button data-a="empty">Empty config</button>
   <button data-a="time_up">Overall time almost up</button><button data-a="lockdown_now">Lockdown active now</button>
   <button data-a="lockdown_soon">Lockdown in 10 min</button><button data-a="queue_samples">Add queued changes</button></div>
 <div class="card"><h2>Server</h2><div id="fail"></div><div id="lat"></div>
   <button data-a="expire_sessions">Expire all sign-ins</button><button data-a="unlock_login">Clear login lockout</button><div id="auth"></div></div>
</div>
<script>
document.getElementById('base').textContent = location.origin + '/api';
async function post(body){ const r = await fetch('/mock/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}); render((await r.json()).data); }
function btn(label, body, on){ return `<button class="${on?'on':''}" data-body='${JSON.stringify(body)}'>${label}</button>`; }
function fmt(s){ s=Math.round(s||0); const h=Math.floor(s/3600), m=Math.floor(s%3600/60); return h?`${h}h ${String(m).padStart(2,'0')}m`:`${m}m ${s%60}s`; }
function render(s){
  if(!s) return;
  document.getElementById('online').innerHTML = btn('PC on', {action:'online',value:true}, s.online) + btn('PC off', {action:'online',value:false}, !s.online);
  document.getElementById('info').innerHTML = `<span>Lockdown now</span><b>${s.lockdown_active?'yes':'no'}</b><span>Queued changes</span><b>${s.queue}</b><span>Signed-in sessions</span><b>${s.sessions}</b><span>Overall used today</span><b>${fmt((s.today_usage||{}).OVERALL)}</b>`;
  document.getElementById('apps').innerHTML = s.apps.map(a => btn(`${a} · ${fmt((s.today_usage||{})[a])}`, {action:'run',app:a,value:!s.running.includes(a)}, s.running.includes(a))).join('');
  document.getElementById('speed').innerHTML = 'Clock: ' + btn('paused',{action:'ticking',value:false},!s.ticking) + [1,10,60].map(v=>btn(`${v}x`,{action:'speed',value:v},s.ticking&&s.speed===v)).join('');
  document.getElementById('fail').innerHTML = 'Failure: ' + [['none','none'],['server_error','HTTP 500'],['primary_reject','Bad primary token']].map(([v,l])=>btn(l,{action:'fail',value:v},s.fail===v)).join('');
  document.getElementById('lat').innerHTML = 'Latency: ' + [0,1500,10000].map(v=>btn(v?`${v} ms`:'none',{action:'latency',value:v},s.latency_ms===v)).join('');
  document.getElementById('auth').innerHTML = 'Sign-in: ' + btn('required',{action:'auth_disabled',value:false},!s.auth_disabled) + btn('disabled',{action:'auth_disabled',value:true},s.auth_disabled);
}
document.addEventListener('click', e => {
  const b = e.target.closest('button'); if(!b) return;
  if (b.dataset.body) post(JSON.parse(b.dataset.body));
  else if (b.dataset.a) post({action:b.dataset.a, app:b.dataset.app, value: b.dataset.v ? +b.dataset.v : undefined});
});
async function poll(){ try { render(await (await fetch('/mock/state')).json()); } catch(e){} }
poll(); setInterval(poll, 2000);
</script></body></html>"""


@app.route("/mock")
def mock_page():
    return Response(MOCK_PAGE, mimetype="text/html")


# ============================================================================
# SIMULATION LOOP
# ============================================================================

def tick_once(seconds=1):
    """Advance the simulated PC by `seconds` of usage."""
    with LOCK:
        if not SIM["online"] or not SIM["ticking"]:
            return
        t = today_str()
        day = PRIMARY["usage"].setdefault(t, {})
        day_name = DAYS[datetime.now().weekday()]
        exc = PRIMARY["exceptions"].get(t, {})
        if lockdown_active(PRIMARY["night_lockdown"]):
            return  # the real PC would be shut down
        ov_lim, ov_unl = effective_limit("OVERALL", day_name, PRIMARY["limits"], exc)
        if not ov_unl and day.get("OVERALL", 0) >= ov_lim:
            return  # time is up: the real PC shuts down
        for name in list(SIM["running"]):
            lim, unl = effective_limit(name, day_name, PRIMARY["limits"], exc)
            if name != "OVERALL" and not unl and day.get(name, 0) >= lim:
                continue  # the engine kills an app that ran out of time
            day[name] = int(day.get(name, 0)) + seconds
            if name != "OVERALL" and "OVERALL" not in SIM["running"]:
                SIM["running"].add("OVERALL")


def simulation_loop():
    last_sync = time.time()
    while True:
        time.sleep(1)
        try:
            tick_once(SIM["speed"])
            if time.time() - last_sync >= CONFIG["sync_interval"]:
                last_sync = time.time()
                if primary_alive() and pending_queue():
                    synced, failed = sync_queue()
                    print(f"[SYNC] {synced} synced, {failed} failed")
        except Exception as e:  # keep simulating whatever happens
            print(f"[SIM] {type(e).__name__}: {e}")


def main():
    parser = argparse.ArgumentParser(description="PCV2 mock secondary API")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=5035)
    parser.add_argument("--password", default=os.getenv("DASHBOARD_PASSWORD", "parent1234"))
    parser.add_argument("--token", default=os.getenv("DASHBOARD_API_TOKEN"), help="enable Bearer token sign-in")
    parser.add_argument("--no-auth", action="store_true", help="run without sign-in (AUTH_DISABLED)")
    parser.add_argument("--offline", action="store_true", help="start with the kid's PC switched off")
    parser.add_argument("--empty", action="store_true", help="start with no limits/exceptions/usage")
    parser.add_argument("--sync-interval", type=int, default=10)
    parser.add_argument("--web-dir", help="path to PCV2/serverside to also serve the real web dashboard at /")
    args = parser.parse_args()

    CONFIG.update(password=args.password, api_token=args.token, auth_disabled=args.no_auth,
                  sync_interval=args.sync_interval, web_dir=args.web_dir)
    seed("empty" if args.empty else "default")
    if args.offline:
        SIM["online"] = False

    threading.Thread(target=simulation_loop, daemon=True).start()
    print("=" * 64)
    print("PCV2 MOCK SERVER")
    print(f"  API:            http://{args.host}:{args.port}/api")
    print(f"  Control panel:  http://{args.host}:{args.port}/mock")
    print(f"  Password:       {args.password}")
    if args.token:
        print(f"  API token:      {args.token}")
    if args.no_auth:
        print("  Sign-in:        DISABLED")
    print("  Android emulator -> http://10.0.2.2:%d" % args.port)
    print("=" * 64)
    app.run(host=args.host, port=args.port, debug=False, threaded=True)


# Seed once on import so tests / WSGI servers get data too
seed("default")

if __name__ == "__main__":
    main()
