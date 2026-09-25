# Phân quyền linh hoạt (RBAC) + Nhóm thực thể — kế hoạch triển khai

Mục tiêu: bổ sung cho fork những gì ThingsBoard PE có — role tuỳ biến, phân quyền theo resource,
nhóm thiết bị / tài sản / entity view, nhóm người dùng, customer hierarchy (sub-customer).

## 1. Điểm cắm kỹ thuật (quan trọng nhất)

- Backend: **mọi** kiểm tra quyền đi qua `accessControlService.checkPermission(user, Resource, Operation[, entityId])`.
  Có ~536 chỗ dùng `@PreAuthorize` và các controller gọi hàm này → chỉ cần cắm RBAC ở một nơi,
  không phải sửa toàn bộ controller.
- Frontend: menu/route hiện hard-code `data.auth: [Authority.SYS_ADMIN]`. Phải bổ sung kiểm tra theo
  permission (menu ẩn/hiện theo quyền) — nếu không, user có role mới sẽ thấy menu/route sai.
- Cache: quyền phải cache theo tenant (dùng hạ tầng cache sẵn có) và invalidate khi đổi role/gán quyền.

## 2. Mô hình dữ liệu đề xuất

| Bảng / entity | Trường chính | Ghi chú |
|---|---|---|
| `role` | id, tenant_id, name, type (GENERIC / GROUP), created_time, version | Role tuỳ biến theo tenant |
| `role_permission` | role_id, resource, operation | Cặp (Resource × Operation) như matrix gốc |
| `entity_group` | id, tenant_id, entity_type (DEVICE/ASSET/ENTITY_VIEW/…), name, owner_id | Nhóm thực thể |
| `entity_group_member` | group_id, entity_id | Hoặc dùng `Relation` sẵn có (`Contains`) |
| `user_group` + `user_group_member` | group_id, user_id | Nhóm người dùng, gán role hàng loạt |
| `role_assignment` | user_id, role_id, scope (TENANT / ALL_ENTITIES / GROUP:<id>) | Gán role theo phạm vi |
| `customer.parent_customer_id` | | Sub-customer (customer hierarchy) |

Quy tắc: user **không có** role tuỳ biến → fallback về matrix CE hiện tại (TENANT_ADMIN / CUSTOMER_USER)
để không phá hành vi đang chạy. TENANT_ADMIN và CUSTOMER_USER sẽ được seed thành 2 role built-in.

## 3. API dự kiến

| Method | Endpoint | Quyền |
|---|---|---|
| GET/POST/DELETE | `/api/role` , `/api/role/{id}` | TENANT_ADMIN |
| GET | `/api/role/{id}/permissions` | TENANT_ADMIN |
| GET/POST | `/api/entityGroup` (theo entityType) | TENANT_ADMIN |
| POST | `/api/entityGroup/{id}/entities` (thêm/bớt member) | TENANT_ADMIN |
| GET/POST | `/api/userGroup` + `/api/userGroup/{id}/users` | TENANT_ADMIN |
| POST | `/api/user/{id}/roles` (gán role + scope) | TENANT_ADMIN |

## 4. Cách enforce

1. Tạo `TbRbacAccessControlService` implements `AccessControlService`, đánh dấu `@Primary` để thay thế bean gốc.
2. Trong `checkPermission`: nếu user có role tuỳ biến → tra (role, resource, operation) và scope
   (tenant / group) → quyết định; nếu không có → gọi lại implementation gốc (matrix CE).
3. Với thao tác trên entity cụ thể: resolve entity → group(s) chứa nó (cache) → kiểm tra role có quyền trên group đó.
4. Frontend: thêm service `permissions$` (lấy từ `/api/user/permissions`), menu/route guard đọc service này.

## 5. Các phase

| Phase | Nội dung | Ước lượng |
|---|---|---|
| A | Model + API + trang **Roles** (tạo role, tick permission resource × operation, gán cho user). Chưa enforce | ~1 tuần |
| B | Enforce trong `AccessControlService` (fallback matrix gốc) + test hồi quy CE | ~1 tuần |
| C | **Entity groups** (Device/Asset/Entity view) + quyền theo nhóm + UI quản lý nhóm | ~1–2 tuần |
| D | **User groups** + **customer hierarchy (sub-customer)** | ~1–2 tuần |

## 6. Rủi ro và cách giảm

- **Vỡ hành vi CE**: luôn có fallback matrix gốc + chạy bộ test hồi quy (`application` có ~443 test class) sau mỗi phase.
- **Menu/route sai**: chuyển frontend sang kiểm tra permission trước khi bật enforce ở backend.
- **Cache sai quyền**: invalidate theo tenant khi save role/assignment; có test cho tình huống đổi quyền khi user đang online.
- **Feature giả định matrix cũ** (version control, edge, rule chain): rà soát riêng ở cuối Phase B.
- **Không copy được PE**: toàn bộ phần này tự implement, dựa trên enum `Resource`/`Operation` sẵn có của CE.

## 7. Thứ tự thực hiện thực dụng cho sản phẩm

## 8. Phase E — Quyền chi tiết kiểu PE + thiết bị thuộc về user (chốt 2026-09-25)

### 8.1 Hiện trạng (đã kiểm chứng trong code)

- UI Roles chỉ có 3 ô cho mỗi entity type (`roles.component.ts`: `operations = ['READ','WRITE','DELETE']`).
- `TbRbacAccessControlService.grantedOperation()` gộp quyền con: mọi `READ_*` (telemetry, attributes,
  credentials, calculated fields) → cần `READ`; mọi thao tác khác (`CREATE`, `RPC_CALL`, `CLAIM_DEVICES`,
  `ASSIGN_*`, `WRITE_*`, `ALL`) → cần `WRITE`.
- Hệ quả: role hiện tại đã đủ cho tạo/sửa/xóa/xem/điều khiển, nhưng **không tách được** các cặp như
  “xem telemetry nhưng không xem credentials” hay “gửi RPC nhưng không sửa cấu hình thiết bị”.

### 8.2 Ma trận quyền chi tiết (theo `Resource` × `Operation` của ThingsBoard)

| Entity type | Thao tác hiển thị trên UI |
|---|---|
| DEVICE | Create, Read, Write, Delete, Read telemetry, Write telemetry, Read attributes, Write attributes, Read credentials, Write credentials, RPC call, Claim devices, Assign to customer |
| ASSET | Create, Read, Write, Delete, Read attributes, Write attributes, Assign to customer |
| ENTITY_VIEW | Create, Read, Write, Delete, Read telemetry, Read attributes, Assign to customer |
| DASHBOARD / RULE_CHAIN / WIDGETS_BUNDLE / WIDGET_TYPE | Create, Read, Write, Delete, Assign to customer |
| ALARM | Read, Write, Delete |
| CUSTOMER / USER | Create, Read, Write, Delete, Assign to customer |
| DEVICE_PROFILE / ASSET_PROFILE / TENANT_PROFILE | Create, Read, Write, Delete |
| CALCULATED_FIELD | Read, Write, Delete (Read/Write calculated field) |

Mỗi entity type thêm nút **preset**: `Viewer` (Read + Read *), `Operator` (Viewer + RPC call, Write telemetry,
Claim devices), `Manager` (Operator + Create/Write/Delete/Assign), `Admin` (chọn tất cả).

### 8.3 Tương thích ngược (bắt buộc)

Backend chỉ chuyển sang chế độ “chính xác từng operation” khi role có **operation chi tiết**:

1. Nếu danh sách operation của resource chứa đúng tên operation (hoặc `ALL`) → cho phép.
2. Nếu danh sách chỉ gồm bộ ba cũ `READ/WRITE/DELETE` → giữ nguyên hành vi suy diễn như hiện nay.
3. Trường hợp còn lại (role có operation chi tiết) → **chặn** nếu không có operation tương ứng.

Nhờ vậy role đang chạy (ví dụ `NM1: DEVICE READ, WRITE, DELETE`) không đổi hành vi sau khi nâng cấp.

### 8.4 Thiết bị do chính user tạo (PE-like ownership)

- ThingsBoard CE 4.4 **không có** trường owner trên `Device` (đã kiểm tra `Device.java` và `DeviceInfo.java`),
  fork lại không được thêm bảng/cột ⇒ lưu chủ sở hữu bằng **server attribute** `rbacOwnerId` trên thiết bị.
- Khi user có `Create` trên DEVICE tạo thiết bị: backend ghi `rbacOwnerId = user.id` (server scope) và cho
  phép chỉnh sửa/xóa chính thiết bị đó.
- Role có cờ **“Chỉ thiết bị của tôi”** (own scope, lưu trong role JSON): khi bật, mọi truy vấn danh sách
  thiết bị bị lọc còn các thiết bị có `rbacOwnerId` = user hiện tại (lọc ở tầng service giống cách đang lọc
  theo entity group, dùng chung giới hạn 1000 entity/lần), và chặn READ/WRITE/DELETE với thiết bị của user khác.
- Hiển thị: thêm cột “Owner” trong trang Devices (chỉ hiện khi tenant bật RBAC) để admin nhìn thấy ai tạo thiết bị nào.

### 8.5 Kế hoạch test local trước khi deploy

1. Tạo 2 user (`userA`, `userB`) + 2 role: `Operator-Own` (Read+Create+Write telemetry+RPC, own scope) và
   `Viewer` (chỉ Read).
2. `userA` tạo `deviceA` → kiểm tra `userA` thấy/sửa/xóa được, `userB` **không** thấy `deviceA` trong danh sách.
3. `userB` thử sửa telemetry của `deviceA` → phải bị 403.
4. `Viewer` thử tạo thiết bị → 403; thử gửi RPC → 403 (nếu không có `RPC_CALL`).
5. Role cũ (`READ/WRITE/DELETE` như `NM1`) chạy lại bộ test RBAC hiện có → hành vi không đổi.

### 8.6 Phạm vi mở rộng (user chốt 2026-09-25): “user tự tạo và chia sẻ tài nguyên của họ”

Áp dụng cùng cơ chế ownership cho **DEVICE, ASSET, ENTITY_VIEW, DASHBOARD** (không chỉ DEVICE):

| Tài nguyên | Owner storage | Cờ role | Người dùng tự tạo được khi có `Create` |
|---|---|---|---|
| DEVICE | server attribute `rbacOwnerId` | “Chỉ thiết bị của tôi” | ✅ |
| ASSET | server attribute `rbacOwnerId` | “Chỉ tài sản của tôi” | ✅ |
| ENTITY_VIEW | server attribute `rbacOwnerId` | “Chỉ entity view của tôi” | ✅ |
| DASHBOARD | `Dashboard.assignedCustomers`? → dùng `ownerId` trong `additionalInfo` (không thêm cột) | “Chỉ dashboard của tôi” | ✅ |

Chia sẻ tài nguyên (share) theo 2 cách, giữ tương thích CE:

1. **Share nội bộ (giống PE)**: bảng `rbac_share` KHÔNG thêm ⇒ lưu danh sách chia sẻ trong **server attribute
   `rbacSharedWith`** của entity (`[{type: USER|USER_GROUP, id, operations:[READ, WRITE_TELEMETRY, RPC_CALL...]}]`).
   Khi kiểm tra quyền: nếu user không phải owner, kiểm tra share trước khi chặn; share có thể chỉ cho READ
   (xem), WRITE (điều khiển) hoặc RPC (gửi lệnh) để đúng chuẩn các hãng IoT.
2. **Share công khai**: dùng đúng tính năng CE (`Make dashboard public` / `public` của device) — không cần thêm gì.

UI kèm theo:
- Nút **Share** trên chi tiết DEVICE/ASSET/ENTITY_VIEW/DASHBOARD: chọn User hoặc User group + mức quyền
  (Xem / Điều khiển / Toàn quyền) + danh sách người đang được chia sẻ (thu hồi được).
- Preset role thêm 2 lựa chọn: **“User tự quản lý tài nguyên của mình”** (Create + Read/Write/Delete own scope,
  không thấy dữ liệu của người khác) và **“User tạo được tài nguyên và chia sẻ”** (thêm operation `SHARE`).

Thứ tự thực hiện: (1) backend explicit-operation + tương thích ngược → (2) UI ma trận quyền + preset →
(3) ownership DEVICE → (4) ownership ASSET/ENTITY_VIEW/DASHBOARD → (5) share + UI Share → (6) test local theo
§8.5 và bổ sung case share/không share, rồi mới bàn tới việc deploy.

Phase A → B là đủ để bán tính năng "phân quyền linh hoạt"; C và D làm sau để hoàn thiện ngang PE.
