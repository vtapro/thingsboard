#
# SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho viec gan role truc tiep cho mot user (tab Roles trong dialog
# "Manage owner and groups"):
#   - GET  /api/tenant/user/{userId}/roles  -> moi role cua tenant + role nao dang gan truc tiep / qua nhom
#   - POST /api/tenant/user/{userId}/roles  -> dat dung danh sach role gan truc tiep
#   - quyen hieu luc cua user doi ngay (union cac role), khong dung tới user khac
#   - chi TENANT_ADMIN goi duoc API nay (customer user -> 403)
#
# Chay:
#   /tmp/emu-venv/bin/python scripts/test-user-roles-local.py

import time
import uuid

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
created = {"customer": None, "users": [], "dashboards": []}
role_id = str(uuid.uuid4())
role = {
    "id": role_id,
    "name": "user-roles-test-%d" % suffix,
    "permissions": {"DASHBOARD": ["CREATE", "READ"], "DEVICE": ["READ"]},
    "userIds": []
}
original_roles = session.get(BASE + "/api/tenant/role").json()["roles"]


def cleanup():
    for dashboard_id in created["dashboards"]:
        session.delete(BASE + "/api/dashboard/" + dashboard_id)
    for user_id in created["users"]:
        session.delete(BASE + "/api/user/" + user_id)
    if created["customer"]:
        session.delete(BASE + "/api/customer/" + created["customer"])
    # the roles live in one settings document: restore the one of the tenant
    session.post(BASE + "/api/tenant/role", json={"roles": original_roles})


def user_token(user_id):
    return session.get(BASE + f"/api/user/{user_id}/token").json()["token"]


def user_session(user_id):
    s = requests.Session()
    s.headers["X-Authorization"] = "Bearer " + user_token(user_id)
    return s


try:
    settings = {"roles": [role] + original_roles}
    r = session.post(BASE + "/api/tenant/role", json=settings)
    check("create a custom role", r.status_code == 200 and any(x["id"] == role_id for x in r.json()["roles"]),
          r.status_code)

    r = session.post(BASE + "/api/customer", json={"title": "user-roles-%d" % suffix})
    check("create customer", r.status_code == 200, r.status_code)
    customer_id = r.json()["id"]["id"]
    created["customer"] = customer_id

    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "user-roles-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "roles",
    })
    check("create customer user", r.status_code == 200, r.status_code)
    user_id = r.json()["id"]["id"]
    created["users"].append(user_id)

    # --- the assignments of the user -----------------------------------------------------------------
    r = session.get(BASE + f"/api/tenant/user/{user_id}/roles")
    check("get the roles of the user", r.status_code == 200, r.status_code)
    assignments = {x["name"]: x for x in r.json()["roles"]}
    check("the new role is listed and not assigned", assignments[role["name"]]["assigned"] is False
          and assignments[role["name"]]["direct"] is False, assignments[role["name"]])
    check("the profile role comes from the user group",
          assignments["Customer User"]["assigned"] is True and assignments["Customer User"]["direct"] is False
          and assignments["Customer User"]["groups"] == ["Customer Users"], assignments["Customer User"])

    user = user_session(user_id)
    r = user.post(BASE + "/api/dashboard", json={"title": "roles-test-%d" % suffix})
    check("the custom role is not effective before the assignment", r.status_code == 403, r.status_code)

    # --- assign the role directly ---------------------------------------------------------------------
    r = session.post(BASE + f"/api/tenant/user/{user_id}/roles", json={"roleIds": [role_id]})
    check("assign the role directly to the user", r.status_code == 200, r.status_code)
    assignments = {x["name"]: x for x in r.json()["roles"]}
    check("the role is now direct and assigned",
          assignments[role["name"]]["direct"] is True and assignments[role["name"]]["assigned"] is True,
          assignments[role["name"]])

    user = user_session(user_id)
    r = user.get(BASE + "/api/user/roles")
    check("the user gets the role", r.status_code == 200
          and role["name"] in [x["name"] for x in r.json()], [x["name"] for x in r.json()] if r.status_code == 200 else r.status_code)
    r = user.post(BASE + "/api/dashboard", json={"title": "roles-test-%d" % suffix})
    check("the permission of the role is effective right away", r.status_code == 200, r.status_code)
    if r.status_code == 200:
        created["dashboards"].append(r.json()["id"]["id"])

    # --- persisted in the database (a fresh read sees the assignment) ----------------------------------
    stored = session.get(BASE + "/api/tenant/role").json()["roles"]
    stored_role = next(x for x in stored if x["id"] == role_id)
    check("the assignment is persisted", stored_role["userIds"] == [user_id], stored_role["userIds"])

    # --- the assignment of the other users is untouched -------------------------------------------------
    other = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "user-roles-other-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "other",
    }).json()
    created["users"].append(other["id"]["id"])
    assignments = {x["name"]: x for x in session.get(BASE + f"/api/tenant/user/{other['id']['id']}/roles").json()["roles"]}
    check("the other user is not assigned to the role", assignments[role["name"]]["assigned"] is False,
          assignments[role["name"]])

    # --- remove the assignment --------------------------------------------------------------------------
    r = session.post(BASE + f"/api/tenant/user/{user_id}/roles", json={"roleIds": []})
    check("remove the direct assignment", r.status_code == 200, r.status_code)
    user = user_session(user_id)
    r = user.post(BASE + "/api/dashboard", json={"title": "roles-test-after-%d" % suffix})
    check("the permission is revoked right away", r.status_code == 403, r.status_code)

    # --- only the tenant administrator may change the assignments ----------------------------------------
    r = user.post(BASE + f"/api/tenant/user/{user_id}/roles", json={"roleIds": [role_id]})
    check("a customer user can not assign roles", r.status_code == 403, r.status_code)
    r = user.get(BASE + f"/api/tenant/user/{user_id}/roles")
    check("a customer user can not list the assignments of a user", r.status_code == 403, r.status_code)
finally:
    cleanup()

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
