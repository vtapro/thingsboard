# Monitoring (Prometheus + Alertmanager + Grafana)

Stack observability chạy trong namespace `monitoring`, tách khỏi `thingsboard` và không nằm trong
`deploy/k3s/kustomization.yaml`.

## 1. Thành phần

| Thành phần | Image | Ghi chú |
|---|---|---|
| Prometheus | `prom/prometheus:v3.1.0` | scrape mọi pod ThingsBoard qua `/actuator/prometheus` + kubelet/cAdvisor, retention 15 ngày (emptyDir — mất khi pod bị thay) |
| Alertmanager | `prom/alertmanager:latest` | nhận alert từ Prometheus; receiver mặc định là webhook placeholder |
| Grafana | `grafana/grafana:latest` | provision sẵn datasource Prometheus và bộ dashboard của ThingsBoard (`docker/monitoring/grafana/provisioning/dashboards/*.json`) |

Cài/ cập nhật:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-monitoring.ps1 -ShowAccess
```

Script tạo `grafana-admin` Secret (user `admin`) và **in mật khẩu một lần**; nếu Secret đã tồn tại
thì giữ nguyên.

## 2. Truy cập

```bash
kubectl -n monitoring port-forward svc/grafana 3000:3000
kubectl -n monitoring port-forward svc/prometheus 9090:9090
kubectl -n monitoring port-forward svc/alertmanager 9093:9093
```

(Có thể thêm `grafana.greeniq.vn` vào HAProxy sau, cần thêm DNS + chứng chỉ.)

## 3. Scrape như thế nào

- `job=thingsboard`: mọi pod có label `app.kubernetes.io/part-of=thingsboard` và container port
  tên `http`, path `/actuator/prometheus`. Hiện scrape được: `tb-core` ×2, `tb-rule-engine` ×2,
  `tb-mqtt-transport` ×2, `tb-http-transport` ×2, `tb-coap/lwm2m/snmp-transport` (11 target).
  `tb-web-ui` (nginx), `tb-haproxy` và `tb-js-executor` không expose actuator nên bị loại trong
  `relabel_configs` — thêm lại khi các service đó có endpoint metrics.
- `job=kubelet` và `job=cadvisor`: scrape trực tiếp `https://<node>:10250/metrics` và
  `/metrics/cadvisor` bằng token của ServiceAccount `prometheus` (cần ClusterRole `tb-prometheus`,
  đã tạo trong `deploy/monitoring/05-prometheus-rbac.yaml`).

## 4. Alert đang bật

| Alert | Điều kiện | Mức |
|---|---|---|
| `TbServiceDown` | `up{job="thingsboard"} == 0` trong 5 phút | critical |
| `TbJvmHeapHigh` | heap JVM > 85% trong 10 phút | warning |
| `TbNodeHighCpu` | CPU node > 85% trong 15 phút | warning |
| `TbNodeHighMemory` | RAM node > 90% trong 15 phút | warning |
| `TbPodRestarted` | container khởi động lại trong 1 giờ | warning |
| `TbHttpServerErrors` | tỉ lệ 5xx > 0.5 req/s trong 10 phút | warning |

Chưa có alert theo Kafka consumer lag (cần JMX exporter) và theo log `Timeout to process` (cần
Loki) — hai phần này nằm trong Phase 1 của [capacity-load-test.md](capacity-load-test.md).

## 5. Gửi cảnh báo đi đâu

Sửa receiver trong `deploy/monitoring/20-alertmanager.yaml` (Slack/Teams webhook hoặc `email_configs`
với SMTP của công ty), rồi apply lại và reload Alertmanager:

```bash
kubectl apply -f deploy/monitoring/20-alertmanager.yaml
kubectl -n monitoring rollout restart deployment/alertmanager
```
