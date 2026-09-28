# Audit tốc độ xử lý cụm k3s (2026-09-28)

Đo **read-only** trên chính cụm production `app.greeniq.vn`, cửa sổ **2026-09-28 05:25–05:45 UTC**
(12:25–12:45 giờ Việt Nam). Không thay đổi cấu hình, không chạy load test, không ghi dữ liệu.

Câu hỏi cần trả lời: *cụm k3s hiện tại đang xử lý nhanh/chậm thế nào, nút cổ chai nằm ở đâu, và
cần làm gì trước khi tăng tải?*

## 1. Cấu hình cụm tại thời điểm đo

| Node | Vai trò | vCPU / RAM | CPU dùng | RAM dùng | CPU request | RAM request |
|---|---|---|---|---|---|---|
| `vmi3215905` | worker, db-worker (Kafka + Cassandra) | 4 / 8 GiB | 25 % | 68 % | 2,57 | ~5,0 GiB |
| `vmi3011340` | worker (Cassandra) | 4 / 8 GiB | 13 % | 69 % | 2,55 | ~6,25 GiB |
| `vmi2917809` | worker (thêm 37 h trước) | 4 / 8 GiB | 14 % | 36 % | 1,30 | ~2,4 GiB |
| `vmi3320735` | control-plane (đã cordon) | 4 / 8 GiB | 5 % | 17 % | — | — |

- **3 node worker khả dụng = 12 vCPU / 24 GiB**; control-plane không nhận pod.
- k3s **lệch phiên bản**: `vmi2917809` chạy `v1.36.2+k3s1` (containerd 2.3.2), ba node còn lại
  `v1.35.5+k3s1` (containerd 2.2.3).
- 21 pod trong namespace `thingsboard`, **0 restart**, không có pod Pending.
- `tb-rule-engine` ×2, `tb-core` ×3, `tb-mqtt-transport` ×2, `tb-js-executor` ×4, `tb-web-ui` ×1,
  `tb-cassandra-0` + `tb-kafka-0` + `tb-zookeeper-0` (mỗi thứ 1 replica).

## 2. Tải thực tế lúc đo: gần như rỗng

- Lấy 2 mẫu metrics cách nhau 50 s: **không có counter message nào tăng** (MQTT publish, transport
  api requests, rule-engine msgs). Cụm đang **~0 message/s** từ thiết bị.
- Rule engine mới xử lý **20 message** trong 18 h uptime (đều của tenant `system`).
- Cassandra keyspace `greeniq`: **5.691 write / 3.922 read** kể từ lúc khởi động 19 h trước.
- Kafka: **lag = 0** trên toàn bộ consumer group ⇒ hàng đợi trống, không dồn ứ.

⇒ Kết luận về "tốc độ" phải đọc theo nghĩa **năng lực xử lý và độ trễ nền**, không phải throughput
đang chạy.

## 3. Kết quả đo độ trễ (điểm quan trọng nhất)

### 3.1 Trong cụm (từ pod `tb-core`, đo bằng `bash` + `EPOCHREALTIME`, 5 mẫu/đích)

| Đích | TCP connect | Nhận xét |
|---|---|---|
| `127.0.0.1:8080` (chính pod) | 1,4–6,3 ms | bình thường |
| `tb-cassandra:9042` (ClusterIP, trong cụm) | **2,2–9,6 ms** | tốt |
| `tb-kafka-0` (pod IP, trong cụm) | **6,1–36,8 ms** | broker đang bận (671 m CPU) |
| PostgreSQL managed (Mỹ) | **125–159 ms** | xấu, xem §4.1 |

### 3.2 Chi phí mỗi truy vấn SQL (từ `/actuator/prometheus`, cửa sổ 14,7 h)

| Repository call | Số lần | Thời gian trung bình |
|---|---|---|
| `TbResourceRepository.findByTenantIdAndResourceTypeAndResourceKey` | 2 | 854 ms |
| `QueueRepository.findAll` | 2 | 328 ms |
| `AlarmRepository.findAllAlarmsV2` | 1 | 278 ms |
| `TenantRepository.findTenantsNextPage` | 4 | 260 ms |
| `TenantRepository.findTenantsIds` | **3.088** | **223 ms** |
| `DeviceRepository.findByTenantIdAndId` | 2 | 244 ms |

**Mọi lời gọi SQL đều tốn ~180–850 ms**, vì PostgreSQL nằm ở Mỹ (một RTT ≈ 130–160 ms, mỗi thao tác
thường cần ≥ 1 round trip). Đây là chi phí xử lý lớn nhất của toàn hệ thống hiện tại.

### 3.3 Cassandra (trong cụm)

| Chỉ số | Giá trị |
|---|---|
| Write latency coordinator (p50 / p95 / max) | 0,64 ms / 1,33 ms / 1,33 ms |
| Read latency coordinator | 0 ms (không có read đồng bộ đáng kể) |
| Local write `greeniq.ts_kv_cf` | 0,118 ms |
| Local write `greeniq.ts_kv_latest_cf` | 0,045 ms |
| Dropped mutations / dropped messages | **0** |

⇒ Ghi telemetry bằng Cassandra **trong cụm là rất nhanh**; đây là cải thiện lớn so với bản Cassandra
managed ở Mỹ (RTT 110 ms như tài liệu đo tải 2026-09-25).

### 3.4 API/HTTP

| Phép đo | Kết quả |
|---|---|
| `GET /actuator/health` gọi từ trong pod | 118–353 ms (trung bình Prometheus: **226,8 ms** / 20.132 lần) |
| `GET https://app.greeniq.vn/api/noauth/whiteLabeling` (client ở Việt Nam, 12 mẫu) | TCP 0,25–0,44 s; TLS 0,51–0,89 s; **TTFB 1,13–1,63 s** |
| `GET https://app.greeniq.vn/` (12 mẫu) | TCP 0,25–0,39 s; TLS 0,50–0,89 s; **TTFB 0,82–1,27 s** |

Phần lớn thời gian TTFB đến từ: (1) khoảng cách client → châu Âu ~250–350 ms, (2) TLS ~300 ms,
(3) **≥ 1 truy vấn PostgreSQL ở Mỹ ~130–250 ms** cho mỗi request.

## 4. Nút cổ chai và rủi ro (xếp theo mức độ)

### 4.1 PostgreSQL managed ở Mỹ là nút cổ chai số 1

Mỗi request API và mỗi lần kiểm tra entity đều trả giá 130–250 ms; các call nặng (resources, queue,
alarm) lên tới 300–850 ms. Đây là thứ quyết định p95 hiện tại, chứ không phải CPU của cụm.

### 4.2 HPA đã chạm trần ở đúng 2 service quan trọng

| HPA | Hiện tại | Ngưỡng | Min/Max | Trạng thái |
|---|---|---|---|---|
| `tb-rule-engine` | **80 %** | 70 % | 1 / **2** | **chạm trần, không scale được nữa** |
| `tb-js-executor` | **116 %** | 70 % | 1 / **4** | **chạm trần 4/4** |
| `tb-core` | 51 % | 70 % | 1 / 4 | 3 replica, còn dư |
| `tb-mqtt-transport` | 38 % | 70 % | 1 / 3 | 2 replica |
| `tb-web-ui` | 30 % | 70 % | 1 / 4 | 1 replica, **flap 1↔2 liên tục** |

`tb-web-ui` trong 1 h qua đã scale up/down 4 lần (probe `Readiness … context deadline exceeded`), mỗi
lần tạo pod mới ⇒ sinh thêm topic Kafka `js_eval.responses.<pod>` / `tb_core.notifications.<pod>`.

### 4.3 CPU throttling (cgroup) ở rule-engine

| Pod | `cpu.max` | Số chu kỳ bị throttle | Thời gian bị throttle |
|---|---|---|---|
| `tb-rule-engine` | 2 CPU | 6.184 / 646.248 | **461 s** (~0,7 % thời gian) |
| `tb-core` | 2 CPU | 1.513 / 533.306 | 58 s |
| `tb-js-executor` | 2 CPU | 10 | ~0,5 s |

Rule engine bị throttle trong khi HPA lại đã ở max replica ⇒ phần CPU tăng thêm không thể lấy được.

### 4.4 Kafka: 1 broker, RF=1, và 353 topic (rác quay lại)

- `TB_QUEUE_KAFKA_REPLICATION_FACTOR=1`, `min.insync.replicas=1` ⇒ **mất message nếu broker/pod chết**.
- **353 topic**; rất nhiều topic trỏ tới pod đã bị xoá (`tb-core-5b66dff97d-wzlp9`,
  `tb-mqtt-transport-6c9fd94687-vdrfw`, …) do HPA/pod churn. Lần dọn 2026-09-25 để lại 113 topic ⇒
  rác sinh lại trong ~15 h Kafka uptime.
- `tb_transport.api.requests` = 30 partition; queue rule-engine = 10 topic/queue (`hp`, `main`, `sq`).

### 4.5 Cassandra 1 node, RF=1 (SPOF telemetry)

Cassandra đã chuyển vào cụm (tốt cho độ trễ) nhưng hiện chỉ **1 node**: node `vmi3011340` chết là mất
telemetry (chưa có RF=3/repair). Đồng thời node này đang giữ **6,25 GiB memory request / 8 GiB** ⇒ rất
ít chỗ cho pod mới.

### 4.6 Nhiễu nhỏ nhưng đáng xử lý

- Probe `/actuator/health` trung bình **227 ms** × 20.132 lần trong 15 h; giảm tần suất hoặc dùng
  endpoint nhẹ hơn sẽ tiết kiệm CPU đáng kể.
- Log lặp `TbKafkaConsumerStatsService: Failed to get consumer group stats` (hàng giờ).
- `tb-mqtt-transport`: 61 log `ERROR` — toàn bộ là `Message decoding failed: Illegal BIT 2 or 1 in
  fixed header of PUBACK` (thiết bị gửi packet sai), không phải lỗi server.

### 4.7 Điều *không* tìm thấy (tin tốt)

Trong toàn bộ log thu được (rule-engine 15 h, core 4–8 h, transport, Cassandra 19 h):
**0 lần `Timeout to process`, 0 `Server unavailable`, 0 `OutOfMemory`, 0 dropped mutation**, 0 restart pod.
Rule-engine và core có **0 log `ERROR`** (chỉ 1 dòng Kafka offset-commit do rebalance).

## 5. Kết luận nhanh

| Hạng mục | Kết luận |
|---|---|
| Tốc độ ghi telemetry (Cassandra trong cụm) | **Rất tốt** — p95 1,3 ms, 0 drop |
| Tốc độ đọc/ghi entity (PostgreSQL Mỹ) | **Kém** — 130–850 ms cho mỗi truy vấn |
| API/UI cảm nhận từ ngoài | TTFB **0,8–1,6 s**, chủ yếu do khoảng cách + PostgreSQL |
| Hàng đợi | Trống hoàn toàn (lag 0), không có dấu hiệu quá tải hiện tại |
| Dư địa scale | **Đã hết ở rule-engine và js-executor** (HPA max), node db-worker còn ít memory headroom |
| Trần đã kiểm chứng (2026-09-25) | 1.000 thiết bị × 1 msg/s không mất dữ liệu; 2.000 msg/s thì mất |
| Đo lại tải thật 1.000 thiết bị (2026-09-28, §9) | **0 % mất dữ liệu** (240.000/240.000 dòng) nhưng rule-engine timeout 29.220 message (~49 %) |

Sau lần audit read-only, bài **đo tải thật 1.000 thiết bị** đã được chạy trên chính cụm này
(kết quả đầy đủ ở §9 và trong [`capacity-load-test.md` §3.1](capacity-load-test.md)).

## 6. Khuyến nghị (theo thứ tự lợi ích/chi phí)

1. **Đưa PostgreSQL về gần cụm** (cùng region, hoặc self-host trong cụm). Đây là thay đổi mang lại
   nhiều nhất: p95 API giảm từ ~1,5 s xuống dưới 0,5 s, giảm tải cho mọi service.
2. **Nâng trần HPA**: `tb-rule-engine` max 2 → 4–6, `tb-js-executor` max 4 → 6; thêm
   `behavior.scaleDown.stabilizationWindowSeconds: 300` cho `tb-web-ui` để hết flap. Cân nhắc scale
   rule-engine theo **Kafka consumer lag** thay vì CPU.
3. **Thêm worker 4 vCPU/8 GiB** (Phase 1) trước khi nâng tải: hiện CPU request trên 2 node đã
   ~64 %, memory request node `vmi3011340` ~78 %.
4. **Dọn Kafka định kỳ** bằng `deploy/k3s/96-kafka-cleanup.yaml` (353 topic, phần lớn là rác của pod
   đã chết) — giảm metadata, rebalance và thời gian khởi động.
5. **Cassandra**: lên 3 node + RF=3 + repair định kỳ trước khi chạy tải thật; hiện là SPOF.
6. **Kafka 3 broker, RF=3, min.insync.replicas=2** trước khi go-live chính thức.
7. **Giảm nhiễu**: tăng `periodSeconds` probe hoặc chuyển sang endpoint nhẹ; đồng bộ phiên bản k3s
   giữa các node (`v1.36.2` vs `v1.35.5`).
8. Giữ nguyên `TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS` / `TB_QUEUE_CORE_PACK_PROCESSING_TIMEOUT_MS`
   = 30000 ms và bật cảnh báo khi log xuất hiện `Timeout to process` (dấu hiệu mất dữ liệu âm thầm).
   **Đo tải 1.000 thiết bị cho thấy đây là cảnh báo thật**: 29.220 message-timeout dù dữ liệu cuối cùng
   vẫn đủ, tức là hệ thống đang chạy ở mép giới hạn.

## 7. Cách đo lại (tái lập)

```powershell
$k  = "C:\Users\vthea\AppData\Local\Programs\Lens\resources\x64\kubectl.exe"
$kc = "C:\Users\vthea\AppData\Roaming\Lens\kubeconfigs\f2e08611-80c2-43e2-bda8-06ffce6759b4-pasted-kubeconfig.yaml"

# tài nguyên + HPA + hàng đợi
& $k --kubeconfig $kc top nodes
& $k --kubeconfig $kc top pods -n thingsboard --containers
& $k --kubeconfig $kc get hpa -n thingsboard

# độ trễ trong cụm (chạy trong pod tb-core)
& $k --kubeconfig $kc exec -n thingsboard tb-core-5bd4786987-5srfm -- bash -c @'
now() { echo ${EPOCHREALTIME/./}; }
for hp in 10.43.246.126:9042 10.42.2.48:9092 postgresql-215231-0.cloudclusters.net:10012; do
  h=${hp%%:*}; p=${hp##*:}; s=$(now); exec 3<>/dev/tcp/$h/$p && exec 3<&- 3>&-
  echo "$h:$p connect_us=$(( ( $(now) - s ) ))"
done
'@

# lag Kafka (0 = hàng đợi trống)
& $k --kubeconfig $kc exec -n thingsboard tb-kafka-0 -- bash -lc `
  '/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups | head -400'

# metrics chi tiết của service
& $k --kubeconfig $kc exec -n thingsboard tb-core-5bd4786987-5srfm -- bash -lc `
  "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /actuator/prometheus HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3; cat <&3"
```

Muốn có **trần tải mới**, chạy lại bộ đo trong [`deploy/loadtest`](../deploy/loadtest/README.md)
(tạo thiết bị → bơm MQTT → đối chiếu số dòng Cassandra → dọn dẹp). Bộ này ghi dữ liệu test vào
production nên cần chạy ngoài giờ và được duyệt trước.

## 8. Giới hạn của lần audit

- Phần §1–§4 đo **read-only**: không tạo/xoá entity, nên các số "tốc độ" ở §3 là **độ trễ nền +
  năng lực còn dư**, không phải throughput cực đại. Bài đo tải thật ở §9 được chạy sau đó và có ghi dữ
  liệu test vào production (đã dọn sạch).
- Log thu là `--tail=4000` mỗi pod, cửa sổ thực tế từ 2026-09-27 13:40 đến 2026-09-28 07:27 (giờ máy chủ).
- Lúc đo read-only cụm gần như không có traffic thiết bị; hành vi dưới tải thật chỉ có ở §9.
- Kết quả đo tải tham chiếu trước đó là lần **2026-09-25** trong
  [`capacity-load-test.md`](capacity-load-test.md) — khác biệt đáng kể so với hiện tại: lúc đó
  Cassandra còn nằm ở Mỹ và chỉ có 2 worker.

## 9. Đo tải thật 1.000 thiết bị (2026-09-28)

Chạy đúng bộ công cụ trong [`deploy/loadtest`](../deploy/loadtest/README.md), tạm sửa `generate_series`
về 1.000 thiết bị và job MQTT còn **4 shard × 250** (`DURATION=60 s`).

| Chỉ số | Kết quả |
|---|---|
| Kết nối | 1.000/1.000, `CONNACK: Success` 250/250 mỗi shard, 0 lỗi kết nối |
| Publish / PUBACK | **60.000 / 60.000**, `publish_errors=0` |
| `ts_kv_cf` (delta) | **+240.000** = 60.000 × 4 key → **0 % mất dữ liệu** |
| `ts_kv_latest_cf` (delta) | +4.000 = 1.000 thiết bị × 4 key |
| `Timeout to process` (rule-engine) | 135 dòng / **29.220 message-timeout** (~49 %) |
| Lag Kafka sau 60 s | **0** trên mọi consumer group |
| Đỉnh tài nguyên | `vmi3011340` **97 % CPU / 79 % RAM**; rule-engine 1,1–1,6 core/pod; mqtt 0,65–0,87 core ×3 |
| HPA lúc bơm | rule-engine **290 %/70 %** (max 2), mqtt 347 %/70 % (scale 2→3), core 127 %/70 % |

Kết luận: ngưỡng **1.000 thiết bị × 1 msg/s hiện vẫn không mất dữ liệu**, nhưng đây là **mép trên** của
cấu hình hiện tại — rule-engine đã bị chặn bởi `maxReplicas=2`, ~49 % message phải xử lý lại sau timeout và
node db-worker đã chạm 97 % CPU. Trước khi lên 1.500–2.000 thiết bị phải:
tăng `tb-rule-engine` max replica (4–6) + thêm worker, và giảm phụ thuộc PostgreSQL ở Mỹ (mục 1 của §6).

### 9.1. Vì sao có `Timeout to process` (không phải do ghi DB)

Cơ chế trong code (`TbRuleEngineQueueConsumerManager.processMsgs`): rule engine gom message thành **pack**
theo partition, đẩy vào rule-chain actor rồi chờ tối đa `queue.getPackProcessingTimeout()`; hết hạn thì
log `Timeout to process [N] messages` và **chiến lược xử lý** của queue quyết định bỏ hay chạy lại.

Bằng chứng thu được cho lần đo 1.000 thiết bị:

| Bằng chứng | Giá trị |
|---|---|
| `queue.pack_processing_timeout` trong PostgreSQL (HighPriority/Main/SequentialByOriginator) | **2000 ms** (env `TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS=30000` **không** được áp dụng) |
| Pack bị timeout / tổng message-timeout | 184 pack / 39.896 trong 25 s |
| Độ trễ `log_time − ts` của message bị timeout | min 2,05 s; p50 5,9 s; p90 7,7 s; max 10,35 s |
| Lag Kafka trên từng partition `tb_rule_engine.main.*` lúc bơm | 431–516 message |
| Message bị timeout đang ở node nào | **165/184 tại `Message Type Switch`** (chưa tới `Save Timeseries`), 19/184 tại `Save Timeseries` |
| Chiến lược queue `Main` | `SKIP_ALL_FAILURES` (chỉ bỏ message lỗi) → pack timeout được **chạy lại** |

Đọc bảng này: phần lớn message timeout khi **còn đang xếp hàng chờ rule-engine xử lý**, chưa hề chạm tới
bước ghi dữ liệu. Ghi telemetry đi Cassandra trong cụm (p95 1,3 ms, 0 dropped mutation), còn PostgreSQL
chỉ phục vụ tra cứu entity — **không phải nguyên nhân của timeout này**. Chuỗi nhân quả thực tế: cửa sổ
pack 2 s quá ngắn so với tốc độ xử lý của 2 pod rule-engine (1 tenant, 10 partition) → timeout → phần
`pending` được submit lại (retry) → tải tăng gấp đôi → backlog và độ trễ tăng → timeout tiếp.

Đó cũng là lý do **không mất dữ liệu**: message timeout không bị bỏ mà được chạy lại. Nếu đổi queue sang
`SKIP_ALL_FAILURES_AND_TIMED_OUT` thì đúng 39.896 lượt message đó sẽ mất thật.

Việc cần làm (theo thứ tự): (1) sửa `pack_processing_timeout` của queue **trong DB/UI Queues** (sửa env
không có tác dụng với queue rule-engine), (2) tăng năng lực rule-engine (maxReplicas 4–6 + thêm worker),
(3) đo lại 1.000 thiết bị để xác nhận timeout về 0 trước khi thử mức cao hơn.

Dọn dẹp sau khi đo: xoá 1.000 thiết bị `loadtest-####` khỏi PostgreSQL, purge 240.000 dòng telemetry test
khỏi Cassandra (`ts_kv_cf` về 31 dòng, `ts_kv_latest_cf` về 3 dòng — đúng bằng baseline trước khi đo),
xoá toàn bộ job test khỏi namespace.

## 10. Lộ trình lên 10.000 thiết bị (≈10.000 msg/s)

**Thêm node là chưa đủ.** Có 4 nhóm việc mà thêm node không giải quyết, và đều đang là giới hạn cứng của
cấu hình hiện tại:

### 10.1. Giới hạn cứng (thêm node cũng không giúp)

| # | Hiện tại | Cần cho 10.000 thiết bị | Vì sao |
|---|---|---|---|
| 1 | Queue rule-engine `partitions=10`, `consumer_per_partition=true` → **tối đa 10 consumer thread cho toàn cụm** | `partitions` 32–64 (tạo thêm topic `tb_rule_engine.main.N`) | Thêm pod thứ 11 trở đi **không nhận partition nào**, hoàn toàn vô ích |
| 2 | `queue.pack_processing_timeout = 2000 ms` (trong DB, env không có tác dụng) | 10.000–30.000 ms | Timeout → retry → gấp đôi tải → timeout tiếp; ở 1.000 msg/s đã có 39.896 lượt message-timeout |
| 3 | PVC: Kafka **10 Gi**, Cassandra **20 Gi**, storage class `local-path` (`allowVolumeExpansion: false`) | Kafka ≥ 200–500 Gi/broker, Cassandra ≥ 1 TiB/node | 10.000 msg/s ≈ 200–260 GB/ngày/topic ở Kafka và ~3,46 tỷ cell/ngày (~170 GB/ngày nén) ở Cassandra; 20 Gi đầy trong vài giờ |
| 4 | `TB_QUEUE_KAFKA_*_TOPIC_PROPERTIES retention.bytes=1 GB`, `retention.ms=7 ngày` | 10–50 GB/topic hoặc retention 6–24 h + disk tương ứng | Ở 10.000 msg/s (~250 B/message ≈ 2,5 MB/s) thì 1 GB chỉ giữ được **~7 phút**; consumer lag vượt mốc đó là **mất segment** → mất dữ liệu |

### 10.2. Phải tăng theo tải (song song với thêm node)

- **HPA**: `tb-rule-engine` max 2 → 8; `tb-mqtt-transport` max 3 → 8; `tb-core` max 4 → 6–8;
  `tb-js-executor` nếu rule chain có script node. Đồng thời nâng `requests.cpu` của rule-engine
  (500m → 1 core) — HPA tính theo % request, request 500m làm CPU 1,6 core = "290 %" và luôn kẹt ở max.
- **Kafka**: 1 broker RF=1 → **3 broker RF=3, `min.insync.replicas=2`**; ZooKeeper 1 → 3; topic
  `tb_transport.api.requests` 30 → 60–100 partition; dọn topic rác định kỳ (đang 353 topic).
- **Cassandra**: 1 node RF=1 → **≥3 node RF=3**, compaction **TWCS** cho `ts_kv_cf`, JVM heap/thread pool
  tăng theo, đặt **TTL/retention** cho telemetry (quyết định dung lượng dài hạn).
- **PostgreSQL**: đưa về cùng region (hiện 135–160 ms RTT, mỗi truy vấn 180–850 ms) và theo dõi
  `max_connections`: mỗi pod giữ pool HikariCP 16 kết nối, 30 pod ≈ 480 kết nối.
- **Cache**: Caffeine là cache *trong từng pod*; càng nhiều pod thì cache miss ×N và SQL tăng theo.
  Với 10.000 thiết bị phải tăng size/TTL hoặc dùng cache chia sẻ.
- **Transport/HAProxy**: nhiều replica + cân bằng tải thật, nâng file descriptor, cấu hình
  session/keepalive cho ~10.000 kết nối MQTT thường trực và chống bão reconnect (backoff phía thiết bị).
- **Fork RBAC**: lọc entity theo group hiện chạy **trong bộ nhớ, giới hạn 1.000 entity/lần**
  ([production-k3s.md](production-k3s.md) §7) — với 10.000 thiết bị phải chuyển sang query theo group ở SQL.
- **Monitoring**: Prometheus/Grafana ngoài cụm + cảnh báo lag Kafka, `Timeout to process`, dung lượng đĩa,
  GC (repo đã gỡ manifest monitoring).

### 10.3. Cách rẻ nhất để giảm tải (không cần thêm máy)

- **Gộp nhiều key vào 1 message**: bài đo dùng 4 key/message. Nếu thiết bị (hoặc gateway) gửi 5 key trong
  1 message mỗi 5 giây thay vì 1 key mỗi giây, số message giảm **25×** — cụm chỉ còn phải xử lý tương đương
  ~400 thiết bị hiện tại.
- **Bỏ script node khỏi đường nóng**: `JS_EVALUATOR=remote` nghĩa là mỗi message có script sẽ tốn thêm một
  vòng Kafka → JS executor. Chỉ giữ "Save Timeseries" trên đường telemetry.

### 10.4. Thứ tự triển khai

1. **Phase 0 (không thêm máy)**: sửa `pack_processing_timeout` trong DB/UI, tăng partition queue
   rule-engine, nâng HPA max, dọn Kafka, đổi retention → đo lại 1.000–2.000 thiết bị để xác nhận
   timeout về 0 và xem CPU rule-engine giảm bao nhiêu (hiện ~50 % CPU bị đốt cho retry).
2. **Phase 1 (thêm máy + hạ tầng)**: 4–6 worker (8 vCPU/16–32 GiB + NVMe), Kafka 3 broker RF=3,
   Cassandra 3 node RF=3, ZooKeeper 3, PostgreSQL cùng region → đo 3.000–5.000 thiết bị.
3. **Phase 2**: tinh chỉnh (partition, retention, TTL, cache, TWCS) → đo 10.000 thiết bị và diễn tập
   failover (tắt 1 broker / 1 Cassandra / 1 rule-engine pod, kiểm tra không mất dữ liệu).

Ước lượng thô từ bài đo 1.000 msg/s: cụm dùng thêm ~5–6 core (chưa kể pod bơm tải) và node db-worker đã
97 % CPU ⇒ 10× tải cần khoảng **40–60 core dư địa** cùng dung lượng đĩa ở §10.1. Con số này phải đo lại
sau Phase 0 vì phần lớn CPU đang bị retry đốt oan.

## 11. Kết quả Phase 0 (đã chạy 2026-09-28)

Đã đổi cấu hình production, **không thêm node**:

| Việc | Trước | Sau |
|---|---|---|
| `queue.pack_processing_timeout` (bảng `queue`, cả 3 queue) | 2000 ms | **30000 ms** |
| `queue.partitions` của `Main` | 10 | **32** (thêm topic `tb_rule_engine.main.10..31`) |
| HPA `tb-rule-engine` | min 1 / max 2 | **min 2 / max 4** |
| `requests.cpu` của rule-engine | 500m | **1000m** (để HPA phản ánh đúng tải) |
| HPA `tb-mqtt-transport` | min 1 / max 3 | **min 2 / max 4** |
| Topic Kafka | 436 | 353 (job dọn topic rác đang chạy tiếp) |

Cách làm: sửa trực tiếp bảng `queue` trong PostgreSQL rồi rolling restart rule-engine (API `/api/queues`
cần mật khẩu sysadmin — mật khẩu mặc định đã bị đổi trên production), sau đó restart transport để mọi
service thấy 32 partition. Đã kiểm chứng rule-engine nhận đủ **32 consumer group** `re-Main-consumer-*`.

Đo lại đúng kịch bản cũ (4 shard × 250 thiết bị, 60 s):

| Chỉ số | Trước Phase 0 | Sau Phase 0 |
|---|---|---|
| Message bơm được | 60.000 (1.000 msg/s) | 50.750 (~850 msg/s — giới hạn của client Python) |
| Kết nối / CONNACK | 1.000/1.000 Success | 1.000/1.000 Success (connect phase 9–10 s) |
| `publish_errors` | 0 | 0 |
| `ts_kv_cf` tăng thêm | +240.000 = 60.000 × 4 | **+203.000 = 50.750 × 4** (khớp tuyệt đối) |
| Mất dữ liệu | 0 % | **0 %** |
| `Timeout to process` | 184 pack / **39.896** message | **0 pack / 0 message** |
| CPU đỉnh các node | **97 %** (dồn 1 node) | **80 %** (trải đều 3 node) |
| Rule-engine lúc bơm | 2 pod, ~2,6 core (kèm retry) | 4 pod, ~3,7 core, không retry |

Kết luận: **timeout biến mất hoàn toàn** và tải được trải đều hơn; đổi `pack_processing_timeout` trong DB
là thứ có tác dụng thật (env không tác dụng). Lưu ý hai điều khi đọc số:

- Bài bơm lần này chỉ đạt ~850 msg/s (client Python 1 luồng/shard không đủ nhanh để giữ 250 msg/s), nên
  **chưa** khẳng định được trần mới — cần bơm bằng công cụ mạnh hơn hoặc từ ngoài cụm để đạt đúng 1.000 msg/s.
- Lần chạy đầu của Phase 0 bị chậm vì `tb-mqtt-transport` chỉ có 1 replica lúc bắt đầu (connect phase
  26–31 s) — đã sửa bằng `minReplicas: 2` cho transport.

Sau khi đo: đã xoá 1.000 thiết bị test khỏi PostgreSQL và purge toàn bộ telemetry test của **2 thế hệ**
thiết bị (giữ lại đúng dữ liệu của thiết bị thật TB1): `ts_kv_cf` về **31** dòng, `ts_kv_latest_cf` về
**3** dòng — đúng bằng baseline trước các bài đo.
