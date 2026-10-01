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

## 8. "Manage owner and groups" (2026-09-30)

Panel chi tiết của một user (khi đăng nhập bằng `TENANT_ADMIN`) có nút **Manage owner and groups** giống ThingsBoard
PE, gộp 2 việc:

| Mục | Ý nghĩa | Ai sửa được |
|---|---|---|
| **Owner** | Customer sở hữu user — quyết định user nhìn thấy dữ liệu của khách hàng nào | chỉ `CUSTOMER_USER` mới có owner; user `TENANT_ADMIN` không có owner nên trường này bị ẩn |
| **Groups** | Các entity group loại `USER` — dùng để scope quyền của role theo nhóm | nhóm **All** luôn được tick và không sửa được (mọi user mặc nhiên thuộc All) |

### Nhóm user mặc định (giống PE)

Mỗi tenant được tạo sẵn 3 nhóm cho user, đúng như bộ chọn nhóm của PE:

| Nhóm | Ai thuộc nhóm | Ghi chú |
|---|---|---|
| `All` | mọi user của tenant | nhóm hệ thống, không sửa/xoá được |
| `Tenant Administrators` | user authority `TENANT_ADMIN` | id sinh từ tenant + tên nhóm nên ổn định; thành viên do nền tảng đồng bộ |
| `Tenant Users` | user authority `CUSTOMER_USER` | như trên |

Quy tắc đồng bộ: khi tạo/sửa user (`POST /api/user`), nền tảng tự thêm user vào nhóm theo authority của họ và bỏ
khỏi nhóm còn lại; khi mở dialog (hoặc gọi API membership) hệ thống đồng bộ lại user đang xem, nên cả những user
tạo trước khi có 2 nhóm này cũng hiện đúng; khi xoá user thì user được bỏ khỏi mọi nhóm user.

Các nhóm này là nhóm thường (có thể gán role/scope theo chúng), không phải nhóm hệ thống bị khoá như `All`.

### API

| Method | Endpoint | Việc |
|---|---|---|
| GET | `/api/tenant/entityGroup/members/{entityType}/{entityId}` | Danh sách group của loại entity đó + entity có là thành viên hay không |
| POST | `/api/tenant/entityGroup/members/{entityType}/{entityId}` | Đặt membership: `{"groupIds":["..."]}` — thêm vào các group trong danh sách, bỏ khỏi các group còn lại (không đụng tới entity khác) |
| POST | `/api/user` | Đổi owner: gửi lại user với `customerId` mới (API chuẩn của ThingsBoard, giống cách PE lưu owner) |

Đổi owner bị siết ở `UserController#checkOwnerChange`:

- chỉ `TENANT_ADMIN`/`SYS_ADMIN` (customer user không bao giờ đổi owner — code đã ép `customerId` của chính họ);
- chỉ user authority `CUSTOMER_USER` mới có owner (đổi owner của `TENANT_ADMIN` → **400**);
- caller phải có quyền `CUSTOMER:WRITE` trên **cả customer cũ và customer mới** (tôn trọng scope `ownCustomerOnly`
  của role), customer mới phải tồn tại (**404** nếu không);
- sau khi đổi, hệ thống phát `UserCredentialsInvalidationEvent` để token cũ của user hết hiệu lực (phạm vi dữ
  liệu của họ đã thay đổi);
- `UserDataValidator` (dao) được nới đúng một chỗ: cho phép đổi `customerId` của `CUSTOMER_USER`, vẫn **không cho**
  đổi `tenantId` hay `authority` (CE mặc định cấm mọi thay đổi `customerId`).

Quyền vào group cũng cần quyền `WRITE` trên chính entity đó (`checkEntityId(..., WRITE)`), nên một tenant admin bị
giới hạn role cũng không thể tự đưa entity ngoài phạm vi vào nhóm để leo quyền.

### Kiểm chứng

```bash
/tmp/emu-venv/bin/python scripts/test-owner-groups-local.py    # 17/17 PASS
```

Script kiểm tra: tạo user trong customer A → thêm/bỏ user khỏi một user group (và xác nhận thành viên của entity
khác không đổi) → chuyển user sang customer B (**200**, đọc lại thấy đúng B) → `TENANT_ADMIN` không nhận owner
(**400**) → customer không tồn tại (**404**) → entity không tồn tại (**404**).
