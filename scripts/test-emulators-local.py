#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho tinh nang Emulators tren moi truong dev local
# (backend ThingsBoard o localhost:8080, PostgreSQL + queue in-memory).
#
# Script se:
#   1. dang nhap tenant admin (tenant@thingsboard.org / tenant)
#   2. doc catalog: cac profile theo linh vuc (Energy/Agriculture/Industrial/Lighting/Water/...)
#   3. tao emulator cho 1 linh vuc -> kiem tra device + dashboard duoc tao
#   4. sinh history 24h -> kiem tra telemetry co du lieu
#   5. start emulator -> kiem tra scheduler publish telemetry moi
#   6. doi scenario / interval, tao lai dashboard, xoa emulator (kem device)
#
# Chay:
#   python -m pip install requests
#   python scripts/test-emulators-local.py

import collections
import json
import time

import requests

BASE = "http://localhost:8080"

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" | " + str(detail) if detail else ""), flush=True)


session = requests.Session()
r = session.post(BASE + "/api/auth/login", json={"username": "tenant@thingsboard.org", "password": "tenant"})
check("login tenant admin", r.status_code == 200, r.status_code)
session.headers["X-Authorization"] = "Bearer " + r.json()["token"]

# --- catalog -----------------------------------------------------------------------------------
r = session.get(BASE + "/api/tenant/emulator/catalog")
catalog = r.json() if r.status_code == 200 else {}
profiles = catalog.get("profiles", [])
categories = catalog.get("categories", [])
check("catalog returned", r.status_code == 200 and len(profiles) >= 10, f"{len(profiles)} profiles")
check("domains covered", len({"Energy", "Agriculture", "Industrial", "Lighting"} & set(categories)) == 4, categories)
check("PE domains covered",
      {"Cold Chain", "Environment", "Fleet", "Healthcare", "Retail", "Smart Home", "Utilities"}
      <= set(categories), len(categories))
check("profiles carry a model", all(p.get("model") for p in profiles), profiles[0].get("model"))
per_category = collections.Counter(p["category"] for p in profiles)
check("five profiles per domain", len(profiles) == 75 and all(count == 5 for count in per_category.values()),
      dict(sorted(per_category.items())))
check("every profile has 4-6 signals", all(4 <= len(p["signals"]) <= 6 for p in profiles),
      min(len(p["signals"]) for p in profiles))

profile = next(p for p in profiles if p["id"] == "greenhouse-system")
check("profile has signals and scenarios",
      len(profile["signals"]) >= 3 and len(profile["scenarios"]) >= 2,
      f'{len(profile["signals"])} signals')

# --- create emulator (device + dashboard) -------------------------------------------------------
name = "test-greenhouse-%d" % int(time.time())
r = session.post(BASE + "/api/tenant/emulator", json={
    "profileId": profile["id"],
    "name": name,
    "scenario": profile["defaultScenario"],
    "intervalSeconds": 5,
    "createDashboard": True
})
check("create emulator", r.status_code == 200, r.status_code)
emulator = r.json()
emulator_id = emulator["id"]
device_id = emulator["deviceId"]["id"]
dashboard_id = emulator.get("dashboardId")
check("device created", bool(device_id))
check("dashboard created for the domain", bool(dashboard_id), emulator.get("dashboardTitle"))

r = session.get(BASE + "/api/dashboard/" + dashboard_id)
dashboard = r.json() if r.status_code == 200 else {}
widgets = (dashboard.get("configuration") or {}).get("widgets") or {}
aliases = (dashboard.get("configuration") or {}).get("entityAliases") or {}
keys = [k["name"] for k in list(widgets.values())[0]["config"]["datasources"][0]["dataKeys"]]
check("dashboard has one chart per signal", len(widgets) == len(profile["signals"]) + 1, len(widgets))
check("dashboard plots the profile keys", set(keys) == {s["key"] for s in profile["signals"]}, keys)
check("dashboard bound to the emulator device",
      list(aliases.values())[0]["filter"]["entityList"] == [device_id])

# --- history ------------------------------------------------------------------------------------
r = session.post(BASE + f"/api/tenant/emulator/{emulator_id}/history?hours=6")
check("generate history", r.status_code == 200 and r.json().get("published", 0) > 0,
      r.json() if r.status_code == 200 else r.status_code)
# the messages are processed asynchronously by the rule engine (the core service may be busy)
time.sleep(40)
end_ts = int(time.time() * 1000)
start_ts = end_ts - 7 * 3600 * 1000
r = session.get(BASE + f"/api/plugins/telemetry/DEVICE/{device_id}/values/timeseries",
                params={"keys": "airTemperature,soilMoisture", "startTs": start_ts, "endTs": end_ts, "limit": 2000,
                        "agg": "NONE"})
telemetry = r.json() if r.status_code == 200 else {}
check("history stored as telemetry", len(telemetry.get("airTemperature", [])) > 100,
      {k: len(v) for k, v in telemetry.items()})

# --- start / pause / stop -----------------------------------------------------------------------
r = session.post(BASE + f"/api/tenant/emulator/{emulator_id}/RUNNING")
check("start emulator", r.status_code == 200 and r.json()["status"] == "RUNNING", r.status_code)
time.sleep(20)
r = session.get(BASE + "/api/tenant/emulator")
current = next((e for e in r.json() if e["id"] == emulator_id), None)
check("telemetry keeps being published", bool(current) and current["publishedMessages"] > 0,
      current["publishedMessages"] if current else None)

r = session.get(BASE + f"/api/plugins/telemetry/DEVICE/{device_id}/values/timeseries",
                params={"keys": "airTemperature"})
check("latest telemetry available for the dashboard", r.status_code == 200 and "airTemperature" in r.json(),
      r.json() if r.status_code == 200 else r.status_code)

# the device of a running emulator must be shown as "active" (device state service)
r = session.get(BASE + "/api/tenant/deviceInfos", params={"pageSize": 20, "page": 0, "textSearch": name})
device_info = next((d for d in (r.json().get("data") or []) if d["id"]["id"] == device_id), {})
check("device is active while the emulator runs", device_info.get("active") is True, device_info.get("active"))
r = session.get(BASE + f"/api/plugins/telemetry/DEVICE/{device_id}/values/attributes/SERVER_SCOPE")
attributes = {a["key"]: a["value"] for a in (r.json() or [])}
check("inactivity timeout set for the device state service", attributes.get("inactivityTimeout", 0) > 0,
      attributes.get("inactivityTimeout"))

r = session.post(BASE + f"/api/tenant/emulator/{emulator_id}/PAUSED")
check("pause emulator", r.status_code == 200 and r.json()["status"] == "PAUSED", r.status_code)

# --- scenario / interval / dashboard recreation --------------------------------------------------
r = session.post(BASE + f"/api/tenant/emulator/{emulator_id}/scenario",
                 params={"scenario": "Heat Wave", "intervalSeconds": 30})
check("switch scenario and interval",
      r.status_code == 200 and r.json()["scenario"] == "Heat Wave" and r.json()["intervalSeconds"] == 30,
      r.status_code)
r = session.post(BASE + f"/api/tenant/emulator/{emulator_id}/dashboard")
check("recreate dashboard", r.status_code == 200 and r.json().get("dashboardId"), r.status_code)

# --- delete (device goes away) --------------------------------------------------------------------
r = session.delete(BASE + f"/api/tenant/emulator/{emulator_id}")
check("delete emulator", r.status_code == 200, r.status_code)

# the dashboard of the emulator is a normal dashboard of the tenant: the test removes it as well, otherwise the
# emulator dashboards of every run would pile up in the database
if dashboard_id:
    session.delete(BASE + "/api/dashboard/" + dashboard_id)
r = session.get(BASE + "/api/tenant/emulator")
check("emulator removed from the list", all(e["id"] != emulator_id for e in r.json()), len(r.json()))
r = session.get(BASE + f"/api/device/{device_id}")
check("device of the emulator deleted", r.status_code == 404, r.status_code)

# --- realistic counters (a meter in service, not a meter starting from zero) -----------------------
meter = session.post(BASE + "/api/tenant/emulator", json={
    "profileId": "electricity-meter-residential", "name": "test-meter-%d" % int(time.time()),
    "createDashboard": False
}).json()
session.post(BASE + f"/api/tenant/emulator/{meter['id']}/history?hours=1")
time.sleep(20)
r = session.get(BASE + f"/api/plugins/telemetry/DEVICE/{meter['deviceId']['id']}/values/timeseries",
                params={"keys": "energy"})
reading = float(r.json()["energy"][0]["value"]) if r.status_code == 200 and r.json().get("energy") else 0
check("meter starts from a realistic reading", reading > 24000, reading)
session.delete(BASE + "/api/tenant/emulator/" + meter["id"])

# --- clear unlinked -------------------------------------------------------------------------------
unlinked = session.post(BASE + "/api/tenant/emulator", json={
    "profileId": "gas-meter", "name": "test-unlinked-%d" % int(time.time()), "createDashboard": False
}).json()
session.delete(BASE + "/api/device/" + unlinked["deviceId"]["id"])
r = session.post(BASE + "/api/tenant/emulator/clearUnlinked")
check("clear unlinked removes the orphan emulator",
      r.status_code == 200 and r.json().get("cleared", 0) >= 1, r.json())
r = session.get(BASE + "/api/tenant/emulator")
check("orphan emulator gone", all(e["id"] != unlinked["id"] for e in r.json()), len(r.json()))

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
