#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Bộ công cụ test tải (k3s)

Các manifest này **không** nằm trong `deploy/k3s/kustomization.yaml` — chúng là công cụ đo tải,
chỉ apply thủ công khi cần đánh giá năng lực hệ thống. Kết quả các lần đo và lộ trình mở rộng
nằm ở [`docs/capacity-load-test.md`](../../docs/capacity-load-test.md).

Thứ tự chạy:

```bash
# 0) (tuỳ chọn) baseline số dòng telemetry trong Cassandra
kubectl -n thingsboard apply -f deploy/loadtest/30-verify-cassandra.yaml
kubectl -n thingsboard logs job/tb-loadtest-verify

# 1) tạo N thiết bị test (mặc định 2000, xem generate_series trong file)
kubectl -n thingsboard apply -f deploy/loadtest/10-create-devices.yaml
kubectl -n thingsboard logs job/tb-loadtest-devices

# 2) bơm tải MQTT 1 msg/s cho mỗi thiết bị
kubectl -n thingsboard apply -f deploy/loadtest/20-mqtt-load.yaml
kubectl -n thingsboard logs -l job-name=tb-loadtest-mqtt --prefix

# 3) đo lại Cassandra (so delta với số gói đã publish)
kubectl -n thingsboard apply -f deploy/loadtest/30-verify-cassandra.yaml
kubectl -n thingsboard logs job/tb-loadtest-verify

# 4) dọn dẹp
kubectl -n thingsboard apply -f deploy/loadtest/90-cleanup-devices.yaml
kubectl -n thingsboard logs job/tb-loadtest-cleanup
kubectl -n thingsboard delete job tb-loadtest-devices tb-loadtest-mqtt tb-loadtest-verify tb-loadtest-cleanup
```

Lưu ý:

- Thiết bị test tên `loadtest-####`, dùng **UUID v1 (timeuuid)**. UUID v4 sẽ làm Cassandra từ chối
  ghi `ts_kv_*` (xem `docs/production-k3s.md` §8).
- Pod bơm tải cài `paho-mqtt` + `psycopg2-binary` bằng `pip`, nên node phải có Internet egress.
- Job MQTT dùng `completionMode: Indexed` (cần k8s ≥ 1.24; cụm hiện tại v1.35) để mỗi pod tự tính
  `OFFSET = JOB_COMPLETION_INDEX * DEVICES_PER_SHARD`.
- Đặt `resources.requests.cpu` của pod bơm tải đủ nhỏ (100m) để 8 shard vào được cùng lúc, nếu không
  các pod sẽ bị `Pending` do hết CPU request trên node.
- Telemetry của thiết bị test sau khi xoá thiết bị sẽ còn lại trong Cassandra dưới dạng bản ghi mồ côi
  (không hiện trên UI, không ảnh hưởng truy vấn theo entity). Dọn bằng script purge nếu cần.
