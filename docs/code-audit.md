# Audit code tự thêm so với ThingsBoard CE

Tài liệu này là kết quả audit toàn bộ phần code đã thêm/sửa trong fork so với ThingsBoard CE gốc
(`origin/master`, base commit `3150f863b7`). Mục đích: đảm bảo phần mở rộng an toàn khi đưa lên bản thương mại,
giữ được license của ThingsBoard và dễ tháo gỡ khi cần.

## 1. Phạm vi

| Nhóm | Số file | Ghi chú |
|---|---|---|
| Backend Java (thêm mới) | 40 | controller, service, model cho RBAC, groups, white labeling, mail template, custom menu/translation |
| Backend Java (sửa) | 12 | chỉ thêm tham số `tenantId` cho mail service, thêm `findDomainByName`, bổ sung nhánh mặc định trong interface |
| Frontend Angular (thêm mới) | 45 | service, model, component, dialog, scss |
| Frontend Angular (sửa) | 25 | menu, title, logo, entity table, admin routing/module, locale, environment |
| Docker/docs | 11 | `docker/tb-custom/*`, `.dockerignore`, `docs/*` |
| **Database** | **0** | RBAC và white labeling lưu trong `admin_settings` (JSON) — không đổi schema |

Tổng: 141 file, ~6.2k dòng thêm.

## 2. Kiểm tra bắt buộc (license & bản quyền)

| Hạng mục | Kết quả | Bằng chứng |
|---|---|---|
| Header SPDX 2 dòng của ThingsBoard trong mọi file Java/TS/SCSS/HTML thêm mới | ✅ giữ nguyên | kiểm tra 2 dòng đầu của toàn bộ file `*.java` mới trong `common/data/.../rbac`, `dao/.../settings`, `application/.../controller` |
| Header trong file gốc bị sửa (`index.html`, `entities-table.component.html`, template mail `.ftl`) | ✅ không đổi | `git diff` chỉ thay nội dung bên trong, khối comment SPDX còn nguyên |
| `LICENSE` (Apache-2.0) | ✅ còn nguyên, không bị sửa | `git status` |
| Nội dung "Copyright © ... The ThingsBoard Authors / Licensed under the Apache License, Version 2.0" | ✅ giữ nguyên | `ui-ngx/src/index.html`, `pom.xml`, README |
| `pom.xml` chỉ thêm 1 dòng loại trừ license-check cho `Dockerfile` | ✅ chấp nhận được | `pom.xml:947` |

## 3. Thiết kế tổng thể

```text
AdminSettings (JSON, 1 bản ghi / tenant)
  whiteLabeling      -> WhiteLabelingSettings
  mailTemplates      -> MailTemplateSettings
  roles              -> RbacRoleSettings
  userGroups         -> RbacUserGroupSettings
  entityGroups       -> RbacEntityGroupSettings
  customerHierarchy  -> RbacCustomerHierarchy
  customMenu         -> CustomMenuSettings
  customTranslation  -> Map<locale, Map<key,value>>
```

- Không thêm bảng/cột nào → nâng cấp ThingsBoard CE bản mới không bị conflict schema.
- Toàn bộ service tenant-scoped dùng `findAdminSettingsByTenantIdAndKey` (không dùng
  `findAdminSettingsByKey` — hàm này bỏ qua `tenantId` và luôn đọc của hệ thống).
- RBAC nằm sau cờ `security.rbac.enabled`; khi tắt, bean `TbRbacAccessControlService` không được tạo và
  hành vi quay về đúng CE.

## 4. Phát hiện và xử lý

### CRITICAL

| # | Vấn đề | Bằng chứng | Trạng thái |
|---|---|---|---|
| C1 | Role có `ownCustomerOnly` **bỏ qua hoàn toàn** bảng quyền: chỉ cần `ownCustomerOnly=true` là mọi operation trên entity thuộc customer/sub-customer đều được phép, dù role không khai báo permission nào. Với tenant admin, cùng nhánh code đó còn khiến **mọi** kiểm tra entity bị từ chối (không có `customerId`). | code cũ: `if (role.isOwnCustomerOnly()) { return userBelongsToCustomerSubtree(...); }` | ✅ đã sửa — `hasCustomerUserPermission()` yêu cầu role phải cấp operation (`hasOperationGrant`) rồi mới xét phạm vi customer/sub-customer |
| C2 | Role có quyền global (`DEVICE: READ`) **phá vỡ cô lập customer của CE**: `hasGlobalOperation(...) return true` khiến customer user đọc được thiết bị của customer khác trong cùng tenant. | CE enforce cô lập tại `CustomerUserPermissions` (`user.getCustomerId().equals(entity.getCustomerId())`) nhưng nhánh RBAC cũ return sớm trước khi gọi kiểm tra đó | ✅ đã sửa — customer user luôn đi qua `hasCustomerUserPermission()`: quyền CE (own customer) được giữ, role chỉ có thể mở rộng trong phạm vi subtree khi bật `ownCustomerOnly` |
| C3 | Mail template do tenant admin nhập được biên dịch bằng FreeMarker với cấu hình mặc định của Spring → SSTI, có thể dẫn tới thực thi lệnh trên server (`${"freemarker.template.utility.Execute"?new()("id")}`). | `DefaultMailService.mergeTemplateIntoString`: `new Template(location, new StringReader(customBody), freemarkerConfig)` | ✅ đã sửa — dùng `Configuration` riêng với `TemplateClassResolver.ALLOWS_NOTHING_RESOLVER` + `setAPIBuiltinEnabled(false)`, thêm giới hạn 256 KB cho body |

### HIGH

| # | Vấn đề | Bằng chứng | Trạng thái |
|---|---|---|---|
| H1 | `/api/user/roles` chỉ trả role gán trực tiếp, không gồm role kế thừa từ user group → menu chặn/nhả sai so với quyền thật ở server. | `RoleController.getCurrentUserRoles` (code cũ) tự lọc `role.getUserIds()` | ✅ đã sửa — dùng chung `RoleService.getEffectiveRole(s)` (một nguồn sự thật) |
| H2 | Mọi lần kiểm tra quyền đều đọc & parse JSON `admin_settings` (roles, userGroups, entityGroups) → thêm 2–3 truy vấn DB cho mỗi request API khi bật RBAC. | `getEffectiveRole`, `entityBelongsToGroups` (code cũ) | ✅ đã sửa — cache TTL 10s theo user/tenant, tự dọn entry hết hạn (đặt `CACHE_TTL_MS = 0` để tắt) |
| H4 | Không có test tự động nào cho phần mới (RBAC, groups, white labeling, mail template). | `git diff --name-status` không có file trong `*/src/test/*` | ⏳ còn lại — nên bổ sung test trước khi phát hành |
| H5 | Build test của module `application` đang fail (không liên quan phần mới): `NotificationApiClientTest` gọi `client.markAllNotificationsAsRead(null)` bị ambiguous giữa bản `Args` và bản `String` do client sinh tự động. | log build `#16 [ERROR] ... reference to markAllNotificationsAsRead is ambiguous` | ⏳ còn lại — dùng `-Dmaven.test.skip=true` cho tới khi sửa; sửa test bằng cách truyền tham số cụ thể thay vì `null` |

### MEDIUM

| # | Vấn đề | Trạng thái |
|---|---|---|
| M1 | `advancedCss` của tenant được chèn qua `style.textContent` (an toàn với XSS) nhưng CSS có thể gọi `url()` ra ngoài để rò rỉ thông tin phiên của người dùng tenant đó. | ⏳ chấp nhận như PE; khuyến nghị thêm CSP cho trang login |
| M2 | Quyền theo entity group không dùng được cho các API danh sách (`checkPermission` không có entity) → user chỉ có quyền theo group sẽ bị 403 khi mở trang Devices/Assets. Cần lọc theo group ở tầng query (như PE) nếu muốn dùng thực tế. | ⏳ còn lại (đã ghi trong `docs/access-control-roadmap.md`) |
| M3 | Nhiều `subscribe` trong component không huỷ (`LogoComponent`, `GithubBadgeComponent`, `AppComponent.setupWhiteLabeling`) vì `settings$` là `ReplaySubject` sống lâu → giữ reference component đã destroy. | ⏳ còn lại — nên dùng `takeUntil(this.destroy$)` |
| M4 | Menu tuỳ biến với `type=url` dùng `routerLink` nên URL ngoài (`https://...`) không mở tab mới mà bị router xử lý như route nội bộ. | ⏳ còn lại — nên render anchor `target="_blank"` cho item loại url |
| M5 | `list users` trong trang Roles cố định `pageSize=100` → tenant >100 user không gán được role cho user còn lại. | ⏳ còn lại — dùng phân trang/autocomplete của CE |
| M6 | Ghi `admin_settings` là "last write wins" (không version) và id group do client sinh → 2 admin sửa cùng lúc sẽ ghi đè nhau. | ⏳ chấp nhận ở giai đoạn này; nên thêm `version` nếu nhiều admin |
| M7 | 4 tuỳ chọn trong tab General **lưu được nhưng không có tác dụng gì** (không có nơi nào đọc): `hideConnectivityDialog`, `hideChatBot`, `showPlatformNameVersion`, `overrideTrendzName` + `trendzName`. | ⏳ còn lại — hoặc hiện thực, hoặc xoá khỏi form + `WhiteLabelingSettings` để không gây hiểu nhầm |
| M8 | `dashboard-page.component.html`: footer "Powered by **Green IQ** v.…" nhưng link vẫn trỏ `https://thingsboard.io` (vừa đổi tên vừa trỏ về nhà cung cấp gốc, dễ gây nhầm). | ⏳ còn lại — nên để white labeling điều khiển hoặc giữ nguyên "Powered by ThingsBoard" |
| M9 | Domain: bảng `domain` **đã có** unique constraint trên `name`, nên không thể trùng tên. Vấn đề thật là: (a) tenant admin đăng ký domain đã dùng → trước đây trả 500 khó hiểu (lỗi ràng buộc DB) thay vì 400; (b) không kiểm tra tenant sở hữu. | ✅ đã sửa — `TenantDomainController.saveTenantDomain` chặn sớm với 400 + `findFirstByName` |
| M10 | `DomainEntity.propagateToEdge` là `Boolean` (cột `edge_enabled`) và `DomainEntity.toData()` unbox trực tiếp → nếu một bản ghi domain có `edge_enabled = NULL` (dữ liệu tạo tay/bản cũ) thì `findDomainByName` ném NPE; endpoint công khai `/api/noauth/whiteLabeling` gọi hàm này. | ⏳ còn lại (lỗi có sẵn của CE, không do phần mới) — client đã có `catchError` nên login page không vỡ, chỉ mất branding |

### LOW

| # | Vấn đề | Trạng thái |
|---|---|---|
| L1 | Component `entity-groups-page.component.*` (cách làm cũ bọc ngoài bảng) không còn dùng. | ✅ đã xoá 3 file + import/declaration |
| L2 | `entityType` trong `data` của 3 route (device/asset/entity-view) không còn ai đọc. | ✅ đã xoá |
| L3 | `groupsEntityType()` dùng `as any` dù `EntityTableConfig.entityType` đã có kiểu. | ✅ đã sửa dùng `EntityType` |
| L4 | Cột "Created time" của group hiển thị số epoch thô, và model không lưu `createdTime`. | ✅ đã sửa — thêm `RbacEntityGroup.createdTime` + pipe `date` |
| L5 | Xoá entity group không có xác nhận. | ✅ đã sửa — thêm dialog xác nhận (`DialogService`) |
| L6 | Nhiều nhãn tiếng Anh hard-code trong `entity-groups.component.html`. | ✅ đã sửa — dùng khoá i18n `entity-group.*` |
| L7 | `docker/tb-custom/docker-compose.yml` là stack dev: mật khẩu `postgres/postgres`, publish nhiều cổng, `SECURITY_RBAC_ENABLED: "true"`, không có TLS/healthcheck cho `tb-node`. | ⏳ còn lại — trước khi lên production cần bản compose riêng (TLS, secret, healthcheck, backup) |
| L8 | `.dockerignore` chưa loại `.git`, `docs`, `.idea` → build context lớn hơn cần thiết (không ảnh hưởng image cuối vì dùng multi-stage). | ⏳ còn lại |
| L9 | `$primary-hue-3` trong `scss/constants.scss` bị đổi thành trắng (ảnh hưởng theme toàn hệ thống, không chỉ white labeling). | ⏳ nên đưa về biến mặc định của CE, phần đổi màu để white labeling lo |
| L10 | `/api/tenant/whiteLabeling` (POST) không gọi `accessControlService.checkPermission(ADMIN_SETTINGS, WRITE)` như các endpoint settings khác (vẫn có `@PreAuthorize('TENANT_ADMIN')`). | ⏳ còn lại — thêm cho nhất quán, trừ khi muốn tenant admin bị giới hạn role vẫn đổi được branding |

### Lỗi biên dịch bắt được trong lúc build

| Vấn đề | Trạng thái |
|---|---|
| `DefaultMailService`: gán giá trị cho `private final Configuration customTemplateConfig` trong `@PostConstruct` → `cannot assign a value to final variable` (Lombok đưa field final vào constructor). | ✅ đã sửa — bỏ `final`, field được khởi tạo trong `init()` |
| `TbRbacAccessControlService` (viết lại): kiểm tra bằng `javap` trên `freemarker-2.3.34.jar` rằng `Configuration.getIncompatibleImprovements()` tồn tại trước khi dùng. | ✅ |

## 5. Đánh giá khả năng tháo gỡ

| Thành phần | Cách tháo |
|---|---|
| RBAC | đặt `security.rbac.enabled=false` → bean không được tạo, hoặc xoá `TbRbacAccessControlService` + `controller/RoleController` + `dao/settings/*Role*` + `common/data/.../rbac` + menu `roles` |
| White labeling | xoá `WhiteLabelingController`, `WhiteLabelingService`, `WhiteLabelingSettings`, component `white-labeling.*`, các key `whiteLabeling` trong `admin_settings` |
| Mail templates | xoá `MailTemplateController/Service/Settings`; `MailService` vẫn chạy với template FTL mặc định (các overload mặc định trỏ về `SYS_TENANT_ID`) |
| Groups | xoá `EntityGroup*`, component `entity-groups.*`, tab trong `entities-table` và route `/entities/groups` |
| Custom menu/translation | xoá 2 controller + 2 service + model + component + `title.service`/`menu.models` hook |

Điểm cần lưu ý: các file CE bị sửa đều ở dạng "thêm nhánh mặc định", nên khi merge upstream chỉ cần giữ
nhánh mặc định đó.

## 6. Việc còn lại trước khi thương mại hoá

1. Bổ sung test (H4): RBAC (unit cho `TbRbacAccessControlService`), API test cho groups/white labeling.
2. Sửa build test `application` (H5) để CI chạy được `mvn install -DskipTests`.
3. Lọc dữ liệu theo entity group ở tầng query (M2) nếu muốn role theo group dùng được trên UI danh sách.
4. Compose production: TLS, secret, healthcheck, backup Postgres, `SECURITY_RBAC_ENABLED` khai báo tường minh.
5. Trả `$primary-hue-3` về mặc định CE (L9) và chỉnh màu qua white labeling.
6. Hỗ trợ URL ngoài cho custom menu (M4) và xử lý 4 tuỳ chọn white labeling chưa có tác dụng (M7).

## 7. Trạng thái sau khi sửa

| Việc | Trạng thái |
|---|---|
| C1, C2 (RBAC cô lập customer), C3 (FreeMarker SSTI) | đã sửa trong code, đã build |
| H1 (effective roles), H2 (cache), H3 (domain trùng) | đã sửa trong code, đã build |
| M3 (subscription leak ở `help`, `logo`, `github-badge`, `home`) | đã sửa — dùng `takeUntilDestroyed`/`takeUntil` sẵn có |
| L1–L6 (dead code, i18n, ngày tạo group, xác nhận xoá) | đã sửa |

## 8. Bằng chứng kiểm chứng trên môi trường local (image `sha256:aacb8fb09c3e`)

Kiểm chứng bằng API thật trên stack `docker/tb-custom` sau khi recreate container (digest image khớp bản build mới):

| # | Kịch bản | Kết quả |
|---|---|---|
| C1 | Role `{}` (không permission) + `ownCustomerOnly=true`, user customer gọi `GET /api/device/{id}` (device thuộc sub-customer) | **403** (trước đây sẽ là 200) |
| C2 | Role `DEVICE: READ` + `ownCustomerOnly=false`, user customer gọi `GET /api/device/{id}` | device cùng customer → **200**; device không thuộc customer nào (`GW1`) → **403**; device của customer khác (`child-device`) → **403** (trước đây 2 trường hợp sau là 200 — rò rỉ dữ liệu giữa các customer) |
| H1 | Role không gán trực tiếp, chỉ gán qua user group; user customer gọi `GET /api/user/roles` | trả **1 role** (lấy từ user group) và quyền được enforce: device cùng customer → 200, `GW1` → 403 (trước đây `/api/user/roles` trả rỗng) |
| H3 | Tenant admin đăng ký domain đã thuộc tenant khác | **400** `Domain name is already registered by another tenant` (trước đây 500) |
| C3 | Đặt `reset.password.ftl` = `${"freemarker.template.utility.Execute"?new()("id")}` rồi trigger `POST /api/noauth/resetPasswordByEmail` | log: `Failed to process mail template: ... Instantiating freemarker.template.utility.Execute is not allowed in the template for security reasons.` → payload bị chặn |
| Sub-customer | Khôi phục role gốc (`DEVICE: READ`, `ownCustomerOnly=true`) | device của sub-customer (`Child Co`) → **200**; `GW1` → **403** → tính năng "own customer + sub-customers" vẫn hoạt động sau khi siết bảo mật |

Dữ liệu test (device `parent-device`, 2 domain test, mail template độc hại, user group test) đã được khôi phục/xoá sau khi kiểm chứng.

## 9. Việc cần làm ngay khi sang bản thương mại

1. Bật `SECURITY_RBAC_ENABLED` tường minh trong compose production (đừng phụ thuộc giá trị trong file dev).
2. Bổ sung test tự động cho RBAC (H4) — đây là phần dễ gây hồi quy nhất khi merge upstream.
3. Xử lý 4 tuỳ chọn white labeling chưa có tác dụng (M7) trước khi bán cho khách.
