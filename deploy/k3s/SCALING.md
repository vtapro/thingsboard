#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Scale-out runbook — thêm node + thêm pod là tăng chịu tải

Mục tiêu: khi tải tăng, chỉ cần **thêm worker node** và **tăng replica** là năng lực xử lý tăng, không phải
sửa kiến trúc. Điều kiện để điều đó đúng nằm ở 2 chỗ:

1. **Partition của queue phải >= số pod tiêu thụ queue đó.** Pod thứ N+1 chỉ có việc khi tồn tại partition
   thứ N+1 (Kafka: số consumer hoạt động trong một group <= số partition).
2. **Hạ tầng dùng chung phải đủ và không phải điểm chết.** Kafka/ZooKeeper/Cassandra 3 node + replication
   factor 3 là mức tối thiểu để mất 1 node không mất dữ liệu và không dừng ghi.

## 1. Trần song song của từng tầng

| Service | Bị giới hạn bởi | Cấu hình | HPA max hiện tại |
|---|---|---|---|
| `tb-rule-engine` | số partition queue rule-engine (Main/HighPriority/SequentialByOriginator) | `TB_QUEUE_RULE_ENGINE_PARTITIONS` (32) | 16 |
| `tb-core` | `TB_QUEUE_CORE_PARTITIONS` (32) và partitions của `tb_transport.api.requests` (64) | `01-config.yaml` | 16 |
| `tb-js-executor` | partitions của topic `tb_js_executor` (30) | `TB_QUEUE_KAFKA_JE_TOPIC_PROPERTIES` | 16 |
| `tb-mqtt-transport` / `tb-http-transport` | số connection + CPU/RAM của node (là producer, không bị partition) | `22-*`, `23-*` + HPA | 16 / 8 |
| Cassandra (telemetry) | số node × replication factor, disk, IO | `06-cassandra.yaml`, `06b-*` (RF=3) | — |
| PostgreSQL (entities) | 3 instance CloudNativePG + PgBouncer pool; thêm node/instance khi CPU hoặc IOPS thiếu | `13-postgres-cluster.yaml` | — |

Quy tắc: **nâng partition TRƯỚC, nâng HPA max SAU**, và luôn giữ `max replicas <= partitions`.

## 2. Node sizing tối thiểu cho bản HA

| Thành phần | Request/pod | Số pod | Tổng |
|---|---|---|---|
| Kafka | 1 GiB / 0.5 vCPU | 3 | 3 GiB |
| ZooKeeper | 0.5 GiB / 0.2 vCPU | 3 | 1.5 GiB |
| Cassandra | 3 GiB / 1 vCPU | 3 | 9 GiB |
| PostgreSQL (CloudNativePG) | 1 GiB request / 4 GiB limit | 3 | 3 GiB request (12 GiB limit) |
| PgBouncer (Pooler) | 128 MiB / 100m | 2 | ~0.3 GiB |
| TB services (core/RE/web-ui/js/transports) | 1-3 GiB tuỳ service | 10-20 | 15-30 GiB |

Khuyến nghị production: **3 k3s server (control plane HA, embedded etcd) + 6 worker × 8 vCPU / 16 GiB**.
Với cụm nhỏ hơn vẫn chạy được nhưng mất khả năng chịu lỗi node.

Lưu ý storage: manifest dùng `local-path`, nghĩa là mất node là mất replica trên node đó. Muốn đạt chuẩn
production hãy dùng storage class nhân bản (Longhorn/Ceph) cho Kafka/Cassandra; khi đó mới thật sự "mất node
không mất dữ liệu".

## 3. Quy trình scale từng bước

1. **Đo baseline** (Prometheus/Grafana hoặc `kubectl -n thingsboard top pods`): CPU/RAM từng service, Kafka
   consumer lag, Cassandra read/write latency, PostgreSQL connection pool, độ trễ REST/WS.
2. **Thêm worker node** vào k3s (`k3s agent` join) và xác nhận node `Ready`, còn dư CPU/RAM.
3. **Scale hạ tầng stateful nếu cần** (Kafka broker / Cassandra node / ZK node) — xem mục 5.
4. **Tăng partition** cho tầng đang nghẽn:
   - Rule engine: `TB_QUEUE_RULE_ENGINE_PARTITIONS` trong `01-config.yaml`, và với cụm đã cài thì cập nhật
     queue trong DB (mục 4).
   - Core / transport API: `TB_QUEUE_CORE_PARTITIONS`, `TB_QUEUE_KAFKA_TA_TOPIC_PROPERTIES`.
5. **Nâng HPA max** trong `40-hpa.yaml` (không vượt partition ở mục 4).
6. **Kiểm chứng**: pod `Running` (không `Pending`), Kafka lag giảm/tan, không tăng RE timeout, chạy lại load
   test ở tải mục tiêu (chấp nhận: mất dữ liệu 0%, RE timeout < 1%).

## 4. Cụm đã cài: cập nhật partition của queue

Installer chỉ tạo queue một lần, nên cụm đang chạy phải cập nhật số partition thủ công. Cách nhanh nhất là
sửa trong **Tenant profile UI** (Default → Queues → Main / HighPriority / SequentialByOriginator → partitions).
Hoặc cập nhật DB rồi rolling-restart tb-rule-engine:

```sql
-- PostgreSQL của ThingsBoard (entities)
UPDATE queue SET partitions = 32
 WHERE name IN ('Main', 'HighPriority', 'SequentialByOriginator');
```

```bash
kubectl -n thingsboard rollout restart deployment/tb-rule-engine
```

Với topic đơn (transport API), resize topic đang tồn tại:

```bash
kubectl -n thingsboard exec tb-kafka-0 -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --alter --topic tb_transport.api.requests --partitions 64
```

Kiểm tra số topic partition của rule-engine (mỗi partition là một topic `tb_rule_engine.main.N`):

```bash
kubectl -n thingsboard exec tb-kafka-0 -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list | grep -c '^tb_rule_engine.main.'
```

## 5. Chuyển từ 1 broker / 1 node sang HA 3 node

Thứ tự bắt buộc (đây là thay đổi có maintenance window):

1. **Kafka**: dừng `tb-core`, `tb-rule-engine`, transports để queue ngừng ghi → xoá StatefulSet + PVC Kafka
   cũ → apply `04-kafka.yaml` (3 broker) cùng `01-config.yaml` (`RF=3`, `min.insync.replicas=2`) → đợi 3 pod
   Ready. Queue cũ chỉ là dữ liệu tạm (retention 7 ngày), TB tự tạo lại topic khi khởi động; message đang
   trong queue tại thời điểm bảo trì sẽ mất.
   > Không bao giờ apply `min.insync.replicas=2` khi mới có 1 broker: mọi ghi sẽ thất bại.
2. **ZooKeeper**: tương tự, `05-zookeeper.yaml` 3 node; `ZOOKEEPER_URL` trong `01-config.yaml` đã liệt kê đủ
   3 thành viên.
3. **Cassandra**: tăng StatefulSet lên 3 node (`06-cassandra.yaml`), đợi cả 3 node `UN` (`nodetool status`),
   rồi chạy lại `06b-cassandra-init.yaml` — job này `ALTER KEYSPACE ... RF=3` (idempotent) nên keyspace cũ
   RF=1 cũng được nâng lên.

## 6. Checklist "đạt chuẩn production"

- [ ] Kafka 3 broker RF=3 minISR=2; ZK 3 node; Cassandra 3 node RF=3 (kiểm tra `nodetool status`).
- [ ] `max replicas` của mỗi HPA <= partition tương ứng.
- [ ] Node sizing đủ cho HA infra + TB pods; có dư địa 1 node mất vẫn chạy.
- [ ] Storage class nhân bản cho Kafka/Cassandra (hoặc chấp nhận rebuild từ replica).
- [ ] Control plane k3s HA (3 server, embedded etcd), backup etcd + PostgreSQL định kỳ.
- [ ] Alert: Kafka consumer lag, Cassandra latency, DB pool saturation, GC, disk, REST/WS error rate.
- [ ] Load test ở 2× tải mục tiêu: 0% mất dữ liệu, RE timeout < 1%, lag ổn định.
- [ ] Failover drill: kill 1 Kafka broker / 1 Cassandra node / 1 ZK node, hệ vẫn phục vụ.

## 7. Bước tiến tiếp theo (khi cụm lớn hơn)

HPA hiện tại scale theo **CPU**. Khi số node nhiều hơn và tải queue biến động mạnh, chuyển HPA của các queue
consumer (`tb-rule-engine`, `tb-core`, `tb-js-executor`) sang scale theo **Kafka consumer lag** bằng KEDA hoặc
prometheus-adapter. Lúc đó pod được thêm đúng lúc queue ùn, thay vì chờ CPU tăng.
