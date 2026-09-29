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
