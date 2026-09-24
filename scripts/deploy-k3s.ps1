# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Trien khai ThingsBoard len cum k3s theo dung thu tu:
#   1. namespace, config, ha tang trong cum (Kafka/ZooKeeper), NetworkPolicy, HAProxy
#   2. job tb-install (cai/cap nhat schema) va cho job chay xong
#   3. cac service ThingsBoard + ingress/HAProxy, cho rollout xong
#
# Cach dung:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-k3s.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-k3s.ps1 -OnlyInfrastructure
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-k3s.ps1 -SkipInstall

param(
    [string]$Kubeconfig = "C:\Users\vthea\AppData\Roaming\Lens\kubeconfigs\f2e08611-80c2-43e2-bda8-06ffce6759b4-pasted-kubeconfig.yaml",
    [string]$Kubectl = "C:\Users\vthea\AppData\Local\Programs\Lens\resources\x64\kubectl.exe",
    [string]$Namespace = "thingsboard",
    [switch]$OnlyInfrastructure,
    [switch]$SkipInstall,
    [int]$InstallTimeoutMinutes = 20
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Kubectl)) { $Kubectl = "kubectl" }
if (-not (Test-Path $Kubeconfig)) { throw "Khong tim thay kubeconfig: $Kubeconfig" }
$env:KUBECONFIG = $Kubeconfig

$repoRoot = Split-Path -Parent $PSScriptRoot
$manifestDir = Join-Path $repoRoot "deploy\k3s"

function Apply-Manifest($name) {
    Write-Host "==> apply $name"
    & $Kubectl apply -f (Join-Path $manifestDir $name) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "apply $name that bai" }
}

Write-Host "==> kiem tra ket noi cum"
& $Kubectl -n $Namespace get ns $Namespace | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Khong ket noi duoc cum hoac chua co namespace $Namespace" }

# ---- 1. cau hinh + ha tang trong cum ---------------------------------------------------
Apply-Manifest "00-namespace.yaml"
Apply-Manifest "01-config.yaml"
Apply-Manifest "05-zookeeper.yaml"
Apply-Manifest "04-kafka.yaml"
Apply-Manifest "07-network-policies.yaml"

Write-Host "==> cho Kafka va ZooKeeper ready"
& $Kubectl -n $Namespace rollout status statefulset/tb-zookeeper --timeout=5m | Out-Host
& $Kubectl -n $Namespace rollout status statefulset/tb-kafka --timeout=5m | Out-Host

if ($OnlyInfrastructure) {
    Write-Host "==> chi ha tang, dung o day (--OnlyInfrastructure)"
    exit 0
}

# ---- 2. schema -------------------------------------------------------------------------
if (-not $SkipInstall) {
    Write-Host "==> chay job tb-install (cai/cap nhat schema)"
    & $Kubectl -n $Namespace delete job tb-install --ignore-not-found | Out-Host
    Apply-Manifest "10-install-job.yaml"
    & $Kubectl -n $Namespace wait --for=condition=complete job/tb-install --timeout="$($InstallTimeoutMinutes)m" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host "==> job that bai, log:"
        & $Kubectl -n $Namespace logs job/tb-install --tail=80 | Out-Host
        throw "tb-install khong hoan thanh"
    }
    & $Kubectl -n $Namespace logs job/tb-install --tail=20 | Out-Host
}

# ---- 3. services -----------------------------------------------------------------------
foreach ($f in @("20-tb-core.yaml","21-tb-rule-engine.yaml","25-tb-web-ui.yaml","26-tb-js-executor.yaml",
                 "22-tb-mqtt-transport.yaml","23-tb-http-transport.yaml","24-protocol-transports.yaml",
                 "30-haproxy.yaml")) {
    Apply-Manifest $f
}

Write-Host "==> cho cac deployment rollout"
foreach ($d in @("tb-core","tb-rule-engine","tb-web-ui","tb-js-executor",
                 "tb-mqtt-transport","tb-http-transport","tb-haproxy")) {
    & $Kubectl -n $Namespace rollout status deploy/$d --timeout=10m | Out-Host
}

Write-Host ""
Write-Host "==> trang thai cuoi cung:"
& $Kubectl -n $Namespace get pods,svc --no-headers | Out-Host
Write-Host ""
Write-Host "Truy cap: http://<worker-ip>:30080 (HAProxy) - domain app.greeniq.vn tro ve IP worker"
