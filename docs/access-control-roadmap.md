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

Phase A → B là đủ để bán tính năng "phân quyền linh hoạt"; C và D làm sau để hoàn thiện ngang PE.
