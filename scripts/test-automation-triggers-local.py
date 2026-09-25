#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test cac trigger moi (INTERVAL + tu tat, TELEMETRY, DEVICE_STATE) tren dev local.
# Chay: python scripts\test-automation-triggers-local.py

import json
import threading
import time

import paho.mqtt.client as mqtt
import requests

BASE = "http://localhost:8080"
results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" | " + str(detail) if detail else ""), flush=True)


s = requests.Session()
r = s.post(BASE + "/api/auth/login", json={"username": "tenant@thingsboard.org", "password": "tenant"})
check("login tenant", r.status_code == 200, r.status_code)
s.headers["X-Authorization"] = "Bearer " + r.json()["token"]

devices = s.get(BASE + "/api/tenant/devices", params={"pageSize": 100, "page": 0}).json().get("data", [])
device = next((d for d in devices if d["name"] == "AutomationTest"), None)
if device is None:
    profiles = s.get(BASE + "/api/deviceProfiles", params={"pageSize": 1, "page": 0}).json()["data"]
    device = s.post(BASE + "/api/device", json={"name": "AutomationTest",
        "deviceProfileId": {"entityType": "DEVICE_PROFILE", "id": profiles[0]["id"]["id"]}}).json()
device_id = device["id"]["id"]
creds = s.get(BASE + f"/api/device/{device_id}/credentials").json()
if creds.get("credentialsType") != "ACCESS_TOKEN":
    creds = s.post(BASE + f"/api/device/{device_id}/credentials", json={"credentialsType": "ACCESS_TOKEN"}).json()
token = creds["credentialsId"]
check("device ready", True, device["name"])

received = []


def on_connect(client, userdata, flags, reason_code, properties=None):
    client.subscribe("v1/devices/me/rpc/request/+", qos=1)


def on_message(client, userdata, msg):
    received.append(msg.payload.decode())
    rid = msg.topic.rsplit("/", 1)[-1]
    client.publish(f"v1/devices/me/rpc/response/{rid}", json.dumps({"ok": True}), qos=1)
    print("   RPC -> " + msg.payload.decode(), flush=True)


dev = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="automation-trigger-test")
dev.username_pw_set(token)
dev.on_connect = on_connect
dev.on_message = on_message
dev.connect("localhost", 1883, 30)
dev.loop_start()
time.sleep(3)
check("device connected", dev.is_connected())


def pump_telemetry():
    while True:
        dev.publish("v1/devices/me/telemetry", json.dumps({"temperature": 30, "soilMoisture": 20}), qos=1)
        time.sleep(3)


threading.Thread(target=pump_telemetry, daemon=True).start()


def rules_of(rule_id):
    all_rules = s.get(BASE + "/api/tenant/automation").json()["rules"]
    return next((x for x in all_rules if x["id"] == rule_id), None)


def wait_until(rule_id, predicate, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(5)
        rule = rules_of(rule_id)
        if rule and predicate(rule):
            return rule
    return None


base_rule = {
    "enabled": True,
    "deviceId": {"entityType": "DEVICE", "id": device_id},
    "method": "setState",
    "params": {"state": "ON"},
    "oneWay": False,
    "persistent": False,
    "schedule": {"type": "DAILY", "time": "06:00", "timeZone": "Asia/Ho_Chi_Minh", "daysOfWeek": []}
}

# 1) INTERVAL 1 phut + tu tat sau 1 phut
rule = dict(base_rule, name="Interval + auto off (local)", triggerType="INTERVAL",
            interval={"value": 1, "unit": "MINUTES"}, durationMinutes=1, offParams={"state": "OFF"})
resp = s.post(BASE + "/api/tenant/automation", json=rule)
check("create INTERVAL rule", resp.status_code == 200, resp.text[:150])
rid = resp.json()["id"]
ran = wait_until(rid, lambda x: x.get("lastStatus") == "OK", 150)
check("INTERVAL rule ran", ran is not None, ran and ran.get("lastMessage"))
off = wait_until(rid, lambda x: any("auto off" in (i.get("message") or "") for i in x.get("runs", [])), 150)
check("auto OFF after duration", off is not None,
      [i["message"] for i in (off or {}).get("runs", [])[:3]])
s.delete(BASE + f"/api/tenant/automation/{rid}")

# 2) TELEMETRY threshold (temperature > -100 luon dung)
rule = dict(base_rule, name="Telemetry threshold (local)", triggerType="TELEMETRY",
            condition={"key": "temperature", "operator": "GT", "value": -100, "forSeconds": 0, "cooldownMinutes": 0})
resp = s.post(BASE + "/api/tenant/automation", json=rule)
check("create TELEMETRY rule", resp.status_code == 200, resp.text[:150])
rid = resp.json()["id"]
ran = wait_until(rid, lambda x: x.get("lastStatus") == "OK", 90)
check("TELEMETRY rule ran", ran is not None, ran and ran.get("lastMessage"))
s.delete(BASE + f"/api/tenant/automation/{rid}")

# 3) DEVICE_STATE ONLINE
rule = dict(base_rule, name="Device online (local)", triggerType="DEVICE_STATE",
            deviceState={"state": "ONLINE"})
resp = s.post(BASE + "/api/tenant/automation", json=rule)
check("create DEVICE_STATE rule", resp.status_code == 200, resp.text[:150])
rid = resp.json()["id"]
ran = wait_until(rid, lambda x: x.get("lastStatus") == "OK", 90)
check("DEVICE_STATE rule ran", ran is not None, ran and ran.get("lastMessage"))
s.delete(BASE + f"/api/tenant/automation/{rid}")

dev.loop_stop()
dev.disconnect()

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
