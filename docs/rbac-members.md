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

Mỗi tenant có 3 nhóm cho user, đúng như bộ chọn nhóm của PE:

| Nhóm | Nội dung | Sửa được trong dialog? |
|---|---|---|
| `All` | mọi user của tenant | không — nhóm hệ thống (nhãn `Auto`) |
| `Customer Administrators` | profile quản trị customer (xem §9) | **có** — đổi profile của user ở đây |
| `Customer Users` | profile chỉ-xem của customer | **có** |

Trước đây fork tạo thêm 2 nhóm suy ra từ authority (`Tenant Administrators` / `Tenant Users`) và đồng bộ thành
viên theo authority. **PE không có 2 nhóm này** nên đã bỏ: không còn được tạo, các bản ghi cũ bị dọn khỏi settings
khi đọc, và `POST /api/user` không còn tự gán nhóm theo authority. Việc gom nhóm để gán role nay dùng đúng 2 nhóm
profile ở trên (hoặc nhóm thường do tenant admin tạo, ví dụ theo phòng ban).

Nhóm `All` là **nhóm hệ thống**: API membership trả `"system": true` và `POST /api/tenant/entityGroup/members/...`
bỏ qua nó, nên dialog chỉ hiển thị trạng thái (checkbox bị khoá, kèm nhãn `Auto`). Khi xoá user, user được bỏ khỏi
mọi nhóm user.

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

## 9. Quyền tạo entity của customer user (audit 2026-10-01)

### Gán role tuỳ biến trực tiếp cho user (2026-10-01)

Trang **Roles** cho phép tạo role tuỳ ý (tên, từng resource/operation, scope theo entity group, cờ `ownOnly`,
`ownCustomerOnly`, gán user/nhóm user). Bổ sung thêm chiều ngược lại — gán role **từ phía user** trong dialog
**Manage owner and groups**:

| Thành phần | Nội dung |
|---|---|
| API | `GET /api/tenant/user/{userId}/roles` → mọi role của tenant + `direct` (gán trực tiếp), `assigned` (đang hiệu lực), `groups` (nhóm user nào cấp role đó); `POST /api/tenant/user/{userId}/roles` `{"roleIds":[…]}` → đặt đúng danh sách role gán **trực tiếp** cho user |
| Quyền | chỉ `TENANT_ADMIN` **và** role phải có `ADMIN_SETTINGS: READ/WRITE` (customer user → 403); user phải thuộc tenant của người gọi |
| Database | **không đổi schema**: role nằm trong `admin_settings` (key `roles`) dạng JSON, `userIds` là danh sách gán trực tiếp — API chỉ ghi lại document đó |
| UI | mục **Roles** trong dialog: mỗi role một checkbox kèm tóm tắt quyền (`DEVICE read, write · DASHBOARD create, read`); role đến từ nhóm user hiện nhãn tên nhóm và chỉ đổi được bằng cách đổi nhóm |
| Hiệu lực | `accessControlService.onPermissionsChanged()` được gọi sau khi lưu → quyền của user đổi **ngay**, không chờ cache |

Quyền hiệu lực của user = **hợp** của: role gán trực tiếp (ticked trong dialog) + role của các nhóm user
(`Customer Administrators` / `Customer Users` / nhóm thường). Dialog cảnh báo khi user là `Customer Users` mà còn
role khác cấp thêm quyền, và có nút **Remove the extra roles** để bỏ gán trực tiếp các role ngoài profile.

### Ai tạo tài khoản nào (2026-10-01)

| Caller | Tạo được | Không tạo được |
|---|---|---|
| `SYS_ADMIN` | `TENANT_ADMIN` (từ trang Tenants) + mọi user | — |
| `TENANT_ADMIN` | **customer user** (trang Users: bắt buộc chọn Owner) | `TENANT_ADMIN` → **403** `Only a system administrator may create a tenant administrator account.` |
| Customer user (có role `USER:CREATE`) | customer user của customer mình | `TENANT_ADMIN`, user của customer khác → **403** |

Trang **Users** của tenant admin vì thế tạo thẳng customer user (dialog có ô **Owner** = customer sở hữu user và
`POST /api/user` phải gửi kèm `customerId`); tài khoản quản trị tenant do system admin cấp ở trang Tenants.
`UserDataValidator` sẵn có vẫn cấm đổi `authority` của một user đã tồn tại, nên không có đường nâng quyền qua
`POST /api/user`.

### Quyền theo role của customer user

#### Hai profile của customer user (chuẩn PE) — 2026-10-01

Mỗi tenant được cấp sẵn **2 role** và **2 nhóm USER** cùng tên, nên không phải cấu hình tay:

| Profile | Nhóm (chọn trong dialog) | Role mặc định | Quyền |
|---|---|---|---|
| **Customer Administrator** | `Customer Administrators` | `Customer Administrator` | DEVICE/ASSET/ENTITY_VIEW/DASHBOARD: CREATE-READ-WRITE-DELETE; USER: CREATE-READ-WRITE-DELETE (user của customer mình); CUSTOMER: CREATE-READ-WRITE (sub-customer); ALARM: READ-WRITE; cờ `ownCustomerOnly` → quản lý được cả customer con |
| **Customer User** | `Customer Users` | `Customer User` | chỉ DEVICE/ASSET/ENTITY_VIEW/DASHBOARD/CUSTOMER: READ, ALARM: READ-WRITE (chỉ xem dữ liệu của customer mình) |

- Customer user mới **mặc định thuộc `Customer Users`** (chỉ xem). Tenant admin vào **Manage owner and groups**,
  chuyển user sang `Customer Administrators` là user có quyền tạo/sửa như PE; chuyển ngược lại là thu hồi ngay.
- Hai nhóm này **sửa được** trong dialog (khác nhóm hệ thống `All` bị khoá).
- Nhóm user cùng tên (dùng để gán role) được sinh tự động và **lấy thành viên từ nhóm entity** — dialog là nơi
  duy nhất quản lý thành viên; role của profile được gán sẵn cho nhóm đó.

Hai quy tắc đã sửa sau khi kiểm tra UI (2026-10-01):

- **Delete không bao giờ được suy ra từ Write.** Trước đây helper của UI coi role có `WRITE` là có luôn `DELETE`
  nên nút xoá **bật** trong khi backend từ chối (`Your role does not grant "Delete" on CUSTOMER.`). Nay UI và
  backend dùng cùng quy tắc: role phải liệt kê `DELETE` (hoặc `ALL`) thì nút mới bật; các operation phụ
  (`READ_ATTRIBUTES`, `WRITE_TELEMETRY`, `RPC_CALL`, …) vẫn suy ra từ `READ`/`WRITE` như trước.
- **Customer user không bao giờ xoá được customer mà nó thuộc về** (kể cả khi role có `CUSTOMER: DELETE`): backend
  chặn và UI không bật nút xoá cho dòng customer đó, tránh việc user tự xoá phạm vi (và tài khoản) của mình.
  Profile `Customer Administrator` **không** cấp `CUSTOMER: DELETE` — muốn cho xoá customer con thì tick `Delete`
  ở resource `CUSTOMER` trong role (hoặc nhờ bổ sung vào profile).
- Muốn tinh chỉnh profile: sửa role `Customer Administrator` / `Customer User` ở trang **Roles** (role là mặc định
  nhưng vẫn sửa được); muốn cấp thêm quyền cho một nhóm người: tạo role mới và gán cho nhóm thường.
- Khi đổi role/nhóm, backend **xoá cache quyền** (`AccessControlService.onPermissionsChanged`) nên hiệu lực ngay,
  không phải chờ 10 giây.

| Resource | Role cấp | Hành vi |
|---|---|---|
| `DEVICE`, `ASSET`, `ENTITY_VIEW` | `CREATE` | tạo được; backend gán `customerId` của người tạo + `rbacOwnerId` trước khi kiểm quyền |
| `DASHBOARD` | `CREATE` | tạo được; dashboard **tự động được gán cho customer của người tạo** (nếu không, dashboard thuộc tenant và user không thấy) |
| `DASHBOARD` | `READ/WRITE/DELETE` | chỉ áp dụng cho dashboard **đã được gán cho customer** của user (đúng cách ly của nền tảng) |
| `DELETE` (mọi resource) | | role phải liệt kê **Delete** tường minh — hệ thống không suy ra Delete từ Write (UI và backend dùng cùng quy tắc) |
| `USER`, `CUSTOMER` | `CREATE/READ/…` | theo phạm vi customer (xem §1) |
| cờ `Only entities created by the user` | | chỉ áp dụng cho DEVICE/ASSET/ENTITY_VIEW/USER/CUSTOMER (các entity có `additionalInfo` để lưu owner); dashboard bị bỏ qua cờ này thay vì ẩn hết danh sách |

Bản CE chỉ cho `TENANT_ADMIN` tạo device/asset/entity view, và cả WEB UI lẫn backend đều chặn customer user
**không phụ thuộc role** — vì vậy role có `DEVICE: CREATE` vẫn không thấy nút "Add" và gọi API cũng bị `403`.
Fork này mở đúng theo hợp đồng của PE:

| Tầng | Trước | Sau |
|---|---|---|
| WEB UI (devices/assets/entity views/dashboards) | `addEnabled = scopes !== 'customer_user'` (ẩn cứng) | nút Add hiện khi role cấp `CREATE`; devices mở đúng wizard, ẩn ô chọn customer vì customer do backend gán |
| `TbRbacAccessControlService` (check theo entity) | nhánh customer user chỉ mở khi `ownCustomerOnly` **và** entity đã có customerId → không bao giờ tạo được (entity mới chưa có customer) | nhánh customer user cho phép khi role cấp operation **và** entity thuộc customer của người gọi (entity mới được controller gán customer trước khi check) |
| `DeviceController` / `AssetController` / `EntityViewController` | không gán customer → customer user tạo ra entity "của tenant" | `applyCustomerScope` gán `customerId` của người tạo trước khi `checkPermission`; `rbacOwnerId` được ghi cả ở đường `saveDeviceWithCredentials` (wizard) |
| `DashboardController` | `saveDashboard` chỉ cho `TENANT_ADMIN` | cho cả `CUSTOMER_USER`: sau khi tạo, dashboard được **gán cho customer của người tạo** |
| `ownOnly` của role | không kiểm ở nhánh customer user | áp dụng như nhánh quản trị: entity không do user tạo bị từ chối |

Kiểm chứng:

```bash
/tmp/emu-venv/bin/python scripts/test-create-permissions-local.py   # 28/28 PASS
```

Script tạo customer + role `DEVICE:[CREATE,READ,WRITE,DELETE]` (`ownOnly.DEVICE = true`) → customer user tạo device
(**200**, `customerId` = customer của người tạo, `additionalInfo.rbacOwnerId` = id người tạo) → device hiện trong
`GET /api/customer/{id}/deviceInfos`; customer user **không** có role → `403`; hai nhóm hệ thống trả `system: true`
và không đổi được qua API membership; nhóm thường vẫn lưu membership bình thường. Bổ sung `DASHBOARD:CREATE`
(tạo dashboard → tự gán cho customer của người tạo → sửa lại được) và quy tắc tài khoản quản trị: tenant admin tạo
`TENANT_ADMIN` → **403**, `SYS_ADMIN` tạo → **200**.

Hai profile customer user có script riêng:

```bash
/tmp/emu-venv/bin/python scripts/test-customer-profiles-local.py   # 21/21 PASS
/tmp/emu-venv/bin/python scripts/test-user-roles-local.py          # 18/18 PASS
```

`test-user-roles-local.py` kiểm tra: tạo role tuỳ biến → `GET /api/tenant/user/{id}/roles` thấy role chưa gán và
role profile đến từ nhóm `Customer Users`; `POST` gán trực tiếp → quyền hiệu lực ngay (tạo được dashboard),
document `roles` trong DB có `userIds` đúng, user khác không bị ảnh hưởng; bỏ gán → 403 ngay; customer user gọi API
này → 403.

Script kiểm tra: tenant có sẵn role `Customer Administrator` / `Customer User` và 2 nhóm cùng tên (có `roleIds`);
user mới vào `Customer Users` → `GET /api/user/roles` = `[Customer User]`, tạo device **403**; chuyển sang
`Customer Administrators` → `[Customer Administrator]`, tạo được device + asset + entity view + dashboard (**200**)
và thêm được user của customer mình; chuyển lại `Customer Users` → tạo device **403** ngay lập tức.

### Phía WEB UI (3 guard bổ sung)

| Guard | Vấn đề | Cách sửa |
|---|---|---|
| Nạp quyền khi boot | `AppComponent.setupAuth()` gọi `loadUserRoles()` trong subscription có `skip(1)`, mà lần boot (F5 với token còn hạn) chỉ phát một lần `isUserLoaded = true` → **quyền không bao giờ được nạp**, UI dùng quyền nền tảng (thấy nút không có quyền, menu không ẩn) | thêm subscription riêng cho `loadUserRoles()` / `setRbacPermissions(null)` theo `selectUserReady` |
| `hasExplicitRbacPermission` | `hasRbacPermission` fallback về "không có role ⇒ giữ quyền nền tảng", nhưng nền tảng **cấm** customer user tạo device/user/customer ⇒ nút vẫn hiện rồi `403` | helper mới yêu cầu role cấp tường minh, dùng cho các nút Add ở scope `customer_user` (devices, assets, entity views, users, customers); scope tenant admin vẫn dùng `hasRbacPermission` |
| Danh sách "own entities" | `ownerIndexCache` có TTL 60s và không ai xoá → device vừa tạo **không hiện** trong danh sách tới 1 phút | `AccessControlService.onEntityCreated(user)` (mặc định no-op) được gọi sau khi tạo device/asset/entity view, `TbRbacAccessControlService` xoá cache owner của tenant |
| Wizard device | ô "Assign to customer" hiện cho cả customer user (không cần thiết vì backend tự gán) | `DeviceWizardDialogData.hideCustomerField`, resolver bật khi scope là `customer_user` |
| Kiểm tra kết nối sau khi thêm | hộp thoại đọc device credentials nên role chỉ có `DEVICE:CREATE` nhận lỗi `Failed to fetch data!` ngay sau khi tạo thành công | chỉ mở hộp thoại khi role có `READ_CREDENTIALS`, còn lại chỉ refresh danh sách |

Kiểm chứng UI (Chrome headless + CDP, `ng serve` local): customer user **không** role → 0 nút Add; có role
`DEVICE:CREATE` → nút "Add new device" (wizard mở, **không** có ô "Assign to customer"); tenant admin → nút "Add"
như cũ; tạo device trong wizard → hộp thoại đóng, device hiện ngay trong bảng.
