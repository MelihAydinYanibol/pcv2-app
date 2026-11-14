"""Tests for the mock server: run with `pytest` in this folder."""

import pytest

import mock_server as ms


@pytest.fixture()
def client():
    ms.CONFIG.update(password="parent1234", api_token="test-token", auth_disabled=False)
    ms.SESSIONS.clear()
    ms.FAILURES.clear()
    ms.SIM.update(latency_ms=0, fail="none", ticking=True, speed=1)
    ms.seed("default")
    c = ms.app.test_client()
    yield c


def login(c, pw="parent1234"):
    return c.post("/login", data={"password": pw})


def test_api_requires_sign_in(client):
    r = client.get("/api/dashboard/data")
    assert r.status_code == 401
    assert r.get_json()["auth"] == "required"


def test_password_login_sets_cookie_and_redirects(client):
    r = login(client)
    assert r.status_code == 303
    assert "pcv2_session=" in r.headers["Set-Cookie"]
    assert client.get("/api/auth/status").status_code == 200


def test_wrong_password_and_lockout(client):
    for _ in range(5):
        r = login(client, "nope")
        assert r.status_code == 401
        assert b"Wrong password." in r.data
    r = login(client, "parent1234")
    assert r.status_code == 429
    assert b"Too many wrong attempts" in r.data


def test_turkish_error_message(client):
    client.set_cookie("pcv2_lang", "tr")
    r = login(client, "nope")
    assert "Yanlış şifre.".encode() in r.data


def test_bearer_token(client):
    r = client.get("/api/limits", headers={"Authorization": "Bearer test-token"})
    assert r.status_code == 200
    assert "OVERALL" in r.get_json()["data"]
    assert client.get("/api/limits", headers={"Authorization": "Bearer bad"}).status_code == 401


def test_logout_ends_session(client):
    login(client)
    client.post("/logout")
    assert client.get("/api/limits").status_code == 401


def test_dashboard_batch_shape(client):
    login(client)
    d = client.get("/api/dashboard/data").get_json()["data"]
    for key in ("limits", "status", "today_exceptions", "today_usage", "config", "night_lockdown", "server", "queue"):
        assert key in d
    assert d["server"]["primary_api"] == "online"
    assert d["server"]["server_date"] == ms.today_str()


def test_limits_crud(client):
    login(client)
    limits = {d: 1800 for d in ms.DAYS}
    r = client.post("/api/limits", json={"app_name": "vlc.exe", "limits": limits})
    assert r.status_code == 200 and r.get_json()["status"] == "success"
    assert client.post("/api/limits", json={"app_name": "vlc.exe", "limits": limits}).status_code == 409
    limits["Sunday"] = None
    assert client.put("/api/limits/vlc.exe", json={"limits": limits}).status_code == 200
    assert client.get("/api/limits/vlc.exe").get_json()["data"]["Sunday"] is None
    assert client.put("/api/limits/vlc.exe/Monday", json={"seconds": 60}).status_code == 200
    assert client.delete("/api/limits/vlc.exe").status_code == 200
    assert client.get("/api/limits/vlc.exe").status_code == 404


def test_exceptions_add_set_delete(client):
    login(client)
    t = ms.today_str()
    assert client.post("/api/exceptions", json={"date": t, "app_name": "chrome.exe", "exception_time": 900}).status_code == 200
    assert client.post("/api/exceptions", json={"date": t, "app_name": "chrome.exe", "exception_time": "3600", "reason": "x"}).status_code == 200
    assert client.post("/api/exceptions", json={"date": "bad", "app_name": "x", "exception_time": 1}).status_code == 400
    lst = client.get(f"/api/exceptions/{t}").get_json()["data"]["chrome.exe"]
    assert lst == [[900, None], ["3600", "x"]]
    assert client.delete(f"/api/exceptions/{t}/chrome.exe/0").status_code == 200
    assert client.get(f"/api/exceptions/{t}").get_json()["data"]["chrome.exe"] == [["3600", "x"]]
    assert client.delete(f"/api/exceptions/{t}/chrome.exe/5").status_code == 400
    assert client.delete(f"/api/exceptions/{t}/chrome.exe").status_code == 200
    assert "chrome.exe" not in client.get(f"/api/exceptions/{t}").get_json()["data"]


def test_night_lockdown_validation(client):
    login(client)
    cfg = {"enabled": True, "per_day": False, "all_days": {"blocked": [["22:00", "07:00"]], "whitelist": []}, "days": {}}
    r = client.put("/api/nightlockdown", json=cfg)
    assert r.status_code == 200
    assert r.get_json()["data"]["days"]["Monday"] == {"blocked": [], "whitelist": []}
    cfg["all_days"]["blocked"] = [["25:00", "07:00"]]
    assert client.put("/api/nightlockdown", json=cfg).status_code == 400


def test_offline_queue_cache_and_sync(client):
    login(client)
    client.get("/api/dashboard/data")
    ms.SIM["online"] = False
    d = client.get("/api/dashboard/data").get_json()["data"]
    assert d["server"]["primary_api"] == "offline"
    assert d["limits"]  # served from cache
    assert d["server"]["last_synced"]

    r = client.get("/api/limits")
    assert r.get_json()["cached"] is True

    r = client.post("/api/exceptions", json={"date": ms.today_str(), "app_name": "OVERALL", "exception_time": 600})
    assert r.status_code == 202 and r.get_json()["status"] == "queued"
    r = client.delete("/api/limits/discord.exe")
    assert r.status_code == 202
    q = client.get("/api/dashboard/data").get_json()["data"]["queue"]
    assert [i["endpoint"] for i in q] == ["/exceptions", "/limits/discord.exe"]

    # cancel one
    assert client.delete(f"/api/server/queue/{q[1]['id']}").status_code == 200
    assert client.delete(f"/api/server/queue/{q[1]['id']}").status_code == 404

    # sync while offline does nothing, then online applies it
    assert client.post("/api/server/sync").get_json()["data"] == {"synced": 0, "failed": 0}
    ms.SIM["online"] = True
    assert client.post("/api/server/sync").get_json()["data"] == {"synced": 1, "failed": 0}
    assert "discord.exe" in client.get("/api/limits").get_json()["data"]
    assert client.get("/api/dashboard/data").get_json()["data"]["queue"] == []


def test_clear_queue(client):
    login(client)
    ms.SIM["online"] = False
    client.put("/api/limits/chrome.exe", json={"limits": {}})
    assert client.post("/api/server/queue/clear").status_code == 200
    assert client.get("/api/server/queue").get_json()["data"]["count"] == 0


def test_config_upload(client):
    login(client)
    r = client.post("/api/config", json={"limits": {"OVERALL": {d: 100 for d in ms.DAYS}}})
    assert r.status_code == 200
    assert list(client.get("/api/limits").get_json()["data"]) == ["OVERALL"]


def test_failure_modes(client):
    login(client)
    ms.SIM["fail"] = "server_error"
    assert client.get("/api/dashboard/data").status_code == 500
    ms.SIM["fail"] = "primary_reject"
    assert client.get("/api/limits").status_code == 502


def test_simulation_counts_usage(client):
    t = ms.today_str()
    before = ms.PRIMARY["usage"][t].get("chrome.exe", 0)
    ms.SIM["running"] = {"OVERALL", "chrome.exe"}
    ms.PRIMARY["night_lockdown"]["enabled"] = False
    ms.PRIMARY["limits"]["chrome.exe"] = {d: None for d in ms.DAYS}
    ms.PRIMARY["limits"]["OVERALL"] = {d: None for d in ms.DAYS}
    ms.tick_once(5)
    assert ms.PRIMARY["usage"][t]["chrome.exe"] == before + 5


def test_mock_control(client):
    r = client.post("/mock/control", json={"action": "online", "value": False})
    assert r.get_json()["data"]["online"] is False
    client.post("/mock/control", json={"action": "lockdown_now"})
    assert ms.lockdown_active(ms.PRIMARY["night_lockdown"])
    client.post("/mock/control", json={"action": "expire_sessions"})
    assert client.post("/mock/control", json={"action": "nope"}).status_code == 400
