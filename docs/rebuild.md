# Rebuild UI và backend (local dev)

Các lệnh dưới đây đã chạy đúng trên máy dev (Windows, JDK 25, Maven 3.9.11, Node 24 + yarn 1.22).
Chạy ở **thư mục gốc repo** trừ phần UI:

```powershell
cd C:\Users\vthea\Documents\GitHub\thingsboard
```

## 1. Backend

```powershell
# một lần cho mỗi cửa sổ PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\vthea\tools\apache-maven-3.9.11\bin;$env:PATH"

# (a) build nhanh – khi chỉ sửa Java trong module application (~30–60 giây)
mvn -o -B -pl application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# (b) build khi sửa cả common/dao/rule-engine… (thêm -am, KHÔNG dùng "clean")
mvn -o -B -pl "application,!ui-ngx" -am install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# (c) chạy lại backend để nạp class mới (bắt buộc sau mỗi lần build)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-tb.ps1 -Force

# (d) kiểm tra
Invoke-WebRequest http://localhost:8080/api/noauth/whiteLabeling -UseBasicParsing | Select-Object StatusCode   # 200
```

> ⚠️ **Không chạy `mvn clean` ở thư mục gốc**: Maven "clean" module `ui-ngx` bằng cách xoá
> `ui-ngx/node_modules` (đang bị esbuild/rollup/VS Code giữ file) → xoá dở, hỏng UI. Nếu lỡ bị:
>
> ```powershell
> cd ui-ngx; corepack yarn install      # ~60 giây
> ```
>
> `mvn clean` cũng xoá `*/target/proto` → lần build sau `protoc` báo `tbmsg.proto: File not found`.
> Cách xử lý: build **tuần tự** (bỏ `-T 1C`) một lần, hoặc copy tạm
> `Copy-Item common/message/src/main/proto/*.proto common/proto/target/proto/`.

## 2. UI (Angular `ui-ngx`)

```powershell
cd C:\Users\vthea\Documents\GitHub\thingsboard\ui-ngx

# (a) dev server hot reload -> http://localhost:4200 (mở ở terminal riêng, để nguyên khi code)
node --max_old_space_size=8048 ./node_modules/@angular/cli/bin/ng.js serve --configuration development --host 0.0.0.0

# (b) build production giống CI (chạy trước khi push) -> ui-ngx/target/generated-resources/public
node --max_old_space_size=4096 ./node_modules/@angular/cli/bin/ng.js build --configuration production
```

- Chỉ sửa `ui-ngx/src/**` → `ng serve` tự build lại, chỉ cần **F5** (Ctrl+F5 nếu sửa i18n/asset).
- Build production mất ~2–3 phút; phải thấy `Application bundle generation complete` và exit 0.

## 3. Khi sửa cả hai

```powershell
# 1) backend
mvn -o -B -pl application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true `
    -Duser.home=C:/Users/vthea -Dmaven.repo.local=C:/Users/vthea/.m2/repository

# 2) UI (nếu đang chạy ng serve thì bỏ qua – nó tự build)
cd ui-ngx
node --max_old_space_size=4096 ./node_modules/@angular/cli/bin/ng.js build --configuration production
cd ..

# 3) restart backend rồi F5 trình duyệt
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-tb.ps1 -Force
```

## 4. Dừng / kiểm tra trạng thái

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-tb.ps1          # dừng backend + ng serve
(Get-NetTCPConnection -LocalPort 8080,4200 -State Listen -ErrorAction SilentlyContinue) | Select-Object LocalPort,OwningProcess
```

## 5. Lỗi thường gặp

| Hiện tượng | Nguyên nhân / cách xử lý |
|---|---|
| `An unrecoverable IOException occurred so the connection was closed` | WebSocket cũ bị đứt khi backend restart → **F5** là hết |
| `The term '.\scripts\start-tb.ps1' is not recognized` | đang ở `ui-ngx` → dùng `..\scripts\start-tb.ps1` hoặc `cd ..` trước |
| `Could not find or load main class org.thingsboard.server.ThingsboardServerApplication` | `application\target\classes` bị thiếu (build lỗi giữa đường / VS Code Java LS xoá) → `scripts\start-tb.ps1` tự build lại, hoặc xoá `application\target\classes` rồi build lại module `application` |
| `[vite] http proxy error: /api/... AggregateError` | backend chưa chạy → `scripts\start-tb.ps1` rồi F5 |
| UI hiện `admin.roles-*` dạng code | key i18n chưa có hoặc dùng thuộc tính `translate` trên component Material → phải dùng `{{ 'key' | translate }}` |

## 6. Đẩy lên GHCR + cụm k3s (chỉ khi được yêu cầu)

```powershell
git add -A; git commit -m "..."; git push origin <branch>
gh -R vtapro/thingsboard run list --limit 1          # theo dõi CI (~8–15 phút, build 8 image)
gh -R vtapro/thingsboard run watch <run-id> --exit-status

kubectl -n thingsboard rollout restart deployment/tb-core deployment/tb-web-ui
kubectl -n thingsboard rollout status deployment/tb-core --timeout=600s
kubectl -n thingsboard rollout status deployment/tb-web-ui --timeout=600s
```

| Sửa gì | Rollout service nào |
|---|---|
| Java (`application`, `dao`, `common`) | `tb-core` (thêm `tb-rule-engine` nếu sửa rule node) |
| `ui-ngx` | `tb-web-ui` |
| `transport/*` | transport tương ứng |
| chỉ `docs/**`, `deploy/**`, `scripts/**`, `*.md` | không cần — workflow có `paths-ignore` |
