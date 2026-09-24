# Trạng thái tính năng và môi trường dev

Tài liệu này tóm tắt tính năng nào đã xong và môi trường chạy local hiện tại.
Cách cài đặt/chạy chi tiết: [local-dev.md](local-dev.md).

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
| 8 | Entity groups + tab ALL/GROUPS trong bảng entity | ✅ | ✅ | API theo từng group, không ghi đè cả danh sách |
| 9 | Quyền theo entity group (scope) | ✅ | ✅ | áp cho DEVICE/ASSET/ENTITY_VIEW; lọc cả API danh sách |
| 10 | User groups + customer hierarchy | ✅ | ✅ | role gán theo nhóm, lan quyền theo cây customer |
| 11 | Trang Roles: 4 tab, mỗi entity type một tab quyền, thông báo lưu | ✅ | ✅ | |
| 12 | Kafka + Cassandra cho môi trường dev | ➖ | — | **đã bỏ ở local**: bản Cassandra của CE không hiện thực `findAllKeysByEntityIds` nên widget không liệt kê được key telemetry; dev dùng PostgreSQL + queue in-memory. Production vẫn dùng Kafka + Cassandra (xem [production-k3s.md](production-k3s.md)) |
| 13 | Deploy production trên k3s (microservices, domain `app.greeniq.vn`) | 🟡 | 🟡 | 8 image riêng theo từng service (`ghcr.io/vtapro/tb-*`), manifest ở `deploy/k3s/`: PostgreSQL + Cassandra ở 2 máy chủ ngoài cụm, Kafka/ZooKeeper/Redis trong cụm; workflow GHCR ở `.github/workflows/publish-images.yml` |

## 2. Môi trường local hiện tại (native, không Docker)

| Thành phần | Giá trị |
|---|---|
| Backend | chạy bằng `java` từ `application/target/classes` (JDK 25), `http://localhost:8080` |
| UI dev | Angular `ng serve`, `http://localhost:4200` (proxy `/api` → 8080) |
| Database | PostgreSQL 16 native, DB `thingsboard`, user `postgres` / `postgres` (dùng cho cả entities và timeseries) |
| Queue | `in-memory` (không cần Kafka/ZooKeeper) |
| Thư mục dữ liệu | `C:\Users\vthea\tb-data` (sql + json cho installer; không cần cassandra) |
| RocksDB (EDQS + calculated fields) | `application\target\rocksdb` — phải truyền đường dẫn vì `user.home` trên máy này = `C:\` |
| MQTT | `localhost:1883`, username = device access token, topic `v1/devices/me/telemetry` |
| RBAC | bật bằng `-Dsecurity.rbac.enabled=true` khi chạy backend |

Tài khoản mặc định: xem [local-dev.md](local-dev.md) mục 2.

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

## 5. Quy trình làm việc trên fork

```powershell
# 1. Sửa code
# 2. Build lại phần backend (nhanh, không UI)
mvn -B -T 1C clean install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true
# 3. Chạy lại backend (dừng process java cũ trước)
java "@$env:TEMP\tb-server.args"
# 4. UI: ng serve đã tự hot-reload khi sửa file trong ui-ngx
# 5. Commit + push
git add -A; git commit -m "..."; git push origin RBAC-full-groups-tabs
```

Bài học đã gặp: `-Dpkg.skip=true` (hoặc `-Dskip.ui.build=true`) làm mất boot jar nên khi dev ta chạy từ
`target/classes`; classpath dài quá giới hạn Windows nên phải dùng argfile của Java; installer cần
`install.data_dir` có `sql/`; thiếu `queue.type=in-memory` thì installer lỗi bảng `queue`; queue `in-memory`
bật RocksDB cho EDQS/calculated fields nên phải trỏ `rocksdb_path` khi `user.home` không phải thư mục ghi được.
