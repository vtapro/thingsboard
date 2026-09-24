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
| 12 | Kafka + Cassandra cho môi trường dev | ⏳ | — | đang cài native (xem mục 4) |

## 2. Môi trường local hiện tại (native, không Docker)

| Thành phần | Giá trị |
|---|---|
| Backend | chạy bằng `java` từ `application/target/classes` (JDK 25), `http://localhost:8080` |
| UI dev | Angular `ng serve`, `http://localhost:4200` (proxy `/api` → 8080) |
| Database | PostgreSQL 16 native, DB `thingsboard`, user `postgres` / `postgres` |
| Thư mục dữ liệu | `C:\Users\vthea\tb-data` (sql + cassandra + json cho installer) |
| MQTT | `localhost:1883`, username = device access token, topic `v1/devices/me/telemetry` |
| RBAC | bật bằng `-Dsecurity.rbac.enabled=true` khi chạy backend |

Tài khoản mặc định: xem [local-dev.md](local-dev.md) mục 2.

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

1. **Kafka 4.0 (KRaft) + Cassandra 5.0 native** cho dev: cài đặt và trỏ ThingsBoard sang
   (`queue.type=kafka`, `database.ts.type=cassandra`) — xem `local-dev.md`.
2. **Test tự động**: đã có unit test cho RBAC và mail template (`TbRbacAccessControlServiceTest`,
   `MailTemplateSecurityTest`); cần thêm API test cho groups/white labeling.
3. **Lọc theo nhóm ở tầng query** (thay vì lọc trong bộ nhớ, giới hạn 1000 entity) khi tenant rất lớn.
4. **Backup**: chạy `pg_dump` định kỳ cho DB dev để tránh mất dữ liệu khi kiểm thử.

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
`install.data_dir` có `sql/` + `cassandra/`; thiếu `queue.type=in-memory` thì installer lỗi bảng `queue`.
