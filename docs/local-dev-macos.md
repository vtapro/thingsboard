# Dev local trên macOS — runbook từng bước

File này là hướng dẫn **duy nhất** để dựng và chạy ThingsBoard ở chế độ dev local trên máy macOS này.
Mọi lệnh chạy ở **thư mục gốc repo** trừ khi có ghi chú `cd`.

Chế độ dev dùng cấu hình chuẩn CE, chạy monolith:

| Thành phần | Lựa chọn dev | Ghi chú |
|---|---|---|
| Kiến trúc | `service.type=monolith` | 1 tiến trình Java; UI chạy riêng bằng Angular dev server |
| Entities | PostgreSQL | |
| Telemetry | PostgreSQL (`database.ts.type=sql`) | |
| Queue | `in-memory` | Không cần Kafka/ZooKeeper |
| Cache | `caffeine` | Không cần Redis |
| MQTT | cổng 1883 do monolith tự mở | Không cần Mosquitto |
| RBAC | `security.rbac.enabled=true` | |

> **Vì sao telemetry dùng PostgreSQL:** bản CE của ThingsBoard không hiện thực
> `findAllKeysByEntityIds(Async)` cho Cassandra (stub trả rỗng), nên widget dashboard không liệt kê được
> key telemetry. Chỉ bật Kafka/Cassandra khi thật sự cần đo hiệu năng kiểu production.

Toàn bộ biến cấu hình local nằm trong **`scripts/tb-local.env`** — sửa file đó thay vì sửa
`thingsboard.yml`, để giữ nguyên config gốc của upstream.

## 0. Phiên bản đã kiểm chứng

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| JDK | Temurin 25, tại `~/jdk25/Contents/Home` | `pom.xml` yêu cầu `release 25`; Lombok không chạy trên JDK 27 |
| Maven | 3.9.16, tại `/opt/homebrew/bin/mvn` | mặc định dùng JDK 27 → phải export `JAVA_HOME` trước khi build |
| Node.js + Yarn | Node 24.21.0, Yarn 1.22.22 (qua `corepack`) | dùng cho `ui-ngx` |
| PostgreSQL | 18 (Homebrew) | superuser là user macOS `geiq`, máy này **không có** role `postgres` |

## 1. Lần đầu: bật PostgreSQL và tạo database

```bash
# Bật service (chỉ cần một lần; các lần sau service tự chạy cùng máy)
brew services start postgresql@18

# psql nằm trong keg của postgresql@18, không có sẵn trong PATH
export PATH="/opt/homebrew/opt/postgresql@18/bin:$PATH"

# Tạo database (bỏ qua nếu đã tồn tại)
psql -d postgres -c "CREATE DATABASE thingsboard OWNER geiq;"
```

Kiểm tra database đã sẵn sàng:

```bash
psql -d thingsboard -t -A -c "select count(*) from information_schema.tables where table_schema='public';"
```

## 2. Mỗi cửa sổ terminal: chọn JDK 25

Maven và `scripts/start-tb.sh` đọc `JAVA_HOME` từ shell. Máy này mặc định có thể trỏ JDK 27, nên **luôn**
export lại ở đầu mỗi terminal mới:

```bash
export JAVA_HOME="$HOME/jdk25/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

java -version    # phải in ra 25.x
mvn -v           # dòng "Java version" phải là 25
```

Nếu `mvn -v` vẫn hiện JDK 27 thì dừng lại, export lại cho đúng — build sẽ lỗi Lombok hoặc `release 25`.

## 3. Build backend

### Lần đầu, hoặc sau khi đổi `pom.xml` / dependency

```bash
mvn -B -T 1C install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true -Dlicense.skip=true
```

### Hằng ngày, khi chỉ sửa Java trong `dao` và `application` (đã kiểm chứng, ~50 giây)

```bash
mvn -B -pl dao,application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true
```

### Sinh classpath cho backend (chỉ cần lại khi đổi dependency)

```bash
mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
```

> **Không chạy `mvn clean` ở thư mục gốc.** Module `ui-ngx` bị clean bằng cách xoá `ui-ngx/node_modules`
> (~920 MB), và `clean` cũng xoá `*/target/proto` khiến lần build sau lỗi `tbmsg.proto: File not found`.
> Nếu lỡ chạy, khôi phục UI bằng:
>
> ```bash
> cd ui-ngx && corepack yarn install --frozen-lockfile
> ```
>
> Cần build lại từ đầu thì xoá thủ công `dao/target/classes` hoặc `application/target/classes`, đừng dùng `clean`.

> **Không chạy `mvn -pl application` một mình** — sẽ lỗi thiếu `org.thingsboard:dao:jar:tests`.
> Luôn dùng `-pl dao,application` như trên (sau khi đã build full một lần). Nếu cần thêm `-am` mà gặp
> `tbmsg.proto: File not found`, xem cách xử lý ở mục 9.

## 4. Cài schema (chỉ lần đầu, và mỗi khi có migration mới)

```bash
./scripts/install-tb-schema.sh              # có dữ liệu demo + 6 user mặc định
./scripts/install-tb-schema.sh --no-demo    # chỉ schema, không demo
```

Kiểm tra nhanh:

```bash
psql -d thingsboard -t -A -c "select count(*) from tb_user;"   # kỳ vọng 6 với --demo
```

Script gom `application/src/main/data/{json,certs,lwm2m-registry,resources}` và
`dao/src/main/resources/sql` vào `application/target/tb-data`, rồi chạy `ThingsboardInstallApplication`.
Cần gom vì installer đọc **cả** `sql/` **và** `json/` từ cùng một `install.data_dir`, mà hai thư mục này
nằm ở hai module khác nhau.

## 5. Chạy backend

```bash
./scripts/start-tb.sh                 # cổng mặc định 8080
HTTP_BIND_PORT=8081 ./scripts/start-tb.sh
./scripts/start-tb.sh --force         # restart sau khi build lại code Java
./scripts/stop-tb.sh                  # dừng
```

- Log: `application/target/tb-server.out` và `application/target/tb-server.err`
- PID: `application/target/tb-server.pid`
- Cổng bị chương trình khác chiếm: script báo lỗi và **không** tự kill tiến trình lạ — đổi cổng hoặc dùng `--force`.

Kiểm tra backend đã lên:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/noauth/whiteLabeling   # 200

curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"tenant@thingsboard.org","password":"tenant"}'                          # {"token":"..."}
```

> `scripts/start-tb.sh` lấy `JAVA_HOME` từ shell (qua `scripts/tb-local.env`). Nếu shell đang trỏ JDK 27,
> chạy `env JAVA_HOME="$HOME/jdk25/Contents/Home" ./scripts/start-tb.sh` để chắc chắn dùng JDK 25.

## 6. Chạy UI dev (hot reload)

Mở **terminal thứ hai** (vẫn export `JAVA_HOME` và `PATH` như mục 2 nếu cần), rồi:

```bash
cd ui-ngx
corepack yarn install --frozen-lockfile     # chỉ lần đầu
TB_BACKEND_PORT=8080 corepack yarn ng serve --configuration development --port 4200
```

Mở **http://localhost:4200**. `ui-ngx/proxy.conf.js` chuyển `/api`, `/static/**`, `/oauth2` sang backend;
`TB_BACKEND_PORT` phải khớp cổng backend.

Kiểm tra UI và proxy:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4200/                          # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4200/api/noauth/whiteLabeling   # 200
```

## 7. Tài khoản mặc định

Installer tạo sẵn khi chạy với demo data:

| Vai trò | Đăng nhập | Mật khẩu |
|---|---|---|
| System Admin | `sysadmin@thingsboard.org` | `sysadmin` |
| Tenant Admin | `tenant@thingsboard.org` | `tenant` |
| Customer User | `customer@thingsboard.org` | `customer` |

## 8. Vòng lặp hằng ngày

1. **Sửa Java trong `dao`/`application`** → build nhanh rồi restart backend:

   ```bash
   mvn -B -pl dao,application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true
   ./scripts/start-tb.sh --force
   ```

2. **Sửa `ui-ngx`** → không cần build, `ng serve` tự hot reload (F5 lại trang nếu cần).

3. **Có migration mới trong `dao/src/main/resources/sql`** → chạy lại installer:

   ```bash
   ./scripts/install-tb-schema.sh --no-demo
   ```

4. **Kiểm tra tổng thể**: `curl http://localhost:8080/api/noauth/whiteLabeling` trả 200 và mở
   http://localhost:4200 đăng nhập được.

## 9. Sự cố thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| `invalid target release: 25` hoặc Lombok lỗi trên JDK 27 | sai `JAVA_HOME`; export lại `~/jdk25/Contents/Home` |
| `FATAL: role "postgres" does not exist` | máy này không có role `postgres`; dùng `geiq` (đã set trong `scripts/tb-local.env`) |
| `database "thingsboard" does not exist` | chạy `psql -d postgres -c "CREATE DATABASE thingsboard OWNER geiq;"` |
| `NoSuchFileException: .../data/sql/schema-entities.sql` | chưa gom data dir; chạy `./scripts/install-tb-schema.sh` |
| `Could not find artifact org.thingsboard:dao:jar:tests` | không chạy `mvn -pl application` một mình; dùng `-pl dao,application` hoặc thêm `-am` |
| `tbmsg.proto: File not found` | do đã `mvn clean`; đã kiểm chứng cách sửa: `mkdir -p common/proto/target/proto && cp common/message/src/main/proto/tbmsg.proto common/proto/target/proto/` rồi build lại (không clean) |
| Cổng 8080 bận | `HTTP_BIND_PORT=8081 ./scripts/start-tb.sh`, rồi chạy UI với `TB_BACKEND_PORT=8081` |
| UI gọi API bị 404 hoặc `[vite] http proxy error` | backend chưa chạy, hoặc `TB_BACKEND_PORT` không khớp cổng backend |
| Widget dashboard không liệt kê key telemetry | telemetry đang ở Cassandra; dùng `DATABASE_TS_TYPE=sql` |
| `AccessDeniedException: ~/.rocksdb` | `scripts/tb-local.env` đã trỏ RocksDB vào `application/target/rocksdb` |
| Redis báo `NOAUTH Authentication required` | chế độ dev này không cần Redis; `CACHE_TYPE=caffeine` và `MANAGEMENT_HEALTH_REDIS_ENABLED=false` đã set trong `scripts/tb-local.env` |
| `git` thoát mã 69 "You have not agreed to the Xcode license agreements" | `export DEVELOPER_DIR=/Library/Developer/CommandLineTools` trước khi chạy `git` |

## 10. Lệnh đã kiểm chứng (2026-09-29)

Chuỗi dưới đây đã chạy đúng trên máy này: macOS, JDK 25 Temurin, Maven 3.9.16, Node 24 + Yarn 1.22,
PostgreSQL 18.

```bash
# Build backend (chỉ 2 module đã sửa)
env JAVA_HOME="$HOME/jdk25/Contents/Home" PATH="$HOME/jdk25/Contents/Home/bin:$PATH" \
  mvn -B -pl dao,application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true
# => BUILD SUCCESS

# Database/schema đã có sẵn
psql -d thingsboard -t -A -c "select count(*) from information_schema.tables where table_schema='public';"  # 76
psql -d thingsboard -t -A -c "select count(*) from tb_user;"                                                  # 6

# Backend
./scripts/start-tb.sh

# UI (terminal khác)
cd ui-ngx && TB_BACKEND_PORT=8080 corepack yarn ng serve --configuration development --port 4200

# Kiểm chứng
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/noauth/whiteLabeling   # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4200/                           # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4200/api/noauth/whiteLabeling   # 200
```

Backend và UI là hai tiến trình foreground/riêng biệt: mở hai terminal, hoặc chạy backend trong `tmux`
nếu muốn giữ nó sống sau khi đóng terminal.
