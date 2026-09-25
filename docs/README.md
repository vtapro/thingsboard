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
| [local-dev.md](local-dev.md) | Cài đặt và chạy local trên Windows (PostgreSQL, backend Java, UI dev server), tài khoản mặc định, xử lý sự cố |
| [production-k3s.md](production-k3s.md) | Triển khai production trên k3s: 8 image microservices, PostgreSQL + Cassandra managed ngoài cụm, Kafka/ZooKeeper trong cụm, HAProxy cho `app.greeniq.vn`, checklist |
| [white-labeling.md](white-labeling.md) | Tính năng white labeling (system + tenant), API, cách cấu hình |
| [white-labeling-roadmap.md](white-labeling-roadmap.md) | Lộ trình các phần white labeling còn lại |
| [access-control-roadmap.md](access-control-roadmap.md) | Lộ trình RBAC (roles, entity groups, user groups, customer hierarchy) |
| [capacity-load-test.md](capacity-load-test.md) | Kết quả đo tải thật trên cụm (MQTT 1 msg/s/thiết bị), nút cổ chai và lộ trình mở rộng |
| [implementation-status.md](implementation-status.md) | Trạng thái từng tính năng và môi trường chạy local |
| [code-audit.md](code-audit.md) | Kết quả audit toàn bộ code tự thêm so với ThingsBoard CE, bằng chứng kiểm chứng |

## Nguyên tắc khi phát triển trên fork

1. Không xoá/đổi header license và không xoá `LICENSE`. File mới phải thêm đúng 2 dòng SPDX ở đầu file.
2. Ưu tiên module/cấu hình riêng thay vì sửa file core ThingsBoard, để giảm conflict khi merge upstream.
3. Khi thêm file mới, chạy `mvn license:check` để chắc chắn không vi phạm check license.
4. Môi trường dev hiện tại chạy native trên Windows (xem [local-dev.md](local-dev.md)); không dùng Docker.
5. Dev local **không** dùng Kafka/ZooKeeper/Cassandra, nhưng production **bắt buộc** có (k3s,
   microservices HA) — xem [production-k3s.md](production-k3s.md); không cắt bỏ nhánh code production.
