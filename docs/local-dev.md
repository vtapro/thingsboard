# Phát triển local trên Windows (không dùng Docker)

Dự án chạy trực tiếp trên Windows: PostgreSQL + ThingsBoard (Java) + Angular dev server.
Không cần Docker cho vòng lặp phát triển hằng ngày.

## 0. Bắt đầu nhanh (copy-paste)

```powershell
# ---------- một lần cho mỗi cửa sổ PowerShell ----------
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\vthea\tools\apache-maven-3.9.11\bin;$env:PATH"
cd C:\Users\vthea\Documents\GitHub\thingsboard

# ---------- BACKEND ----------
# 1) lần đầu (hoặc sau khi đổi dependency): build toàn bộ backend, KHÔNG dùng "clean"
mvn -o -B install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# 2) lần đầu: sinh classpath để chạy backend bằng java
mvn -o -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target\classpath.txt `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# 3) mỗi lần sửa code Java: chỉ build lại module application (~30 giây)
mvn -o -B -pl application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# 4) chạy backend -> http://localhost:8080 (PostgreSQL local + queue in-memory + RBAC bật)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-tb.ps1
Invoke-WebRequest http://localhost:8080/api/noauth/whiteLabeling -UseBasicParsing | Select-Object StatusCode

# ---------- FRONTEND ----------
# 5) dev server hot reload -> http://localhost:4200 (proxy /api -> 8080)
cd ui-ngx
node --max_old_space_size=8048 ./node_modules/@angular/cli/bin/ng.js serve --configuration development --host 0.0.0.0

# 6) build production giống CI (nên chạy trước khi push)
node --max_old_space_size=4096 ./node_modules/@angular/cli/bin/ng.js build --configuration production

# ---------- DỪNG ----------
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-tb.ps1   # dừng cả backend + ng serve
```

Tài khoản local: `sysadmin@thingsboard.org / sysadmin`, `tenant@thingsboard.org / tenant`,
`user@thingsboard.org / user123`.

Test nhanh Automation (tạo rule hẹn giờ + thiết bị MQTT giả):

```powershell
python -m pip install paho-mqtt requests
python .\scripts\test-automation-local.py     # kỳ vọng: SUMMARY 15/15 PASS
```

Chỉ sau khi local pass mới đẩy lên GHCR + k3s (xem §10).

## 1. Thành phần đã cài trên máy

| Thành phần | Phiên bản | Đường dẫn | Ghi chú |
|---|---|---|---|
| JDK (build/run ThingsBoard) | Temurin **25** | `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot` | `pom.xml` yêu cầu `release 25` |
| Maven | 3.9.11 | `C:\Users\vthea\tools\apache-maven-3.9.11` | đã thêm vào PATH user, `JAVA_HOME` trỏ JDK 25 |
| Node.js + Yarn | Node 24, Yarn 1.22 (qua `corepack`) | — | dùng cho `ui-ngx` |
| PostgreSQL | 16 (service `postgresql-x64-16`) | `C:\Program Files\PostgreSQL\16` | DB `thingsboard`, user `postgres` / mật khẩu `postgres` |
| Thư mục dữ liệu runtime | — | `C:\Users\vthea\tb-data` | chỉ cần `sql/` + `json/` cho installer (không cần `cassandra/`) |

> **Không dùng Kafka / ZooKeeper / Cassandra ở local.** Stack dev dùng đúng cấu hình CE: PostgreSQL cho cả
> entities và timeseries, queue `in-memory`. Lý do bỏ Cassandra: bản CE không hiện thực
> `findAllKeysByEntityIds(Async)` trong `CassandraBaseTimeseriesLatestDao` (stub trả rỗng), nên widget trên
> dashboard **không liệt kê được key telemetry**; muốn dùng được thì phải sửa code ThingsBoard — không đáng
> cho môi trường dev. Chỉ khi nào thật sự cần đo hiệu năng production-like mới dựng lại Kafka/Cassandra ở
> môi trường riêng, kèm bản vá tương ứng.

### Cài lại từ đầu (khi cần)

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK -e
winget install --id PostgreSQL.PostgreSQL.16 -e      # mật khẩu superuser: postgres
corepack enable
```

Maven không có trên winget, tải zip:

```powershell
Invoke-WebRequest "https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip" -OutFile $env:TEMP\maven.zip
Expand-Archive $env:TEMP\maven.zip C:\Users\vthea\tools -Force
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot", "User")
```

### Tạo database

```powershell
& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -h 127.0.0.1 -c "CREATE DATABASE thingsboard;"
```

## 2. Tài khoản mặc định (đã tạo trong DB local)

| Vai trò | Đăng nhập | Mật khẩu | Ghi chú |
|---|---|---|---|
| **System Admin** | `sysadmin@thingsboard.org` | `sysadmin` | do installer tạo |
| **Tenant Admin** | `tenant@thingsboard.org` | `tenant` | tenant *Tổ chức của tôi* |
| **User (customer)** | `user@thingsboard.org` | `user123` | thuộc customer *Khách hàng 1* (mật khẩu tối thiểu 6 ký tự) |

Tạo lại/thêm tài khoản mới qua API (thay `<email>`, `<password>`, `<authority>`):

```powershell
# đăng nhập sysadmin -> tạo tenant -> tạo user TENANT_ADMIN
$sa = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/auth/login" `
      -ContentType "application/json" -Body '{"username":"sysadmin@thingsboard.org","password":"sysadmin"}'
$hdr = @{ "X-Authorization" = "Bearer $($sa.token)" }
$t = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/tenant" -Headers $hdr `
     -ContentType "application/json" -Body '{"title":"Tổ chức của tôi"}'
$u = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/user?sendActivationMail=false" -Headers $hdr `
     -ContentType "application/json" `
     -Body (@{ email="tenant2@thingsboard.org"; authority="TENANT_ADMIN"; tenantId=@{ entityType="TENANT"; id=$t.id.id } } | ConvertTo-Json -Depth 5)

# kích hoạt bằng token lấy từ DB
$env:PGPASSWORD="postgres"
$tok = (& "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -h 127.0.0.1 -d thingsboard -t -A `
        -c "select activate_token from user_credentials where user_id='$($u.id.id)';").Trim()
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/noauth/activate" -ContentType "application/json" `
  -Body (@{ activateToken=$tok; password="tenant2pass" } | ConvertTo-Json)
```

## 3. Build backend (không build UI, rất nhanh)

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\vthea\tools\apache-maven-3.9.11\bin;$env:PATH"

# build mọi module, không đóng gói, không build UI
mvn -B -T 1C clean install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true

# classpath để chạy trực tiếp bằng java (chỉ cần chạy lại khi đổi dependency)
mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target\classpath.txt
```

> Lưu ý: `-Dskip.ui.build=true` cũng tắt việc tạo boot jar (theo thiết kế của ThingsBoard: gói không có UI là
> gói không hoàn chỉnh). Muốn có `*-boot.jar` phải build UI (`yarn build`) rồi `mvn -pl application package`.
> Khi dev thì chạy từ `target/classes` (như IDE) là đủ.

## 4. Cài schema (chạy 1 lần, và mỗi khi có migration mới)

### 4.1 Chuẩn bị thư mục dữ liệu (một lần)

```powershell
$dataDir = "C:\Users\vthea\tb-data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
Copy-Item "application\src\main\data\*" $dataDir -Recurse -Force
Copy-Item "dao\src\main\resources\sql" "$dataDir\sql" -Recurse -Force
```

### 4.2 Chạy installer

Windows giới hạn độ dài command line (classpath ~57KB), nên dùng **argfile** của Java (JDK 9+).
Lưu ý: trong argfile phải dùng dấu `/` vì `\` là ký tự escape.

```powershell
$cp = "$($PWD.Path -replace '\\','/')/application/target/classes;" + ((Get-Content application/target/classpath.txt -Raw).Trim() -replace '\\','/')
$args = @(
  "-Dinstall.data_dir=C:/Users/vthea/tb-data",
  "-Dinstall.load_demo=false",
  "-Dinstall.upgrade=false",
  "-Dspring.jpa.hibernate.ddl-auto=none",
  "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/thingsboard",
  "-Dspring.datasource.username=postgres",
  "-Dspring.datasource.password=postgres",
  "-Dqueue.type=in-memory",
  "-Dservice.type=monolith",
  "-cp", $cp,
  "org.thingsboard.server.ThingsboardInstallApplication"
)
[System.IO.File]::WriteAllLines("$env:TEMP\tb-install.args", $args)
& "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe" "@$env:TEMP\tb-install.args"
```

## 5. Chạy backend

```powershell
$cp = "$($PWD.Path -replace '\\','/')/application/target/classes;" + ((Get-Content application/target/classpath.txt -Raw).Trim() -replace '\\','/')
$args = @(
  "-Xmx2G",
  "-Dinstall.data_dir=C:/Users/vthea/tb-data",
  "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/thingsboard",
  "-Dspring.datasource.username=postgres",
  "-Dspring.datasource.password=postgres",
  "-Dqueue.type=in-memory",
  "-Ddatabase.ts.type=sql",
  "-Ddatabase.ts_latest.type=sql",
  "-Dqueue.edqs.local.rocksdb_path=$($PWD.Path -replace '\\','/')/application/target/rocksdb/edqs",
  "-Dqueue.calculated_fields.rocks_db_path=$($PWD.Path -replace '\\','/')/application/target/rocksdb/cf_states",
  "-Dservice.type=monolith",
  "-Dsecurity.rbac.enabled=true",
  "-cp", $cp,
  "org.thingsboard.server.ThingsboardServerApplication"
)
[System.IO.File]::WriteAllLines("$env:TEMP\tb-server.args", $args)
Start-Process -FilePath "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe" `
  -ArgumentList "@$env:TEMP\tb-server.args" -RedirectStandardOutput "$env:TEMP\tb-server.out" -NoNewWindow
```

Backend: `http://localhost:8080`, MQTT `1883`. Kiểm tra nhanh:

```powershell
Invoke-WebRequest "http://localhost:8080/api/noauth/whiteLabeling" -UseBasicParsing | Select-Object StatusCode
```

## 6. Chạy UI (hot reload)

```powershell
cd ui-ngx
corepack yarn install --frozen-lockfile     # chỉ lần đầu
corepack yarn ng serve --configuration development --host 0.0.0.0 --port 4200
```

Mở **http://localhost:4200**. `ui-ngx/proxy.conf.js` tự chuyển `/api`, `/static/**`, `/oauth2` sang backend
`http://localhost:8080`, nên không cần build UI mỗi lần sửa code.

## 7. Chạy stack dev bằng script

```powershell
# Windows đang chặn chạy script (.ps1) nên luôn gọi kèm ExecutionPolicy Bypass
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-tb.ps1   # ThingsBoard 8080 (Postgres + in-memory + SQL + RBAC)

cd ui-ngx; corepack yarn ng serve --configuration development --port 4200   # UI

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-tb.ps1    # dừng backend + UI
```

Kiểm tra nhanh sau khi chạy:

```powershell
Invoke-WebRequest http://localhost:8080/api/noauth/whiteLabeling -UseBasicParsing | Select-Object StatusCode
```

### Kiểm chứng nhanh toàn tuyến (đã chạy đúng trên máy này)

```powershell
# 1. đăng nhập tenant
$t = (Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/auth/login" -ContentType "application/json" `
      -Body '{"username":"tenant@thingsboard.org","password":"tenant"}').token
$hdr = @{ "X-Authorization" = "Bearer $t" }

# 2. ghi telemetry qua REST
$dev = (Invoke-RestMethod "http://localhost:8080/api/tenant/devices?pageSize=1&page=0" -Headers $hdr).data[0].id.id
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/plugins/telemetry/DEVICE/$dev/timeseries/ANY_SCOPE" `
  -Headers $hdr -ContentType "application/json" -Body '{"temperature":42.5}'

# 3. key telemetry phải hiện ở cả 2 API (widget data-key dùng API thứ hai)
Invoke-RestMethod "http://localhost:8080/api/plugins/telemetry/DEVICE/$dev/keys/timeseries" -Headers $hdr
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/entitiesQuery/find/keys?attributes=true&timeseries=true" `
  -Headers $hdr -ContentType "application/json" `
  -Body '{"entityFilter":{"type":"deviceType","deviceType":"default"},"pageLink":{"pageSize":100,"page":0,"sortOrder":{"key":{"key":"createdTime","type":"ENTITY_FIELD"},"direction":"DESC"}},"keyFilters":[],"latestValues":[]}'
```

MQTT: cổng `1883` mở sẵn khi chạy monolith; username = **device access token**, publish JSON vào
`v1/devices/me/telemetry` (MQTTX cấu hình `localhost:1883`).

## 8. Sự cố thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| `invalid target release: 25` | sai `JAVA_HOME` — phải trỏ JDK 25 |
| `The filename or extension is too long` | dùng argfile như mục 4.2 |
| `relation "queue" does not exist` khi cài schema | thiếu `-Dqueue.type=in-memory` hoặc `install.data_dir` sai |
| `C:\...\data\sql\schema-entities.sql` không tồn tại | chưa tạo thư mục dữ liệu ở mục 4.1 |
| Cổng 8080 bận | tắt process đang giữ cổng, hoặc thêm `-Dserver.port=8081` |
| UI gọi API bị 404 | backend chưa chạy, hoặc chạy UI không qua proxy của `ng serve` |
| Không đăng nhập được user mới | mật khẩu tối thiểu 6 ký tự; user phải được **activate** bằng token trong DB |
| Widget dashboard không liệt kê key telemetry | xảy ra khi timeseries lưu ở **Cassandra** (bản CE không hiện thực `findAllKeysByEntityIds`) → dùng `-Ddatabase.ts.type=sql` (mặc định trong `scripts/start-tb.ps1`) là hết |
| `java.nio.file.AccessDeniedException: C:\.rocksdb` khi khởi động | trên máy này `user.home` = `C:\` nên đường dẫn mặc định `${user.home}/.rocksdb` bị chặn → phải truyền `-Dqueue.edqs.local.rocksdb_path` và `-Dqueue.calculated_fields.rocks_db_path` (script `start-tb.ps1` đã làm sẵn, trỏ vào `application/target/rocksdb`) |
| `cannot be loaded because running scripts is disabled` | chạy script kèm `-ExecutionPolicy Bypass` (máy đang bị policy chặn) |
| Installer: `database already upgraded` | thêm biến môi trường `SKIP_SCHEMA_VERSION_CHECK=true` khi chạy ở chế độ upgrade |

## 9. Lệnh chạy dev đã kiểm chứng (2026-09-25)

Chuỗi lệnh dưới đây đã chạy đúng trên máy này (Windows, JDK 25, Maven 3.9.11, Node 24 + yarn 1.22 qua corepack).

### 9.1. Build backend

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\vthea\tools\apache-maven-3.9.11\bin;$env:PATH"

# build toàn bộ module backend (KHÔNG dùng "clean" — xem cảnh báo bên dưới)
mvn -o -B install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# chỉ sửa code Java trong module application (nhanh, ~30 giây)
mvn -o -B -pl application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository
```

> ⚠️ **Không chạy `mvn clean` ở thư mục gốc**: module `ui-ngx` được Maven "clean" bằng cách xoá
> `ui-ngx/node_modules` — thư mục này đang được các tiến trình khác giữ (VS Code Java LS, esbuild/rollup
> `.node`), nên `clean` sẽ xoá dở dang, làm hỏng môi trường UI. Nếu lỡ bị, khôi phục bằng:
>
> ```powershell
> cd ui-ngx; corepack yarn install      # ~60 giây
> ```
>
> `mvn clean` cũng xoá `*/target/proto` (chứa các file `.proto` lấy từ module khác) → lần build sau
> protoc báo `tbmsg.proto: File not found`. Cách xử lý: build **tuần tự** (bỏ `-T 1C`) một lần, hoặc copy
> tạm: `Copy-Item common/message/src/main/proto/*.proto common/proto/target/proto/`.

### 9.2. Chạy backend local

```powershell
# sinh classpath (chỉ cần lại khi đổi dependency)
mvn -o -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target\classpath.txt

# cách nhanh nhất: dùng script có sẵn (Postgres + in-memory + SQL timeseries + RBAC)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-tb.ps1

# kiểm tra
Invoke-WebRequest http://localhost:8080/api/noauth/whiteLabeling -UseBasicParsing | Select-Object StatusCode   # 200
```

### 9.3. Build / chạy UI

```powershell
cd ui-ngx

# build production (đã pass 2026-09-25, ~77 giây; output: ui-ngx/target/generated-resources/public)
node --max_old_space_size=4096 ./node_modules/@angular/cli/bin/ng.js build --configuration production

# dev server hot reload -> http://localhost:4200 (proxy /api sang 8080)
node --max_old_space_size=8048 ./node_modules/@angular/cli/bin/ng.js serve --configuration development --host 0.0.0.0
```

### 9.4. Test tính năng Automation ngay trên máy

Script `scripts/test-automation-local.py` chạy trọn luồng: tạo thiết bị + token, kết nối MQTT đóng vai
máy bơm, tạo rule hẹn giờ, bấm "run now", chờ scheduler tự chạy, rồi sửa/tắt/xoá rule.

```powershell
python -m pip install paho-mqtt requests
python .\scripts\test-automation-local.py
```

Kết quả lần chạy đầu (2026-09-25): **15/15 PASS**, gồm `RPC delivered to device (run now)`,
`scheduler executed the rule`, `RPC delivered by scheduler`.

## 10. Chỉ sau khi local pass: đẩy GHCR + deploy k3s

```powershell
# 1) commit + push -> GitHub Actions build 8 image ghcr.io/vtapro/tb-*:v4.4.0.0 (~8-15 phút)
git add -A
git commit -m "feat: ..."
git push origin RBAC-full-groups-tabs
gh -R vtapro/thingsboard run list --limit 1
gh -R vtapro/thingsboard run watch <run-id> --exit-status

# 2) deploy đúng service đã thay đổi (xem bảng bên dưới)
kubectl -n thingsboard rollout restart deployment/tb-core deployment/tb-web-ui
kubectl -n thingsboard rollout status deployment/tb-core --timeout=600s
kubectl -n thingsboard rollout status deployment/tb-web-ui --timeout=600s

# 3) kiểm tra
kubectl -n thingsboard get pods --no-headers | Select-String -NotMatch "Running"   # kỳ vọng: rỗng
Invoke-WebRequest https://app.greeniq.vn/api/tenant/automation -UseBasicParsing    # 401 = API đã lên (chưa login)
```

| Thay đổi | Service cần rollout lại |
|---|---|
| Java trong `application`, `dao`, `common` (API, rule engine, RBAC…) | `tb-core` (thêm `tb-rule-engine` nếu sửa rule node) |
| `ui-ngx` (trang, menu, i18n, assets, logo) | `tb-web-ui` |
| `transport/*` (MQTT/HTTP/CoAP…) | transport tương ứng, ví dụ `tb-mqtt-transport` |
| chỉ `docs/**`, `deploy/**`, `scripts/**`, `**/*.md` | không cần — workflow có `paths-ignore`, push tài liệu không trigger build |
