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
| [local-build-and-run.md](local-build-and-run.md) | Build image và chạy ThingsBoard local bằng Docker, danh sách lệnh, xử lý sự cố |
| [white-labeling.md](white-labeling.md) | Tính năng white labeling (2 cấp: system và tenant), API, cách cấu hình |

## Nguyên tắc khi phát triển trên fork

1. Không xoá/đổi header license và không xoá `LICENSE`. File mới phải thêm đúng 2 dòng SPDX ở đầu file.
2. Ưu tiên module/cấu hình riêng thay vì sửa file core ThingsBoard, để giảm conflict khi merge upstream.
3. Sau khi thêm file mới, chạy `mvn license:check` (hoặc build image) để chắc chắn không vi phạm check license.

