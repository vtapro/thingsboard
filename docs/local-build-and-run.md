# Build & chạy ThingsBoard local bằng Docker

## 1. Yêu cầu

- Docker Desktop đang chạy (đã kiểm chứng với Docker 29.x, Compose v5.x).
- Khuyến nghị ≥ 4 CPU và ≥ 8 GB RAM cho Docker (máy dev hiện tại: 22 CPU / ~16 GB).
- ~10 GB đĩa trống cho build context, image, Maven cache và `node_modules`.
- **Không cần** cài JDK/Maven/Node trên máy: tất cả chạy trong container (JDK 25 + Maven 3.9.11
  + Node 22 do frontend-maven-plugin tự tải).

## 2. Cấu trúc file phục vụ build local

| File | Vai trò |
|---|---|
| `docker/tb-custom/Dockerfile` | Multi-stage: builder (JDK 25 + Maven) build jar & UI, runtime (JRE 25) chạy server |
| `docker/tb-custom/entrypoint.sh` | Chạy `ThingsboardInstallApplication` (tạo/ nâng schema) rồi start `ThingsboardServerApplication` |
| `docker/tb-custom/docker-compose.yml` | Postgres 16 + tb-node (queue `in-memory`) |
| `.dockerignore` | Loại `target/`, `node_modules/`, log khỏi build context |

## 3. Lệnh build và chạy

Chạy tất cả lệnh từ thư mục gốc repo:

```powershell
# Build image (lần đầu ~40-60 phút; các lần sau nhanh hơn nhờ cache ~/.m2)
docker compose -f docker/tb-custom/docker-compose.yml build tb-node

# Chạy (dùng --force-recreate sau mỗi lần build lại, nếu không container vẫn chạy image cũ)
docker compose -f docker/tb-custom/docker-compose.yml up -d --force-recreate

# Theo dõi log
docker compose -f docker/tb-custom/docker-compose.yml logs -f tb-node

# Restart nhanh (không build lại)
docker compose -f docker/tb-custom/docker-compose.yml restart tb-node

# Dừng, giữ dữ liệu
docker compose -f docker/tb-custom/docker-compose.yml down

# Xoá sạch DB để cài lại từ đầu (mất toàn bộ dữ liệu)
docker compose -f docker/tb-custom/docker-compose.yml down
docker volume rm tb-custom_tb-custom-postgres-data
docker compose -f docker/tb-custom/docker-compose.yml up -d
```

Khi build chạy nền, log được ghi ra `%TEMP%\tb-custom-build.log`:

```powershell
Get-Content "$env:TEMP\tb-custom-build.log" -Tail 30 -Wait
```

## 4. Cổng dịch vụ

| Dịch vụ | Port host | Ghi chú |
|---|---|---|
| Web UI / REST API | 8080 | http://localhost:8080 |
| MQTT | **11883** | 1883 mặc định đang bị stack GreenIQ (`greeniq_emqx`) chiếm |
| Core RPC | 7070 | |
| CoAP | 5683/udp | |
| Postgres | không mở ra host | 5432 đang bị `greeniq_postgres` chiếm; TB kết nối qua network nội bộ compose |

Đổi port trong `docker/tb-custom/docker-compose.yml` nếu trùng với dịch vụ khác.

## 5. Tài khoản mặc định

| Vai trò | Tài khoản | Mật khẩu |
|---|---|---|
| System Administrator | `sysadmin@thingsboard.org` | `sysadmin` |

`tenant@thingsboard.org` / `tenant` là tài khoản **demo**, không tồn tại trong bản cài sạch:
đăng nhập sysadmin → **Tenants** → tạo tenant → tạo Tenant Admin user.

## 6. Kiểm tra nhanh sau khi chạy

```powershell
# Endpoint branding công khai (dùng cho trang login)
curl.exe -s http://localhost:8080/api/noauth/whiteLabeling

# Đăng nhập lấy token
curl.exe -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" `
  --data-binary '{\"username\":\"sysadmin@thingsboard.org\",\"password\":\"sysadmin\"}'
```

Kết quả mong đợi khi chưa cấu hình branding:

```json
{"enabled":false,"appTitle":null,"logoImageUrl":null,"logoImageUrlDark":null,
 "faviconUrl":null,"primaryColor":null,"accentColor":null,
 "hideHelpLinks":false,"hideVendorPromotion":false}
```

## 7. Xử lý sự cố đã gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Build fail: `Some files do not have the expected license header` | File mới (ví dụ `Dockerfile`) không có header SPDX hoặc log lọt vào build context | Thêm header SPDX vào đầu file; thêm pattern vào `.dockerignore`; thêm `<exclude>` trong cấu hình `license-maven-plugin` của `pom.xml` nếu cần |
| Build fail ở `yarn install` | Builder image thiếu `git`/`patch` (yarn cần cho dependency git + `patch-package`) | Dockerfile đã cài `git patch python3 make g++` |
| `no main manifest attribute` / jar chỉ ~3.5 MB | Dùng `-Dpkg.skip=true` sẽ bỏ luôn `spring-boot:repackage` | Chỉ skip từng phần: `-Dpkg.skip.deb=true -Dpkg.skip.rpm=true -Dpkg.skip.zip=true` |
| Install fail: `Not valid working directory: /app` | Thiếu thư mục `data` và biến `INSTALL_DATA_DIR` | Dockerfile copy `application/src/main/data` → `/app/data`; compose set `INSTALL_DATA_DIR=/app/data` |
| Install fail: `NoSuchFileException: /app/data/sql/schema-entities.sql` | Schema nằm ở module `dao`, không nằm trong `application/src/main/data` | Dockerfile copy thêm `dao/src/main/resources/sql` và `.../cassandra` vào `/app/data` |
| API trả 400 `Cannot find cache named ...` | Dùng `@Cacheable` với cache chưa khai báo trong `cache.specs` của `thingsboard.yml` | Bỏ `@Cacheable` hoặc khai báo cache trong `thingsboard.yml` |
| `Bind for 0.0.0.0:5432/1883 failed: port is already allocated` | Trùng port với container khác (GreenIQ) | Bỏ mapping port không cần thiết, đổi port host khác |
| Sửa code nhưng UI vẫn lỗi cũ | Container vẫn chạy image cũ | `up -d --force-recreate` sau khi build |
| Build fail ở `testCompile` (`reference to ... is ambiguous`) | Test có sẵn của upstream không tương thích Java 25 | Build image dùng `-Dmaven.test.skip=true` |

