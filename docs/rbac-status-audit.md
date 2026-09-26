# RBAC — trạng thái thực tế & checklist audit production

Cập nhật: 2026-09-26. Liên quan: [access-control-roadmap.md](access-control-roadmap.md) §8.

## 1. Đã xong (đã build + test local)

| Bước | Nội dung | Bằng chứng |
|---|---|---|
| 1 | Backend **ma trận quyền chi tiết**: `resolveOperation()` trong `TbRbacAccessControlService` — role có operation chi tiết thì kiểm tra đúng operation (hoặc `ALL`); role chỉ có `READ/WRITE/DELETE` giữ hành vi suy diễn cũ; operation không được cấp → chặn và **danh sách trả rỗng** | `mvn -pl application,!ui-ngx -am install` → BUILD SUCCESS (commit `d7f093a78b`) |
| 2 | UI Roles: mỗi entity type có danh sách operation riêng (DEVICE gồm `Create/Read/Write/Delete`, `Read/Write telemetry`, `Read/Write attributes`, `Read/Write credentials`, `RPC call`, `Claim devices`, `Assign to customer`), nhãn dễ đọc, **preset Viewer/Operator/Manager/Admin**, nút `All`/`None`, badge tóm tắt | `ng build --configuration production` → OK (commit `0928afb18b`) |

## 2. CHƯA xong (điều kiện để gọi là “full quyền User”)

| Bước | Việc còn thiếu | Điểm cần sửa (production) |
|---|---|---|
| 3 | **Ownership cho DEVICE**: user có `Create` → thiết bị gắn `rbacOwnerId`; role có cờ “Chỉ thiết bị của tôi” → chỉ thấy/sửa/xoá thiết bị của mình; cột **Owner** ở danh sách Devices | `DeviceController.saveDevice` (gán owner khi tạo), `TbRbacAccessControlService` (check owner + lọc danh sách), `device.component.html` (cột Owner) |
| 4 | **Ownership cho ASSET / ENTITY_VIEW / DASHBOARD** (cùng cơ chế) | tương tự entity controller + service tương ứng |
| 5 | **Share**: `rbacSharedWith` = `[{type: USER\|USER_GROUP, id, operations[]}]`, UI Share (chọn user/user-group, 3 mức **Xem / Điều khiển / Toàn quyền**, thu hồi), kiểm tra share trước khi chặn RPC/ghi telemetry | service RBAC + dialog Share cho DEVICE/ASSET/ENTITY_VIEW/DASHBOARD |
| 6 | **Test local đầy đủ**: userA tạo thiết bị → userB không thấy; share mức Xem → xem được nhưng RPC 403; nâng lên Điều khiển → RPC được; thu hồi → mất quyền; role cũ `NM1` không đổi hành vi | `scripts/test-rbac-full-local.py` (sẽ thêm) |

## 3. Checklist audit production (chạy sau khi xong bước 3–6)

### 3.1 Bảo mật & phân quyền

- [ ] Mọi endpoint entity (device/asset/entity view/dashboard) đều qua `accessControlService.checkPermission` với `Resource` + `Operation` đúng (không chỉ `READ`).
- [ ] Kiểm tra `CREATE` vs `WRITE` đúng: tạo mới cần `CREATE`, sửa cần `WRITE` (TB dùng 2 operation khác nhau).
- [ ] `RPC_CALL` được kiểm tra riêng ở API RPC (`RpcV2Controller`/`AbstractRpcController`) — user chỉ có `Read` phải bị 403 khi gửi RPC.
- [ ] `READ_CREDENTIALS`/`WRITE_CREDENTIALS` được kiểm tra ở API credentials (xem/sửa access token).
- [ ] `READ_TELEMETRY`/`WRITE_TELEMETRY`, `READ_ATTRIBUTES`/`WRITE_ATTRIBUTES` được kiểm tra ở API telemetry/attributes (không chỉ chặn ở UI).
- [ ] `ASSIGN_TO_CUSTOMER`, `CLAIM_DEVICES` được kiểm tra đúng ở API assign/claim.
- [ ] `getAllowedEntityIds` trả rỗng khi không có quyền (không rò dữ liệu qua API list/exports/telemetry query).
- [ ] Owner/share chỉ áp dụng trong cùng tenant; SYS_ADMIN bypass; CUSTOMER_USER vẫn theo luật customer của CE.
- [ ] Không tin dữ liệu client: `rbacOwnerId`/`rbacSharedWith` chỉ backend ghi (server-scope attribute), client sửa sẽ bị ghi đè.
- [ ] Role JSON cũ (READ/WRITE/DELETE) chạy lại toàn bộ test RBAC hiện có → hành vi không đổi.

### 3.2 Hiệu năng & vận hành

- [ ] Lọc theo owner/share dùng chung cơ chế lọc theo group hiện tại; đo lại giới hạn 1000 entity/lần với 1.000-10.000 thiết bị.
- [ ] Cache role (TTL 10s) vẫn hoạt động sau khi thêm owner/share; không tăng số lần đọc DB mỗi request.
- [ ] Log/audit: ghi log khi share/thu hồi share và khi bị chặn (kèm userId, entityId, operation) để truy vết.
- [ ] Migration: tenant đang chạy role cũ không cần thao tác gì; nếu đổi role sang chi tiết thì có cảnh báo trong UI.

### 3.3 Test tự động

- [ ] Unit test cho `resolveOperation()` (legacy / explicit / ALL / thiếu operation).
- [ ] API test cho ma trận quyền theo preset (Viewer/Operator/Manager/Admin) trên DEVICE.
- [ ] API test ownership + share (case §2 bước 6).
- [ ] `mvn license:check` pass cho mọi file mới; UI build production pass.

## 4. Rủi ro đã biết

1. **Ownership không có cột DB**: dùng server attribute ⇒ lọc danh sách phải ở tầng service (giới hạn 1000 entity/lần). Khi tenant > vài nghìn thiết bị cần chuyển sang query theo owner (Phase sau).
2. **Dashboard** không có attribute như DEVICE/ASSET ⇒ lưu owner trong `additionalInfo`; cần chặn client sửa trường này ở API dashboard.
3. **Role chi tiết làm chặt hơn role cũ**: khi tenant chuyển role sang ma trận chi tiết, phải tick đủ `Create`/`RPC_CALL`… nếu không user sẽ bị 403 (UI sẽ có preset + cảnh báo để tránh nhầm).
