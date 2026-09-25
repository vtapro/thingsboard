#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho tinh nang Automation tren moi truong dev local
# (backend ThingsBoard o localhost:8080, MQTT 1883, PostgreSQL + queue in-memory).
#
# Script se:
#   1. dang nhap tenant admin (tenant@thingsboard.org / tenant)
#   2. tao thiet bi AutomationTest + access token, ket noi MQTT de dong vai thiet bi
#   3. tao rule "hen gio bat may bom" (RPC setState) va bam "run now" -> kiem tra thiet bi nhan duoc RPC
#   4. doi den phut hen -> kiem tra scheduler tu chay va thiet bi nhan RPC
#   5. sua / tat / xoa rule
#
# Chay:
#   python -m pip install paho-mqtt requests
#   python scripts\test-automation-local.py

import json
import time

import paho.mqtt.client as mqtt
import requests

BASE = "http://localhost:8080"
MQTT_HOST = "localhost"
MQTT_PORT = 1883

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" | " + str(detail) if detail else ""), flush=True)


session = requests.Session()
r = session.post(BASE + "/api/auth/login", json={"username": "tenant@thingsboard.org", "password": "tenant"})
check("login tenant admin", r.status_code == 200, r.status_code)
session.headers["X-Authorization"] = "Bearer " + r.json()["token"]

# --- device + access token -------------------------------------------------------------------
r = session.get(BASE + "/api/tenant/devices", params={"deviceName": "AutomationTest", "pageSize": 10, "page": 0})
devices = r.json().get("data", [])
device = devices[0] if devices else None
if device is None:
    profiles = session.get(BASE + "/api/deviceProfiles", params={"pageSize": 1, "page": 0}).json()["data"]
    r = session.post(BASE + "/api/device", json={
        "name": "AutomationTest",
        "deviceProfileId": {"entityType": "DEVICE_PROFILE", "id": profiles[0]["id"]["id"]}
    })
    check("create device", r.status_code == 200, r.status_code)
    device = r.json()
device_id = device["id"]["id"]
creds = session.get(BASE + f"/api/device/{device_id}/credentials").json()
if creds.get("credentialsType") != "ACCESS_TOKEN":
    creds = session.post(BASE + f"/api/device/{device_id}/credentials",
                         json={"credentialsType": "ACCESS_TOKEN"}).json()
device_token = creds["credentialsId"]
check("device ready", device_id is not None and device_token is not None, device["name"])

# --- MQTT listener (plays the role of the pump controller) -----------------------------------
received = []


def on_connect(client, userdata, flags, reason_code, properties=None):
    client.subscribe("v1/devices/me/rpc/request/+", qos=1)


def on_message(client, userdata, msg):
    payload = msg.payload.decode()
    received.append((msg.topic, payload))
    request_id = msg.topic.rsplit("/", 1)[-1]
    client.publish(f"v1/devices/me/rpc/response/{request_id}", json.dumps({"ok": True}), qos=1)
    print("   MQTT <- " + msg.topic + " " + payload, flush=True)


listener = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="automation-test-device")
listener.username_pw_set(device_token)
listener.on_connect = on_connect
listener.on_message = on_message
listener.connect(MQTT_HOST, MQTT_PORT, keepalive=30)
listener.loop_start()
time.sleep(3)
check("device connected to MQTT", listener.is_connected())

# --- create automation rule (1 minute ahead, so the scheduler also fires) ---------------------
start_at = time.localtime(time.time() + 70)
scheduled_time = time.strftime("%H:%M", start_at)
rule = {
    "name": "Tuoi cay (local test)",
    "enabled": True,
    "deviceId": {"entityType": "DEVICE", "id": device_id},
    "method": "setState",
    "params": {"state": "ON", "duration": 300},
    "oneWay": False,
    "persistent": False,
    "schedule": {"type": "DAILY", "time": scheduled_time, "timeZone": "Asia/Ho_Chi_Minh", "daysOfWeek": []}
}
r = session.post(BASE + "/api/tenant/automation", json=rule)
check("create automation rule", r.status_code == 200, r.text[:200])
saved = r.json()
rule_id = saved.get("id")
check("nextRunTs computed", bool(saved.get("nextRunTs")),
      time.strftime("%Y-%m-%d %H:%M", time.localtime(saved["nextRunTs"] / 1000)) if saved.get("nextRunTs") else None)

# --- run now ---------------------------------------------------------------------------------
r = session.post(BASE + f"/api/tenant/automation/{rule_id}/run")
check("run now", r.status_code == 200, r.status_code)
time.sleep(3)
check("RPC delivered to device (run now)", len(received) >= 1, received)

listed = session.get(BASE + "/api/tenant/automation").json()["rules"]
current = next((x for x in listed if x["id"] == rule_id), None)
check("run history stored", bool(current and current.get("lastStatus")),
      current.get("lastStatus") if current else None)

# --- wait for the scheduled minute (scheduler) ------------------------------------------------
print("waiting for the scheduled run at " + scheduled_time + " ...", flush=True)
deadline = time.time() + 150
scheduled_fired = False
while time.time() < deadline:
    time.sleep(10)
    listed = session.get(BASE + "/api/tenant/automation").json()["rules"]
    current = next((x for x in listed if x["id"] == rule_id), None)
    if current and current.get("lastRunTs") and current["lastRunTs"] > saved["nextRunTs"] - 1000:
        scheduled_fired = True
        break
check("scheduler executed the rule", scheduled_fired, f"runs={len(current.get('runs', [])) if current else 0}")
check("RPC delivered by scheduler", len(received) >= 2, len(received))

# --- update + disable + delete ----------------------------------------------------------------
rule_update = dict(rule)
rule_update["id"] = rule_id
rule_update["name"] = "Tuoi cay (renamed)"
rule_update["enabled"] = False
r = session.post(BASE + "/api/tenant/automation", json=rule_update)
check("update rule", r.status_code == 200 and r.json()["name"].endswith("(renamed)"), r.status_code)
r = session.post(BASE + f"/api/tenant/automation/{rule_id}/enabled/false")
check("disable rule", r.status_code == 200 and r.json()["enabled"] is False, r.status_code)
r = session.delete(BASE + f"/api/tenant/automation/{rule_id}")
check("delete rule", r.status_code == 200, r.status_code)
left = session.get(BASE + "/api/tenant/automation").json()["rules"]
check("rule removed from list", all(x["id"] != rule_id for x in left), len(left))

listener.loop_stop()
listener.disconnect()

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
