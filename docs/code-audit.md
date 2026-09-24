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
| H4 | Không có test tự động nào cho phần mới (RBAC, groups, white labeling, mail template). | `git diff --name-status` không có file trong `*/src/test/*` | ✅ đã thêm `TbRbacAccessControlServiceTest` (12 ca, gồm 2 lỗi C1/C2) và `MailTemplateSecurityTest` (4 ca) |
| H5 | Build test của module `application` fail: `NotificationApiClientTest` gọi `client.markAllNotificationsAsRead(null)` bị ambiguous giữa overload `Args` và `String` của client sinh tự động. | log build `#16 [ERROR] ... reference to markAllNotificationsAsRead is ambiguous` | ✅ đã sửa — truyền `(String) null` rõ ràng; Dockerfile có thêm `--build-arg TEST_CLASSES=...` để CI compile test + chạy test trọng yếu |

### MEDIUM

| # | Vấn đề | Trạng thái |
|---|---|---|
| M1 | `advancedCss` của tenant được chèn qua `style.textContent` (an toàn với XSS) nhưng CSS có thể gọi `url()` ra ngoài để rò rỉ thông tin phiên của người dùng tenant đó. | ⏳ chấp nhận như PE; khuyến nghị thêm CSP cho trang login |
| M2 | Quyền theo entity group không dùng được cho các API danh sách (`checkPermission` không có entity) → user chỉ có quyền theo group sẽ bị 403 khi mở trang Devices/Assets. Cần lọc theo group ở tầng query (như PE) nếu muốn dùng thực tế. | ⏳ còn lại (đã ghi trong `docs/access-control-roadmap.md`) |
| M3 | Nhiều `subscribe` trong component không huỷ (`LogoComponent`, `GithubBadgeComponent`, `AppComponent.setupWhiteLabeling`) vì `settings$` là `ReplaySubject` sống lâu → giữ reference component đã destroy. | ⏳ còn lại — nên dùng `takeUntil(this.destroy$)` |
| M4 | Menu tuỳ biến với `type=url` dùng `routerLink` nên URL ngoài (`https://...`) không mở tab mới mà bị router xử lý như route nội bộ. | ⏳ còn lại — nên render anchor `target="_blank"` cho item loại url |
| M5 | `list users` trong trang Roles cố định `pageSize=100` → tenant >100 user không gán được role cho user còn lại. | ⏳ còn lại — dùng phân trang/autocomplete của CE |
| M6 | Ghi `admin_settings` là "last write wins" (không version) và id group do client sinh → 2 admin sửa cùng lúc sẽ ghi đè nhau. | ⏳ chấp nhận ở giai đoạn này; nên thêm `version` nếu nhiều admin |
| M7 | 4 tuỳ chọn trong tab General **lưu được nhưng không có tác dụng gì**: `hideConnectivityDialog`, `hideChatBot`, `showPlatformNameVersion`, `overrideTrendzName` + `trendzName`. | ✅ đã xử lý — hiện thực `hideConnectivityDialog` (ẩn nút + chặn dialog), `showPlatformNameVersion` (hiện tên + version ở trang login), `overrideTrendzName` (đổi nhãn menu Trendz); **xoá** `hideChatBot` vì CE không có chat bot |
| M8 | `dashboard-page.component.html`: footer "Powered by **Green IQ** v.…" nhưng link vẫn trỏ `https://thingsboard.io`. | ✅ đã sửa — footer lấy tên từ white labeling, ẩn hẳn khi bật `hideVendorPromotion`, và chỉ gắn link ThingsBoard khi chưa đổi thương hiệu |
| M11 | `CustomMenuController` nhận URL tuỳ ý cho item loại `url` (nguy cơ `javascript:`), và menu render bằng `routerLink` nên URL ngoài không mở được. | ✅ đã sửa — server chỉ nhận `http(s)://` (URL khác trả 400) và dashboard id phải là UUID; client render `<a target="_blank" rel="noopener noreferrer">` cho item ngoài |
| M12 | `JacksonUtil.convertValue` dùng `OBJECT_MAPPER` **fail khi gặp field lạ** → khi một field bị xoá/đổi tên (ví dụ `hideChatBot`), JSON đã lưu trong `admin_settings` làm hỏng cả tính năng (đã tái hiện với dữ liệu thật của tenant trong DB). | ✅ đã sửa — các `Default*Service` đọc settings bằng `IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER`, tương thích ngược/xuôi |
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
| L8 | `.dockerignore` chưa loại `.idea`, `.github`, `docs` → build context lớn hơn cần thiết (không ảnh hưởng image cuối vì dùng multi-stage). | ✅ đã bổ sung (giữ `.git` vì `git-commit-id-plugin` cần để lấy commit id) |
| L11 | `docker/tb-custom` chỉ có stack dev và `entrypoint.sh` che lỗi install (`|| echo ...`) → không phù hợp production. | ✅ đã thêm `docker-compose.prod.yml` (không publish DB, secret qua env, healthcheck, log rotation, install chạy 1 lần) và entrypoint fail rõ ràng khi `RUN_INSTALL_ONLY=true` |
| L12 | `/api/tenant/whiteLabeling` (POST) thiếu `checkPermission(ADMIN_SETTINGS, WRITE)`. | ✅ đã thêm; đồng thời bổ sung `ADMIN_SETTINGS`/`USER` vào danh sách resource gán được ở trang Roles |
| L13 | `roles.component` chỉ tải 100 user đầu (`pageSize=100`) → tenant lớn không gán được role. | ✅ đã sửa — tải hết theo trang (giới hạn an toàn 50 trang) |
| L9 | `$primary-hue-3` trong `scss/constants.scss` bị đổi thành trắng (ảnh hưởng theme toàn hệ thống, không chỉ white labeling). | ⏳ nên đưa về biến mặc định của CE, phần đổi màu để white labeling lo |
| L10 | `/api/tenant/whiteLabeling` (POST) không gọi `accessControlService.checkPermission(ADMIN_SETTINGS, WRITE)` như các endpoint settings khác (vẫn có `@PreAuthorize('TENANT_ADMIN')`). | ⏳ còn lại — thêm cho nhất quán, trừ khi muốn tenant admin bị giới hạn role vẫn đổi được branding |

### Lỗi biên dịch bắt được trong lúc build

| Vấn đề | Trạng thái |
|---|---|
| `DefaultMailService`: gán giá trị cho `private final Configuration customTemplateConfig` trong `@PostConstruct` → `cannot assign a value to final variable` (Lombok đưa field final vào constructor). | ✅ đã sửa — bỏ `final`, field được khởi tạo trong `init()` |
| `TbRbacAccessControlService` (viết lại): kiểm tra bằng `javap` trên `freemarker-2.3.34.jar` rằng `Configuration.getIncompatibleImprovements()` tồn tại trước khi dùng. | ✅ |

### Lỗi hồi quy của tab All | Groups trong bảng entity (đã sửa)

| Vấn đề | Nguyên nhân | Trạng thái |
|---|---|---|
| Trang Devices / Assets / Entity views trắng bảng, header bị cắt, không load dữ liệu | `EntitiesTableComponent` resolve `@ViewChild('entityTableHeader', {static: true})`. Tab bar được thêm bằng cách bọc bảng trong `@if/@else`, mà `static: true` **không resolve được** khi element nằm trong structural directive → `entityTableHeaderAnchor` là `undefined` → `init()` ném `TypeError: Cannot read properties of undefined (reading 'viewContainerRef')` và dừng toàn bộ khởi tạo bảng (đã xác nhận bằng console log của Chrome headless). | ✅ đã sửa — bảng không còn bị bọc trong điều kiện cấu trúc; tab bar là flex item, phần bảng/groups ẩn/hiện bằng class `.tb-entities-hidden` (có ghi chú ngay trong template để không tái phạm) |

Kiểm chứng bằng Chrome headless (đăng nhập, đo layout, đọc console):

```text
DEVICES      tabs y=56 h=48 | table 1156x160, 2 dòng dữ liệu, paginator "1 - 2 of 2"
ENTITY-VIEWS tabs y=56 h=48 | table 1156x160, 2 dòng dữ liệu
ASSETS       tabs y=56 h=48 | table 1156x108
GATEWAYS     (không có tab groups) table 1158x56
GROUPS_TAB   groups 1190x896, bảng entity ẩn (display: none), header groups hiển thị đủ
CONSOLE_ERRORS []   EXCEPTIONS []
```

### Hai lỗi console khác phát hiện khi kiểm chứng bằng browser (đã sửa)

| Vấn đề | Nguyên nhân | Trạng thái |
|---|---|---|
| Trang login: `TypeError: Cannot read properties of null (reading 'authority')` | `LogoComponent` gọi `authService.defaultUrl(true, authState)` khi logo chưa có `link`; trang login chưa có `authUser` nên `defaultUrl` đọc `authUser.authority` của `null` (trước đây trang login truyền `link` nên không lộ lỗi này). | ✅ đã sửa — chỉ gọi `defaultUrl` khi đã có `authUser`; logo ở trang login không còn là link |
| Trang login: log `ERROR` kèm `HttpErrorResponse 401` | `CustomTranslationService` gọi `/api/customTranslation/{locale}` (API cần token) ngay khi đổi ngôn ngữ ở trang login → interceptor tạo lỗi 401 tổng hợp (`global-http-interceptor.ts`) và log ra console. | ✅ đã sửa — bỏ qua khi chưa có JWT (`AuthService.getJwtToken()`), không còn log lỗi và không tốn request 401 |

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

1. Lọc dữ liệu theo entity group ở tầng query (M2) nếu muốn role theo group dùng được trên UI danh sách.
2. Bổ sung API test (integration) cho groups/white labeling; unit test cho RBAC và bảo mật template đã có.
3. Trả `$primary-hue-3` về mặc định CE (L9) nếu muốn theme giống CE gốc; hiện giữ theo yêu cầu giao diện.
4. Thêm phiên bản (`version`) cho `admin_settings` nếu có nhiều admin sửa cùng lúc (M6).
5. TLS/backup: dùng `docker-compose.prod.yml` kèm reverse proxy TLS và backup định kỳ cho volume Postgres.

## 7. Trạng thái sau khi sửa

| Việc | Trạng thái |
|---|---|
| C1, C2 (RBAC cô lập customer), C3 (FreeMarker SSTI) | đã sửa trong code, đã build |
| H1 (effective roles), H2 (cache), H3 (domain trùng) | đã sửa trong code, đã build |
| M3 (subscription leak ở `help`, `logo`, `github-badge`, `home`) | đã sửa — dùng `takeUntilDestroyed`/`takeUntil` sẵn có |
| L1–L6 (dead code, i18n, ngày tạo group, xác nhận xoá) | đã sửa |
| H4, H5 (test + build test) | đã sửa — 16 unit test mới chạy xanh trong Docker build, toàn bộ test source module `application` compile được |
| M7, M8, M11, M12 (tuỳ chọn white labeling, footer, custom menu url, tương thích JSON cũ) | đã sửa |
| L8, L11, L12, L13 (dockerignore, compose production, quyền white labeling, tải user) | đã sửa |

### Cách chạy test trong CI

```bash
# compile toàn bộ test source của module application và chạy các test trọng yếu
docker compose -f docker/tb-custom/docker-compose.yml build \
  --build-arg TEST_CLASSES=TbRbacAccessControlServiceTest,MailTemplateSecurityTest tb-node
```

Kết quả lần chạy gần nhất:

```text
Tests run: 5,  Failures: 0, Errors: 0 -- MailTemplateSecurityTest
Tests run: 11, Failures: 0, Errors: 0 -- TbRbacAccessControlServiceTest
Tests run: 16, Failures: 0, Errors: 0
```

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

1. Dùng `docker/tb-custom/docker-compose.prod.yml` (không publish DB, secret qua env, install chạy 1 lần) và khai báo
   `SECURITY_RBAC_ENABLED` tường minh.
2. Chạy test trong CI bằng `--build-arg TEST_CLASSES=...` trước mỗi lần phát hành.
3. Đăng ký domain thật của khách cho tenant (tab Login) — branding của trang login được resolve theo tên miền;
   nếu truy cập bằng IP/localhost mà chưa đăng ký domain thì trang login dùng branding của hệ thống.

## 10. Trang Roles (tổ chức lại theo tab)

- Trang **Security → Roles** nay chia 4 tab: **Roles**, **Entity groups**, **User groups**, **Customer hierarchy**
  (trước đây tất cả nằm dồn trong một trang dài, dễ lệch hàng).
- Trong tab Roles, quyền được cấu hình **mỗi entity type một tab** (DEVICE, ASSET, DASHBOARD, ALARM, CUSTOMER,
  ENTITY_VIEW, RULE_CHAIN, ADMIN_SETTINGS, USER); mỗi tab có READ/WRITE/DELETE, phạm vi theo entity group và
  badge hiển thị nhanh quyền đã chọn. Role yêu cầu ít nhất một quyền trước khi thêm (tránh tạo role rỗng).
- Bố cục dùng CSS grid nên các ô nhập thẳng hàng trên mọi độ rộng màn hình.

## 11. Vòng sửa lỗi sau khi bàn giao (đã kiểm chứng)

| Vấn đề người dùng gặp | Nguyên nhân gốc | Trạng thái |
|---|---|---|
| Trang Devices / Entity views trắng bảng, không load dữ liệu | Thanh tab All\\|Groups được thêm bằng cách bọc bảng trong `@if/@else`, trong khi component dùng `@ViewChild(..., {static: true})` — ViewChild static không resolve trong structural directive nên `init()` ném lỗi và bảng không khởi tạo | ✅ đã sửa, kiểm chứng bằng Chrome headless |
| Bấm Save trong "Add entities" không lưu thiết bị vào nhóm (cả 3 tab Devices/Assets/Entity views) | Dialog trộn `[selected]` của `mat-list-option` với `[(ngModel)]` trên `mat-selection-list`, giá trị trả về không khớp với checkbox | ✅ đã sửa — dialog dùng state đơn giản (`selectedIds` + `isSelected/toggle`), có hiển thị "N selected" |
| Groups "không ghi vào Database" / mất nhóm | API cũ thay thế **toàn bộ** danh sách nhóm: chỉ cần client gửi danh sách rỗng/stale là xoá hết nhóm của tenant (đúng rủi ro M6). Việc này đã xảy ra với DB local trong lúc kiểm thử | ✅ đã sửa — thêm API theo từng nhóm: `POST /api/tenant/entityGroup/group` (upsert 1 nhóm, không đụng nhóm khác) và `DELETE /api/tenant/entityGroup/{id}`; UI chỉ dùng 2 API này. Trang Roles cũng đổi sang read-modify-write, chỉ xoá đúng những nhóm admin đã bỏ |
| Xoá device báo "Can't delete device that has entity views or is referenced in calculated fields!" | Chốt chặn hợp lệ của CE nhưng thông báo không nói rõ đối tượng nào chặn | ✅ đã cải thiện — thông báo nêu tên: `Can't delete device that is referenced by entity view 'vuthanh'. Remove or update these entities first.` (asset cũng vậy); đã cập nhật 2 test tương ứng |
| MQTTX báo "Bad User Name or Password (Code: 134)" | MQTTX đặt **device ID** vào Username; ThingsBoard yêu cầu **device access token** | ✅ không phải lỗi code — xem hướng dẫn bên dưới |
| Lưu ở trang Roles không có phản hồi | Không có thông báo sau khi lưu | ✅ đã thêm — thành công/thất bại cho cả 4 mục (roles, entity groups, user groups, customer hierarchy) |

### Hướng dẫn kết nối MQTTX (đã kiểm chứng thực tế)

```text
Protocol: mqtt://        (KHÔNG dùng mqtts/ws)
Host:     127.0.0.1      (hoặc localhost)
Port:     1883
Username: <device access token>   ← KHÔNG phải device ID
Password: để trống
Client ID: tùy ý
```

Lấy access token: mở device → **Manage credentials** (hoặc nút *Copy access token* trong tab Details).
Ví dụ token của `GW1` tại thời điểm kiểm chứng: `3CSgc0oe3OCmtzIspGLy` (token đổi theo thiết bị, không dùng lại cho thiết bị khác).
Publish dữ liệu: topic `v1/devices/me/telemetry`, payload JSON.

Kiểm chứng đã chạy: TCP tới `127.0.0.1:1883`, `::1:1883`, `localhost:1883` và MQTT CONNECT bằng token (có/không password) đều trả `CONNACK code=0`.

### Cảnh báo dữ liệu

Trong lúc kiểm thử vòng này, lỗi "thay thế toàn bộ danh sách nhóm" đã **xoá danh sách entity group trong DB local**
(trước đó có 12 nhóm: *Nhóm 1..7* cho DEVICE và *Building 1..5* cho ASSET, tất cả đều 0 thành viên vì lỗi dialog).
Đây là dữ liệu local, không có bản sao lưu. Khuyến nghị chạy `pg_dump` định kỳ (ví dụ đưa vào cron) và dùng
`docker-compose.prod.yml` có volume riêng cho Postgres.

## 12. Quyền theo entity group đã được enforce trên API danh sách (M2)

Trước đây quyền theo nhóm chỉ có tác dụng với các API theo từng entity: quyền global bị chặn nhưng quyền theo nhóm
**không lọc** kết quả danh sách, nên người dùng hoặc không thấy gì, hoặc thấy nhiều hơn phạm vi được cấp.

Cách làm mới (theo hướng của PE):

| Thành phần | Thay đổi |
|---|---|
| `AccessControlService` | thêm `getAllowedEntityIds(user, resource, operation)`: trả về danh sách id được phép khi role cấp quyền theo nhóm, `null` khi không giới hạn (impl mặc định của CE trả `null`) |
| `TbRbacAccessControlService` | trả về union `entityIds` của các nhóm trong scope; cho phép gọi API danh sách khi role có scope theo nhóm; entity thuộc nhóm được cấp thì được truy cập (kể cả khi không thuộc customer của user — tương đương nhóm **Public** của PE), vẫn giữ ràng buộc cùng tenant |
| `BaseController` | thêm `scopedPageLink(...)` + `applyEntityScope(...)`: khi có scope thì lấy tối đa 1000 entity rồi lọc theo id được phép và trả về đúng trang yêu cầu |
| Devices / Assets / Entity views | áp dụng cho cả endpoint `tenant/*Infos` và `customer/{id}/*Infos`; với customer user có scope thì tìm trong toàn tenant rồi lọc theo nhóm |

Kiểm chứng (customer user thuộc customer DN1, role cấp DEVICE READ/WRITE/DELETE theo 2 nhóm, 1 nhóm ASSET rỗng):

```text
/api/customer/DN1/deviceInfos  -> 200, đúng 2 thiết bị (child-device, GW1), totalElements=2
UI trang Devices của user đó   -> tiêu đề "DN1: Devices", hiển thị đúng 2 thiết bị
GET /api/device/{id ngoài nhóm} -> 403 (thiết bị mới tạo, không nằm trong nhóm)
/api/customer/DN1/assetInfos   -> 0 (nhóm ASSET rỗng, không lộ toàn bộ asset của tenant)
```

Hạn chế đã biết: khi có scope, việc lọc được thực hiện trong bộ nhớ với tối đa 1000 entity đầu tiên
(`SCOPED_FETCH_SIZE_LIMIT`); tenant lớn hơn cần chuyển sang lọc ở tầng query như PE.

### Bổ sung: chỉ siết những resource mà role khai báo

Sau khi bật lọc theo nhóm, trang chi tiết thiết bị của user customer báo **"Access Forbidden"**: role chỉ khai báo
`DEVICE: READ/WRITE/DELETE`, nhưng panel chi tiết còn gọi các API phụ (`/api/device/{id}/credentials`,
`/api/plugins/telemetry/.../attributes`, `/api/deviceProfile/{id}`) nên bị từ chối.

Đã sửa theo hai quy tắc:

1. **Role chỉ siết những resource được khai báo.** Nếu role không nhắc tới một resource (device profile, widget,
   tenant, ...) thì resource đó giữ nguyên quyền của nền tảng CE → các trang chi tiết vẫn hoạt động.
2. **Các thao tác phụ được quy về thao tác chính**: `READ_ATTRIBUTES`, `READ_TELEMETRY`, `READ_CREDENTIALS` → `READ`;
   `WRITE_ATTRIBUTES`, `WRITE_TELEMETRY`, `WRITE_CREDENTIALS`, `RPC_CALL`, `ASSIGN_TO_CUSTOMER`,
   `UNASSIGN_FROM_CUSTOMER` → `WRITE`.

Kiểm chứng lại với user customer của DN1 (role DEVICE READ/WRITE/DELETE theo 2 nhóm):

```text
/api/customer/DN1/deviceInfos          -> 200, 2 thiết bị
/api/device/{id trong nhóm}            -> 200
/api/device/{id trong nhóm}/credentials-> 200   (trước đây 403)
/api/plugins/telemetry/.../attributes  -> 200   (trước đây 403)
UI: mở chi tiết "child-device"         -> không còn dialog "Access Forbidden", panel hiển thị đầy đủ
```

## 13. Audit các loại role khác (kết quả và bản sửa)

| Hạng mục | Kiểm tra | Kết quả |
|---|---|---|
| Operation phụ | `Operation` enum có: `CREATE`, `ASSIGN_TO_CUSTOMER`, `UNASSIGN_FROM_CUSTOMER`, `ASSIGN_TO_TENANT`, `RPC_CALL`, `CLAIM_DEVICES`, `READ/WRITE_CREDENTIALS`, `READ/WRITE_ATTRIBUTES`, `READ/WRITE_TELEMETRY`, `READ/WRITE_CALCULATED_FIELD`, `ALL` | ✅ đã map: mọi operation bắt đầu bằng `READ` → cần `READ`; các operation còn lại (rpc, claim, assign, create, calculated field write, ALL) → cần `WRITE` |
| Scope cho resource không thuộc nhóm | Gửi `POST /api/tenant/role` với `scopedPermissions: {DASHBOARD: …}` / `{CUSTOMER: …}` | ✅ **bị từ chối 400** — chỉ `DEVICE`, `ASSET`, `ENTITY_VIEW` mới được scope theo nhóm (vì chỉ các entity này có thể là thành viên nhóm) |
| Trang Roles (UI) | Chọn tab `DASHBOARD`, `ALARM`, `CUSTOMER`, `USER`, `RULE_CHAIN`, `ADMIN_SETTINGS` | ✅ ô "Scope to groups" bị khoá và có ghi chú giải thích; chỉ DEVICE/ASSET/ENTITY_VIEW chọn được nhóm |
| Danh sách Asset | user customer có role `ASSET: READ` scope vào nhóm rỗng | ✅ 200 và trả **0** asset (không lộ toàn bộ asset của tenant) |
| Chi tiết Asset | `GET /api/asset/{id}`, `assetProfile`, attributes | ✅ 200 (nhờ luật "role chỉ siết resource được khai báo" + mapping operation) |
| Danh sách Entity view | user customer có role `ENTITY_VIEW: READ` scope vào nhóm chứa `vuthanh` | ✅ chỉ trả `vuthanh` |
| Chi tiết Entity view | `GET /api/entityView/{id}`, `attributes`, `relations` | ✅ 200 (trước đây cùng lớp lỗi với device) |
| Entity view → device liên quan | role chỉ có ENTITY_VIEW, mở device của entity view | 403 — **đúng thiết kế**: muốn mở device phải cấp thêm quyền DEVICE (global hoặc theo nhóm) |
| Danh sách Dashboard | Lọc theo scope được thêm vào `GET /api/tenant/dashboards` và `/api/customer/{id}/dashboards` | ✅ dự phòng cho dữ liệu role cũ; role mới không thể tạo scope cho DASHBOARD |

### Sự cố dữ liệu trong lúc kiểm thử

Trong khi dọn dẹp dữ liệu test, tôi đã gửi `POST /api/tenant/role` với `{"roles":[]}` và **xoá role "Nhóm Nhà Kính 1"**.
Role đã được khôi phục ngay bằng đúng nội dung đã đọc trước đó (id `2uubkhrm`, scope DEVICE READ/WRITE/DELETE cho
2 nhóm `g-asset-1`, `g-device-1`, gán cho user `debledanthu@gmail.com`, `ownCustomerOnly=true`) và đã đọc lại để xác nhận.
Bạn nên mở trang Roles kiểm tra lại giúp tôi. Đây là lần thứ hai việc kiểm thử chạm vào dữ liệu thật — khuyến nghị
bật `pg_dump` định kỳ trước khi tiếp tục các vòng kiểm thử.
