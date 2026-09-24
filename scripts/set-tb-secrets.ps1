# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Cap nhat secret tb-secrets tren cum k3s (PostgreSQL + Cassandra + trang stats HAProxy).
# Mat khau duoc truyen thang vao kubectl nen khong bi loi quoting cua PowerShell.
#
# Cach dung:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\set-tb-secrets.ps1 `
#     -PostgresPassword 'mat-khau-postgres' -CassandraPassword 'mat-khau-cassandra'
#
# Khong truyen tham so thi script se hoi mat khau (nhap an).

param(
    [string]$PostgresUser = "vtheanh04@gmail.com",
    [string]$PostgresPassword,
    [string]$CassandraUser = "vtheanh04@gmail.com",
    [string]$CassandraPassword,
    [string]$HaproxyStatsUser = "admin",
    [string]$HaproxyStatsPassword,
    [string]$Namespace = "thingsboard",
    [string]$SecretName = "tb-secrets",
    [string]$Kubeconfig = "C:\Users\vthea\AppData\Roaming\Lens\kubeconfigs\f2e08611-80c2-43e2-bda8-06ffce6759b4-pasted-kubeconfig.yaml",
    [string]$Kubectl = "C:\Users\vthea\AppData\Local\Programs\Lens\resources\x64\kubectl.exe"
)

$ErrorActionPreference = "Stop"

function Read-SecretValue($prompt) {
    $secure = Read-Host -Prompt $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

if (-not (Test-Path $Kubectl)) { $Kubectl = "kubectl" }
if (-not (Test-Path $Kubeconfig)) { throw "Khong tim thay kubeconfig: $Kubeconfig" }
$env:KUBECONFIG = $Kubeconfig

if ([string]::IsNullOrWhiteSpace($PostgresPassword)) { $PostgresPassword = Read-SecretValue "Mat khau PostgreSQL ($PostgresUser)" }
if ([string]::IsNullOrWhiteSpace($CassandraPassword)) { $CassandraPassword = Read-SecretValue "Mat khau Cassandra ($CassandraUser)" }
if ([string]::IsNullOrWhiteSpace($HaproxyStatsPassword)) { $HaproxyStatsPassword = Read-SecretValue "Mat khau trang /stats HAProxy ($HaproxyStatsUser)" }

Write-Host "Cap nhat secret $SecretName trong namespace $Namespace ..."

$createArgs = @(
    "-n", $Namespace, "create", "secret", "generic", $SecretName,
    "--from-literal=SPRING_DATASOURCE_USERNAME=$PostgresUser",
    "--from-literal=SPRING_DATASOURCE_PASSWORD=$PostgresPassword",
    "--from-literal=CASSANDRA_USERNAME=$CassandraUser",
    "--from-literal=CASSANDRA_PASSWORD=$CassandraPassword",
    "--from-literal=HAPROXY_STATS_USER=$HaproxyStatsUser",
    "--from-literal=HAPROXY_STATS_PASSWORD=$HaproxyStatsPassword",
    "--dry-run=client", "-o", "yaml"
)

$yaml = & $Kubectl @createArgs
if ($LASTEXITCODE -ne 0) { throw "Khong tao duoc secret (kiem tra ket noi cum / quyen)" }

$yaml | & $Kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "Khong apply duoc secret" }

Write-Host ""
Write-Host "Da cap nhat. Cac key hien co:"
(& $Kubectl -n $Namespace get secret $SecretName -o json | ConvertFrom-Json).data.PSObject.Properties.Name |
    ForEach-Object { Write-Host "  - $_" }
