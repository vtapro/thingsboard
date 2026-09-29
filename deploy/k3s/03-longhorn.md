#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Longhorn + PostgreSQL trong cụm (production profile)

Cụm chạy PostgreSQL **trong** k3s thay cho managed database bên ngoài. Ba thành phần được cài một lần:

| Thành phần | Version đã dùng | Vai trò |
|---|---|---|
| Longhorn | v1.13.0 | volume nhân bản (2 replica) cho PostgreSQL |
| external-snapshotter | v8.6.0 | VolumeSnapshot CRD/controller để backup bằng snapshot |
| CloudNativePG (CNPG) | v1.30.1 | operator HA cho PostgreSQL: 3 instance, failover, pooler, backup |

## 1. Tiền đề trên node (đã kiểm tra)

| Yêu cầu | Trạng thái |
|---|---|
| `open-iscsi` + `iscsid` chạy | ✅ cả 4 node |
| `iscsi_tcp` module | ✅ nạp được |
| `multipathd` phải blacklist thiết bị `sd*` | ✅ `/etc/multipath/conf.d/99-longhorn.conf` |
| Đĩa | mỗi node 1 ổ ~150 GiB, còn 112–140 GiB; Longhorn giữ 30% dự trữ |

```bash
# cài lại tiền đề (idempotent) nếu sau này thêm node mới
#   /etc/multipath/conf.d/99-longhorn.conf
#   blacklist { devnode "^sd[a-z0-9]+" }
#   systemctl reload multipathd
#   modprobe iscsi_tcp && systemctl start iscsid
```

## 2. Cài đặt một lần

**Cách nhanh (idempotent, làm đúng các bước bên dưới):**

```bash
KUBECTL=/Applications/Lens.app/Contents/Resources/arm64/kubectl ./scripts/install-k3s-postgres.sh
# hoặc: KUBECONFIG=~/.kube/config ./scripts/install-k3s-postgres.sh
```

Hoặc làm thủ công từng bước:

```bash
# Longhorn (StorageClass longhorn + longhorn-static, namespace longhorn-system)
kubectl apply -f https://raw.githubusercontent.com/longhorn/longhorn/v1.13.0/deploy/longhorn.yaml
kubectl -n longhorn-system rollout status daemonset/longhorn-manager --timeout=300s
# Longhorn tự đăng ký StorageClass `longhorn` là default: bỏ cờ đó để không đổi mặc định của cụm
kubectl annotate storageclass longhorn storageclass.kubernetes.io/is-default-class-

# external-snapshotter (VolumeSnapshot CRD + controller) cho backup bằng snapshot
V=v8.6.0
for f in volumesnapshotclasses volumesnapshotcontents volumesnapshots; do
  kubectl apply -f "https://raw.githubusercontent.com/kubernetes-csi/external-snapshotter/${V}/client/config/crd/snapshot.storage.k8s.io_${f}.yaml"
done
kubectl apply -f "https://raw.githubusercontent.com/kubernetes-csi/external-snapshotter/${V}/deploy/kubernetes/snapshot-controller/rbac-snapshot-controller.yaml"
kubectl apply -f "https://raw.githubusercontent.com/kubernetes-csi/external-snapshotter/${V}/deploy/kubernetes/snapshot-controller/setup-snapshot-controller.yaml"

# CloudNativePG
kubectl apply --server-side -f https://github.com/cloudnative-pg/cloudnative-pg/releases/download/v1.30.1/cnpg-1.30.1.yaml
kubectl -n cnpg-system rollout status deployment/cnpg-controller-manager --timeout=300s
```

## 3. Storage class cho database

`12-longhorn-postgres-storage.yaml` tạo:

* `longhorn-postgres` — `numberOfReplicas=2`, `reclaimPolicy=Retain`, `dataLocality=best-effort`
  (database đã nhân bản ở tầng PostgreSQL nên không cần bản sao block thứ 3);
* `longhorn-snapshot` — VolumeSnapshotClass dùng cho `ScheduledBackup`.

## 4. Cụm PostgreSQL

`13-postgres-cluster.yaml` tạo:

* `Cluster/tb-pg` — 3 instance, anti-affinity **required** trên 3 node, 20 GiB/instance trên
  `longhorn-postgres`, PostgreSQL 18, tuning cho workload ThingsBoard, TLS bật sẵn của CNPG;
* `Pooler/tb-pg-pooler-rw` — PgBouncer transaction mode, 2 replica;
* `ScheduledBackup/tb-pg-daily` — snapshot Longhorn mỗi ngày 02:00 (giữ theo volume snapshot).

Kiểm tra:

```bash
kubectl -n thingsboard get cluster tb-pg          # 3/3, "Cluster in healthy state"
kubectl -n thingsboard get pooler
kubectl -n thingsboard get pvc -l cnpg.io/cluster=tb-pg
```

User ứng dụng do operator sinh trong Secret `tb-pg-app` (`username: tb`). ThingsBoard kết nối qua
pooler: `tb-pg-pooler-rw.thingsboard.svc.cluster.local:5432`.

## 5. Migrate dữ liệu từ DB cũ

```bash
# 1. dừng ghi (cửa sổ bảo trì ngắn; transports vẫn xếp message vào Kafka)
kubectl -n thingsboard scale deployment/tb-core deployment/tb-rule-engine --replicas=0

# 2. copy schema + dữ liệu (đọc nguồn từ tb-common/tb-secrets, ghi đích bằng tb-pg-app)
kubectl -n thingsboard apply -f deploy/k3s/14-postgres-migrate.yaml
kubectl -n thingsboard wait --for=condition=complete job/tb-pg-migrate --timeout=30m
kubectl -n thingsboard logs job/tb-pg-migrate | tail -20

# 3. trỏ ThingsBoard sang DB mới
kubectl -n thingsboard patch configmap tb-common --type merge -p '{"data":{"SPRING_DATASOURCE_URL":"jdbc:postgresql://tb-pg-pooler-rw.thingsboard.svc.cluster.local:5432/greeniq?sslmode=require"}}'
APP_USER=$(kubectl -n thingsboard get secret tb-pg-app -o jsonpath='{.data.username}' | base64 -d)
APP_PASS=$(kubectl -n thingsboard get secret tb-pg-app -o jsonpath='{.data.password}')
kubectl -n thingsboard patch secret tb-secrets --type merge -p "{\"data\":{\"SPRING_DATASOURCE_USERNAME\":\"$(printf %s "$APP_USER" | base64)\",\"SPRING_DATASOURCE_PASSWORD\":\"$APP_PASS\"}}"

# 4. bật lại
kubectl -n thingsboard scale deployment/tb-core deployment/tb-rule-engine --replicas=2
kubectl -n thingsboard rollout status deployment/tb-core --timeout=300s
kubectl -n thingsboard rollout status deployment/tb-rule-engine --timeout=300s
```

Rollback: giữ DB cũ (chưa xoá), đổi lại `SPRING_DATASOURCE_URL` + credentials cũ rồi restart.
Credential DB cũ cần lấy lại từ console CloudClusters vì `tb-secrets` đã bị ghi đè.

## 6. Giới hạn cần biết

* Longhorn nhân bản ở tầng block: mỗi ghi đi qua mạng tới 2 replica. Vì vậy class
  `longhorn-postgres` cố ý để 2 replica và `dataLocality=best-effort`; đừng nâng lên 3 mà không đo lại.
* Snapshot Longhorn chỉ cho restore theo mốc backup. Muốn **PITR/DR ra ngoài cụm** phải thêm
  `barmanObjectStore` (S3/MinIO) vào `spec.backup` và WAL archiving.
* Longhorn dùng chung ổ với OS; Longhorn đã giữ 30% dự trữ. Khi thêm workload nặng nên gắn ổ riêng
  cho `/var/lib/longhorn`.
* PostgreSQL cần thêm ~3 × (1–2 vCPU / 1–4 GiB) trên các worker; xem node sizing trong `SCALING.md`.
