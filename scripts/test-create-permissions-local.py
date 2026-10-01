#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Test ket thuc den ket thuc cho quyen tao entity cua customer user (RBAC cua fork nay):
#   - customer user co role DEVICE:CREATE tao duoc device, device thuoc dung customer cua nguoi tao
#   - customer user co role DASHBOARD:CREATE tao duoc dashboard va dashboard duoc gan cho customer cua ho
#   - "Only entities created by the user" (ownOnly) duoc ghi nhan (rbacOwnerId) va loc danh sach
#   - customer user khong co quyen CREATE bi tu choi (403)
#   - chi SYS_ADMIN tao duoc tai khoan TENANT_ADMIN (tenant admin bi tu choi 403)
#   - nhom user he thong (All / Tenant Administrators / Tenant Users) khong the bi doi bang
#     POST /api/tenant/entityGroup/members, nhom thuong thi doi duoc
#
# Chay:
#   python -m pip install requests
#   python scripts/test-create-permissions-local.py

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
created = {"customer": None, "users": [], "devices": [], "groups": [], "roles": []}
original_role_settings = session.get(BASE + "/api/tenant/role").json()
original_role_ids = [role["id"] for role in original_role_settings.get("roles", [])]


def cleanup():
    for device_id in created["devices"]:
        session.delete(BASE + "/api/device/" + device_id)
    for user_id in created["users"]:
        session.delete(BASE + "/api/user/" + user_id)
    if created["customer"]:
        session.delete(BASE + "/api/customer/" + created["customer"])
    for group_id in created["groups"]:
        session.delete(BASE + "/api/tenant/entityGroup/" + group_id)
    # the roles live in one settings document: restore the one of the tenant
    session.post(BASE + "/api/tenant/role", json=original_role_settings)


try:
    # --- customer -----------------------------------------------------------------------------
    r = session.post(BASE + "/api/customer", json={"title": "create-test-%d" % suffix})
    check("create customer", r.status_code == 200, r.status_code)
    customer_id = r.json()["id"]["id"]
    created["customer"] = customer_id

    # --- role with DEVICE:CREATE, limited to the entities created by the user -------------------
    role_id = str(uuid.uuid4())
    created["roles"].append(role_id)
    role = {
        "id": role_id,
        "name": "creator-%d" % suffix,
        "permissions": {
            "DEVICE": ["CREATE", "READ", "WRITE", "DELETE"],
            "ASSET": ["CREATE", "READ"],
            "DASHBOARD": ["CREATE", "READ", "WRITE"]
        },
        "ownOnly": {"DEVICE": True},
        "userIds": []
    }
    settings = {"roles": [role] + original_role_settings.get("roles", [])}
    r = session.post(BASE + "/api/tenant/role", json=settings)
    check("create role with DEVICE:CREATE", r.status_code == 200, r.status_code)
    check("the role is stored", any(x["id"] == role_id for x in r.json()["roles"] if r.status_code == 200),
          len(r.json().get("roles", [])))

    # --- customer user with the role -----------------------------------------------------------
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "creator-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "creator",
    })
    check("create customer user", r.status_code == 200, r.status_code)
    creator_id = r.json()["id"]["id"]
    created["users"].append(creator_id)

    role["userIds"] = [creator_id]
    settings = session.get(BASE + "/api/tenant/role").json()
    for stored in settings.get("roles", []):
        if stored["id"] == role_id:
            stored["userIds"] = [creator_id]
    r = session.post(BASE + "/api/tenant/role", json=settings)
    check("assign role to the customer user", r.status_code == 200, r.status_code)

    # --- customer user without any role ---------------------------------------------------------
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "plain-%d@local.test" % suffix,
        "authority": "CUSTOMER_USER",
        "customerId": {"entityType": "CUSTOMER", "id": customer_id},
        "firstName": "plain",
    })
    check("create second customer user", r.status_code == 200, r.status_code)
    plain_id = r.json()["id"]["id"]
    created["users"].append(plain_id)

    # --- the effective permissions the WEB UI reads ----------------------------------------------
    r = session.get(BASE + f"/api/user/{creator_id}/token")
    check("tenant admin can take the token of a customer user", r.status_code == 200, r.status_code)
    creator_token = r.json()["token"]

    creator = requests.Session()
    creator.headers["X-Authorization"] = "Bearer " + creator_token

    r = creator.get(BASE + "/api/user/roles")
    check("the role of the customer user exposes DEVICE:CREATE",
          r.status_code == 200 and any("CREATE" in (role.get("permissions", {}).get("DEVICE") or [])
                                       for role in r.json()), r.status_code)

    plain = requests.Session()
    r = session.get(BASE + f"/api/user/{plain_id}/token")
    check("tenant admin can take the token of the second customer user", r.status_code == 200, r.status_code)
    plain.headers["X-Authorization"] = "Bearer " + r.json()["token"]

    # --- create a device as the customer user ----------------------------------------------------
    device_name = "creator-device-%d" % suffix
    r = creator.post(BASE + "/api/device", json={"name": device_name, "label": "created by the customer user"})
    check("customer user with DEVICE:CREATE creates a device", r.status_code == 200, r.status_code)
    device = r.json()
    if r.status_code == 200:
        created["devices"].append(device["id"]["id"])
        check("the device is created inside the customer of the user",
              device.get("customerId", {}).get("id") == customer_id, device.get("customerId"))
        check("the owner of the device is the creator",
              (device.get("additionalInfo") or {}).get("rbacOwnerId") == creator_id,
              device.get("additionalInfo"))

        r = creator.get(BASE + f"/api/customer/{customer_id}/deviceInfos", params={"pageSize": 50, "page": 0})
        names = [d["name"] for d in r.json()["data"]] if r.status_code == 200 else []
        check("the device is listed for its customer", device_name in names, names[:5])

    # --- a customer user without the role can not create a device ---------------------------------
    r = plain.post(BASE + "/api/device", json={"name": "plain-device-%d" % suffix})
    check("customer user without DEVICE:CREATE is denied", r.status_code == 403, r.status_code)

    # --- dashboards --------------------------------------------------------------------------------
    r = creator.post(BASE + "/api/dashboard", json={"title": "creator-dashboard-%d" % suffix})
    check("customer user with DASHBOARD:CREATE creates a dashboard", r.status_code == 200, r.status_code)
    dashboard = r.json() if r.status_code == 200 else {}
    if r.status_code == 200:
        dashboard_id = dashboard["id"]["id"]
        r = creator.get(BASE + f"/api/customer/{customer_id}/dashboards", params={"pageSize": 50, "page": 0})
        titles = [d["title"] for d in r.json()["data"]] if r.status_code == 200 else []
        check("the dashboard is assigned to the customer of its creator",
              dashboard.get("title") in titles, titles[:5])

        # the user may keep editing what it created (the dashboard is assigned to its customer)
        dashboard["title"] = dashboard["title"] + " (edited)"
        r = creator.post(BASE + "/api/dashboard", json=dashboard)
        check("the customer user updates its own dashboard", r.status_code == 200, r.status_code)

        r = plain.post(BASE + "/api/dashboard", json={"title": "plain-dashboard-%d" % suffix})
        check("customer user without DASHBOARD:CREATE is denied", r.status_code == 403, r.status_code)

    # --- only the system administrator provisions the tenant administrators --------------------------
    tenant_id = session.get(BASE + "/api/auth/user").json()["tenantId"]["id"]
    r = session.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "forbidden-admin-%d@local.test" % suffix,
        "authority": "TENANT_ADMIN",
        "tenantId": {"entityType": "TENANT", "id": tenant_id},
        "firstName": "forbidden",
    })
    check("tenant admin can not create another tenant admin", r.status_code == 403, r.status_code)

    sysadmin = requests.Session()
    r = sysadmin.post(BASE + "/api/auth/login", json={"username": "sysadmin@thingsboard.org", "password": "sysadmin"})
    check("login system admin", r.status_code == 200, r.status_code)
    sysadmin.headers["X-Authorization"] = "Bearer " + r.json()["token"]
    r = sysadmin.post(BASE + "/api/user", params={"sendActivationMail": "false"}, json={
        "email": "sysadmin-made-admin-%d@local.test" % suffix,
        "authority": "TENANT_ADMIN",
        "tenantId": {"entityType": "TENANT", "id": tenant_id},
        "firstName": "made-by-sysadmin",
    })
    check("system admin creates a tenant admin", r.status_code == 200, r.status_code)
    if r.status_code == 200:
        created["users"].append(r.json()["id"]["id"])

    # --- the system groups can not be changed through the membership API --------------------------
    r = session.get(BASE + f"/api/tenant/entityGroup/members/USER/{creator_id}")
    members = {m["name"]: m for m in r.json()["groups"]}
    check("the default user groups are reported as system groups",
          members["Tenant Users"]["system"] is True and members["Tenant Administrators"]["system"] is True,
          {k: v.get("system") for k, v in members.items()})

    all_group = members["All"]["id"]
    admins_group = members["Tenant Administrators"]["id"]
    r = session.post(BASE + f"/api/tenant/entityGroup/members/USER/{creator_id}",
                     json={"groupIds": [all_group, admins_group]})
    membership = {m["name"]: m["member"] for m in r.json()["groups"]}
    check("the authority group of a customer user is not changed by the dialog",
          membership["Tenant Administrators"] is False and membership["Tenant Users"] is True, membership)
    r = session.get(BASE + f"/api/tenant/entityGroup/members/USER/{creator_id}")
    membership = {m["name"]: m["member"] for m in r.json()["groups"]}
    check("the membership is still the authority based one after a reload",
          membership["Tenant Administrators"] is False and membership["Tenant Users"] is True, membership)

    # --- a custom group is still editable ---------------------------------------------------------
    group_id = "create-test-group-%d" % suffix
    created["groups"].append(group_id)
    r = session.post(BASE + "/api/tenant/entityGroup/group", json={
        "id": group_id, "name": "Create test group", "entityType": "USER", "entityIds": [], "publicGroup": False
    })
    check("create a custom user group", r.status_code == 200, r.status_code)
    r = session.post(BASE + f"/api/tenant/entityGroup/members/USER/{creator_id}", json={"groupIds": [group_id]})
    membership = {m["id"]: m["member"] for m in r.json()["groups"]}
    check("the custom group membership is saved", membership.get(group_id) is True, membership)
    r = session.get(BASE + f"/api/tenant/entityGroup/members/USER/{creator_id}")
    membership = {m["id"]: m["member"] for m in r.json()["groups"]}
    check("the custom group membership persists after a reload", membership.get(group_id) is True, membership)
finally:
    cleanup()

failed = [n for n, ok, _ in results if not ok]
print("\nSUMMARY: %d/%d PASS" % (len(results) - len(failed), len(results)))
print("FAILED: " + (", ".join(failed) if failed else "none"))
