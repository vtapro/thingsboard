# White labeling

Tính năng cho phép thay branding mặc định của ThingsBoard bằng branding của sản phẩm, ở **2 cấp**:

- **System level**: áp dụng toàn hệ thống, dùng cho cả trang login (khi chưa biết người dùng thuộc tenant nào).
- **Tenant level**: mỗi tenant tự cấu hình branding riêng, override system level cho người dùng đã đăng nhập.

## 1. Mô hình dữ liệu

Cấu hình được lưu dưới dạng JSON trong `AdminSettings` với key `whiteLabeling`:

- System: `AdminSettings(tenantId = SYS_TENANT_ID, key = "whiteLabeling")`
- Tenant: `AdminSettings(tenantId = <tenant>, key = "whiteLabeling")`

Quy tắc fallback: tenant chưa có bản ghi → dùng cấu hình system; tenant đã lưu (kể cả `enabled=false`)
→ tôn trọng cấu hình của tenant.

## 2. API

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/noauth/whiteLabeling` | công khai | Branding cấp system (dùng cho trang login) |
| GET | `/api/whiteLabeling` | đã đăng nhập | Branding của tenant hiện tại, fallback về system |
| GET | `/api/admin/whiteLabeling` | SYS_ADMIN | Đọc branding cấp system |
| POST | `/api/admin/whiteLabeling` | SYS_ADMIN | Ghi branding cấp system |
| POST | `/api/tenant/whiteLabeling` | TENANT_ADMIN | Ghi branding của tenant |

Ví dụ lưu cấu hình (cần header `X-Authorization: Bearer <token>`):

```json
{
  "enabled": true,
  "appTitle": "GEIQ IoT",
  "logoImageUrl": "/api/images/public/<public-resource-key>",
  "logoImageUrlDark": "/assets/logo_title_white.svg",
  "faviconUrl": "/favicon.ico",
  "primaryColor": "#0B7285",
  "accentColor": "#FF6B35",
  "hideHelpLinks": true,
  "hideVendorPromotion": true
}
```

## 3. Các file chính

Backend:

| File | Vai trò |
|---|---|
| `common/data/.../whiteLabeling/WhiteLabelingSettings.java` | DTO cấu hình |
| `dao/.../settings/WhiteLabelingService.java` + `DefaultWhiteLabelingService.java` | Đọc/ghi theo tenant, có fallback |
| `application/.../controller/WhiteLabelingController.java` | Các endpoint ở bảng trên |
| `application/.../service/mail/DefaultMailService.java` | Bơm `appTitle`, `emailHeaderImageUrl` vào model FreeMarker của email |

Frontend:

| File | Vai trò |
|---|---|
| `ui-ngx/src/app/shared/models/white-labeling.models.ts` | Model + giá trị mặc định |
| `ui-ngx/src/app/core/http/white-labeling.service.ts` | Gọi API, cache trong `settings$`, sinh `<style>` override token màu Material |
| `ui-ngx/src/app/modules/home/pages/admin/white-labeling.component.*` | Trang cấu hình (Settings → White labeling) |
| `ui-ngx/src/app/core/services/title.service.ts` | Áp `appTitle` vào tiêu đề tab/breadcrumb |
| `ui-ngx/src/app/shared/components/logo.component.ts` | Áp logo (light/dark) |
| `ui-ngx/src/app/app.component.ts` | Áp favicon, nạp branding tenant sau khi đăng nhập |

## 4. Phạm vi áp dụng hiện tại

- Tiêu đề trình duyệt + breadcrumb, logo navbar và logo trang login, favicon.
- Màu: override các token Material (`--mat-sys-primary`, `--mdc-*`) theo `primaryColor`/`accentColor`.
- Email: tên ứng dụng (`appTitle`) và ảnh header (`emailHeaderImageUrl` lấy từ logo), với giá trị mặc
  định giữ nguyên hành vi upstream khi branding tắt.
- Ẩn thành phần vendor: huy hiệu GitHub, nút help, nút "Switch to PE" trong dashboard trang chủ sysadmin.

## 5. Các tab cấu hình (đã đủ 5 tab như ThingsBoard PE)

| Tab | Nội dung | File chính |
|---|---|---|
| General | title, logo light/dark, favicon, palette (14 preset + Customize), Advanced CSS, ẩn help/vendor | `general-settings.component.*` |
| Login | branding theo domain (host → tenant), title/subtitle/background, icon | `login-settings.component.*`, `WhiteLabelingController` |
| Mail templates | 8 luồng email theo tenant, WYSIWYG; FreeMarker đã siết bảo mật (SSTI) | `mail-templates.component.*`, `DefaultMailService` |
| Custom translation | override bản dịch theo locale, áp khi đổi ngôn ngữ | `custom-translation.component.*` |
| Custom menu | ghép sidebar theo assignee type, URL ngoài mở tab mới | `custom-menu.component.*`, `CustomMenuController` |

Các mục từng nằm trong roadmap (logo height, palette picker, Advanced CSS, tab Login/Mail/Translation/Menu)
nay đã hoàn thành.

## 6. Lưu ý

- Trang login luôn dùng branding cấp system, vì chưa xác định được tenant. Muốn branding theo tenant ở
  trang login cần cơ chế domain → tenant (CE đã có entity/port cho `Domain`).
- Nếu dùng logo dạng đường dẫn tương đối trong email, cần đổi sang URL tuyệt đối hoặc ảnh public
  (`/api/images/public/<key>`) thì mới hiển thị được trong hộp thư.
