#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho luong "khach mua thiet bi roi tu them" (claiming):
#   - tenant admin dang ky thiet bi (chua gan customer) va ghi "claimingData" (secret + han) nhu thiet bi gui
#   - customer user thuong (profile Customer User) claim bang ten + secret -> thiet bi ve dung customer cua ho
#   - claim sai secret -> that bai, thiet bi khong doi customer
#   - customer user khong co quyen CLAIM_DEVICES -> 403
#   - trung ten role -> 400
#
# Chay:
#   /tmp/emu-venv/bin/python scripts/test-device-claim-local.py

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

suffix = int(time.time())
created = {"customer": None, "users": [], "devices": []}
original_roles = session.get(BASE + "/api/tenant/role").json()["roles"]


def cleanup():
    for device_id in created["devices"]:
        session.delete(BASE + "/api/device/" + device_id)
    for user_id in created["users"]:
        session.delete(BASE + "/api/user/" + user_id)
    if created["customer"]:
        session.delete(BASE + "/api/customer/" + created["customer"])
    # keep the roles as they were (the test adds a role and a duplicate name check)
    session.post(BASE + "/api/tenant/role", json={"roles": original_roles})


try:
    # --- a device the provider pre-registered for the customer ---------------------------------------
    r = session.post(BASE + "/api/customer", json={"title": "claim-test-%d" % suffix})
    check("create customer", r.status_code == 200, r.status_code)
    customer_id = r.json()["id"]["id"]
    created["customer"] = customer_id

    r = session.post(BASE + "/api/device", json={"name": "claim-device-%d" % suffix})
    check("provider registers the device (unassigned)", r.status_code == 200, r.status_code)
    device = r.json()
    created["devices"].append(device["id"]["id"])
    check("the device is not assigned to a customer yet",
          (device.get("customerId") or {}).get("id") in (None, "13814000-1dd2-11b2-8080-808080808080"),
          device.get("customerId"))

    secret = "secret-%d" % suffix
    r = session.post(BASE + "/api/plugins/telemetry/DEVICE/%s/attributes/SERVER_SCOPE" % device["id"]["id"],
                     json={"claimingData": json.dumps({"secretKey": secret,
                                                       "expirationTime": int(time.time() * 1000) + 3600_000})})
    check("the claim request of the device is registered", r.status_code == 200, r.status_code)

    # --- customer user (plain profile) ---------------------------------------------------------------
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "claim-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "claim",
    })
    check("create customer user", r.status_code == 200, r.status_code)
    user_id = r.json()["id"]["id"]
    created["users"].append(user_id)

    token = session.get(BASE + f"/api/user/{user_id}/token").json()["token"]
    user = requests.Session()
    user.headers["X-Authorization"] = "Bearer " + token

    roles = user.get(BASE + "/api/user/roles").json()
    check("the plain user gets the 'Customer User' profile",
          [role["name"] for role in roles] == ["Customer User"], [role["name"] for role in roles])
    check("the profile grants claiming but not creating",
          "CLAIM_DEVICES" in (roles[0]["permissions"].get("DEVICE") or [])
          and "READ" in (roles[0]["permissions"].get("DEVICE") or []), roles[0]["permissions"].get("DEVICE"))

    r = user.post(BASE + "/api/device", json={"name": "not-allowed-%d" % suffix})
    check("the plain user still can not create a device", r.status_code == 403, r.status_code)

    # wrong secret first, the device must keep its owner
    r = user.post(BASE + f"/api/customer/device/{device['name']}/claim", json={"secretKey": "wrong"})
    check("claiming with a wrong secret fails", r.status_code in (400, 200) and "FAILURE" in r.text, r.text[:80])

    r = user.post(BASE + f"/api/customer/device/{device['name']}/claim", json={"secretKey": secret})
    check("the user claims the device", r.status_code == 200 and "SUCCESS" in r.text, r.text[:120])

    device_id = device["id"]["id"]
    assigned = session.get(BASE + "/api/device/" + device_id).json()
    check("the device belongs to the customer of the user",
          (assigned.get("customerId") or {}).get("id") == customer_id, assigned.get("customerId"))
    r = user.get(BASE + f"/api/customer/{customer_id}/deviceInfos", params={"pageSize": 20, "page": 0})
    check("the claimed device shows up for the customer user",
          r.status_code == 200 and device["name"] in [d["name"] for d in r.json()["data"]], r.status_code)

    # --- a role without CLAIM_DEVICES can not claim ---------------------------------------------------
    r = session.post(BASE + "/api/device", json={"name": "claim-device-2-%d" % suffix})
    created["devices"].append(r.json()["id"]["id"])
    device2 = r.json()
    session.post(BASE + "/api/plugins/telemetry/DEVICE/%s/attributes/SERVER_SCOPE" % device2["id"]["id"],
                 json={"claimingData": json.dumps({"secretKey": secret,
                                                   "expirationTime": int(time.time() * 1000) + 3600_000})})
    role_id = "no-claim-%d" % suffix
    roles = [{"id": role_id, "name": role_id, "permissions": {"DEVICE": ["READ"]}, "userIds": [user_id]}] + \
            session.get(BASE + "/api/tenant/role").json()["roles"]
    session.post(BASE + "/api/tenant/role", json={"roles": roles})
    # the profile still grants claiming: the union of the roles keeps it, so remove it from the profile first
    profile_roles = session.get(BASE + "/api/tenant/role").json()["roles"]
    for role in profile_roles:
        if role["name"] == "Customer User":
            role["permissions"]["DEVICE"] = ["READ", "READ_ATTRIBUTES", "READ_TELEMETRY"]
    session.post(BASE + "/api/tenant/role", json={"roles": profile_roles})
    r = user.post(BASE + f"/api/customer/device/{device2['name']}/claim", json={"secretKey": secret})
    check("a role without CLAIM_DEVICES can not claim", r.status_code == 403, r.status_code)

    # --- two roles of a tenant may not share a name ----------------------------------------------------
    duplicate = {"id": "dup-%d" % suffix, "name": "Customer User", "permissions": {"DEVICE": ["READ"]}, "userIds": []}
    r = session.post(BASE + "/api/tenant/role", json={"roles": [duplicate] + original_roles})
    check("a duplicate role name is rejected", r.status_code == 400, r.status_code)
finally:
    cleanup()

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
