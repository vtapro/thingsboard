# Năng lực hệ thống: kết quả đo tải và lộ trình mở rộng

Ngày đo: **2026-09-25**, trên chính cụm production `app.greeniq.vn` (không phải môi trường mô phỏng).
Công cụ tái sử dụng nằm ở [`deploy/loadtest`](../deploy/loadtest/README.md).

Câu hỏi cần trả lời: *nếu mỗi thiết bị gửi 1 message mỗi giây thì cụm hiện tại chịu được bao nhiêu thiết bị,
và muốn tăng thì phải làm gì?*

## 1. Cấu hình tại thời điểm đo

| Thành phần | Thực tế |
|---|---|
| Node | 3 node k3s: 1 control plane (đã cordon) + **2 worker 4 vCPU / 8 GiB** |
| ThingsBoard | `tb-core` ×2, `tb-rule-engine` ×2, `tb-web-ui` ×2, `tb-js-executor` ×2, `tb-mqtt-transport` ×2, các transport còn lại ×1 |
| Hàng đợi | **1 Kafka broker** (KRaft, `num.partitions=1`, `replication.factor=1`) + 1 ZooKeeper |
| Database | PostgreSQL + Cassandra managed **ở Mỹ**, RTT từ cụm ≈ **110–115 ms** |
| Cache | Không Redis — Caffeine in-process, TTL ngắn |
| Cổng vào | HAProxy ×2 (hostPort 80/443/1883/8883/7070) |

## 2. Cách đo (và cách chống "tự lừa mình")

- Sinh N thiết bị test tên `loadtest-####` + access token trong PostgreSQL.
  Entity id **bắt buộc là UUID v1 (timeuuid)** — xem §7.
- Mỗi thiết bị mở **1 kết nối MQTT** tới `tb-mqtt-transport` và publish **1 message/giây** vào
  `v1/devices/me/telemetry`, QoS 1, payload 4 key (`temperature`, `humidity`, `battery`, `seq`).
  Đây đúng là mẫu lưu lượng thật của thiết bị.
- Đối chiếu 3 nguồn độc lập, không tin một nguồn duy nhất:
  1. Phía client: số gói publish thành công, số PUBACK nhận được, số lỗi, mã CONNACK.
  2. Log transport/rule-engine: `Server unavailable`, `Timeout to process`.
  3. **Cassandra số dòng `ts_kv_cf` tăng thêm** — với payload 4 key thì số dòng phải đúng bằng
     `số message × 4`. Đây là bằng chứng "không mất dữ liệu".
- Mỗi lần chạy chỉ đo **một biến**: số thiết bị (tải) hoặc thời gian bơm.

Cảnh báo quan trọng rút ra từ chính lần đo đầu: khi nhồi 500 client trong **một** pod Python,
chỉ 340 kết nối được và 160 thiết bị "im lặng" — nguyên nhân nằm ở phía client (500 kết nối
non-blocking bung cùng lúc, không stagger), **không phải server**. Chia thành 2 pod × 250 (stagger 20 ms)
thì 500/500 kết nối thành công. Vì vậy bộ test trong repo chia shard bằng Job `completionMode: Indexed`.

## 3. Kết quả đo MQTT (1 message/giây/thiết bị)

| Lần chạy | Thiết bị | Gói publish | PUBACK | Lỗi phía client | Dòng `ts_kv_cf` tăng | Mất dữ liệu |
|---|---|---|---|---|---|---|
| A – 1 pod | 500 (chỉ 340 kết nối) | 20.400 | 20.400 | 9.600 | (gộp ở lần B) | 0 % |
| B – 2 pod × 250 | 500 | 29.500 | 29.500 | 0 | **+199.600** = (20.400 + 29.500) × 4 | **0 %** |
| C – 4 pod × 250 | 1.000 | 58.250 | 58.250 | 0 | **+233.000** = 58.250 × 4 | **0 %** |
| D – 8 pod × 250 | 2.000 | 101.631 | 100.667 | 106 CONNACK `Server unavailable` + 41 dòng `Timeout to process` | `latest_cf` +1.000 = 250 thiết bị mới × 4 key | **có mất** (xem §5) |

Diễn giải:

- **1.000 thiết bị × 1 msg/s ≈ 970 msg/s: tuyệt đối không mất gói.** Số dòng Cassandra tăng khớp
  chính xác tới từng dòng với số message client publish.
- Ở 2.000 thiết bị, hệ thống vẫn ghi được dữ liệu cho **tất cả** 2.000 thiết bị (bảng `latest` tăng
  đúng 250 thiết bị × 4 key cho nhóm chưa từng gửi), nhưng xuất hiện 2 hiện tượng suy giảm:
  - **106/2.000 kết nối bị từ chối tạm thời** (`CONNACK: Server unavailable`) khi bão kết nối;
    thiết bị tự retry (paho backoff) rồi kết nối được.
  - **Rule-engine timeout** ở node `Save Timeseries` (log `Timeout to process [114] messages`) —
    đây chính là đường mất dữ liệu âm thầm: thiết bị vẫn nhận PUBACK nhưng message không được ghi.

### Đối chiếu với các lần đo giao thức HTTP (k6) trước đó, cùng cụm

| Kịch bản | Nhận được | Lỗi HTTP | p95 | Ghi chú |
|---|---|---|---|---|
| 100 msg/s, 60 s | 100/s | 0 % | thấp | |
| 300 msg/s, 60 s | 300/s | 0 % | thấp | |
| 600 msg/s, 60 s | 556/s | ~0 % | — | bắt đầu bão hoà |
| 1.000 msg/s, 60 s | 910/s | ~0 % | — | |
| 1.500 msg/s, 60 s | 852/s | — | 8,6 s | trần thực tế ~850–900 msg/s |
| 200 thiết bị × 1/s | 12.000 | 0 % | 1,25 s | không mất |
| 500 thiết bị × 1/s | 30.000 | 0 % | 2,17 s | không mất |
| 1.000 thiết bị × 1/s | 59.489 | 0 % | 4,47 s | mất ~0,9 % |

Hai phương pháp cho cùng một kết luận: **trần của cấu hình hiện tại nằm quanh 900–1.100 msg/s**,
và trần đó bị quyết định bởi rule-engine + Kafka + CPU của cụm, không phải bởi transport.

## 4. Tài nguyên tiêu thụ khi đo

| Thành phần | 1.000 msg/s | 2.000 msg/s (bão hoà) |
|---|---|---|
| Node vmi3011340 / vmi3215905 | 49 % / 47 % CPU | 71 % / 51 % CPU |
| `tb-kafka-0` | ~1,03 core | 0,7–1,0 core |
| `tb-rule-engine` (mỗi pod) | ~0,5 core | 0,81–0,84 core |
| `tb-mqtt-transport` (mỗi pod) | ~0,34 core | 0,41–0,43 core |
| `tb-core` | 0,27–0,49 core | 0,29–0,47 core |

CPU request trên mỗi worker đã ở mức **65–66 %**, memory request 59–65 % — dư địa để thêm service còn
rất ít, và 8 pod test tải phải dùng `requests.cpu: 100m` mới vào đủ cùng lúc (nếu để 1 CPU/pod thì
pod thứ 8 bị `Pending` vì hết CPU request).

## 5. Kết luận năng lực

| Mức tải | Trạng thái | Khuyến nghị |
|---|---|---|
| ≤ 500 thiết bị × 1 msg/s | An toàn, p95 thấp, còn dư CPU cho dashboard/API | **Đây là mức khai thác hiện tại** |
| ~600–1.000 thiết bị × 1 msg/s | Chạy được, đã kiểm chứng 0 % mất gói ở đúng 1.000; p95 tăng | Chỉ dùng khi chấp nhận hết dư địa |
| ≥ 1.500 msg/s | Mất gói, latency tăng mạnh | **Phải mở rộng trước khi lên mức này** |

Nút cổ chai theo thứ tự tác động:

1. **`queue.rule-engine.pack-processing-timeout` = 2.000 ms** (`TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS`,
   mặc định trong `application/src/main/resources/thingsboard.yml`). Mỗi message phải đi qua node
   `Save Timeseries` → ghi Cassandra **ở Mỹ (RTT 110 ms)**; khi hàng đợi dồn, một pack > 2 s là bị coi
   là timeout và message bị bỏ. Đây là nguyên nhân trực tiếp của mất dữ liệu ở mức 2.000 msg/s.
2. **Kafka 1 broker, `replication.factor=1`**, topic `tb_transport.api.requests` 10 partition,
   rule-engine chỉ 4 partition `tb_rule_engine.hp.0..3` → số instance rule-engine chạy song song bị chặn
   ở mức nhỏ; broker này cũng là điểm chết đơn (mất broker = dừng ingest + mất message chưa ack).
3. **8 vCPU tổng cho toàn cụm** (2 worker). CPU request đã chiếm ~66 %.
4. **Database ở US**: không chặn băng thông khi batching tốt, nhưng làm p95 và thời gian xử lý pack tăng,
   đồng thời làm bão kết nối (mỗi CONNECT cần tra credential) chậm hẳn — 250 thiết bị mất 13–58 s để vào đủ.
5. **Rác trong Kafka**: 236 topic (189 topic `tb_*`), nhiều `tb_core.notifications.<pod cũ>` và consumer
   group của các pod đã bị xoá — tăng chi phí rebalance/metadata.

## 6. Lộ trình mở rộng

### Phase 0 — cấu hình, không thêm hạ tầng — ✅ **đã làm ngày 2026-09-25**

1. ✅ Tăng `TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS` 2000 → **30000 ms** cho `tb-rule-engine`
   và `TB_QUEUE_CORE_PACK_PROCESSING_TIMEOUT_MS` 2000 → **30000 ms** cho `tb-core`
   (`deploy/k3s/20-tb-core.yaml`, `deploy/k3s/21-tb-rule-engine.yaml`, đã rolling restart).
2. ✅ Tăng partition `tb_transport.api.requests` 10 → **30** (đã alter topic trên cụm và đặt mặc định
   `TB_QUEUE_KAFKA_TA_TOPIC_PROPERTIES` trong `deploy/k3s/01-config.yaml` cho topic tạo mới). Queue
   rule-engine vẫn 10 partition (`tb_rule_engine.hp|main|sq.0..9`) — tăng khi thêm replica (Phase 1).
3. ✅ Dọn topic/consumer-group cũ: job `deploy/k3s/96-kafka-cleanup.yaml` xoá **236 → 113 topic**
   (chỉ còn topic thật + topic của pod đang chạy), consumer group mồ côi cũng bị xoá.
4. ✅ `TB_TRANSPORT_SESSIONS_INACTIVITY_TIMEOUT` (600000 ms) và `DEFAULT_INACTIVITY_TIMEOUT` (600 s)
   đã đồng bộ sẵn theo mặc định — ghi chú lại trong `01-config.yaml`.
5. ✅ Stack Prometheus + Alertmanager + Grafana (namespace `monitoring`) đã được **gỡ khỏi cụm và khỏi repo**
   (2026-09-26). ThingsBoard vẫn expose `/actuator/prometheus`
   (`METRICS_ENDPOINTS_EXPOSE: info,health,prometheus` trong `deploy/k3s/01-config.yaml`) nên chỉ cần trỏ một
   Prometheus/Grafana **bên ngoài cụm** vào endpoint đó khi cần. Alert theo Kafka lag / log timeout cần
   JMX exporter / Loki → Phase 1.

Việc còn lại của Phase 0: dựng monitoring ngoài cụm (hoặc dịch vụ SaaS) nếu cần — repo không còn manifest.

### Phase 1 — thêm node trong cụm (mục tiêu 3.000–5.000 thiết bị × 1 msg/s)

1. Thêm worker: **2 → 4–6 node 4 vCPU/8 GiB** (chỉ cần join k3s, không đổi manifest).
2. Kafka: **3 broker, `replication.factor=3`, `min.insync.replicas=2`**, ZooKeeper 3 node.
3. Tăng replica: `tb-core` 2 → 3, `tb-rule-engine` 2 → 4 (kèm tăng partition), `tb-mqtt-transport` 2 → 4.
4. Bật lại HPA (đã gỡ vì thiếu node) theo CPU 70 %.

### Phase 2 — tối ưu đường dữ liệu (mục tiêu 10.000 thiết bị × 1 msg/s)

1. Chuyển PostgreSQL/Cassandra về **cùng region với cụm** (RTT < 10 ms) hoặc thuê máy chủ DB gần hơn;
   đây là thay đổi rẻ nhất làm giảm p95 và tăng thông lượng rule-engine.
2. Cassandra: tăng số node/throughput, kiểm tra `batch_max_delay` cho `ts_kv` khi tải cao.
3. Thiết bị nên gộp nhiều key trong 1 message (giảm số message/giây) hoặc dùng gateway/Edge để gộp.
4. Cân nhắc tách rule-engine theo tenant (`queue` riêng) cho khách hàng lớn.

### Phase 3 — HA thật & vận hành production

1. Kafka RF=3 + ZooKeeper 3, HAProxy/ingress nhiều điểm, backup/restore định kỳ cho PG + Cassandra.
2. Diễn tập failover: tắt 1 worker, 1 broker, 1 rule-engine; kiểm tra không mất dữ liệu và không dừng ingest.
3. Load test định kỳ (quý) bằng chính bộ công cụ trong `deploy/loadtest/` để cập nhật con số năng lực.

## 7. Bài học vận hành (đã gặp thật khi test)

1. **UUID v4 = mất dữ liệu âm thầm.** Nếu entity id không phải timeuuid, Cassandra từ chối ghi telemetry,
   nhưng thiết bị vẫn nhận HTTP 200 / PUBACK; lỗi chỉ hiện ở rule-engine (`Timeout to process ... Save Timeseries`).
   Khi import/nhân bản dữ liệu thiết bị phải đảm bảo UUID v1.
2. **Bão reconnect cần backoff.** 2.000 thiết bị kết nối đồng thời làm 106 kết nối bị `Server unavailable`;
   firmware thiết bị phải retry có backoff (paho mặc định đã có), không được fail vĩnh viễn.
3. **Đo tải phải chia nhiều pod bơm** và đặt CPU request nhỏ, nếu không kết luận sẽ sai về phía server.
4. **Bản ghi mồ côi**: telemetry của thiết bị test sau khi xoá thiết bị vẫn nằm trong Cassandra (không hiện
   trên UI, không ảnh hưởng truy vấn theo entity). Có thể purge bằng script khi cần giải phóng dung lượng.

## 8. Chạy lại bài đo

Xem [`deploy/loadtest/README.md`](../deploy/loadtest/README.md) — 4 bước: tạo thiết bị → bơm tải MQTT →
đo Cassandra → dọn dẹp. Toàn bộ manifest đều nằm ngoài `kustomization.yaml` nên không ảnh hưởng deploy production.
