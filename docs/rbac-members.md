# Quản lý thành viên theo quyền (User & Customer RBAC)

Tính năng này cho phép **bất kỳ user nào được cấp quyền** quản lý thành viên của mình, thay vì chỉ
`TENANT_ADMIN` như bản CE. Nhờ đó doanh nghiệp nhỏ (5–10 người) có thể vận hành hệ thống mà không cần
một tài khoản "quản trị tenant" làm hết mọi việc: chủ doanh nghiệp cấu hình một role cho quản lý, quản lý
tự thêm nhân viên của mình.

## 1. Mô hình quyền

Hai resource `USER` và `CUSTOMER` nằm trong ma trận phân quyền như mọi resource khác:

| Resource | Operations | Ghi chú |
|---|---|---|
| `USER` | `CREATE`, `READ`, `WRITE`, `DELETE` | thêm/sửa/xoá/kích hoạt thành viên |
| `CUSTOMER` | `CREATE`, `READ`, `WRITE`, `DELETE` | tạo/quản lý customer con (sub-customer) |

Các cờ scope (bật trong dialog role của trang **Roles**):

| Cờ | Ý nghĩa |
|---|---|
| `ownerOnly` (nút "Only entities created by the user") | user chỉ thấy/quản lý các bản ghi **do chính họ tạo** (owner lưu ở server attribute `rbacOwnerId`) |
| `ownCustomerOnly` | user được truy cập customer của mình **và các customer con** khai báo ở tab Customer hierarchy |
| User groups | gán role cho cả nhóm user một lúc (tab User groups) |

Nguyên tắc an toàn:

- Role chỉ **mở rộng trong phạm vi tenant/customer của user**, không vượt tenant/customer.
- Customer user không bao giờ tạo/sửa được `SYS_ADMIN` hay `TENANT_ADMIN`; authority bị ép về `CUSTOMER_USER`.
- Tài khoản `TENANT_ADMIN` **không bị disable/enable credentials** bởi tenant admin khác, kể cả chính người đó:
  chỉ `SYS_ADMIN` được làm việc này. Nút "Disable User Account" vì thế bị ẩn, và tenant admin cũng không mở lại
  được tài khoản quản trị mà system admin đã đình chỉ (backend trả `403`). Nếu để nguyên, một role quản lý
  thành viên có `USER: WRITE` có thể khoá tài khoản quản trị tenant và chiếm quyền — hoặc tenant admin tự khoá
  mình ra khỏi hệ thống.
- **Login as user**: tenant admin chỉ mượn được token của `CUSTOMER_USER` (đúng hợp đồng của
  `GET /api/user/{id}/token` và giống ThingsBoard PE, nút bị mờ với tài khoản quản trị), không login as chính
  mình. `SYS_ADMIN` vẫn login as được tenant admin.
- Owner của một bản ghi chỉ được ghi lúc tạo, update không ghi đè được (chống leo quyền).
- Không có role quản lý `USER`/`CUSTOMER` thì hành vi CE giữ nguyên.

## 2. Cấu hình một role "quản lý thành viên"

Vào **Roles → Add role**, chọn tab `USER`, tick `Create / Read / Write / Delete`.

- Muốn quản lý chỉ nhân viên do mình tạo: tick thêm **Only entities created by the user**.
- Muốn quản lý được cả customer con: bật **Own customer only**, rồi khai báo quan hệ cha–con ở tab
  **Customer hierarchy**.
- Gán role cho user ở ô **Assign users**, hoặc tạo **User group** rồi gán role cho nhóm.

Ví dụ: role `SMB manager` gồm `USER: [CREATE, READ, WRITE, DELETE]`, `DEVICE: [READ]`,
`ownOnly.USER = true` → quản lý tự thêm nhân viên và chỉ thấy nhân viên do mình tạo.

## 3. Hành vi theo loại tài khoản

| Tài khoản | Không có role | Có role `USER`/`CUSTOMER` |
|---|---|---|
| `TENANT_ADMIN` | toàn quyền tenant (CE) | role thu hẹp quyền như mọi resource khác |
| Customer user | xem được user cùng customer (CE), không tạo được | tạo/xoá/kích hoạt thành viên trong customer mình (và customer con nếu bật `ownCustomerOnly`) |

Menu **Users** và **Customers** chỉ hiện với customer user khi role tương ứng được cấp `READ`; tenant admin
luôn thấy (không có role thì giữ quyền nền tảng).

## 4. API liên quan

| Endpoint | Ai dùng |
|---|---|
| `POST /api/user`, `DELETE /api/user/{id}` | theo quyền `USER` |
| `POST /api/user/{id}/userCredentialsEnabled` | theo quyền `USER` |
| `GET /api/users` | customer user: user của customer mình (lọc theo role); tenant admin: toàn tenant |
| `GET/POST/DELETE /api/customer` | theo quyền `CUSTOMER`; customer con tự ghi quan hệ hierarchy |
| `GET /api/user/roles` | quyền hiệu lực của user (menu/UI dùng) |

## 5. Kiểm chứng (2026-09-29)

Đã chạy trên local (`security.rbac.enabled=true`) bằng API thật:

- `TENANT_ADMIN` tạo customer + customer user + role `USER:[CREATE,READ,WRITE,DELETE]`.
- Customer user (được cấp role) `POST /api/user` → **200**, user mới thuộc đúng customer của người tạo.
- Customer user tạo sub-customer → **200**, quan hệ cha–con ghi vào `customerHierarchy`.
- Customer user gửi `authority: TENANT_ADMIN` → bị ép về `CUSTOMER_USER`.
- Role có `ownOnly.USER` → danh sách chỉ còn thành viên do chính user tạo.
- Customer user đọc customer ngoài phạm vi → **403**.
- Customer user **không có** quyền `USER` → `POST /api/user` **403**.
- Customer user gọi `GET /api/customer/{id}/users` → **403** (endpoint này vẫn của tenant admin; customer user
  dùng `GET /api/users`).

## 6. Kiểm chứng bảo vệ tài khoản quản trị (2026-09-30)

| Thao tác | Kết quả |
|---|---|
| Tenant admin disable/enable chính tài khoản `TENANT_ADMIN` | **403** `You don't have permission to disable a tenant administrator account.` |
| Tenant admin disable/enable `TENANT_ADMIN` khác trong tenant | **403** |
| Tenant admin disable → enable lại `CUSTOMER_USER` | **200 / 200** |
| Tenant admin `GET /api/user/{id}/token` với chính mình | **403** `login as yourself` |
| Tenant admin lấy token của `TENANT_ADMIN` khác | **403** `login as a tenant administrator` |
| Tenant admin lấy token của `CUSTOMER_USER` | **200** (có `token` + `refreshToken`) |
| Sysadmin disable → enable `TENANT_ADMIN`, và lấy token của tenant admin | **200 / 200**, **200** |

## 7. Nhóm thực thể cho Customers/Users (2026-09-30)

Tab **Groups** của trang Customers và Users dùng cùng cơ chế với Devices/Assets/Entity views: mọi dòng trong
bảng (kể cả nhóm **All**) bấm vào được và mở trang thành viên của nhóm tại
`/customers/groups/{groupId}` hoặc `/users/groups/{groupId}` — giống item group của ThingsBoard PE.

| Thành phần | Thay đổi |
|---|---|
| `EntityGroupsComponent` | `openGroup()` điều hướng `<trang danh sách>/groups/{id}` cho cả 5 loại (trước đây Customers/Users chỉ quay về trang danh sách nên bấm không thấy gì) |
| `EntityGroupEntitiesComponent` | hiểu `CUSTOMER`/`USER`: nhóm **All** lấy `/api/customers`, `/api/users`; nhóm thường lọc theo `entityIds` bằng entity query API (API này trả `name`/`label` cho cả customer và user) |
| `EntityGroupController` | `ALLOWED_ENTITY_TYPES` thêm `CUSTOMER`, `USER` — trước đây lưu nhóm kiểu này bị **400** dù backend đã tạo nhóm "All" cho chúng |
| Nút Add trên trang nhóm | với nhóm **All** chỉ tạo thực thể mới (mọi thực thể của tenant mặc nhiên thuộc All, gửi lên server sẽ bị từ chối) |

Kiểm chứng trên local: `POST /api/tenant/entityGroup/group` với `entityType=CUSTOMER` và `=USER` → **200**,
`DELETE` → **200**; `/api/customers` và `/api/users` nhận `pageSize/page/sortProperty/sortOrder`;
`POST /api/entitiesQuery/find` với `entityFilter.type=entityList` cho `CUSTOMER`/`USER` → **200**.
