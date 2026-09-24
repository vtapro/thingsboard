# Trạng thái triển khai các tính năng (cập nhật theo từng mốc)

Tài liệu này ghi lại chính xác tính năng nào đã xong, đang dở, và bước tiếp theo cụ thể —
để có thể tiếp tục công việc mà không cần đọc lại toàn bộ lịch sử.

## 1. Bảng trạng thái

| # | Tính năng | Backend | Frontend | Đã deploy | Commit |
|---|---|---|---|---|---|
| 1 | White labeling (5 tab: General, Login, Mail templates, Custom translation, Custom menu) | ✅ | ✅ | ✅ | `1b9964c8a7`, `3500fe7c25`, `ece9ed4484`, `855bed3cde`, `597fbc947a`, `162b5ebc9a` |
| 2 | Mail templates theo tenant (8 luồng email, WYSIWYG, tên dễ đọc) | ✅ | ✅ | ✅ | `60b7af5e0c`, `c40f59d47c`, `4e5304dda4` |
| 3 | Custom translation (tự áp khi đổi ngôn ngữ, bảng locale) | ✅ | ✅ | ✅ | `e846b22424`, `306c5c0bfa`, `0d33e876d2`, `855bed3cde` |
| 4 | Custom menu (ghép sidebar theo assignee type) | ✅ | ✅ | ✅ | `497ffdf7a7`, `162b5ebc9a` |
| 5 | Login branding theo domain (tenant tự quản lý domain) | ✅ | ✅ | ✅ | `d025167b7f`, `f2645ada35`, `597fbc947a` |
| 6 | RBAC Phase A (model, API, trang Roles, gán user) | ✅ | ✅ | ✅ | `1892bfee99`, `e2931f8562`, `e069ed0478`, `f32078be7f` |
| 7 | RBAC Phase B (enforce sau cờ `security.rbac.enabled`, fallback, gating menu) | ✅ | ✅ | ✅ (mặc định tắt) | `24ccc8846c`, `3aadb64bc7` |
| 8 | RBAC Phase C — quản lý entity groups | ✅ | ✅ | ✅ | `50686c2fff`, `4388eff5a9` |
| 9 | RBAC Phase C — **quyền theo nhóm** (scope permission theo entity group) | ❌ | ❌ | ❌ | — |
| 10 | RBAC Phase D — user groups + customer hierarchy | ❌ | ❌ | ❌ | — |

## 1b. Việc còn lại — chi tiết triển khai

### Quyền theo nhóm (khép Phase C)

1. Model: đổi `permissions` của `RbacRole` sang dạng có phạm vi, ví dụ
   `{"DEVICE": {"READ": ["<group-id>"]}}`, **vẫn đọc được dạng cũ** `{"DEVICE": ["READ"]}` (tương thích ngược).
2. Enforce trong `TbRbacAccessControlService`: với thao tác trên entity cụ thể → resolve entity → nhóm chứa nó
   (cache theo tenant) → so với phạm vi quyền; quyền không gắn nhóm = áp cho toàn bộ entity loại đó.
3. UI trang Roles: cho chọn group khi tick quyền.
4. Test: user bị chặn với thiết bị ngoài nhóm, xem được thiết bị trong nhóm; user khác không đổi hành vi.

### Phase D

- **User groups**: `AdminSettings(tenant, "userGroups")` + gán role cho cả nhóm một lần.
- **Customer hierarchy**: thêm `parentCustomerId`, lan quyền theo cây customer (ảnh hưởng dashboard/entity visibility).

## 1c. Trạng thái môi trường chạy local

- Stack: `docker compose -f docker/tb-custom/docker-compose.yml` (Postgres 16 + tb-node, queue in-memory).
- Port: UI/API **8080**, MQTT **11883** (1883 thuộc stack GreenIQ của bạn).
- Sau mỗi lần build **phải** `up -d --force-recreate` và kiểm tra digest đổi, nếu không container vẫn là bản cũ.
- RBAC mặc định **tắt**; bật bằng `SECURITY_RBAC_ENABLED: "true"` trong environment của `tb-node`.

## 1d. Cập nhật cuối (đã test trên stack local)

### RBAC — đã hoàn thành và kiểm chứng

- Role có 3 dạng quyền: **toàn cục** (`permissions`), **theo nhóm** (`scopedPermissions`), và **own customer only**
  (`ownCustomerOnly`) — dùng cho customer user để giữ cách ly customer của CE (chỉ truy cập customer mình và customer con).
- **Entity groups**: trang riêng *Entities → Groups* (3 tab Devices/Assets/Entity views) + tab **ALL | GROUPS** ngay
  trong bảng entity (Devices, Assets, Entity views) + dialog Add/Edit + Add entities + phân trang `mat-paginator`.
- **User groups**: gán role cho cả nhóm, thành viên thừa hưởng quyền.
- **Customer hierarchy**: nhập quan hệ cha–con ở *Security → Roles → Customer hierarchy*; lan quyền theo cây customer.

### Bằng chứng test (API thật trên local)

| Kiểm tra | Kết quả |
|---|---|
| Customer user (thuộc Parent-Co) đọc device thuộc **Child Co** (customer con) | **200** — lan quyền hierarchy OK |
| Cùng user đọc device **ngoài customer** khi role bật `ownCustomerOnly` | **403** — cách ly customer OK |
| Endpoint tenant-scope (`/api/tenant/devices`) gọi bằng customer user | **403** — đúng |

### Lưu ý khi gọi REST API (quan trọng cho script/CI)

Payload `EntityId` **phải có `entityType`**, nếu thiếu TB trả về lỗi gây nhầm lẫn:

```
# SAI  -> 500 {"message":"I/O error while reading input message"}
{"name":"dev","deviceProfileId":{"id":"<uuid>"}}

# ĐÚNG -> 200
{"name":"dev","deviceProfileId":{"entityType":"DEVICE_PROFILE","id":"<uuid>"}}
```

Tương tự với `customerId`, `tenantId`, `entityId` (thêm `"entityType":"CUSTOMER" | "TENANT" | ...`).

### MQTT

- TB MQTT đang ở **cổng chuẩn 1883** (`localhost:1883`, username = device access token, topic `v1/devices/me/telemetry`).
- EMQX của stack GreenIQ đã dừng để giải phóng 1883; nếu cần dùng lại, chạy nó ở cổng khác (ví dụ `1884:1883`).

## 2. Quy trình làm việc đã dùng (giữ nguyên cho các mốc sau)

```powershell
# 1. Sửa code
# 2. Build (8-12 phút; log ở %TEMP%\tb-custom-build.log)
docker compose -f docker/tb-custom/docker-compose.yml build tb-node
# 3. Chạy lại với image mới (BẮT BUỘC, nếu không vẫn là bản cũ)
docker compose -f docker/tb-custom/docker-compose.yml up -d --force-recreate
# 4. Xác nhận đã lên bản mới: hai digest phải giống nhau và khác digest cũ
docker images --no-trunc --format '{{.ID}}' tb-custom-tb-node:latest
docker inspect tb-custom-tb-node-1 --format '{{.Image}}'
# 5. Commit + push mốc
git add -A; git commit -m "..."; git push origin GEIQ-WhiteLabeling
```

Bài học đã gặp: `-Dpkg.skip=true` làm mất boot jar (phải dùng `package` cho module `application` với
`-Dpkg.skip.deb/rpm/zip=true`); thiếu `git`/`patch` thì yarn fail; thiếu `data/sql` + `data/cassandra`
thì install schema fail; cache của `@Cacheable` phải khai báo trong `cache.specs`; `up` chạy ngay khi build
chưa xong thì container vẫn là image cũ.

## 3. Custom translation — phần còn lại (1 vòng build)

Đã có: `CustomTranslationService` (frontend, tự áp khi đổi ngôn ngữ), API `GET /api/tenant/customTranslation`,
`GET /api/customTranslation/{locale}`, `POST/DELETE /api/tenant/customTranslation/{locale}`.

Cần làm:

1. `custom-translation.component.ts/html/scss` trong `ui-ngx/src/app/modules/home/pages/admin/`:
   bảng locale (cờ + tên + % hoàn thành) bằng `mat-table`, nút tải bundle gốc, nút sửa (dialog editor
   key/value), nút xoá, nút "Add new language".
2. Thêm tab thứ 3 vào `white-labeling.component.html` (`<tb-custom-translation>`).
3. Khai báo component trong `admin.module.ts`, thêm key i18n vào `locale.constant-en_US.json`.
4. Verify: `POST /api/tenant/customTranslation/en_US` → F5 → chuỗi đổi theo.

## 4. Custom menu — thiết kế đã chốt

- Dữ liệu: `AdminSettings(tenant, "customMenu")` = danh sách
  `{ id, name, icon, type (dashboard|url), target, assigneeType (TENANT_ADMIN|CUSTOMER_USER), order }`.
- Backend: CRUD `/api/tenant/customMenu` (theo mẫu `MailTemplateController`).
- Frontend: `buildUserMenu()` trong `core/services/menu.models.ts` ghép thêm mục custom sau khi dựng menu
  mặc định, lọc theo `assigneeType` của user hiện tại; tab UI có bảng + dialog "Add custom menu" như PE.

## 5. Login branding theo domain — thiết kế đã chốt

- Dùng entity `Domain` sẵn có của CE (`DomainController`, `DomainService`) để map host → tenant.
- `GET /api/noauth/whiteLabeling` nhận `HttpServletRequest`, resolve host → tenant, trả branding của tenant
  đó (fallback system). Thêm `loginTitle`, `loginSubtitle`, `loginBackgroundUrl` vào `WhiteLabelingSettings`.
- Tab UI "Login": quản lý domain (Create new), base URL, checkbox "Prohibit to use hostname from client
  request headers", và các control branding tái sử dụng từ tab General.

## 6. RBAC + Groups

Chi tiết ở [access-control-roadmap.md](access-control-roadmap.md): điểm cắm duy nhất
`accessControlService.checkPermission(...)`, mô hình dữ liệu (role, role_permission, role_assignment,
entity_group, user_group, customer hierarchy), 4 phase và rủi ro. **Chưa viết dòng code nào** — bắt đầu
từ Phase A (model + API + trang Roles, chưa enforce).
