#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
/**
 * End-to-end audit of the custom RBAC rules against a running ThingsBoard (node scripts/test-rbac-full-local.mjs).
 *
 * The script reads the effective roles of the user (GET /api/user/roles), derives the same permission matrix as
 * TbRbacAccessControlService (the basic operations imply the auxiliary read/write ones, credentials have to be
 * granted explicitly, a resource that no role configures keeps the platform permissions) and then checks the entity
 * APIs that the WEB UI calls when the details panel is opened.
 *
 * Usage:
 *   node scripts/test-rbac-full-local.mjs --login user@thingsboard.org:password --device-id <uuid>
 *   TB_TOKEN=<jwt> node scripts/test-rbac-full-local.mjs --all-devices
 */
import process from 'node:process';

const BASIC_OPERATIONS = ['CREATE', 'READ', 'WRITE', 'DELETE'];
const CREDENTIAL_OPERATIONS = ['READ_CREDENTIALS', 'WRITE_CREDENTIALS'];

const arg = (name, fallback = null) => {
  const index = process.argv.indexOf(`--${name}`);
  return index > -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
};
const flag = (name) => process.argv.includes(`--${name}`);

const base = arg('base', process.env.TB_BASE || 'http://localhost:8080');

const http = async (method, path, token, body) => {
  const response = await fetch(base + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body ? {'Content-Type': 'application/json'} : {}),
      ...(token ? {'X-Authorization': 'Bearer ' + token} : {})
    },
    ...(body ? {body: JSON.stringify(body)} : {})
  });
  return {status: response.status, text: await response.text()};
};

const login = async (credentials) => {
  const [username, password] = credentials.split(':');
  const res = await http('POST', '/api/auth/login', null, {username, password});
  if (res.status !== 200) {
    throw new Error(`login failed for ${username}: HTTP ${res.status} ${res.text.slice(0, 200)}`);
  }
  return JSON.parse(res.text).token;
};

/** Same merge as the WEB UI (rbac.service.ts): the operations of all roles of the user, per resource. */
const mergePermissions = (roles) => {
  const merged = {};
  for (const role of roles) {
    for (const [resource, operations] of Object.entries(role.permissions || {})) {
      merged[resource] = merged[resource] || new Set();
      (operations || []).forEach(operation => merged[resource].add(operation));
    }
    for (const [resource, byOperation] of Object.entries(role.scopedPermissions || {})) {
      for (const [operation, groups] of Object.entries(byOperation || {})) {
        if (groups && groups.length) {
          merged[resource] = merged[resource] || new Set();
          merged[resource].add(operation);
        }
      }
    }
  }
  return Object.fromEntries(Object.entries(merged).map(([resource, operations]) => [resource, [...operations]]));
};

/** null when the platform permissions apply (no role configures the resource), true/false otherwise. */
const granted = (permissions, resource, operation) => {
  const operations = permissions[resource];
  if (!operations) {
    return null;
  }
  if (operations.includes(operation) || operations.includes('ALL')) {
    return true;
  }
  if (CREDENTIAL_OPERATIONS.includes(operation)) {
    return false;
  }
  if (!operations.every(op => BASIC_OPERATIONS.includes(op))) {
    return false;
  }
  return operations.includes(operation.startsWith('READ') ? 'READ' : 'WRITE');
};

const token = arg('token', process.env.TB_TOKEN) || (arg('login') ? await login(arg('login')) : null);
if (!token) {
  console.error('provide --login user:password or TB_TOKEN/--token');
  process.exit(2);
}

const me = await http('GET', '/api/auth/user', token);
if (me.status !== 200) {
  console.error(`cannot read the current user: HTTP ${me.status} ${me.text.slice(0, 200)}`);
  process.exit(2);
}
const user = JSON.parse(me.text);
console.log(`user: ${user.email} (${user.authority})`);

const rolesRes = await http('GET', '/api/user/roles', token);
const roles = rolesRes.status === 200 ? JSON.parse(rolesRes.text) : [];
const permissions = mergePermissions(roles);
console.log('effective roles: ' + (roles.length ? roles.map(r => r.name).join(', ') : '(none - platform matrix)'));
for (const resource of Object.keys(permissions).sort()) {
  console.log(`  ${resource}: ${permissions[resource].join(', ')}`);
}

let deviceIds = [];
if (arg('device-id')) {
  deviceIds = [arg('device-id')];
} else {
  const list = await http('GET', '/api/entities?entityType=DEVICE&pageSize=100&page=0', token);
  if (list.status === 200) {
    deviceIds = (JSON.parse(list.text).data || []).map(item => item.id.id);
  }
  if (!deviceIds.length) {
    console.log('no device found to check (pass --device-id)');
    process.exit(0);
  }
}

const failures = [];
for (const deviceId of flag('all-devices') ? deviceIds : deviceIds.slice(0, 1)) {
  const checks = [
    ['device info', `/api/device/${deviceId}`, 'DEVICE', 'READ'],
    ['device credentials', `/api/device/${deviceId}/credentials`, 'DEVICE', 'READ_CREDENTIALS'],
    ['device attributes', `/api/plugins/telemetry/DEVICE/${deviceId}/values/attributes`, 'DEVICE', 'READ_ATTRIBUTES'],
    ['device telemetry', `/api/plugins/telemetry/DEVICE/${deviceId}/values/timeseries`, 'DEVICE', 'READ_TELEMETRY']
  ];
  console.log(`device ${deviceId}`);
  for (const [name, path, resource, operation] of checks) {
    const expected = granted(permissions, resource, operation);
    const res = await http('GET', path, token);
    if (expected === null) {
      console.log(`  ${name.padEnd(20)} HTTP ${res.status} (platform rules, not asserted)`);
      continue;
    }
    const ok = expected ? res.status === 200 : res.status === 403;
    console.log(`  ${name.padEnd(20)} HTTP ${res.status} expected ${expected ? 200 : 403} -> ${ok ? 'PASS' : 'FAIL'}`);
    if (!ok) {
      failures.push(`${name}: HTTP ${res.status} (role grant: ${expected}) ${res.text.slice(0, 120)}`);
    }
  }
}

if (failures.length) {
  console.log('\nFAILED:');
  failures.forEach(failure => console.log('  - ' + failure));
  process.exit(1);
}
console.log('\nRBAC audit PASSED');
