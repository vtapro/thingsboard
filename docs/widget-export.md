# Xuất dữ liệu widget (CSV / XLS / XLSX)

Giống ThingsBoard PE: mỗi widget trên dashboard có nút **tải xuống** trên thanh tiêu đề. Bấm vào sẽ mở menu:

| Mục | Nội dung file |
|---|---|
| **Export data to CSV...** | Bảng dữ liệu dạng CSV (UTF-8 có BOM để Excel mở đúng tiếng Việt) |
| **Export data to XLS...** | Cùng bảng dữ liệu, định dạng Excel 97-2003 tương thích (bảng HTML, Excel mở trực tiếp) |
| **Export data to XLSX...** | Workbook Excel nhiều sheet |

Nút chỉ hiện khi widget **đã có dữ liệu** (time series hoặc latest/attribute) và dashboard đang ở chế độ xem — không
hiện khi đang edit dashboard, để không vướng thao tác kéo/thả.

## Nội dung file

1. **Time series** — một dòng cho mỗi timestamp, mỗi cột là một key:

   | Timestamp | `solar-inverter-50kw-001 · AC Power (kW)` | `solar-inverter-50kw-001 · DC Power (kW)` | … |
   |---|---|---|---|
   | 2026-09-30 20:55:05.013 | 36.4 | 51.53 | … |

   Tên cột là `<tên entity> · <nhãn key> (<đơn vị>)`, các key không có dữ liệu ở một timestamp thì để trống — mở
   trong Excel là vẽ biểu đồ báo cáo được ngay.
2. **Latest values** (sheet thứ hai với XLSX, bảng thứ hai với CSV/XLS) — các key latest value/attribute của widget:
   `Entity | Alias | Key | Value | Unit | Timestamp`.

Với XLSX mỗi bảng là một sheet; với CSV (không hỗ trợ nhiều sheet) hai bảng cách nhau bằng một dòng trống và mỗi
bảng có dòng tiêu đề riêng; với XLS hai bảng nằm liên tiếp trong cùng một sheet.

Tên file: `<tiêu đề widget> - <yyyyMMddHHmmss>.ext` (thêm tiêu đề dashboard khi context có), ví dụ
`Solar Inverter (50 kW) - 20260930220212772.xlsx`.

## Triển khai

- `ui-ngx/src/app/modules/home/components/widget/widget-export.service.ts` — thu thập `widgetContext.data` (time
  series) và `widgetContext.latestData` (latest/attribute), dựng bảng, ghi CSV/XLS/XLSX và tải file.
- `widget-container.component.html/ts` — nút tải xuống + menu, gọi service với `widgetContext` của widget.
- XLSX sinh bằng **JSZip** (đã có sẵn trong dự án), không thêm dependency mới; CSV và XLS sinh bằng chuỗi/HTML nên
  build không cần thư viện ngoài.

## Kiểm chứng (2026-09-30)

Chạy trên Chrome thật (headless, `Browser.setDownloadBehavior=allow`), mở dashboard của emulator
`solar-inverter-50kw-001` và bấm lần lượt 3 mục trong menu:

| Định dạng | Kết quả |
|---|---|
| CSV | `Solar Inverter (50 kW) - 20260930220152656.csv` (9,7 KB) — đúng header `Timestamp, <entity> · <key> (kW), …` |
| XLS | `Solar Inverter (50 kW) - 20260930220202706.xls` (15 KB) — bảng HTML có tiêu đề "Time series" |
| XLSX | `Solar Inverter (50 kW) - 20260930220212772.xlsx` (32 KB) — zip hợp lệ với `[Content_Types].xml`, `xl/workbook.xml`, `xl/worksheets/sheet1.xml` chứa đúng dữ liệu |
