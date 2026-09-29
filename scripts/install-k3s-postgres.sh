#!/bin/bash
#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Cai PostgreSQL trong cum k3s: Longhorn + external-snapshotter + CloudNativePG + Cluster tb-pg.
# Idempotent: chay lai nhieu lan cung duoc.
#
# Dung:
#   KUBECONFIG=~/.kube/config ./scripts/install-k3s-postgres.sh
#   KUBECTL=/Applications/Lens.app/Contents/Resources/arm64/kubectl ./scripts/install-k3s-postgres.sh
#
# Sau khi chay xong, xem deploy/k3s/03-longhorn.md §5 de migrate du lieu va cutover.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${NS:-thingsboard}"
LONGHORN_VERSION="${LONGHORN_VERSION:-v1.13.0}"
SNAPSHOTTER_VERSION="${SNAPSHOTTER_VERSION:-v8.6.0}"
CNPG_VERSION="${CNPG_VERSION:-v1.30.1}"

if [[ -z "${KUBECTL:-}" ]]; then
    if command -v kubectl >/dev/null 2>&1; then
        KUBECTL=kubectl
    elif [[ -x /Applications/Lens.app/Contents/Resources/arm64/kubectl ]]; then
        KUBECTL=/Applications/Lens.app/Contents/Resources/arm64/kubectl
    else
        echo "Khong tim thay kubectl (dat bien KUBECTL neu dung ban khac)." >&2
        exit 1
    fi
fi

step() { echo; echo "==> $*"; }

step "Kiem tra ket noi cum (namespace $NS)"
"$KUBECTL" -n "$NS" get namespace "$NS" >/dev/null

step "Longhorn $LONGHORN_VERSION"
if ! "$KUBECTL" get namespace longhorn-system >/dev/null 2>&1; then
    "$KUBECTL" apply -f "https://raw.githubusercontent.com/longhorn/longhorn/${LONGHORN_VERSION}/deploy/longhorn.yaml"
fi
"$KUBECTL" -n longhorn-system rollout status daemonset/longhorn-manager --timeout=300s
# Longhorn tu dat StorageClass `longhorn` lam default; bo co do de khong doi mac dinh cua cum
"$KUBECTL" annotate storageclass longhorn storageclass.kubernetes.io/is-default-class- --overwrite >/dev/null 2>&1 || true

step "external-snapshotter $SNAPSHOTTER_VERSION (VolumeSnapshot CRD + controller)"
for f in volumesnapshotclasses volumesnapshotcontents volumesnapshots; do
    "$KUBECTL" apply -f "https://raw.githubusercontent.com/kubernetes-csi/external-snapshotter/${SNAPSHOTTER_VERSION}/client/config/crd/snapshot.storage.k8s.io_${f}.yaml" >/dev/null
done
"$KUBECTL" apply -f "https://raw.githubusercontent.com/kubernetes-csi/external-snapshotter/${SNAPSHOTTER_VERSION}/deploy/kubernetes/snapshot-controller/rbac-snapshot-controller.yaml" >/dev/null
"$KUBECTL" apply -f "https://raw.githubusercontent.com/kubernetes-csi/external-snapshotter/${SNAPSHOTTER_VERSION}/deploy/kubernetes/snapshot-controller/setup-snapshot-controller.yaml" >/dev/null
"$KUBECTL" -n kube-system rollout status deployment/snapshot-controller --timeout=300s

step "CloudNativePG $CNPG_VERSION"
"$KUBECTL" apply --server-side -f "https://github.com/cloudnative-pg/cloudnative-pg/releases/download/${CNPG_VERSION}/cnpg-${CNPG_VERSION}.yaml" >/dev/null
"$KUBECTL" -n cnpg-system rollout status deployment/cnpg-controller-manager --timeout=300s

step "StorageClass + VolumeSnapshotClass + PostgreSQL cluster"
"$KUBECTL" apply -f "$REPO_ROOT/deploy/k3s/12-longhorn-postgres-storage.yaml"
"$KUBECTL" apply -f "$REPO_ROOT/deploy/k3s/13-postgres-cluster.yaml"

step "Cho PostgreSQL ready (3 instance)"
"$KUBECTL" -n "$NS" wait --for=condition=Ready cluster/tb-pg --timeout=900s
"$KUBECTL" -n "$NS" get cluster tb-pg
"$KUBECTL" -n "$NS" get pooler

echo
echo "Database san sang:"
echo "  URL   : jdbc:postgresql://tb-pg-pooler-rw.${NS}.svc.cluster.local:5432/greeniq?sslmode=require"
echo "  User  : Secret tb-pg-app (keys: username/password)"
echo
echo "Buoc tiep theo (migrate du lieu + cutover): deploy/k3s/03-longhorn.md §5"
