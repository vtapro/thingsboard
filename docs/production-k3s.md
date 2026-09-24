# Production: ThingsBoard microservices HA trên k3s

Tài liệu này mô tả cách đưa fork lên cụm **k3s** dạng **microservices HA**, đúng chuẩn
production của ThingsBoard. Khác biệt với môi trường dev (xem [local-dev.md](local-dev.md)):

| | Dev local | Production (k3s) |
|---|---|---|
| Kiến trúc | `service.type=monolith`, 1 process | microservices: `tb-core`, `tb-rule-engine`, `tb-transport` |
| Entities | PostgreSQL | PostgreSQL |
| Telemetry | PostgreSQL (`DATABASE_TS_TYPE=sql`) | Cassandra (`DATABASE_TS_TYPE=cassandra`) |
| Queue | `in-memory` | **Kafka** (bắt buộc) |
| Discovery | không cần | **ZooKeeper** (bắt buộc) |
| Cache | `caffeine` | **Redis/Valkey** (bắt buộc khi nhiều replica) |
| Số replica | 1 | ≥ 2 mỗi service, có HPA |

Code ThingsBoard **không bị sửa để bỏ Kafka/Cassandra**. Việc "không dùng Kafka/Cassandra" chỉ
áp dụng cho script và tài liệu dev; toàn bộ nhánh Kafka/Cassandra/microservices của ThingsBoard
CE vẫn nguyên vẹn (xem mục 8 để có bằng chứng kiểm tra).

## 1. Kiến trúc triển khai

```text
                     ┌─────────────── Ingress (Traefik) ───────────────┐
   user ── HTTPS ──▶ │  /  (UI + REST API)                             │
                     └──────────────────────┬──────────────────────────┘
                                            ▼
                                   Service tb-core (8080, 7070)
                     ┌──────────────────────┴──────────────────────────┐
                     │  tb-core x N          tb-rule-engine x N        │
                     │  tb-mqtt-transport x N   tb-http-transport x N  │
                     └───────┬───────────┬───────────┬─────────────────┘
        ── TCP (Endpoints trỏ ra ngoài cụm) ──┼───────────┼─────────────┼─────
                             ▼           ▼           ▼
                          Kafka     ZooKeeper    Cassandra ─ telemetry
                             │           │
                             ▼           ▼
                        PostgreSQL ─ entities/relations/alarms
                             │
                          Redis ─ shared cache

              (Kafka / ZooKeeper / Cassandra / PostgreSQL / Redis
               nằm trên MÁY CHỦ DỮ LIỆU RIÊNG, không nằm trong k3s — xem mục 3)
```

Một **image duy nhất** chạy mọi thành phần; `TB_SERVICE_TYPE` quyết định vai trò:

| Deployment | `TB_SERVICE_TYPE` | Cổng | Vai trò |
|---|---|---|---|
| `tb-core` | `tb-core` | 8080 (REST/UI/actuator), 7070 (edge gRPC) | API, entities, telemetry, white labeling, RBAC |
| `tb-rule-engine` | `tb-rule-engine` | 8080 (actuator) | thực thi rule chain |
| `tb-mqtt-transport` | `tb-transport` + `MQTT_ENABLED=true` | 1883 (MQTT), 8080 | thiết bị kết nối MQTT |
| `tb-http-transport` | `tb-transport` + `HTTP_ENABLED=true` | 8080 | thiết bị gọi REST `/api/v1/**` |
| Job `tb-install` | `monolith` (chỉ để chạy installer) | — | cài/nâng cấp schema, chạy 1 lần mỗi release |

`TB_SERVICE_ID` lấy từ `metadata.name` của pod (duy nhất trong namespace) — đây là id node trong
cluster. Nếu muốn id ổn định qua các lần restart, dùng `StatefulSet` thay `Deployment`.

## 2. Build image lên GHCR

Image của sản phẩm là **`ghcr.io/vtapro/greeniq-backend`** (cùng nhóm với `greeniq-frontend` trên
GHCR), version hiện tại **`v4.4.0.0`** — khai báo trong biến `IMAGE_VERSION` của workflow.

Workflow [`.github/workflows/publish-images.yml`](../.github/workflows/publish-images.yml) build
`docker/tb-custom/Dockerfile` và push lên GHCR mỗi khi push branch hoặc tag `v*`:

> Lưu ý: GitHub **chặn push** file nằm trong `.github/workflows/` nếu token/credential đang dùng
> không có scope `workflow`. Nếu gặp lỗi
> `refusing to allow an OAuth App to create or update workflow ... without workflow scope`, chạy
> `gh auth refresh -h github.com -s workflow` (hoặc tạo PAT có scope `workflow`) rồi push lại file đó.
> Trong lúc chờ, vẫn build image bằng tay: `docker build -f docker/tb-custom/Dockerfile -t ... .`
> và `docker push` lên GHCR.

```text
ghcr.io/vtapro/greeniq-backend:v4.4.0.0          # version sản phẩm, tag chính để deploy
ghcr.io/vtapro/greeniq-backend:<branch>          # ví dụ :RBAC-full-groups-tabs
ghcr.io/vtapro/greeniq-backend:sha-<short>       # truy vết theo commit
ghcr.io/vtapro/greeniq-backend:latest            # chỉ trên default branch
```

### 2.1. Build/push khi máy có Docker

```bash
# build
docker build -f docker/tb-custom/Dockerfile -t ghcr.io/vtapro/greeniq-backend:v4.4.0.0 .

# đăng nhập GHCR (PAT cần scope write:packages)
echo "$CR_PAT" | docker login ghcr.io -u vtapro --password-stdin

# push
docker push ghcr.io/vtapro/greeniq-backend:v4.4.0.0
```

### 2.2. Không có Docker ở máy dev — dùng GitHub Actions

Máy dev hiện tại **không cài Docker Desktop / docker CLI**, nên cách gọn nhất là để GitHub Actions
build. Việc duy nhất còn thiếu là quyền `workflow` cho credential đang dùng để push file workflow:

```bash
# 1. cấp scope (mở trình duyệt, xác nhận 1 lần)
gh auth refresh -h github.com -s workflow

# 2. push file workflow lên repo
git add .github/workflows/publish-images.yml
git commit -m "ci: publish greeniq-backend image to GHCR"
git push origin RBAC-full-groups-tabs

# 3. theo dõi build (khoảng 10–20 phút cho lần đầu)
gh run watch

# 4. kiểm tra image đã lên GHCR
docker manifest inspect ghcr.io/vtapro/greeniq-backend:v4.4.0.0    # nếu có docker
# hoặc xem trực tiếp: https://github.com/vtapro?tab=packages
```

Nếu chưa muốn động vào scope, có thể copy nội dung file
[`ci/publish-images.yml`](../ci/publish-images.yml) rồi tạo file
`.github/workflows/publish-images.yml` bằng nút **Add file** trên web GitHub (web không bị giới hạn
scope như token của git).

Image là **private** theo mặc định của GHCR. Trên k3s tạo pull secret:

```bash
kubectl -n thingsboard create secret docker-registry ghcr \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<PAT có scope read:packages>
```

và thêm `imagePullSecrets: [{name: ghcr}]` vào `spec.template.spec` của từng Deployment (hoặc gắn
vào ServiceAccount của namespace).

## 3. Data plane ngoài cụm (máy chủ riêng link vào k3s)

PostgreSQL, Cassandra, Kafka và ZooKeeper chạy trên **một máy chủ riêng**, kết nối vào cụm k3s
qua TCP. Trong cụm không deploy data plane; các pod chỉ nhìn thấy 5 hostname nội bộ
(`tb-postgres`, `tb-cassandra`, `tb-kafka`, `tb-zookeeper`, `tb-redis`) do
[`deploy/k3s/03-external-data-plane.yaml`](../deploy/k3s/03-external-data-plane.yaml) tạo ra
(`Service` không selector + `Endpoints` trỏ về IP máy chủ đó).

```text
  k3s cluster (namespace thingsboard)              máy chủ dữ liệu (1 VM)
  ┌───────────────────────────────────┐            ┌──────────────────────────────┐
  │  tb-core / tb-rule-engine / ...   │            │  PostgreSQL      :5432       │
  │  ── Service + Endpoints ──────────┼── TCP ────▶│  Cassandra       :9042       │
  │  tb-postgres / tb-cassandra /     │            │  Kafka           :9092       │
  │  tb-kafka / tb-zookeeper /        │            │  ZooKeeper       :2181       │
  │  tb-redis                         │            │  Redis/Valkey    :6379       │
  └───────────────────────────────────┘            └──────────────────────────────┘
```

### 3.1. Cấu hình bắt buộc trên máy chủ dữ liệu

| Thành phần | Việc phải làm | Vì sao |
|---|---|---|
| PostgreSQL 16 | `listen_addresses='*'`, `pg_hba.conf` cho CIDR của pod/service k3s, user + DB `thingsboard` | pod nối trực tiếp tới `:5432` |
| Cassandra 5 | `listen_address`/`rpc_address` = IP của VM, `broadcast_rpc_address` = IP mà pod thấy, `local_datacenter` khớp `CASSANDRA_LOCAL_DATACENTER` | driver dùng `broadcast_rpc_address` để nối lại các node |
| Kafka | **`advertised.listeners` phải là địa chỉ pod resolve và reach được** (ví dụ `PLAINTEXT://10.10.0.5:9092`), không dùng `localhost` | sau handshake đầu, client nhận metadata và nối thẳng tới node được quảng cáo |
| ZooKeeper | mở cổng client `2181`, đặt `maxClientCnxns` đủ lớn | mỗi pod TB giữ 1 session discovery |
| Redis/Valkey | mở `6379`, đặt `requirepass`, `maxmemory-policy` (khuyến nghị `allkeys-lru`) | cache chia sẻ giữa các replica |
| Firewall | cho phép dải CIDR của k3s vào 5 cổng trên | mặc định k3s: pod `10.42.0.0/16`, service `10.43.0.0/16` |

Kafka (KRaft) tham khảo:

```properties
listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=PLAINTEXT://<IP-máy-chủ-dữ-liệu>:9092
```

Cassandra tham khảo (`cassandra.yaml`):

```yaml
listen_address: <IP-máy-chủ-dữ-liệu>
rpc_address: 0.0.0.0
broadcast_rpc_address: <IP-máy-chủ-dữ-liệu>
```

### 3.2. Khai báo trong cụm và kiểm tra

```bash
# 1. sửa IP 203.0.113.10 trong file này thành IP máy chủ dữ liệu (5 chỗ), rồi:
kubectl apply -f deploy/k3s/03-external-data-plane.yaml

# 2. kiểm tra pod thấy được data plane
kubectl -n thingsboard run netcheck --rm -it --restart=Never --image=busybox:1.37 -- \
  sh -c 'for p in tb-postgres:5432 tb-cassandra:9042 tb-kafka:9092 tb-zookeeper:2181 tb-redis:6379; do \
         nc -vz ${p%%:*} ${p##*:}; done'
```

Quy mô hiện tại (1 máy chủ cho cả 4 dịch vụ) là điểm chết đơn lẻ cho toàn hệ thống: mất máy chủ đó
thì nền tảng ngừng ghi telemetry và không khởi động lại được pod. Khi cần HA thật, chỉ cần tách máy
chủ thành cụm (Kafka ≥ 3 broker, Cassandra ≥ 3 node RF=3, ZooKeeper ≥ 3, PostgreSQL primary +
standby) rồi cập nhật lại `Endpoints` — manifest của ThingsBoard **không phải sửa**.

## 4. Cấu hình production (đối chiếu `thingsboard.yml`)

Toàn bộ biến dưới đây do `thingsboard.yml` định nghĩa, đặt qua `ConfigMap`/`Secret`:

| Env | Giá trị production | Ghi chú |
|---|---|---|
| `TB_SERVICE_TYPE` | `tb-core` / `tb-rule-engine` / `tb-transport` | `monolith` chỉ dùng cho job install |
| `TB_SERVICE_ID` | tên pod | id node trong cluster, phải duy nhất |
| `TB_QUEUE_TYPE` | `kafka` | microservices bắt buộc Kafka |
| `TB_KAFKA_SERVERS` | `tb-kafka:9092` | danh sách broker, cách nhau dấu `,` |
| `TB_QUEUE_KAFKA_REPLICATION_FACTOR` | `3` | số bản sao của mỗi topic |
| `ZOOKEEPER_ENABLED` / `ZOOKEEPER_URL` | `true` / `tb-zookeeper:2181` | discovery giữa các service |
| `ZOOKEEPER_SESSION_TIMEOUT_MS` | `30000` | tăng khi mạng chập chờn để tránh rebalance liên tục |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://tb-postgres:5432/thingsboard` | |
| `spring.jpa.hibernate.ddl-auto` | đã là `none` trong `thingsboard.yml` | schema chỉ do job install tạo/cập nhật |
| `DATABASE_TS_TYPE` / `DATABASE_TS_LATEST_TYPE` | `cassandra` | hybrid: entities ở Postgres, telemetry ở Cassandra |
| `CASSANDRA_URL` / `CASSANDRA_KEYSPACE_NAME` | `tb-cassandra:9042` / `thingsboard` | |
| `CASSANDRA_USE_CREDENTIALS` + `CASSANDRA_USERNAME`/`_PASSWORD` | `true` + secret | |
| `CACHE_TYPE` | `redis` | bắt buộc khi > 1 replica |
| `REDIS_HOST` / `REDIS_PORT` | `tb-redis` / `6379` | |
| `METRICS_ENABLED` + `METRICS_ENDPOINTS_EXPOSE` | `true` + `info,health,prometheus` | `health` cần cho probe, `prometheus` cho scrape |
| `RUN_INSTALL` | `false` | service không tự chạy migration |
| `SECURITY_RBAC_ENABLED` | `true` | RBAC của fork; `false` = hành vi CE gốc |
| `JS_EVALUATOR` | `remote` (khuyến nghị) hoặc `local` | `remote` cần service JS executor; `local` chạy Nashorn trong rule engine |
| `JAVA_OPTS` | `-Xms1G -Xmx4G -XX:+UseG1GC -XX:MaxRAMPercentage=70` | đặt heap < limit container |

## 5. Quy trình deploy

```bash
# 0. máy chủ dữ liệu đã chạy Postgres/Cassandra/Kafka/ZooKeeper(/Redis) và mở firewall

# 1. namespace, config, endpoints trỏ ra máy chủ dữ liệu, secret, pull secret
kubectl apply -f deploy/k3s/00-namespace.yaml
kubectl apply -f deploy/k3s/01-config.yaml
kubectl apply -f deploy/k3s/03-external-data-plane.yaml
kubectl -n thingsboard create secret generic tb-secrets --from-literal=...

# 2. image đã mặc định là ghcr.io/vtapro/greeniq-backend:v4.4.0.0 trong manifest;
#    chỉ cần đổi tag khi roll bản mới
sed -i 's#greeniq-backend:v4.4.0.0#greeniq-backend:v4.4.0.1#' deploy/k3s/*.yaml

# 3. cài/cập nhật schema — 1 lần cho mỗi release, TRƯỚC khi rolling service
kubectl apply -f deploy/k3s/10-install-job.yaml
kubectl -n thingsboard wait --for=condition=complete job/tb-install --timeout=15m
kubectl -n thingsboard logs job/tb-install --tail=50
kubectl -n thingsboard delete job tb-install

# 4. services
kubectl apply -f deploy/k3s/20-tb-core.yaml
kubectl apply -f deploy/k3s/21-tb-rule-engine.yaml
kubectl apply -f deploy/k3s/22-tb-mqtt-transport.yaml
kubectl apply -f deploy/k3s/23-tb-http-transport.yaml
kubectl apply -f deploy/k3s/30-ingress.yaml

# 5. kiểm tra
kubectl -n thingsboard rollout status deploy/tb-core
kubectl -n thingsboard get pods,svc,ingress
curl -fsS https://app.greeniq.vn/api/noauth/whiteLabeling
```

Thứ tự quan trọng: **install job trước**, vì job tạo bảng/bảng mã hoá Cassandra và topics Kafka;
pod `tb-core` không chạy migration.

## 5.1. Domain `app.greeniq.vn`

Domain production của hệ thống là **`app.greeniq.vn`**, đã đặt sẵn trong `deploy/k3s/30-ingress.yaml`.
Ba việc phải làm sau khi deploy:

1. **DNS**: trỏ `app.greeniq.vn` về IP của node k3s / LoadBalancer của Traefik.

   ```bash
   kubectl -n kube-system get svc traefik      # lấy EXTERNAL-IP
   dig +short app.greeniq.vn                   # kiểm tra DNS đã trỏ đúng
   ```

2. **Base URL của platform** (dùng để sinh link trong email, QR code device, mobile app):
   `baseUrl` nằm trong `admin_settings` (không có biến môi trường), nên đặt một lần bằng UI
   **System Admin → Settings → General → Base URL** = `https://app.greeniq.vn`
   (hoặc `POST /api/admin/settings/general` với `{"general":{"baseUrl":"https://app.greeniq.vn"}}`).
   Bật luôn `prohibitDifferentUrl` để chặn truy cập qua hostname khác.

3. **Branding theo domain** (tính năng white labeling của fork): tạo bản ghi domain để
   `app.greeniq.vn` được map sang tenant tương ứng, khi đó trang login tự lấy logo/màu/mail template
   của tenant đó.

   - UI: **White labeling → tab Login** (tenant admin hoặc system admin đều có trang riêng).
   - API: `GET`/`POST /api/tenant/domain` với `{"name":"app.greeniq.vn"}`.
   - Ví dụ nhiều domain cho nhiều tenant:

     | Host | Tenant | Ghi chú |
     |---|---|---|
     | `app.greeniq.vn` | tenant GreenIQ | portal chính |
     | `khachhang.greeniq.vn` | tenant khác | cùng ingress, chỉ cần thêm DNS + record domain |

   Ingress hiện chỉ khai báo `app.greeniq.vn`; muốn dùng nhiều hostname con, thêm host vào cùng
   `rules` (cùng backend `tb-core`), không cần thêm service.

4. **MQTT cho thiết bị**: `tb-mqtt-transport` là `LoadBalancer` cổng **1883** (TCP, không đi qua
   ingress). Lấy IP bằng `kubectl -n thingsboard get svc tb-mqtt-transport`, rồi trỏ một bản ghi DNS
   kiểu `mqtt.greeniq.vn` về IP đó nếu muốn thiết bị dùng hostname thay vì IP.

## 6. HA & vận hành

- **Rollout an toàn**: `maxUnavailable: 0`, `maxSurge: 1`, `terminationGracePeriodSeconds: 90–120`.
  Rule engine cần thời gian drain actor trước khi chết.
- **Scale**: HPA theo CPU 70% (2→6 replica). Với rule engine, scale theo consumer lag của Kafka
  (`tb_rule_engine.*`) sẽ chính xác hơn CPU nếu tải không đều.
- **Probe**: `/actuator/health` (đúng `management.endpoints.web.exposure.include`), `startupProbe`
  cho phép tới 10 phút cho lần khởi động đầu.
- **Kafka**: `TB_QUEUE_KAFKA_REPLICATION_FACTOR=3`; với broker 3 node nên đặt `min.insync.replicas=2`
  để không mất message khi 1 broker chết.
- **ZooKeeper**: mất quorum → các service không tìm thấy nhau (API vẫn chạy nhưng không nhận được
  message). Cảnh báo theo dõi `zk` session và số node đăng ký.
- **Cassandra**: RF=3, repair định kỳ, không để dung lượng vượt 50% mỗi node.
- **Backup**: `pg_dump`/PITR cho PostgreSQL (chứa cả white labeling + RBAC trong `admin_settings`)
  và snapshot Cassandra cho telemetry.
- **Monitoring**: bật `METRICS_ENABLED=true`, scrape `/actuator/prometheus` của mọi service; import
  dashboard Grafana của ThingsBoard (`docker/monitoring` trong repo là bản tham chiếu).
- **TLS**: kết thúc TLS ở Ingress/ LB; MQTT nên có LB riêng (TCP passthrough) hoặc bật SSL trong
  transport (`SSL_ENABLED`, `SSL_*`).

## 7. Đặc thù của fork này khi chạy HA

| Hạng mục | Trạng thái | Lưu ý khi scale |
|---|---|---|
| White labeling, mail template, custom menu/translation | lưu trong `admin_settings` (PostgreSQL, có `tenant_id`) | không dùng state cục bộ → an toàn khi nhiều replica |
| RBAC (roles, entity groups, user groups, customer hierarchy) | lưu trong `admin_settings`, enforce ở tầng service | mỗi replica có cache TTL 10s → sau khi sửa role, các replica nhận thay đổi trong ≤ 10s |
| RBAC có thể tắt | `SECURITY_RBAC_ENABLED=false` | quay về đúng hành vi CE, không cần build lại image |
| Lọc entity theo nhóm | hiện lọc trong bộ nhớ, giới hạn 1000 entity/lần | với tenant lớn cần chuyển sang query theo group (đã ghi trong [access-control-roadmap.md](access-control-roadmap.md)) |
| Schema | không thêm bảng/cột | nâng cấp ThingsBoard CE bản mới không xung đột DB |

## 8. Giới hạn đã biết: Cassandra + danh sách key telemetry

Bản **CE gốc** không hiện thực việc liệt kê key telemetry theo entity trong implementation Cassandra:

`dao/src/main/java/org/thingsboard/server/dao/timeseries/CassandraBaseTimeseriesLatestDao.java`

```java
@Override
public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
    return Collections.emptyList();
}
```

Hệ quả khi production dùng `DATABASE_TS_TYPE=cassandra`:

- Tab telemetry của device vẫn hiển thị key (API `GET /api/plugins/telemetry/DEVICE/{id}/keys/timeseries`).
- Nhưng widget dashboard khi chọn data key (API `POST /api/entitiesQuery/find/keys`) sẽ **không thấy
  key nào**, vì API này đi qua hàm bị stub ở trên. Đã kiểm chứng thực tế trên stack Cassandra.

Ba lựa chọn (chọn 1 khi go-live):

1. **Chấp nhận**: người dùng tự nhập tên key trong widget (đúng hành vi CE hiện tại).
2. **Vá**: hiện thực `findAllKeysByEntityIds(Async)` cho Cassandra (đã thử và chạy đúng ở local,
   nhưng cần bản patch riêng vì đụng vào file gốc của ThingsBoard — cân nhắc khi merge upstream).
3. **Hybrid theo nhu cầu**: `DATABASE_TS_TYPE=cassandra` cho dữ liệu nóng, hoặc dùng `timescale/sql`
   nếu ưu tiên trải nghiệm UI hơn khả năng ghi cực lớn.

## 9. Bằng chứng: code không bị cắt bỏ nhánh production

Kiểm tra nhanh trên repo (đã chạy):

```bash
# không có file code nào bị sửa ngoài docs/ + scripts/ của môi trường dev
git diff HEAD --stat -- dao application common rule-engine transport msa

# thingsboard.yml không bị sửa so với bản CE gốc
git diff 3150f863b7..HEAD --stat -- application/src/main/resources/thingsboard.yml

# các bean Kafka/Cassandra/microservices vẫn còn nguyên
rg "queue.type:null.*kafka|service.type:null.*tb-core" common/queue application
```

Các bean chứng minh nhánh production còn nguyên: `KafkaTbCoreQueueFactory`, `KafkaTbRuleEngineQueueFactory`,
`KafkaTbTransportQueueFactory`, `KafkaEdqsSyncService`, `KafkaEdgeEventService`,
`CassandraTimeseriesDao`, `CassandraBaseTimeseriesLatestDao`.

## 10. Checklist trước khi go-live

- [ ] Image đã được push lên GHCR và `imagePullSecrets` đã cấu hình.
- [ ] Máy chủ dữ liệu đã mở firewall cho CIDR của k3s; `Endpoints` trong `03-external-data-plane.yaml`
      đã trỏ đúng IP (kiểm tra bằng pod `netcheck` ở mục 3.2).
- [ ] `advertised.listeners` của Kafka và `broadcast_rpc_address` của Cassandra đã đúng địa chỉ
      mà pod nhìn thấy (lỗi phổ biến nhất khi data plane nằm ngoài cụm).
- [ ] Chấp nhận rủi ro 1 máy chủ dữ liệu (SPOF) hoặc đã tách thành cụm HA (Postgres ≥ 2,
      Cassandra ≥ 3, Kafka ≥ 3, ZooKeeper ≥ 3).
- [ ] Secret tách khỏi Git (Sealed Secrets/SOPS/External Secrets), không hardcode trong manifest.
- [ ] Job `tb-install` chạy thành công trước khi rollout.
- [ ] `SECURITY_RBAC_ENABLED` đúng như mong muốn (mặc định trong manifest là `true`).
- [ ] Quyết định cho giới hạn Cassandra ở mục 8 đã được ghi nhận.
- [ ] Probe `/actuator/health` xanh, HPA hoạt động, cảnh báo consumer lag đã bật.
- [ ] Backup tự động (PostgreSQL + Cassandra) và đã thử restore.
- [ ] `mvn -B license:check` vẫn pass (không sửa header bản quyền của ThingsBoard).
