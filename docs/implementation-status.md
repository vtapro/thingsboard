# Trạng thái triển khai các tính năng (cập nhật theo từng mốc)

Tài liệu này ghi lại chính xác tính năng nào đã xong, đang dở, và bước tiếp theo cụ thể —
để có thể tiếp tục công việc mà không cần đọc lại toàn bộ lịch sử.

## 1. Bảng trạng thái

| # | Tính năng | Backend | Frontend | Đã deploy | Commit |
|---|---|---|---|---|---|
| 1 | White labeling (system + tenant, PE-like UI) | ✅ | ✅ | ✅ | `1b9964c8a7` |
| 2 | Mail templates theo tenant | ✅ | ✅ (tab UI dùng textarea) | ✅ | `60b7af5e0c`, `c40f59d47c`, `4e5304dda4` |
| 3 | Custom translation | ✅ API | ⏳ còn tab UI | ✅ backend | `e846b22424`, `306c5c0bfa` |
| 4 | Custom menu | ❌ | ❌ | ❌ | — |
| 5 | Login branding theo domain | ❌ | ❌ | ❌ | — |
| 6 | RBAC + Groups (PE) | ❌ | ❌ | ❌ | — |

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
