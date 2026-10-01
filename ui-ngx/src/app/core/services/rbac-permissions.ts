// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * Effective permissions of the custom tenant roles (GET /api/user/roles).
 *
 * The rules mirror TbRbacAccessControlService so the WEB UI hides exactly what the backend denies:
 * <ul>
 *   <li>a resource that no role of the user configures keeps the platform permissions;</li>
 *   <li>the other resources are checked against the operations of the role: a role with the basic operations
 *       (CREATE/READ/WRITE/DELETE) also grants the auxiliary read/write operations of that entity type;</li>
 *   <li>credentials are never implied: they have to be granted explicitly.</li>
 * </ul>
 */
let rbacPermissions: { [resource: string]: string[] } | null = null;

const BASIC_OPERATIONS = ['CREATE', 'READ', 'WRITE', 'DELETE'];

type RbacPermissionsListener = () => void;

const listeners = new Set<RbacPermissionsListener>();

/**
 * Registers a listener called when the effective permissions change (roles loaded, user logged out).
 * Returns the function that unregisters it.
 */
export const onRbacPermissionsChanged = (listener: RbacPermissionsListener): (() => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export const setRbacPermissions = (permissions: { [resource: string]: string[] } | null): void => {
  if (JSON.stringify(rbacPermissions) === JSON.stringify(permissions)) {
    return;
  }
  rbacPermissions = permissions;
  listeners.forEach(listener => listener());
};

export const getRbacPermissions = (): { [resource: string]: string[] } | null => rbacPermissions;

/**
 * Operation that a "classic" role (one that only lists CREATE/READ/WRITE/DELETE) has to contain to grant the
 * requested one. It mirrors TbRbacAccessControlService#grantedOperation: everything that reads maps to READ, the
 * auxiliary write operations (attributes, telemetry, rpc, claim, assign) map to WRITE, while DELETE is never derived
 * — the role has to list it explicitly.
 */
const baseOperation = (operation: string): string =>
  operation === 'DELETE' ? 'DELETE' : (operation.startsWith('READ') ? 'READ' : 'WRITE');

/**
 * True when the custom roles of the user allow the operation on the resource. When the user has no custom role,
 * or when no role configures the resource, the platform permissions apply (the caller keeps them).
 */
export const hasRbacPermission = (resource: string, operation: string): boolean => {
  if (!rbacPermissions) {
    return true;
  }
  const operations = rbacPermissions[resource];
  if (!operations) {
    return true;
  }
  if (operations.includes(operation) || operations.includes('ALL')) {
    return true;
  }
  if (operation.endsWith('CREDENTIALS')) {
    return false;
  }
  if (!operations.every(op => BASIC_OPERATIONS.includes(op))) {
    return false;
  }
  return operations.includes(baseOperation(operation));
};

/**
 * True when a custom role of the user grants the operation. Unlike {@link hasRbacPermission} there is no fallback to
 * the platform permissions, so the caller can check an operation that the platform denies to the authority of the
 * user — for example {@code DEVICE:CREATE} of a customer user: without a role the platform denies it, so the WEB UI
 * must not show the button either.
 */
export const hasExplicitRbacPermission = (resource: string, operation: string): boolean => {
  const permissions = getRbacPermissions();
  if (!permissions || !permissions[resource]) {
    return false;
  }
  return hasRbacPermission(resource, operation);
};
