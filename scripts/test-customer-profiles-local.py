#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho 2 profile customer user (giong ThingsBoard PE):
#   - tenant co san 2 role "Customer Administrator" / "Customer User" va 2 nhom USER cung ten
#   - customer user moi mac dinh thuoc nhom "Customer Users" (chi xem)
#   - chuyen user sang nhom "Customer Administrators" (dialog Manage owner and groups) thi user
#     tao duoc device / asset / entity view / dashboard, va quan ly duoc user cua customer minh
#   - chuyen nguoc lai thi quyen bi thu hoi ngay
#
# Chay:
#   python -m pip install requests
#   python scripts/test-customer-profiles-local.py

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
created = {"customer": None, "users": [], "devices": [], "assets": [], "entity_views": [], "dashboards": []}


def cleanup():
    # the entity views reference the devices, so they have to go first (the platform refuses to delete a device
    # that is referenced by an entity view)
    for view_id in created["entity_views"]:
        session.delete(BASE + "/api/entityView/" + view_id)
    for device_id in created["devices"]:
        session.delete(BASE + "/api/device/" + device_id)
    for asset_id in created["assets"]:
        session.delete(BASE + "/api/asset/" + asset_id)
    for dashboard_id in created["dashboards"]:
        session.delete(BASE + "/api/dashboard/" + dashboard_id)
    for user_id in created["users"]:
        session.delete(BASE + "/api/user/" + user_id)
    if created["customer"]:
        session.delete(BASE + "/api/customer/" + created["customer"])


def members(entity_id):
    r = session.get(BASE + f"/api/tenant/entityGroup/members/USER/{entity_id}")
    return {m["name"]: m for m in r.json()["groups"]}


def set_profile(entity_id, group_name):
    current = members(entity_id)
    group_ids = [group["id"] for name, group in current.items() if name == "All" or name == group_name]
    return session.post(BASE + f"/api/tenant/entityGroup/members/USER/{entity_id}", json={"groupIds": group_ids})


def user_session(user_id):
    token = session.get(BASE + f"/api/user/{user_id}/token").json()["token"]
    s = requests.Session()
    s.headers["X-Authorization"] = "Bearer " + token
    return s


try:
    # --- the two profiles are pre-configured ------------------------------------------------------
    roles = session.get(BASE + "/api/tenant/role").json()["roles"]
    role_names = {role["name"] for role in roles}
    check("the tenant has the 'Customer Administrator' role",
          "Customer Administrator" in role_names, sorted(role_names))
    check("the tenant has the 'Customer User' role",
          "Customer User" in role_names, sorted(role_names))

    user_groups = session.get(BASE + "/api/tenant/userGroup").json().get("groups", [])
    profile_groups = {group["name"]: group for group in user_groups
                      if group["name"] in ("Customer Administrators", "Customer Users")}
    check("the two customer profile groups exist",
          {"Customer Administrators", "Customer Users"} <= set(profile_groups), sorted(profile_groups))
    check("the profile groups carry the pre-configured roles",
          all(len(group.get("roleIds") or []) == 1 for group in profile_groups.values()),
          {name: group.get("roleIds") for name, group in profile_groups.items()})

    # --- a new customer user is a plain user -------------------------------------------------------
    r = session.post(BASE + "/api/customer", json={"title": "profile-test-%d" % suffix})
    check("create customer", r.status_code == 200, r.status_code)
    customer_id = r.json()["id"]["id"]
    created["customer"] = customer_id

    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "profile-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "profile",
    })
    check("create customer user", r.status_code == 200, r.status_code)
    user_id = r.json()["id"]["id"]
    created["users"].append(user_id)

    membership = members(user_id)
    check("a new customer user is a plain 'Customer Users' member",
          membership["Customer Users"]["member"] is True
          and membership["Customer Administrators"]["member"] is False,
          {k: v["member"] for k, v in membership.items()})
    check("the two customer profile groups are managed by the platform",
          membership["Customer Users"]["system"] is True
          and membership["Customer Administrators"]["system"] is True,
          {k: v.get("system") for k, v in membership.items()})

    # the platform groups can not be renamed nor deleted (the profiles and the mirrored user groups depend on them)
    r = session.delete(BASE + "/api/tenant/entityGroup/" + membership["Customer Users"]["id"])
    check("the profile group can not be deleted", r.status_code == 400, r.status_code)
    renamed = {"id": membership["Customer Users"]["id"], "name": "Renamed by the test", "entityType": "USER",
               "entityIds": [], "publicGroup": False}
    r = session.post(BASE + "/api/tenant/entityGroup/group", json=renamed)
    check("the profile group can not be renamed", r.status_code == 400, r.status_code)

    user = user_session(user_id)
    r = user.get(BASE + "/api/user/roles")
    check("the user gets the 'Customer User' role",
          r.status_code == 200 and [role["name"] for role in r.json()] == ["Customer User"],
          [role["name"] for role in r.json()] if r.status_code == 200 else r.status_code)
    r = user.post(BASE + "/api/device", json={"name": "profile-device-plain-%d" % suffix})
    check("a plain customer user can not create a device", r.status_code == 403, r.status_code)

    # --- promote the user to the administrator of its customer --------------------------------------
    r = set_profile(user_id, "Customer Administrators")
    check("move the user to 'Customer Administrators'", r.status_code == 200, r.status_code)
    membership = members(user_id)
    check("the user is now a customer administrator",
          membership["Customer Administrators"]["member"] is True
          and membership["Customer Users"]["member"] is False,
          {k: v["member"] for k, v in membership.items()})

    user = user_session(user_id)
    r = user.get(BASE + "/api/user/roles")
    check("the user gets the 'Customer Administrator' role",
          r.status_code == 200 and [role["name"] for role in r.json()] == ["Customer Administrator"],
          [role["name"] for role in r.json()] if r.status_code == 200 else r.status_code)

    for name, endpoint, body, collection in (
            ("device", "/api/device", {"name": "profile-device-%d" % suffix}, "devices"),
            ("asset", "/api/asset", {"name": "profile-asset-%d" % suffix}, "assets"),
            ("dashboard", "/api/dashboard", {"title": "profile-dashboard-%d" % suffix}, "dashboards")):
        r = user.post(BASE + endpoint, json=body)
        check(f"the customer administrator creates a {name}", r.status_code == 200, r.status_code)
        if r.status_code == 200:
            created[collection].append(r.json()["id"]["id"])

    # an entity view needs a referenced device
    r = user.post(BASE + "/api/entityView", json={
        "name": "profile-view-%d" % suffix,
        "type": "test",
        "entityId": {"entityType": "DEVICE", "id": created["devices"][0]},
    })
    check("the customer administrator creates an entity view", r.status_code == 200, r.status_code)
    if r.status_code == 200:
        created["entity_views"].append(r.json()["id"]["id"])

    # the administrator manages the users of its own customer
    r = user.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "profile-member-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "member",
    })
    check("the customer administrator adds a user of its customer", r.status_code == 200, r.status_code)
    if r.status_code == 200:
        created["users"].append(r.json()["id"]["id"])

    # --- demote the user again ----------------------------------------------------------------------
    r = set_profile(user_id, "Customer Users")
    check("move the user back to 'Customer Users'", r.status_code == 200, r.status_code)
    user = user_session(user_id)
    r = user.post(BASE + "/api/device", json={"name": "profile-device-demoted-%d" % suffix})
    check("the demoted user can not create a device any more", r.status_code == 403, r.status_code)
finally:
    cleanup()

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
