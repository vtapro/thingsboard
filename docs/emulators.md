# Emulators: catalog thiết bị ảo + dashboard theo lĩnh vực

Tính năng lấy ý tưởng từ mục **Emulators** của ThingsBoard PE, nhưng gắn với nghiệp vụ của fork:
chọn một *emulator profile* theo lĩnh vực (năng lượng, nông nghiệp, nhà máy, chiếu sáng, nước, vận tải,
toà nhà, đô thị thông minh) → hệ thống tạo **device thật**, **sinh telemetry** theo kịch bản và **tạo luôn
dashboard** của lĩnh vực đó (mỗi telemetry key một biểu đồ), nên có ngay một bản demo chạy được mà không cần
thiết bị vật lý.

## 1. Catalog

**75 profile — mỗi lĩnh vực đúng 5 profile**, chia **15 lĩnh vực** trùng danh sách lĩnh vực của PE (catalog định
nghĩa trong code, giống PE — tenant không sửa được): Agriculture, Buildings, Cold Chain, Energy, Environment,
Fleet, Healthcare, Industrial, Lighting, Retail, Smart Cities, Smart Home, Transportation, Utilities, Water.
Mỗi profile có thêm `model` hiển thị cạnh device profile trên card (ví dụ `GENERIC • ST-50K`).

Thông số được chọn theo thiết bị thật để dùng cho demo/chào hàng: đúng đơn vị và dải giá trị của từng ngành
(kW/kWh, m³/h, bar, °C, ppm, dB, NTU, AQI…), mỗi profile 4–6 telemetry key và 3–4 scenario nghiệp vụ. Các bộ đếm
(công tơ điện/gas/nước, đồng hồ km, sản lượng) **bắt đầu từ chỉ số thực tế** thay vì 0 — ví dụ công tơ điện dân
dụng khởi tạo `24 680,7 kWh`, đồng hồ xe `76 320,5 km`, sản lượng dây chuyền `18 450 pcs` — nên dashboard báo cáo
trông như thiết bị đang vận hành thật.

| Lĩnh vực | Profile tiêu biểu | Telemetry chính |
|---|---|---|
| Energy | Solar Inverter (50 kW), Smart Home Energy Hub, EV Fast Charging Station, Wind Turbine (2 MW) | `acPower`, `dcPower`, `energy`, `power`, `chargerState` |
| Agriculture | Greenhouse System, Smart Irrigation Pump, Soil Moisture Sensor, Weather Station | `airTemperature`, `soilMoisture`, `rainfall`, `flowRate`, `pumpState` |
| Industrial | Manufacturing Line - OEE, Air Compressor, Three Phase Energy Meter, CNC Machine | `oee`, `availability`, `pressure`, `spindleSpeed`, `activePower` |
| Lighting | Smart Street Light, Street Lighting Cabinet | `lux`, `brightness`, `power`, `lampState`, `cabinetTemperature` |
| Water | Water Tank Monitor, Water Treatment Plant | `level`, `volume`, `chlorine`, `ph`, `turbidity` |
| Transportation / Fleet | Refrigerated Delivery Truck, Fleet Vehicle Tracker | `speed`, `cargoTemp`, `fuelLevel`, `odometer` |
| Buildings | HVAC Air Handling Unit, Elevator | `supplyTemperature`, `fanSpeed`, `load`, `doorState` |
| Smart Cities | Smart Parking System, Smart Waste Bin | `occupancy`, `freeSlots`, `airQuality`, `fillLevel` |
| Cold Chain | Cold Storage Room | `temperature`, `humidity`, `power`, `compressorState` |
| Environment | Air Quality Station | `pm25`, `pm10`, `co2`, `noise` |
| Healthcare | Medical Refrigerator | `temperature`, `battery`, `doorState`, `alarmState` |
| Retail | Vending Machine | `sales`, `stock`, `temperature`, `doorState` |
| Smart Home | Smart Thermostat | `temperature`, `targetTemperature`, `hvacState`, `motion` |
| Utilities | Gas Meter | `flowRate`, `volume`, `pressure`, `valveState` |

Mỗi profile có 3–4 **scenario** (ví dụ "Sunny Afternoon", "Grid Blackout", "Heat Wave", "Cooling Failure").
Scenario đổi cách giá trị diễn ra: kịch bản lỗi đẩy nhiệt độ/rung/pressure lên ngưỡng cảnh báo và cho công
suất/lưu lượng về 0 (để rule chain và alarm có việc để làm), kịch bản "night/dimming/off" đẩy về mức thấp,
kịch bản "peak/busy/load" đẩy lên mức cao. Các bộ đếm (`energy`, `sessionEnergy`, `outputCount`) chỉ tăng.

## 2. Tạo emulator — có gì được tạo

`POST /api/tenant/emulator` với `{profileId, name, scenario, intervalSeconds, createDashboard}` sẽ:

1. tạo **device** thật của tenant (có access token, có `device profile`, xuất hiện ở trang Devices);
2. tạo bản ghi emulator (lưu trong `admin_settings` key `emulators`, không cần đổi schema DB);
3. tạo **dashboard của lĩnh vực**: 1 biểu đồ tổng hợp tất cả key + 1 biểu đồ cho từng key, tất cả trỏ vào
   device của emulator qua entity alias `entityList` (widget `system.time_series_chart` lấy từ bộ widget
   demo của ThingsBoard nên không phụ thuộc phiên bản UI);
4. mỗi `intervalSeconds` (5 s → 5 phút) scheduler của `tb-core` sinh một bản tin telemetry và đẩy vào rule
   engine (`POST_TELEMETRY_REQUEST`, metadata `ts`), nên dữ liệu cũng chảy qua rule chain/alarm/dashboard
   như thiết bị thật.
5. thiết bị được **device state service** đánh dấu `active` giống thiết bị thật: khi tạo emulator, hệ thống ghi
   server attribute `inactivityTimeout` = `max(3 × interval, 60 s)`, và mỗi lần publish lại gửi thêm bản tin
   hoạt động (`TransportToDeviceActorMsg` + `lastActivityTime`) tới device actor. Vì vậy emulator đang chạy hiện
   `Active` ở trang Devices, còn khi **stop** thì sau `inactivityTimeout` thiết bị chuyển `Inactive` (đúng như
   hành vi của PE).

`POST /api/tenant/emulator/{id}/history?hours=24` sinh thêm dữ liệu quá khứ (tối đa 500 điểm, bước tự giãn)
để dashboard có dữ liệu ngay sau khi tạo.

## 3. API

| Method | Endpoint | Việc |
|---|---|---|
| GET | `/api/tenant/emulator/catalog` | catalog + danh sách lĩnh vực/loại |
| GET | `/api/tenant/emulator` | danh sách emulator của tenant |
| POST | `/api/tenant/emulator` | tạo emulator (device + dashboard) |
| POST | `/api/tenant/emulator/{id}/RUNNING` \| `PAUSED` \| `STOPPED` | start / pause / stop |
| POST | `/api/tenant/emulator/{id}/scenario?scenario=&intervalSeconds=` | đổi kịch bản / chu kỳ publish |
| POST | `/api/tenant/emulator/{id}/dashboard` | tạo lại dashboard của lĩnh vực |
| POST | `/api/tenant/emulator/{id}/history?hours=24` | sinh dữ liệu quá khứ |
| DELETE | `/api/tenant/emulator/{id}?deleteDevice=true` | xoá emulator (mặc định xoá cả device) |
| POST | `/api/tenant/emulator/clearUnlinked` | xoá các bản ghi emulator không còn device (nút **Clear Unlinked**) |

Tất cả endpoint yêu cầu `TENANT_ADMIN` và quyền `ADMIN_SETTINGS` (giống trang Automation), nên tenant admin
bị giới hạn bởi role RBAC cũng không tự ý bật/tắt emulator ngoài phạm vi được cấp.

## 4. UI

Menu **Emulators** (`/emulators`) có 2 tab giống PE:

- **Emulator Catalog**: lọc theo lĩnh vực/loại/tìm kiếm + chip *All / Verified*; mỗi card hiển thị mô tả,
  `device profile • model`, các telemetry key, các scenario, chu kỳ publish, số emulator đang có của profile
  ("N emulator(s), M stopped") và nút **Create emulator** (dialog cho chọn tên, scenario, chu kỳ, tuỳ chọn
  tạo dashboard).
- **Emulators**: lọc theo trạng thái (All/Running/Stopped/Paused), profile và tên; bảng có start/pause/stop/
  delete, đổi scenario và chu kỳ ngay trên dòng, thời điểm hoạt động cuối, nút **Generate history** (24h) và
  **Open** dashboard của lĩnh vực; có **Clear Unlinked** (dọn emulator mồ côi) và phân trang
  (*Items per page*: 10/20/50).

## 5. Kiểm chứng local (2026-09-30)

```bash
python -m venv /tmp/emu-venv && /tmp/emu-venv/bin/pip install requests
/tmp/emu-venv/bin/python scripts/test-emulators-local.py     # 30/30 PASS
```

Script kiểm tra: catalog **75 profile / 15 lĩnh vực (5 profile mỗi lĩnh vực, profile nào cũng có `model` và 4–6
signal)**, tạo emulator → device + dashboard đúng key và đúng device, sinh 500 điểm history và đọc lại được bằng
API telemetry, scheduler tiếp tục publish khi RUNNING, **device Active** + `inactivityTimeout`, công tơ khởi tạo
từ chỉ số thực tế, pause/đổi scenario/đổi chu kỳ/tạo lại dashboard, xoá emulator thì device bị xoá theo, và
**Clear Unlinked** dọn đúng emulator mồ côi.

## 6. Cấu hình

| Thuộc tính | Mặc định | Việc |
|---|---|---|
| `emulator.scheduler.initial-delay-ms` | 25000 | chờ trước vòng publish đầu tiên |
| `emulator.scheduler.interval-ms` | 5000 | nhịp scheduler kiểm tra các emulator đến hạn |

Ghi chú vận hành: chỉ `tb-core` giữ system partition chạy scheduler (giống Automation) nên chạy nhiều replica
không publish trùng; trạng thái runtime (`lastActivityTs`, `publishedMessages`) được ghi lại vào
`admin_settings` tối đa 1 lần/phút để không tạo tải ghi DB.
