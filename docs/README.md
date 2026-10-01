# Tài liệu nội bộ (fork thương mại của ThingsBoard CE)

Repo này là bản fork của **ThingsBoard CE 4.4.0-SNAPSHOT** (giấy phép Apache-2.0), dùng làm nền
cho sản phẩm thương mại. Mọi file nguồn gốc của ThingsBoard giữ nguyên header:

```
SPDX-FileCopyrightText: Copyright The Thingsboard Authors
SPDX-License-Identifier: Apache-2.0
```

## Mục lục

| Tài liệu | Nội dung |
|---|---|
| [local-dev-macos.md](local-dev-macos.md) | Runbook dev local trên macOS: từng lệnh build, cài schema, chạy backend + UI, tài khoản mặc định, xử lý sự cố |
| [production-k3s.md](production-k3s.md) | Triển khai production trên k3s: 8 image microservices, PostgreSQL managed ngoài cụm, Cassandra + Kafka + ZooKeeper trong cụm, HAProxy cho `app.greeniq.vn`, checklist |
| [white-labeling.md](white-labeling.md) | Tính năng white labeling (system + tenant), API, cách cấu hình |
| [rbac-members.md](rbac-members.md) | Quản lý thành viên theo quyền: role cho USER/CUSTOMER, scope own-only, customer con |
| [emulators.md](emulators.md) | Emulators: catalog thiết bị ảo theo lĩnh vực (năng lượng, nông nghiệp, nhà máy, chiếu sáng…), sinh telemetry theo scenario và tự tạo dashboard |
| [widget-export.md](widget-export.md) | Xuất dữ liệu widget ra CSV/XLS/XLSX (nút tải xuống trên header widget, giống ThingsBoard PE) |
| [capacity-load-test.md](capacity-load-test.md) | Kết quả đo tải thật trên cụm (MQTT 1 msg/s/thiết bị), nút cổ chai và lộ trình mở rộng |
| [k3s-processing-speed-audit.md](k3s-processing-speed-audit.md) | Audit tốc độ xử lý cụm k3s (2026-09-28): độ trễ PostgreSQL/Cassandra/Kafka, HPA, throttling, khuyến nghị |
| [implementation-status.md](implementation-status.md) | Trạng thái từng tính năng và môi trường chạy local |
| [code-audit.md](code-audit.md) | Kết quả audit toàn bộ code tự thêm so với ThingsBoard CE, bằng chứng kiểm chứng |

## Nguyên tắc khi phát triển trên fork

1. Không xoá/đổi header license và không xoá `LICENSE`. File mới phải thêm đúng 2 dòng SPDX ở đầu file.
2. Ưu tiên module/cấu hình riêng thay vì sửa file core ThingsBoard, để giảm conflict khi merge upstream.
3. Khi thêm file mới, chạy `mvn license:check` để chắc chắn không vi phạm check license.
4. Môi trường dev hiện tại chạy native trên macOS (xem [local-dev-macos.md](local-dev-macos.md)); không dùng Docker.
5. Dev local **không** dùng Kafka/ZooKeeper/Cassandra, nhưng production **bắt buộc** có (k3s,
   microservices HA) — xem [production-k3s.md](production-k3s.md); không cắt bỏ nhánh code production.
