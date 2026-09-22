# White labeling — kế hoạch hoàn thiện theo chuẩn ThingsBoard PE

Mục tiêu: bổ sung 4 tab còn thiếu để ngang tầm PE, theo thứ tự ưu tiên dưới đây.
Nguyên tắc chung: lưu cấu hình trong `AdminSettings` theo tenant, giữ diff nhỏ, không sửa cấu trúc
menu/route core nhiều hơn cần thiết, mọi file mới phải có header SPDX.

## Trạng thái hiện tại

- Tab **General**: đã có — Application title, Website icon, Logo (light/dark), Logo height, Primary/Accent
  palette (14 palette dựng sẵn + Customize), Advanced CSS, Hide help links, Hide vendor promotion.
- Cấp cấu hình: system (SYS_ADMIN) và tenant (TENANT_ADMIN), tenant override system.
- Đã áp dụng: title, favicon, logo, màu Material token, email (appTitle + ảnh header), ẩn GitHub badge/help/PE upsell.

## Tab 1 — Login (branding theo domain)

Mục tiêu: trang login của từng khách hàng hiển thị branding riêng khi truy cập bằng domain của họ.

- Dữ liệu: dùng entity `Domain` sẵn có của CE (`DomainController`, `DomainService`) để map host → tenant;
  cấu hình branding cho login lưu thêm trong `AdminSettings(tenant, "whiteLabeling")` với các trường
  `loginTitle`, `loginSubtitle`, `loginBackgroundUrl`.
- Backend: `GET /api/noauth/whiteLabeling` nhận `HttpServletRequest`, resolve host → tenant id
  (`DomainService.findByDomain`) → trả branding của tenant đó, fallback về branding cấp system.
  Thêm endpoint `POST /api/tenant/whiteLabeling/domain` để gán domain cho tenant.
- Frontend: thêm tab "Login" trong trang White labeling: hiển thị domain hiện tại (nút Create new), base URL,
  checkbox "Prohibit to use hostname from client request headers", Application title, website icon, logo, logo height
  — tái sử dụng các control đã có ở tab General.
- Rủi ro: cần cẩn thận cache theo host (không cache toàn cục) và xử lý nhiều domain / domain chưa cấu hình.

## Tab 2 — Mail templates

Mục tiêu: mỗi tenant tự sửa subject/body của từng template email; có nút "Use system mail templates".

- Dữ liệu: `AdminSettings(tenant, "mailTemplates")` =
  `{ useSystemMailTemplates: boolean, templates: { <templateKey>: { subject, body } } }`.
- Backend: `DefaultMailService.mergeTemplateIntoString(...)` ưu tiên template override của tenant
  (render từ DB) trước khi dùng `*.ftl` mặc định; subject lấy theo override nếu có.
  Thêm API `GET/POST /api/tenant/mailTemplates` (TENANT_ADMIN) và
  `GET/POST /api/admin/mailTemplates` (SYS_ADMIN).
- Frontend: tab "Mail templates" với dropdown chọn template, ô "Mail subject", editor body (dùng Ace editor có sẵn
  trong CE), nút "Use system mail templates" để reset về mặc định của hệ thống.

## Tab 3 — Custom translation

Mục tiêu: tenant override chuỗi dịch của từng ngôn ngữ.

- Dữ liệu: `AdminSettings(tenant, "customTranslation")` = `{ <locale>: { <key>: <value> } }`.
- Backend: `GET /api/tenant/customTranslation/{locale}` trả map override (gộp theo tenant hiện tại);
  `POST` để lưu; `DELETE` để xoá một locale.
- Frontend: tab "Custom translation" hiển thị bảng ngôn ngữ (cờ, tên, % hoàn thành) + tải file ngôn ngữ,
  sửa từng key; khi app khởi động, `TranslateService` nạp thêm bundle override theo `lang` của user.
- Rủi ro: bundle của CE khá lớn (28 ngôn ngữ) nên chỉ nạp phần override của tenant đang đăng nhập.

## Tab 4 — Custom menu

Mục tiêu: tenant tự thêm mục menu (trỏ tới dashboard hoặc URL) và gán cho vai trò.

- Dữ liệu: `AdminSettings(tenant, "customMenu")` = danh sách
  `{ id, name, icon, type (dashboard|url), target, assigneeType (TENANT_ADMIN|CUSTOMER_USER|...), order }`
  (có thể tách thành entity riêng nếu cần phân quyền chi tiết hơn).
- Backend: CRUD `/api/tenant/customMenu`.
- Frontend: tab "Custom menu" (bảng + dialog Add custom menu như PE); `buildUserMenu()` ghép thêm mục custom
  sau khi dựng menu mặc định, lọc theo `assigneeType` của user hiện tại.

## Thứ tự thực hiện đề xuất

1. Login (domain) — mở khoá branding riêng cho từng khách hàng ở trang login.
2. Mail templates — hoàn thiện trải nghiệm email theo tenant.
3. Custom translation — cần thiết khi triển khai đa ngôn ngữ cho khách hàng.
4. Custom menu — phần "nice to have", phụ thuộc mô hình dashboard của từng khách hàng.

Mỗi tab đều đi theo cùng một quy trình: code backend + frontend → build image → `up -d --force-recreate`
→ verify trực tiếp trên UI đang chạy local.
