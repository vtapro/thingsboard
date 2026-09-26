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
| Cache | `caffeine` | `caffeine` trong mỗi pod (TTL ngắn) — deployment này **không dùng Redis** |
| Số replica | 1 | ≥ 2 mỗi service, có HPA |

Code ThingsBoard **không bị sửa để bỏ Kafka/Cassandra**. Việc "không dùng Kafka/Cassandra" chỉ
áp dụng cho script và tài liệu dev; toàn bộ nhánh Kafka/Cassandra/microservices của ThingsBoard
CE vẫn nguyên vẹn (xem mục 8 để có bằng chứng kiểm tra).

## 1. Kiến trúc triển khai

```text
                     ┌─────────────── Ingress (nginx) ─────────────────┐
   user ── HTTPS ──▶ │  /  (UI + REST API)                             │
                     └──────────────────────┬──────────────────────────┘
                                            ▼
    ══════════════════════════ TRONG CỤM k3s ═══════════════════════════════════════
                     ┌──────────────────────┴──────────────────────────┐
                     │  tb-core x2           tb-rule-engine x2         │
                     │  tb-mqtt-transport   tb-http-transport          │
                     │  tb-coap / tb-lwm2m / tb-snmp (tuỳ chọn)        │
                     └───────┬───────────┬───────────┬─────────────────┘
                             ▼           ▼           ▼
                          Kafka     ZooKeeper   (cache: caffeine
                       (có sẵn hoặc  (discovery)  trong từng pod)
                       trong cụm)
    ══════════════ Hostname public + TLS (ngoài cụm) ══════════════════════════════
                             ▼                              ▼
                    PostgreSQL ─ entities,             Cassandra ─ telemetry
                    users, RBAC, white labeling
                  (MÁY CHỦ 1, thuê riêng)          (MÁY CHỦ 2, thuê riêng)
```

**Mỗi service một image riêng** — deployment nào kéo đúng image đó:

| Deployment | Image | `TB_SERVICE_TYPE` | Cổng | Vai trò |
|---|---|---|---|---|
| `tb-core` | `tb-node` | `tb-core` | 8080 (REST/UI/actuator), 7070 (edge gRPC) | API, entities, telemetry, white labeling, RBAC |
| `tb-rule-engine` | `tb-node` | `tb-rule-engine` | 8080 (actuator) | thực thi rule chain |
| `tb-mqtt-transport` | `tb-mqtt-transport` | `tb-transport` | 1883 (MQTT), 8080 (actuator) | thiết bị kết nối MQTT |
| `tb-http-transport` | `tb-http-transport` | `tb-transport` | 8080 | thiết bị gọi REST `/api/v1/**` |
| `tb-coap-transport` | `tb-coap-transport` | `tb-transport` | 5683/UDP | CoAP (tuỳ chọn) |
| `tb-lwm2m-transport` | `tb-lwm2m-transport` | `tb-transport` | 5685/UDP | LwM2M (tuỳ chọn) |
| `tb-snmp-transport` | `tb-snmp-transport` | `tb-transport` | 1620/UDP | SNMP (tuỳ chọn) |
| `tb-edqs` | `tb-edqs` | — (service riêng) | 8080 | entity data query service (tuỳ chọn) |
| `tb-vc-executor` | `tb-vc-executor` | `tb-vc-executor` | — | version control executor (tuỳ chọn) |
| Job `tb-install` | `tb-node` | `monolith` (chỉ chạy installer) | — | cài/nâng cấp schema, chạy 1 lần mỗi release |

`tb-core` và `tb-rule-engine` **dùng chung image `tb-node`** vì ThingsBoard CE đóng gói cùng một jar
cho hai vai trò này (khác nhau ở `TB_SERVICE_TYPE`); các transport thì mỗi module Maven có main class
riêng nên có image riêng.

`TB_SERVICE_ID` lấy từ `metadata.name` của pod (duy nhất trong namespace) — đây là id node trong
cluster. Nếu muốn id ổn định qua các lần restart, dùng `StatefulSet` thay `Deployment`.

## 2. Build image lên GHCR

Mỗi ThingsBoard service có **một image riêng**, đặt tên theo service, version sản phẩm hiện tại
**`v4.4.0.0`** (biến `IMAGE_VERSION` trong workflow):

| Service | Image |
|---|---|
| tb-node (monolith / tb-core / tb-rule-engine, kiêm job installer) | `ghcr.io/vtapro/tb-node:v4.4.0.0` |
| tb-mqtt-transport | `ghcr.io/vtapro/tb-mqtt-transport:v4.4.0.0` |
| tb-http-transport | `ghcr.io/vtapro/tb-http-transport:v4.4.0.0` |
| tb-coap-transport | `ghcr.io/vtapro/tb-coap-transport:v4.4.0.0` |
| tb-lwm2m-transport | `ghcr.io/vtapro/tb-lwm2m-transport:v4.4.0.0` |
| tb-snmp-transport | `ghcr.io/vtapro/tb-snmp-transport:v4.4.0.0` |
| tb-edqs | `ghcr.io/vtapro/tb-edqs:v4.4.0.0` |
| tb-vc-executor | `ghcr.io/vtapro/tb-vc-executor:v4.4.0.0` |

Lưu ý: `greeniq-backend` / `greeniq-frontend` trên GHCR đã là của ứng dụng khác
(`greeniq-backend:v2.8.2.69`), nên nền tảng ThingsBoard dùng nhóm `tb-*` để không đụng tên.

Workflow [`.github/workflows/publish-images.yml`](../.github/workflows/publish-images.yml) chạy 2
job: `build-jars` build toàn bộ repo **một lần** (backend + UI + boot jar của từng service), rồi
`images` build song song từng image theo matrix và push lên GHCR mỗi khi push branch hoặc tag `v*`:

```text
docker/msa/Dockerfile.tb-node     -> tb-node (+ script SQL/Cassandra cho installer)
docker/msa/Dockerfile.service     -> mọi service còn lại (jre + đúng 1 boot jar)
```

Cấu trúc image giống bản chính thức: `eclipse-temurin:25-jre` + boot jar của module tương ứng
(`transport/mqtt` → `tb-mqtt-transport-boot.jar`, `transport/http` → `tb-http-transport-boot.jar`, …),
nên mỗi pod chỉ mang đúng những gì nó chạy.

> Lưu ý: GitHub **chặn push** file nằm trong `.github/workflows/` nếu token/credential đang dùng
> không có scope `workflow`. Nếu gặp lỗi
> `refusing to allow an OAuth App to create or update workflow ... without workflow scope`, chạy
> `gh auth refresh -h github.com -s workflow` (hoặc tạo PAT có scope `workflow`) rồi push lại file đó.
> Trong lúc chờ, vẫn build image bằng tay: `docker build -f docker/tb-custom/Dockerfile -t ... .`
> và `docker push` lên GHCR.

Mỗi image được gắn 4 tag giống nhau: `:v4.4.0.0` (tag để deploy), `:<branch>`, `:sha-<short>`
(truy vết commit) và `:latest` (chỉ trên default branch).

### 2.1. Build/push khi máy có Docker

```bash
# build toàn bộ boot jar một lần
mvn -B -T 1C clean install -DskipTests \
  -Dpkg.skip.deb=true -Dpkg.skip.rpm=true -Dpkg.skip.zip=true

# tb-node (kèm script SQL/Cassandra cho installer)
mkdir -p /tmp/jars && cp application/target/thingsboard-4.4.0-SNAPSHOT-boot.jar /tmp/jars/tb-node.jar
docker build -f docker/msa/Dockerfile.tb-node \
  --build-context jars=/tmp/jars --build-arg SERVICE_JAR=tb-node.jar \
  -t ghcr.io/vtapro/tb-node:v4.4.0.0 .

# mqtt transport (lặp lại cho http/coap/lwm2m/snmp/edqs/vc-executor, đổi jar tương ứng)
cp transport/mqtt/target/tb-mqtt-transport-4.4.0-SNAPSHOT-boot.jar /tmp/jars/tb-mqtt-transport.jar
docker build -f docker/msa/Dockerfile.service \
  --build-context jars=/tmp/jars --build-arg SERVICE_JAR=tb-mqtt-transport.jar \
  -t ghcr.io/vtapro/tb-mqtt-transport:v4.4.0.0 .

# đăng nhập GHCR (PAT cần scope write:packages) rồi push
echo "$CR_PAT" | docker login ghcr.io -u vtapro --password-stdin
docker push ghcr.io/vtapro/tb-node:v4.4.0.0
docker push ghcr.io/vtapro/tb-mqtt-transport:v4.4.0.0
```

### 2.2. Không có Docker ở máy dev — dùng GitHub Actions

Máy dev hiện tại **không cài Docker Desktop / docker CLI**, nên cách gọn nhất là để GitHub Actions
build. Việc duy nhất còn thiếu là quyền `workflow` cho credential đang dùng để push file workflow:

```bash
# 1. cấp scope (mở trình duyệt, xác nhận 1 lần)
gh auth refresh -h github.com -s workflow

# 2. push file workflow lên repo
git add .github/workflows/publish-images.yml
git commit -m "ci: publish the ThingsBoard service images to GHCR"
git push origin RBAC-full-groups-tabs

# 3. theo dõi build — job build-jars ~15 phút, sau đó 8 image build song song
gh run watch

# 4. kiểm tra image đã lên GHCR (8 package tb-*)
docker manifest inspect ghcr.io/vtapro/tb-node:v4.4.0.0    # nếu có docker
# hoặc xem trực tiếp: https://github.com/vtapro?tab=packages
```

Workflow đã nằm trong repo (`.github/workflows/publish-images.yml`), nên chỉ cần push nhánh là
Actions chạy. Nếu sau này credential mất scope `workflow`, có thể tạo lại file này bằng nút
**Add file** trên web GitHub (web không bị giới hạn scope như token của git).

Image là **private** theo mặc định của GHCR. Trên k3s tạo pull secret:

```bash
kubectl -n thingsboard create secret docker-registry ghcr \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<PAT có scope read:packages>
```

và thêm `imagePullSecrets: [{name: ghcr}]` vào `spec.template.spec` của từng Deployment (hoặc gắn
vào ServiceAccount của namespace).

## 3. Data plane: 2 database managed ngoài cụm + hạ tầng trong cụm

PostgreSQL và Cassandra là **2 cluster managed, nằm ngoài k3s**, đều bật SSL:

| | Endpoint | Port | Ghi chú |
|---|---|---|---|
| PostgreSQL | `postgresql-215231-0.cloudclusters.net` | `10012` | database **`greeniq`**, user `vtheanh04@gmail.com` — đặt trong `SPRING_DATASOURCE_URL` |
| Cassandra | `cassandra-215233-0.cloudclusters.net` | `19948` | keyspace **`greeniq`**, user `vtheanh04@gmail.com` — đặt trong `CASSANDRA_URL` / `CASSANDRA_KEYSPACE_NAME` |

```text
  k3s cluster (namespace thingsboard)              CloudClusters (ngoài cụm)
  ┌───────────────────────────────────┐            ┌──────────────────────────────┐
  │  tb-core / tb-rule-engine /       │            │  postgresql-215231-0 …:10012 │
  │  tb-web-ui / tb-js-executor /     │── TLS ────▶│  (entities, users, RBAC,     │
  │  tb-*-transport                   │            │   white labeling)            │
  │                                   │            ├──────────────────────────────┤
  │  tb-kafka  tb-zookeeper           │── TLS ────▶│  cassandra-215233-0 …:19948  │
  │  tb-haproxy (edge)                │            │  (telemetry)                 │
  └───────────────────────────────────┘            └──────────────────────────────┘
            (hạ tầng trong cụm)                       (2 cluster DB thuê riêng)
```

Vì endpoint là **hostname public có TLS**, không cần `Service`/`Endpoints` trỏ IP trong cụm: pod
resolve trực tiếp hostname của nhà cung cấp, và việc verify certificate hoạt động đúng (hostname
trong `CASSANDRA_URL`/JDBC URL khớp CN/SAN của cert).

### 3.1. Cấu hình bắt buộc cho 2 database managed

| Việc | PostgreSQL | Cassandra |
|---|---|---|
| Whitelist IP client | thêm IP 2 worker `89.117.54.100`, `144.91.106.154` vào allow-list của cluster | như Postgres (cùng danh sách) |
| TLS | `sslmode=require` trong JDBC URL (nâng lên `verify-full` khi có CA của provider) | `CASSANDRA_USE_SSL=true` + `CASSANDRA_SSL_HOSTNAME_VALIDATION=true` |
| Credentials | `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` trong secret `tb-secrets` | `CASSANDRA_USERNAME` / `CASSANDRA_PASSWORD` trong secret `tb-secrets` |
| Database/keyspace | DB `greeniq` đã tạo sẵn (đổi tên trong `SPRING_DATASOURCE_URL` nếu muốn khác) | keyspace `greeniq` đã tạo sẵn; job install chỉ chạy `CREATE KEYSPACE IF NOT EXISTS` nên **không ghi đè** replication của keyspace hiện có |
| Data center | — | `CASSANDRA_LOCAL_DATACENTER` phải khớp tên datacenter của cluster — đã kiểm tra thực tế: **`DC1`** (không phải `datacenter1`) |
| Truststore | — | server dùng **cert self-signed của CloudClusters**, không có trong JVM truststore → phải tải `cassandra.truststore.jks` từ console, tạo Secret `tb-cassandra-tls` và khai `CASSANDRA_SSL_TRUST_STORE=/certs/cassandra.truststore.jks` + password trong `tb-secrets` |
| Hostname validation | — | `CASSANDRA_SSL_HOSTNAME_VALIDATION=false` vì cert có `CN=US, OU=CassandraCluster` (không chứa hostname); kết nối vẫn được mã hoá và CA được pin qua truststore |

Kiểm tra tên datacenter của Cassandra (mở Shell/SSH trong CloudClusters hoặc dùng `cqlsh`):

```bash
cqlsh --ssl cassandra-215233-0.cloudclusters.net 19948 -u vtheanh04@gmail.com -p '<password>' \
  -e "SELECT data_center, release_version FROM system.local;"
# kết quả trên cluster hiện tại: DC1 / 3.11.10
```

Tạo 2 secret cho Cassandra (file `cassandra.truststore.jks` tải từ tab **Security** của console):

```bash
kubectl -n thingsboard create secret generic tb-cassandra-tls \
  --from-file=cassandra.truststore.jks=./cassandra.truststore.jks
# password của truststore nằm trong tb-secrets (chạy scripts/set-tb-secrets.ps1 hoặc thêm key
# CASSANDRA_SSL_TRUST_STORE_PASSWORD)
```

Hai file `user.cer.pem` / `user.key.pem` (keystore) **chỉ cần** khi cluster bật xác thực bằng client
certificate (mTLS). Cluster hiện tại không yêu cầu: đã kết nối thành công chỉ với user + password
+ TLS, nên ta bỏ qua keystore.

> Lưu ý phiên bản: CloudClusters đang chạy **Cassandra 3.11.10**. ThingsBoard CE 4.4 dùng DataStax
> driver 4.x (hỗ trợ từ 3.11 trở lên) nên về nguyên tắc là chạy được, nhưng bản 3.11 đã hết hỗ trợ
> chính thức — nếu console cho phép nâng lên 4.x/5.x thì nên nâng để tránh rủi ro về sau.

Tất cả biến trên đã khai báo sẵn trong `01-config.yaml`; chỉ cần điền user/password vào secret:

```bash
kubectl -n thingsboard create secret generic tb-secrets \
  --from-literal=SPRING_DATASOURCE_USERNAME='<user postgres>' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<mật khẩu postgres>' \
  --from-literal=CASSANDRA_USERNAME='<user cassandra>' \
  --from-literal=CASSANDRA_PASSWORD='<mật khẩu cassandra>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 3.2. Khai báo trong cụm và kiểm tra

```bash
# 1. sửa 2 IP mẫu (203.0.113.10 = Postgres, 203.0.113.11 = Cassandra) thành IP thật, rồi:
kubectl apply -f deploy/k3s/03-external-databases.yaml

# 2. kiểm tra pod thấy được 2 database ngoài cụm
kubectl -n thingsboard run netcheck --rm -it --restart=Never --image=busybox:1.37 -- \
  sh -c 'for p in tb-postgres:5432 tb-cassandra:9042; do \
         nc -vz ${p%%:*} ${p##*:}; done'
```

### 3.1b. Kafka chạy trong cụm (mặc định của deployment này)

**Cấu hình đang dùng: Kafka + ZooKeeper + toàn bộ ThingsBoard services chạy trong k3s; chỉ
PostgreSQL và Cassandra nằm ngoài cụm.** Phần dưới chỉ là phương án dự phòng nếu sau này muốn
chuyển queue sang một broker có sẵn.

| | Cách A — dùng Kafka có sẵn (dự phòng) | Cách B — Kafka trong cụm (**đang dùng**) |
|---|---|---|
| Khai báo | `TB_KAFKA_SERVERS: <host>:<port>` trong `01-config.yaml` | `TB_KAFKA_SERVERS: tb-kafka:9092` |
| Manifest | bỏ `04-kafka.yaml` khỏi `kustomization.yaml` (xoá Deployment nếu đã tạo) | giữ `04-kafka.yaml` |
| Yêu cầu | broker phải cho pod kết nối; nếu bật SASL/SSL thì đọc bảng dưới | — |

Broker có sẵn dùng TLS/SASL thì thêm vào `01-config.yaml`:

```yaml
  # TLS (SSL keystore/truststore mount vào pod)
  TB_KAFKA_SSL_ENABLED: "true"
  TB_KAFKA_SSL_TRUSTSTORE_LOCATION: /certs/kafka.truststore.jks
  TB_KAFKA_SSL_TRUSTSTORE_PASSWORD: <từ secret>
  # SASL (đi qua khối "confluent" của thingsboard.yml)
  TB_QUEUE_KAFKA_USE_CONFLUENT_CLOUD: "true"
  TB_QUEUE_KAFKA_CONFLUENT_SECURITY_PROTOCOL: SASL_SSL
  TB_QUEUE_KAFKA_CONFLUENT_SASL_MECHANISM: PLAIN
  TB_QUEUE_KAFKA_CONFLUENT_SASL_JAAS_CONFIG: 'org.apache.kafka.common.security.plain.PlainLoginModule required username="<user>" password="<password>";'
  # quan trọng: js-executor (Node) KHÔNG đọc được cấu hình SASL này
  JS_EVALUATOR: local
```

`TB_QUEUE_KAFKA_CONFLUENT_SASL_JAAS_CONFIG` chứa mật khẩu nên phải để trong Secret `tb-secrets`
(các pod ThingsBoard đã `envFrom` secret này), không đặt trong ConfigMap.

### 3.1c. Hạ tầng chạy trong cụm

| Manifest | Thành phần | Cấu hình hiện tại | Khi cần HA |
|---|---|---|---|
| `04-kafka.yaml` | Kafka (tuỳ chọn, xem §3.1b) | KRaft, **1 broker**, PVC 10Gi (`local-path`), `advertised.listeners=PLAINTEXT://tb-kafka-0...:9092` | scale lên 3 broker (node id + quorum voters) và đặt `TB_QUEUE_KAFKA_REPLICATION_FACTOR=3`, `min.insync.replicas=2` |
| `05-zookeeper.yaml` | ZooKeeper | **1 replica**, PVC 2Gi | 3 replica (số lẻ mới có quorum) |

**Không dùng Redis** (theo yêu cầu): cache là caffeine trong từng pod, với TTL 5 phút cho các cache
nhạy cảm (`CACHE_SPECS_*_TTL` trong `01-config.yaml`) để dữ liệu cũ tự hết hạn nhanh. Đánh đổi: sửa
device/credential/role trên một replica có thể mất tới 5 phút mới thấy ở replica còn lại. Nếu sau
này muốn nhất quán tức thời thì thêm Redis và đổi `CACHE_TYPE: redis` (bỏ các TTL override).

ZooKeeper vẫn phải chạy trong cụm (hoặc trỏ `ZOOKEEPER_URL` ra ngoài — CE không có auth cho ZK nên
chỉ nên để trong mạng nội bộ): đây là service discovery, mỗi pod giữ 1 session.

Điểm chết đơn lẻ hiện tại: **Kafka và ZooKeeper chỉ 1 replica** (nếu dùng phương án trong cụm) và 2
cluster database bên ngoài.
Mất Kafka thì nền tảng ngừng xử lý; mất Cassandra thì không ghi/đọc telemetry; mất Postgres thì
không đăng nhập được. Khi cần HA thật: Kafka ≥ 3 broker, ZooKeeper ≥ 3, Cassandra ≥ 3 node RF=3,
PostgreSQL primary + standby — manifest của ThingsBoard **không phải sửa**, chỉ đổi `Endpoints`
(nếu DB chuyển sang cụm mới) và tăng replica.

### 3.4. Bảng cổng chuẩn của ThingsBoard (và cách expose trong cụm này)

Đây là danh sách cổng chính thức của ThingsBoard, đối chiếu với manifest trong `deploy/k3s/`:

| Cổng | Protocol | Dịch vụ | Ai giữ cổng | Địa chỉ truy cập |
|---|---|---|---|---|
| 80 | TCP | Web UI + REST API (HTTP) | `tb-haproxy` (hostPort 80) | `http://app.greeniq.vn` |
| 443 | TCP | Web UI + REST API (HTTPS, cert Let's Encrypt) | `tb-haproxy` (hostPort 443) | `https://app.greeniq.vn` |
| 1883 | TCP | MQTT | `tb-haproxy` (hostPort 1883 → `tb-mqtt-transport`) | `mqtt://app.greeniq.vn:1883` |
| 8883 | TCP | MQTT over SSL | `tb-haproxy` (TLS terminate, hostPort 8883) | `mqtts://app.greeniq.vn:8883` |
| 5683 | UDP | CoAP | `tb-coap-transport` (hostPort 5683) | `<worker-ip>:5683` |
| 5684 | UDP | CoAP over DTLS | `tb-coap-transport` (bật `COAP_DTLS_ENABLED=true` + cert) | `<worker-ip>:5684` |
| 5685 | UDP | LwM2M CoAP | `tb-lwm2m-transport` (hostPort 5685) | `<worker-ip>:5685` |
| 5686 | UDP | LwM2M over DTLS | `tb-lwm2m-transport` + cert | `<worker-ip>:5686` |
| 5687 | UDP | LwM2M Bootstrap | `tb-lwm2m-transport` (hostPort 5687) | `<worker-ip>:5687` |
| 5688 | UDP | LwM2M Bootstrap DTLS | `tb-lwm2m-transport` + cert | `<worker-ip>:5688` |
| 161 | UDP | SNMP | `tb-snmp-transport` (hostPort 161) | `<worker-ip>:161` |
| 7070 | TCP | Edge RPC (gRPC) | `tb-haproxy` (hostPort 7070 → `tb-core`) | `<worker-ip>:7070` |

**HAProxy không hỗ trợ UDP**, nên CoAP/LwM2M/SNMP được phục vụ trực tiếp bằng `hostPort` trên
transport (đúng số cổng chuẩn, không cần DNAT). Các cổng TCP đi qua HAProxy để có TLS và một điểm
vào duy nhất; NodePort (30080/31883/30707) vẫn còn trong `Service` cho nhu cầu nội bộ.

Kiểm tra nhanh:

```bash
kubectl -n thingsboard run portcheck --rm -i --restart=Never --image=busybox:1.37 -- \
  sh -c 'for p in 80 443 1883 8883 7070; do printf "%-6s " $p; nc -z -w2 <worker-ip> $p && echo OPEN || echo closed; done'
```

### 3.5. Chứng chỉ TLS (Let's Encrypt) và gia hạn tự động

HAProxy chỉ là proxy, **không phát hành chứng chỉ** — nó cần cert do CA cấp (giống bản docker chính
thức của ThingsBoard ghép `haproxy + certbot`). Trong cụm này:

| Thành phần | Vai trò |
|---|---|
| `31-acme-webroot.yaml` | Pod nginx + PVC `tb-acme-webroot`: nơi chứa challenge HTTP-01 |
| `31-certbot-issue.yaml` | Job xin cert (chạy lại khi cần cấp mới/force) |
| `32-certbot-renew.yaml` | CronJob ngày 1 mỗi tháng: renew + cập nhật secret + restart HAProxy |
| `scripts/use-letsencrypt-cert.ps1` | Lấy cert từ PVC → secret `tb-haproxy-tls` → restart HAProxy |

Cách hoạt động: HAProxy route `/.well-known/acme-challenge/` sang pod nginx, certbot (job) ghi
challenge vào PVC dùng chung, nên Let's Encrypt kiểm tra được dù DNS trỏ vào worker nào trong 2
worker.

```bash
# cap moi / gia han thu cong
kubectl -n thingsboard delete job tb-certbot --ignore-not-found
kubectl apply -f deploy/k3s/31-certbot-issue.yaml
kubectl -n thingsboard logs job/tb-certbot -f
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\use-letsencrypt-cert.ps1

# kiem tra gia han tu dong
kubectl -n thingsboard get cronjob tb-certbot-renew
kubectl -n thingsboard create job tb-certbot-renew-manual --from=cronjob/tb-certbot-renew
kubectl -n thingsboard logs job/tb-certbot-renew-manual
```

> Lưu ý vận hành: **sửa ConfigMap `tb-haproxy-config` xong phải `kubectl rollout restart
> deploy/tb-haproxy`** để HAProxy nạp lại cấu hình. Deployment dùng strategy `Recreate` (vì
> `hostPort` chỉ cho 1 pod/node), nên có vài giây gián đoạn khi restart.

### 3.3. Cụm k3s thực tế (kiểm tra ngày 2026-09-24)

Đọc trực tiếp từ cụm bằng kubectl (kubeconfig trong `%APPDATA%\Lens\kubeconfigs`):

| Node | Vai trò | vCPU / RAM | IP | Trạng thái |
|---|---|---|---|---|
| `vmi3011340` | worker | 4 / ~8Gi | `89.117.54.100` | Ready, nhận workload |
| `vmi3215905` | worker | 4 / ~8Gi | `144.91.106.154`, IPv6 | Ready, nhận workload |
| `vmi3320735` | control plane | 4 / ~8Gi | `62.171.137.148`, IPv6 | Ready nhưng **cordoned** (taint `node-role.kubernetes.io/control-plane`, `node.kubernetes.io/unschedulable`) |

Version k3s: `v1.35.5+k3s1`. StorageClass: `local-path` (mặc định) — dùng cho PVC của Kafka và
ZooKeeper chạy trong cụm; các pod ThingsBoard thì **không cần PVC** vì state nằm ở 2 cluster
database bên ngoài và cache là caffeine trong pod.

**Vậy capacity khả dụng = 2 worker = 8 vCPU / ~16Gi.** Control plane không nhận pod, nên đừng tính
vào. Bộ manifest hiện tại (TB services + Kafka + ZooKeeper) đã được hạ request cho vừa cụm.

**Thành phần hệ thống đang có / còn thiếu** (kiểm tra bằng `kubectl get pods -A`):

| Thành phần | Trạng thái | Ảnh hưởng |
|---|---|---|
| CoreDNS, local-path-provisioner, metrics-server | ✅ đang chạy | đủ cho nhu cầu của ThingsBoard |
| Ingress controller | ❌ **không có pod nào**, nhưng `IngressClass nginx` vẫn tồn tại (tàn dư) | `30-ingress.yaml` chưa hoạt động, `app.greeniq.vn` chưa vào được |
| k3s servicelb / MetalLB / Traefik | ❌ không có | `Service type=LoadBalancer` sẽ mãi ở trạng thái `<pending>` |

Hai việc phải làm trên cụm (sau khi đã có image trên GHCR):

```bash
# 1) cài ingress controller (bản baremetal dùng NodePort, không cần LoadBalancer)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/baremetal/deploy.yaml
kubectl -n ingress-nginx get pods,svc        # chờ controller Running, xem NodePort 80/443

# 2) trỏ DNS app.greeniq.vn về IP một worker (89.117.54.100 hoặc 144.91.106.154),
#    hoặc dùng LoadBalancer thật nếu sau này cài MetalLB:
#    kubectl apply -f https://raw.githubusercontent.com/metallb/metallb/v0.14.9/config/manifests/metallb-native.yaml
```

Đường vào mặc định của nền tảng **không dùng ingress controller** mà dùng HAProxy
(`30-haproxy.yaml`): `http://89.117.54.100:30080` hoặc `http://144.91.106.154:30080`. Chỉ khi muốn
thay HAProxy bằng ingress-nginx thì mới cài controller ở trên rồi dùng `30-ingress.yaml`.

**Phân bố pod**: mỗi Deployment đã khai báo `topologySpreadConstraints` (maxSkew 1 theo
`kubernetes.io/hostname`) nên 2 replica được đẩy sang 2 worker khác nhau. `whenUnsatisfiable:
ScheduleAnyway` để pod vẫn chạy được khi chỉ còn 1 node trống (mất 1 worker không làm pod Pending).

```bash
kubectl -n thingsboard get pods -o wide        # kiểm tra NODE của từng pod
kubectl top nodes                              # kiểm tra tài nguyên còn trống
```

**Tài nguyên**: control plane bị cordon nên chỉ 2 worker nhận pod. Tổng request của bộ manifest hiện
tại:

| Workload | replica | request/pod | tổng request |
|---|---|---|---|
| tb-core | 2 | 500m / 1.5Gi | 1 CPU / 3Gi |
| tb-rule-engine | 2 | 500m / 1.5Gi | 1 CPU / 3Gi |
| tb-mqtt-transport | 2 | 250m / 512Mi | 0.5 CPU / 1Gi |
| tb-http-transport | 2 | 250m / 512Mi | 0.5 CPU / 1Gi |
| tb-zookeeper | 1 | 200m / 512Mi | 0.2 CPU / 0.5Gi |
| tb-kafka | 1 | 500m / 1Gi | 0.5 CPU / 1Gi |
| **tổng (chưa tính protocol transport tuỳ chọn)** | **11 pod** | — | **~3.8 CPU / ~9.8Gi** |

Con số này nằm trong 8 vCPU / 16Gi của 2 worker, còn khoảng 4 CPU / 6Gi trống cho k3s system pods và
HPA (HPA tối đa 4 replica cho tb-core và tb-rule-engine). `JAVA_OPTS` trong ConfigMap đã đặt heap
`-Xmx2G`, thấp hơn `limits` 3Gi để tránh OOMKilled.

Nhớ giữ `limits` > `requests` để HPA còn chỗ nhân bản, và đặt heap (`-Xmx`) thấp hơn `limits` memory
khoảng 20–25% để JVM không bị OOMKilled.

**Storage**: mọi trạng thái đã nằm ở 2 cluster database (PostgreSQL/Cassandra) và cache là caffeine trong pod, nên
các pod ThingsBoard **không cần PVC** — đây là điểm thuận lợi của mô hình data plane bên ngoài.

**MQTT**: vì cụm **không có servicelb/MetalLB**, `tb-mqtt-transport` được khai báo `type: NodePort`
với `nodePort: 31883` → thiết bị kết nối `89.117.54.100:31883` hoặc `144.91.106.154:31883`
(DNS `mqtt.greeniq.vn` trỏ vào 1 trong 2 IP đó). Nếu sau này cài MetalLB, chỉ cần đổi Service thành
`type: LoadBalancer`, `port: 1883` và bỏ `nodePort`.

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
| `METRICS_ENABLED` + `METRICS_ENDPOINTS_EXPOSE` | `true` + `info,health,prometheus` | `health` cần cho probe, `prometheus` cho scrape |
| `RUN_INSTALL` | `false` | service không tự chạy migration |
| `SECURITY_RBAC_ENABLED` | `true` | RBAC của fork; `false` = hành vi CE gốc |
| `JS_EVALUATOR` | `remote` (khuyến nghị) hoặc `local` | `remote` cần service JS executor; `local` chạy Nashorn trong rule engine |
| `JAVA_OPTS` | `-Xms512M -Xmx2G -XX:+UseG1GC -XX:MaxRAMPercentage=70` | heap (`-Xmx`) phải thấp hơn `limits` memory |

## 5. Quy trình deploy

```bash
# 0. 2 máy chủ đã chạy PostgreSQL và Cassandra, firewall đã mở cho CIDR của k3s

# 1. namespace, config, endpoints trỏ ra 2 máy chủ database, secret, pull secret
kubectl apply -f deploy/k3s/00-namespace.yaml
kubectl apply -f deploy/k3s/01-config.yaml
kubectl apply -f deploy/k3s/03-external-databases.yaml
kubectl -n thingsboard create secret generic tb-secrets --from-literal=...

# 2. hạ tầng trong cụm: ZooKeeper trước, sau đó Kafka (bỏ Kafka nếu dùng broker có sẵn)
kubectl apply -f deploy/k3s/05-zookeeper.yaml
kubectl apply -f deploy/k3s/04-kafka.yaml
kubectl -n thingsboard rollout status statefulset/tb-zookeeper --timeout=5m
kubectl -n thingsboard rollout status statefulset/tb-kafka --timeout=5m

# 3. image đã mặc định là ghcr.io/vtapro/tb-*:v4.4.0.0 trong manifest;
#    chỉ đổi tag khi roll bản mới
sed -i 's#:v4.4.0.0#:v4.4.0.1#' deploy/k3s/*.yaml

# 4. cài/cập nhật schema — 1 lần cho mỗi release, TRƯỚC khi rolling service
kubectl apply -f deploy/k3s/10-install-job.yaml
kubectl -n thingsboard wait --for=condition=complete job/tb-install --timeout=15m
kubectl -n thingsboard logs job/tb-install --tail=50
kubectl -n thingsboard delete job tb-install

# 5. services
kubectl apply -f deploy/k3s/20-tb-core.yaml
kubectl apply -f deploy/k3s/21-tb-rule-engine.yaml
kubectl apply -f deploy/k3s/22-tb-mqtt-transport.yaml
kubectl apply -f deploy/k3s/23-tb-http-transport.yaml
kubectl apply -f deploy/k3s/24-protocol-transports.yaml   # tuỳ chọn
kubectl apply -f deploy/k3s/30-ingress.yaml

# 6. kiểm tra
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
- **Monitoring**: bật `METRICS_ENABLED=true`, scrape `/actuator/prometheus` của mọi service (đã bật sẵn trong
  `deploy/k3s/01-config.yaml`); dùng Prometheus/Grafana **bên ngoài cụm** — repo không còn manifest monitoring
  (stack trong namespace `monitoring` đã được gỡ).
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

## 8. Đã vá: Cassandra + danh sách key telemetry

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

**Fork này đã vá** trong `dao/src/main/java/org/thingsboard/server/dao/timeseries/CassandraBaseTimeseriesLatestDao.java`:

- `findAllKeysByEntityIds(Async)`: `SELECT DISTINCT key FROM ts_kv_latest_cf WHERE entity_type = ?
  AND entity_id = ?` cho từng entity, hợp nhất và loại trùng — đúng ngữ nghĩa của bản SQL
  (`SqlTimeseriesLatestDao`).
- `findLatestByEntityIds(Async)`: hợp nhất `findAllLatest` của từng entity.

Vì file nằm trong module `dao` (mã gốc ThingsBoard), khi merge upstream cần giữ lại hai đoạn này;
header SPDX của file vẫn nguyên vẹn. Sau khi deploy image mới, widget dashboard chọn được data key
như bản dùng PostgreSQL.

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

## 10. Năng lực đo được & lộ trình mở rộng

Đã đo tải thật trên chính cụm này ngày **2026-09-25** (chi tiết, số liệu và cách chạy lại:
[capacity-load-test.md](capacity-load-test.md)). Kết luận ngắn:

| Mức tải (1 message/giây/thiết bị) | Kết quả đo | Khuyến nghị |
|---|---|---|
| ≤ 500 thiết bị (~500 msg/s) | Không mất gói, p95 thấp | **Mức khai thác an toàn hiện tại** |
| 1.000 thiết bị (~1.000 msg/s) | Đã kiểm chứng 0 % mất gói (số dòng Cassandra khớp tuyệt đối) | Trần thực tế, hết dư địa CPU |
| 2.000 thiết bị (~2.000 msg/s) | 106 kết nối bị `CONNACK: Server unavailable`, rule-engine `Timeout to process` | **Không dùng — phải mở rộng trước** |

Nút cổ chai theo thứ tự: (1) `TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS` mặc định **2000 ms**
quá ngắn khi mỗi message phải ghi Cassandra cách 110 ms; (2) Kafka 1 broker `replication.factor=1`,
queue rule-engine ít partition; (3) tổng CPU của cụm (2 worker × 4 vCPU, CPU request đã ~66 %);
(4) database managed đặt ở Mỹ làm tăng p95 và làm bão kết nối chậm.

Các bước mở rộng đã soạn sẵn trong [capacity-load-test.md](capacity-load-test.md) §6 (Phase 0 chỉ đổi cấu hình,
Phase 1 thêm worker + Kafka 3 broker RF=3, Phase 2 đưa database về cùng region, Phase 3 HA/failover drill).

## 11. Checklist trước khi go-live

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
- [ ] Đã tăng `TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS` (mặc định 2000 ms là quá thấp cho
      database ở xa) và đã bật cảnh báo khi log xuất hiện `Timeout to process`.
- [ ] Đã ghi nhận mức tải an toàn của cụm hiện tại và lịch đo tải định kỳ
      ([capacity-load-test.md](capacity-load-test.md)).
