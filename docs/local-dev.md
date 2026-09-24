# Phát triển local trên Windows (không dùng Docker)

Dự án chạy trực tiếp trên Windows: PostgreSQL + ThingsBoard (Java) + Angular dev server.
Không cần Docker cho vòng lặp phát triển hằng ngày.

## 1. Thành phần đã cài trên máy

| Thành phần | Phiên bản | Đường dẫn | Ghi chú |
|---|---|---|---|
| JDK (build/run ThingsBoard) | Temurin **25** | `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot` | `pom.xml` yêu cầu `release 25` |
| JDK (cho Cassandra) | Temurin **17** | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` | Cassandra 5.0 cần Java 17, không chạy được với JDK 21/25 |
| Maven | 3.9.11 | `C:\Users\vthea\tools\apache-maven-3.9.11` | đã thêm vào PATH user, `JAVA_HOME` trỏ JDK 25 |
| Node.js + Yarn | Node 24, Yarn 1.22 (qua `corepack`) | — | dùng cho `ui-ngx` |
| PostgreSQL | 16 (service `postgresql-x64-16`) | `C:\Program Files\PostgreSQL\16` | DB `thingsboard`, user `postgres` / mật khẩu `postgres` |
| Kafka | 4.0.0 (KRaft, không cần ZooKeeper) | `C:\Users\vthea\tools\kafka` | broker `localhost:9092`, log ở `C:\Users\vthea\tb-data\kafka-logs` |
| Cassandra | 5.0.9 (chạy trong **WSL Ubuntu**) | `~/tools/cassandra` (trong WSL) | `localhost:9042`, keyspace `thingsboard`; windows không có bản Cassandra 5.0 native |
| Thư mục dữ liệu runtime | — | `C:\Users\vthea\tb-data` | chứa `sql/`, `cassandra/`, `json/` cho installer |

### Cài lại từ đầu (khi cần)

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK -e
winget install --id EclipseAdoptium.Temurin.17.JDK -e
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
Copy-Item "dao\src\main\resources\cassandra" "$dataDir\cassandra" -Recurse -Force
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

## 7. Kafka (queue) — KRaft, không cần ZooKeeper

ThingsBoard 4.4 dùng Kafka 4.0; từ Kafka 3.3 có thể chạy **KRaft** nên **không cần ZooKeeper**.

```powershell
# lần đầu: format storage KRaft (chỉ chạy 1 lần, xoá dữ liệu Kafka cũ)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$kafka = "C:\Users\vthea\tools\kafka"
$bytes = [Guid]::NewGuid().ToByteArray()
$uuid  = [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
& "$kafka\bin\windows\kafka-storage.bat" format -t $uuid -c "$kafka\config\server.properties" --standalone

# chạy broker
..\scripts\start-kafka.ps1          # tương đương lệnh bên dưới
```

`config/server.properties` đã được sửa `log.dirs=C:/Users/vthea/tb-data/kafka-logs`.
Lưu ý bản `.bat` của Kafka gọi `wmic` (đã bị Windows mới xoá) — script `scripts/start-kafka.ps1` đặt sẵn
`KAFKA_HEAP_OPTS` để bỏ qua bước đó.

## 8. Cassandra 5.0 (timeseries) — chạy trong WSL

Apache không phát hành bản Cassandra 5.0 chạy native trên Windows (chỉ có tarball Linux), nên dev dùng **WSL Ubuntu**
(không phải Docker). ThingsBoard trên Windows kết nối qua `localhost:9042` nhờ WSL2 forward localhost.

```powershell
..\scripts\start-cassandra.ps1        # start + chờ cổng 9042
..\scripts\stop-tb.ps1                # dừng TB, Kafka, UI và Cassandra
```

Thiết lập bên trong WSL (đã làm, ghi lại để tham khảo):

```bash
# JDK 17 + Cassandra 5.0.9 trong $HOME (không cần quyền root)
mkdir -p ~/tools && cd ~/tools
curl -fL -o jdk17.tar.gz "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"
mkdir -p jdk17 && tar xzf jdk17.tar.gz -C jdk17 --strip-components=1
cp /mnt/c/Users/vthea/tools/cassandra.tgz . && mkdir -p cassandra && tar xzf cassandra.tgz -C cassandra --strip-components=1
# conf/cassandra.yaml: cluster_name 'Thingsboard Cluster', data/commitlog/saved_caches/hints trỏ về ~/cassandra-data
```

Keyspace `thingsboard` và các bảng `ts_kv_cf`, `ts_kv_latest_cf`, `ts_kv_partitions_cf` do installer tạo:

```powershell
$env:SKIP_SCHEMA_VERSION_CHECK = "true"     # bắt buộc khi DB đã cài schema (upgrade)
java "@$env:TEMP\tb-upgrade.args"
```

## 9. Chạy toàn bộ stack dev bằng script

```powershell
.\scripts\start-kafka.ps1         # Kafka 9092
.\scripts\start-cassandra.ps1     # Cassandra 9042 (WSL)
.\scripts\start-tb.ps1            # ThingsBoard 8080 (Postgres + Kafka + Cassandra + RBAC)

cd ui-ngx; corepack yarn ng serve --configuration development --port 4200   # UI

.\scripts\stop-tb.ps1             # dừng tất cả
```

Kiểm tra nhanh sau khi chạy:

```powershell
Invoke-WebRequest http://localhost:8080/api/noauth/whiteLabeling -UseBasicParsing | Select-Object StatusCode
& "C:\Users\vthea\tools\kafka\bin\windows\kafka-topics.bat" --bootstrap-server localhost:9092 --list | Select-String "^tb_"
wsl -d Ubuntu -e bash ~/tools/cassandra/bin/nodetool status     # UN = node bình thường
```

## 10. Sự cố thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| `invalid target release: 25` | sai `JAVA_HOME` — phải trỏ JDK 25 |
| `The filename or extension is too long` | dùng argfile như mục 4.2 |
| `relation "queue" does not exist` khi cài schema | thiếu `-Dqueue.type=in-memory` hoặc `install.data_dir` sai |
| `C:\...\data\sql\schema-entities.sql` không tồn tại | chưa tạo thư mục dữ liệu ở mục 4.1 |
| Cổng 8080 bận | tắt process đang giữ cổng, hoặc thêm `-Dserver.port=8081` |
| UI gọi API bị 404 | backend chưa chạy, hoặc chạy UI không qua proxy của `ng serve` |
| Không đăng nhập được user mới | mật khẩu tối thiểu 6 ký tự; user phải được **activate** bằng token trong DB |
| Kafka: `'wmic' is not recognized` | đặt `KAFKA_HEAP_OPTS` trước khi start (script đã làm) |
| Kafka: `controller.quorum.voters is not set` khi format | thêm `--standalone` (KRaft single node) |
| Cassandra không lên trong WSL | chạy `scripts/wsl/cassandra.sh status`, xem `~/tools/cassandra/logs/system.log`; Cassandra cần ~1 phút để bootstrap |
| cqlsh báo `unsupported version of Python` | cqlsh cần Python 3.6–3.13, WSL đang có 3.14 → dùng `nodetool` để kiểm tra |
| Installer: `database already upgraded` | thêm biến môi trường `SKIP_SCHEMA_VERSION_CHECK=true` |
