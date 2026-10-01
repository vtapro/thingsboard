#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho tinh nang "Manage owner and groups" tren moi truong dev local:
#   - API thanh vien entity group: GET/POST /api/tenant/entityGroup/members/{entityType}/{entityId}
#   - doi owner (customer) cua mot user qua API chuan POST /api/user + cac guard bao ve
#
# Chay:
#   python -m pip install requests
#   python scripts/test-owner-groups-local.py

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
tenant_token = r.json()["token"]
session.headers["X-Authorization"] = "Bearer " + tenant_token

customers = session.get(BASE + "/api/customers", params={"pageSize": 2, "page": 0}).json()["data"]
check("tenant has at least 2 customers", len(customers) >= 2, len(customers))
c1, c2 = customers[0]["id"]["id"], customers[1]["id"]["id"]

r = session.get(BASE + "/api/tenant/entityGroup")
user_groups = {g["name"] for g in r.json()["groups"] if g["entityType"] == "USER"}
check("default user groups of PE exist",
      {"All", "Tenant Administrators", "Tenant Users"} <= user_groups, sorted(user_groups))

suffix = int(time.time())
group_id = "owner-test-%d" % suffix
created_user_id = None

try:
    # --- customer user of the first customer -------------------------------------------------
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "owner-test-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": c1},
        "firstName": "owner",
    })
    check("create customer user", r.status_code == 200, r.status_code)
    user = r.json()
    created_user_id = user["id"]["id"]
    check("user belongs to the first customer", user["customerId"]["id"] == c1)

    # --- user groups membership ---------------------------------------------------------------
    r = session.get(BASE + f"/api/tenant/entityGroup/members/USER/{created_user_id}")
    check("get group membership", r.status_code == 200, r.status_code)
    members = {m["name"]: m for m in r.json()["groups"]}
    check("the All group is always a membership", members.get("All", {}).get("member") is True,
          list(members.keys()))
    check("a customer user belongs to Tenant Users", members.get("Tenant Users", {}).get("member") is True,
          {k: v["member"] for k, v in members.items()})
    check("a customer user is not a Tenant Administrator",
          members.get("Tenant Administrators", {}).get("member") is False,
          {k: v["member"] for k, v in members.items()})

    r = session.post(BASE + "/api/tenant/entityGroup/group", json={
        "id": group_id, "name": "Owner test group", "entityType": "USER", "entityIds": [], "publicGroup": False
    })
    check("create user group", r.status_code == 200, r.status_code)

    r = session.post(BASE + f"/api/tenant/entityGroup/members/USER/{created_user_id}",
                     json={"groupIds": [group_id]})
    check("add user to the group", r.status_code == 200, r.status_code)
    membership = {m["id"]: m["member"] for m in r.json()["groups"]}
    check("user is a member of the group", membership.get(group_id) is True, membership)

    r = session.post(BASE + f"/api/tenant/entityGroup/members/USER/{created_user_id}", json={"groupIds": []})
    membership = {m["id"]: m["member"] for m in r.json()["groups"]}
    check("user removed from the group", membership.get(group_id) is False, membership)

    # other users must not be touched by the previous call
    r = session.get(BASE + "/api/tenant/entityGroup")
    stored = next(g for g in r.json()["groups"] if g["id"] == group_id)
    check("membership of the other entities untouched", stored["entityIds"] in ([], None), stored["entityIds"])

    # --- owner change --------------------------------------------------------------------------
    user["customerId"] = {"entityType": "CUSTOMER", "id": c2}
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json=user)
    check("move the user to the second customer", r.status_code == 200 and r.json()["customerId"]["id"] == c2,
          r.status_code)

    r = session.get(BASE + "/api/user/" + created_user_id)
    check("owner persisted", r.json()["customerId"]["id"] == c2, r.json()["customerId"])

    # a tenant administrator has no owner
    me = session.get(BASE + "/api/auth/user").json()
    me["customerId"] = {"entityType": "CUSTOMER", "id": c2}
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json=me)
    check("tenant administrator can not get an owner", r.status_code == 400, r.status_code)

    # unknown customer
    user["customerId"] = {"entityType": "CUSTOMER", "id": "00000000-0000-0000-0000-000000000001"}
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json=user)
    check("unknown owner rejected", r.status_code == 404, r.status_code)

    # group membership of an entity that does not exist
    r = session.get(BASE + "/api/tenant/entityGroup/members/USER/00000000-0000-0000-0000-000000000002")
    check("unknown entity rejected", r.status_code in (400, 404), r.status_code)
finally:
    if created_user_id:
        session.delete(BASE + "/api/user/" + created_user_id)
    session.delete(BASE + "/api/tenant/entityGroup/" + group_id)

r = session.get(BASE + "/api/tenant/entityGroup")
check("test group cleaned up", all(g["id"] != group_id for g in r.json()["groups"]), len(r.json()["groups"]))

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
