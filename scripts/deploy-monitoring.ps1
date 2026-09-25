# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Trien khai stack observability (Prometheus + Alertmanager + Grafana) len cum k3s.
#
# - Prometheus scrape moi pod ThingsBoard qua /actuator/prometheus (label
#   app.kubernetes.io/part-of=thingsboard, container port ten "http") + kubelet/cAdvisor.
# - Grafana duoc provision san datasource Prometheus va bo dashboard co san cua ThingsBoard
#   (docker/monitoring/grafana/provisioning/dashboards/*.json).
# - Alertmanager nhan alert tu Prometheus; receiver mac dinh la webhook placeholder, xem
#   deploy/monitoring/20-alertmanager.yaml de doi sang Slack/Teams/email.
#
# Cach dung:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-monitoring.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-monitoring.ps1 -ShowAccess

param(
    [string]$Kubeconfig = "C:\Users\vthea\AppData\Roaming\Lens\kubeconfigs\f2e08611-80c2-43e2-bda8-06ffce6759b4-pasted-kubeconfig.yaml",
    [string]$Kubectl = "C:\Users\vthea\AppData\Local\Programs\Lens\resources\x64\kubectl.exe",
    [string]$Namespace = "monitoring",
    [string]$AdminPassword = "",
    [switch]$ShowAccess
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Kubectl)) { $Kubectl = "kubectl" }
if (-not (Test-Path $Kubeconfig)) { throw "Khong tim thay kubeconfig: $Kubeconfig" }
$env:KUBECONFIG = $Kubeconfig

$repoRoot = Split-Path -Parent $PSScriptRoot
$manifestDir = Join-Path $repoRoot "deploy\monitoring"
$grafanaDir = Join-Path $repoRoot "docker\monitoring\grafana\provisioning"

function Apply-Manifest($name) {
    Write-Host "==> apply $name"
    & $Kubectl apply -f (Join-Path $manifestDir $name) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "apply $name that bai" }
}

Write-Host "==> kiem tra ket noi cum"
& $Kubectl get ns | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Khong ket noi duoc cum" }

Apply-Manifest "00-namespace.yaml"
Apply-Manifest "05-prometheus-rbac.yaml"

# ---- Grafana provisioning: lay truc tiep tu docker/monitoring cua ThingsBoard ------------------
Write-Host "==> tao configmap provisioning cho Grafana tu docker/monitoring/grafana"
$dsFile = Join-Path $grafanaDir "datasources\datasource.yml"
$dsArg = "--from-file=datasource.yml=$dsFile"
$dsArgs = @("-n", $Namespace, "create", "configmap", "grafana-datasources", $dsArg,
            "--dry-run=client", "-o", "yaml")
(& $Kubectl @dsArgs) | & $Kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "tao configmap grafana-datasources that bai" }

$dashFiles = Get-ChildItem (Join-Path $grafanaDir "dashboards") -File
$fromFile = @()
foreach ($f in $dashFiles) { $fromFile += "--from-file=$($f.Name)=$($f.FullName)" }
$dashArgs = @("-n", $Namespace, "create", "configmap", "grafana-dashboards") + $fromFile +
            @("--dry-run=client", "-o", "yaml")
(& $Kubectl @dashArgs) | & $Kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "tao configmap grafana-dashboards that bai" }

# ---- Grafana admin secret ----------------------------------------------------------------------
$existing = & $Kubectl -n $Namespace get secret grafana-admin --ignore-not-found -o name
if (-not $existing) {
    if (-not $AdminPassword) {
        $bytes = New-Object byte[] 12
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $AdminPassword = ([Convert]::ToBase64String($bytes) -replace "[+/=]", "a")
    }
    Write-Host "==> tao secret grafana-admin (user admin)"
    & $Kubectl -n $Namespace create secret generic grafana-admin `
        --from-literal=admin-user=admin --from-literal=admin-password=$AdminPassword | Out-Host
    Write-Host "    mat khau Grafana: $AdminPassword  (luu lai, chi in mot lan)"
} else {
    Write-Host "==> secret grafana-admin da ton tai, giu nguyen"
}

Apply-Manifest "10-prometheus.yaml"
Apply-Manifest "20-alertmanager.yaml"
Apply-Manifest "30-grafana.yaml"

Write-Host "==> doi rollout"
foreach ($d in @("prometheus", "alertmanager", "grafana")) {
    & $Kubectl -n $Namespace rollout status deployment/$d --timeout=300s | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "rollout $d that bai" }
}

Write-Host "==> trang thai"
& $Kubectl -n $Namespace get deploy,svc,pod | Out-Host

if ($ShowAccess) {
    Write-Host @"

Cach truy cap (tam thoi qua port-forward):
  kubectl -n monitoring port-forward svc/grafana 3000:3000
  kubectl -n monitoring port-forward svc/prometheus 9090:9090
  kubectl -n monitoring port-forward svc/alertmanager 9093:9093
"@
}
