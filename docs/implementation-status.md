# Trạng thái tính năng và môi trường dev

Tài liệu này tóm tắt tính năng nào đã xong và môi trường chạy local hiện tại.
Cách cài đặt/chạy chi tiết: [local-dev-macos.md](local-dev-macos.md).

## 1. Bảng trạng thái tính năng

| # | Tính năng | Backend | Frontend | Ghi chú |
|---|---|---|---|---|
| 1 | White labeling (5 tab: General, Login, Mail templates, Custom translation, Custom menu) | ✅ | ✅ | tenant admin tự quản lý |
| 2 | Mail templates theo tenant (8 luồng email, WYSIWYG) | ✅ | ✅ | render bằng FreeMarker đã siết bảo mật |
| 3 | Custom translation (áp tự động khi đổi ngôn ngữ) | ✅ | ✅ | bảng locale + override |
| 4 | Custom menu (ghép sidebar theo assignee type, URL ngoài mở tab mới) | ✅ | ✅ | server chỉ nhận http(s) |
| 5 | Login branding theo domain | ✅ | ✅ | bảng `domain` map host → tenant |
| 6 | RBAC: role, phân quyền theo resource/operation, gán user | ✅ | ✅ | thêm `ADMIN_SETTINGS`, `USER` |
| 7 | RBAC enforce sau cờ `security.rbac.enabled`, fallback về CE | ✅ | ✅ | role chỉ siết resource được khai báo |
| 8 | Entity groups + tab ALL/GROUPS trong bảng entity | ✅ | ✅ | API theo từng group, không ghi đè cả danh sách; nhóm có cả cho Customers/Users — bấm vào nhóm (kể cả "All") mở trang thành viên, xem [rbac-members.md](rbac-members.md) §7 |
| 9 | Quyền theo entity group (scope) | ✅ | ✅ | áp cho DEVICE/ASSET/ENTITY_VIEW; lọc cả API danh sách |
| 10 | User groups + customer hierarchy | ✅ | ✅ | role gán theo nhóm, lan quyền theo cây customer |
| 11 | Trang Roles: 4 tab, mỗi entity type một tab quyền, thông báo lưu | ✅ | ✅ | |
| 12 | Kafka + Cassandra cho môi trường dev | ➖ | — | **đã bỏ ở local**: bản Cassandra của CE không hiện thực `findAllKeysByEntityIds` nên widget không liệt kê được key telemetry; dev dùng PostgreSQL + queue in-memory. Production vẫn dùng Kafka + Cassandra (xem [production-k3s.md](production-k3s.md)) |
| 13 | Deploy production trên k3s (microservices, domain `app.greeniq.vn`) | 🟡 | 🟡 | 8 image riêng theo từng service (`ghcr.io/vtapro/tb-*`), manifest ở `deploy/k3s/`: **PostgreSQL trong cụm (CloudNativePG 3 instance trên Longhorn + PgBouncer)**; Cassandra + Kafka + ZooKeeper chạy trong cụm; cache caffeine — **không dùng Redis**; edge là HAProxy; workflow GHCR ở `.github/workflows/publish-images.yml` |
| 14 | Đo tải / năng lực hệ thống | ✅ | — | bộ công cụ ở `deploy/loadtest/`, kết quả ở [capacity-load-test.md](capacity-load-test.md): an toàn ≤ 500 thiết bị × 1 msg/s, trần ~1.000 msg/s; 2.000 msg/s bắt đầu mất dữ liệu |
| 15 | Automation (hẹn giờ điều khiển thiết bị) | ✅ | ✅ | rule hẹn giờ theo ngày/tuần/cron → gửi server-side RPC (bật/tắt máy bơm, tưới cây…); lưu trong `admin_settings` key `automation` (không thêm bảng); API `/api/tenant/automation`; trang **Automation** ở menu trái; test local 15/15 PASS (`scripts/test-automation-local.py`) |
| 16 | Quản lý thành viên theo quyền (USER/CUSTOMER RBAC) | ✅ | ✅ | user có quyền `USER`/`CUSTOMER` tự thêm/xoá thành viên và customer con; scope `ownOnly` + `ownCustomerOnly`; menu Users/Customers hiện theo quyền; tài khoản `TENANT_ADMIN` chỉ system admin mới disable/enable và `Login as` chỉ áp cho customer user; xem [rbac-members.md](rbac-members.md) |
| 17 | Emulators: catalog thiết bị ảo + dashboard theo lĩnh vực | ✅ | ✅ | 15 profile/8 lĩnh vực (năng lượng, nông nghiệp, nhà máy, chiếu sáng, nước, vận tải, toà nhà, đô thị); tạo emulator = device thật + sinh telemetry theo scenario + tự tạo dashboard của lĩnh vực; xem [emulators.md](emulators.md) |
| 18 | Manage owner and groups (theo chuẩn PE) | ✅ | ✅ | Dialog trong panel chi tiết user: đổi owner (customer sở hữu user, chỉ `CUSTOMER_USER`) + gán user vào các user group; API `entityGroup/members/{entityType}/{id}`; guard quyền + invalidate token khi đổi owner; xem [rbac-members.md](rbac-members.md) §8 |
| 19 | Xuất dữ liệu widget (CSV/XLS/XLSX) | — | ✅ | Menu tải xuống trên header mỗi widget (giống PE): Time series (1 dòng/timestamp, cột `<entity> · <key> (unit)`) + Latest values; XLSX nhiều sheet bằng JSZip, không thêm dependency; xem [widget-export.md](widget-export.md) |
| 20 | Customer user tạo device/asset/entity view theo role | ✅ | ✅ | role có `CREATE` thì nút Add hiện (devices dùng wizard, ẩn ô chọn customer); backend gán `customerId` của người tạo + `rbacOwnerId` trước khi kiểm quyền, `ownOnly` được áp ở nhánh customer user; nhóm hệ thống `All`/`Tenant Administrators`/`Tenant Users` trả `system: true` và không sửa được qua dialog — xem [rbac-members.md](rbac-members.md) §8–§9 |
| 21 | Quyền tạo theo role (dashboard) + ai tạo tài khoản nào | ✅ | ✅ | role `DASHBOARD:CREATE` cho customer user → dashboard tự gán cho customer của người tạo; trang Users của tenant admin chỉ tạo **customer user** (dialog có ô Owner); chỉ `SYS_ADMIN` tạo được `TENANT_ADMIN` — xem [rbac-members.md](rbac-members.md) §9 |
| 22 | Hai profile customer user (Customer Administrator / Customer User) như PE | ✅ | ✅ | tenant có sẵn 2 role + 2 nhóm `Customer Administrators` / `Customer Users`; user mới mặc định chỉ-xem, chuyển nhóm trong dialog "Manage owner and groups" là đổi quyền (tạo device/asset/entity view/dashboard, quản lý user của customer) và có hiệu lực ngay; 2 nhóm cũ theo authority (`Tenant Administrators`/`Tenant Users`) đã bỏ vì PE không có — xem [rbac-members.md](rbac-members.md) §8–§9 |

## 2. Môi trường local hiện tại (native, không Docker)

| Thành phần | Giá trị |
|---|---|
| Backend | chạy bằng `java` từ `application/target/classes` (JDK 25), `http://localhost:8080` |
| UI dev | Angular `ng serve`, `http://localhost:4200` (proxy `/api` → 8080) |
| Database | PostgreSQL 18 native, DB `thingsboard`, user `geiq` (không mật khẩu), dùng cho cả entities và timeseries |
| Queue | `in-memory` (không cần Kafka/ZooKeeper) |
| Thư mục dữ liệu | `application/target/tb-data` (sql + json cho installer; không cần cassandra) |
| RocksDB (EDQS + calculated fields) | `application/target/rocksdb` — `scripts/tb-local.env` tự set qua `TB_ROCKSDB_DIR` |
| MQTT | `localhost:1883`, username = device access token, topic `v1/devices/me/telemetry` |
| RBAC | bật bằng `-Dsecurity.rbac.enabled=true` khi chạy backend |

Tài khoản mặc định: xem [local-dev-macos.md](local-dev-macos.md) mục 7.

**Đã kiểm chứng end-to-end (native, PostgreSQL + in-memory):** ghi telemetry qua REST và qua MQTT
(`localhost:1883`, token thiết bị) → dữ liệu nằm trong `ts_kv` của PostgreSQL; `GET /api/plugins/telemetry/DEVICE/{id}/keys/timeseries`
và `POST /api/entitiesQuery/find/keys` đều trả `battery, humidity, temperature` (widget dashboard chọn được key).

## 3. Lưu ý khi gọi REST API (cho script/CI)

Payload `EntityId` **phải có `entityType`**, nếu thiếu TB trả lỗi gây nhầm lẫn:

```
# SAI  -> 500 {"message":"I/O error while reading input message"}
{"name":"dev","deviceProfileId":{"id":"<uuid>"}}

# ĐÚNG -> 200
{"name":"dev","deviceProfileId":{"entityType":"DEVICE_PROFILE","id":"<uuid>"}}
```

Tương tự với `customerId`, `tenantId`, `entityId`.

## 4. Việc còn lại

1. **Test tự động**: đã có unit test cho RBAC và mail template (`TbRbacAccessControlServiceTest`,
   `MailTemplateSecurityTest`); cần thêm API test cho groups/white labeling.
2. **Lọc theo nhóm ở tầng query** (thay vì lọc trong bộ nhớ, giới hạn 1000 entity) khi tenant rất lớn.
3. **Backup**: chạy `pg_dump` định kỳ cho DB dev để tránh mất dữ liệu khi kiểm thử.
4. **Nếu sau này muốn dùng Cassandra cho timeseries** (production-like, không dùng cho dev): phải bổ sung hiện thực
   `findAllKeysByEntityIds(Async)` trong `CassandraBaseTimeseriesLatestDao` (hiện là stub trả rỗng) — nếu không
   widget dashboard sẽ không có key telemetry để chọn. Quyết định hiện tại: **không dùng**, để dev bám chuẩn CE.
5. **Năng lực production**: thực hiện Phase 0 trong [capacity-load-test.md](capacity-load-test.md) §6
   (tăng `TB_QUEUE_RULE_ENGINE_PACK_PROCESSING_TIMEOUT_MS`, tăng partition queue, bật monitoring/cảnh báo)
   trước khi vượt mốc ~500 thiết bị × 1 msg/s.

## 5. Quy trình làm việc trên fork

```bash
# 1. Sửa code
# 2. Build lại phần backend (nhanh, không UI)
mvn -B -pl dao,application install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true -Dlicense.skip=true
# 3. Restart backend
./scripts/start-tb.sh --force
# 4. UI: ng serve đã tự hot-reload khi sửa file trong ui-ngx
# 5. Commit + push
git add -A && git commit -m "..." && git push origin RBAC-full-groups-tabs
```

Bài học đã gặp: `-Dpkg.skip=true` (hoặc `-Dskip.ui.build=true`) làm mất boot jar nên khi dev ta chạy từ
`target/classes`; không dùng `mvn clean` vì sẽ xoá `ui-ngx/node_modules` và `*/target/proto`; installer cần
`install.data_dir` có `sql/`; thiếu `queue.type=in-memory` thì installer lỗi bảng `queue`; queue `in-memory`
bật RocksDB cho EDQS/calculated fields nên phải trỏ `rocksdb_path` khi `user.home` không phải thư mục ghi được.

Bài học đã gặp (bổ sung 2026-10-01): **Java language server của VS Code (redhat.java/Eclipse JDT) tự biên dịch
vào `target/classes`** bằng ECJ. Bytecode của ECJ có thể thay thế bytecode javac (thấy rõ ở class có
`Unresolved compilation problems` hoặc `Duplicate method name`), và nếu `mvn install` chạy sau đó thì jar trong
`~/.m2` cũng bị nhiễm (lỗi kiểu `cannot access ImageCacheKeyProto / class file not found`). Cách xử lý:

```bash
# 1. tạm dừng language server (SIGSTOP) để nó không ghi vào target/classes trong lúc build
kill -STOP "$(pgrep -f eclipse.jdt.ls | head -1)"
# 2. build lại sạch đúng các module đã sửa rồi mới compile application
mvn -o -q -pl common/data,dao install -DskipTests -Dskip.ui.build=true -Dlicense.skip=true
rm -rf application/target/classes
mvn -o -pl application compile -DskipTests -Dskip.ui.build=true -Dlicense.skip=true
# 3. cho language server chạy lại
kill -CONT "$(pgrep -f eclipse.jdt.ls | head -1)"
./scripts/start-tb.sh --force
```

Không dùng `mvn -pl application -am ...`: module `common/proto` build từ source cần `tbmsg.proto` (chỉ có trong
artifact đã publish) nên sẽ chết ở `protoc`, và `-am` không cần thiết vì các module khác đã nằm trong `~/.m2`.
Backend local phải chạy ngoài sandbox (cần mở socket tới PostgreSQL/Redis) và nên tách session
(`subprocess.Popen(..., start_new_session=True)`) để không bị dừng khi lệnh kết thúc.
