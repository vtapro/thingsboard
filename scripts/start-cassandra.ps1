# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Khởi động Cassandra 5.0 trong WSL Ubuntu (không cần Docker) và chờ cổng 9042 sẵn sàng trên Windows.
# Cassandra 5.0 không có bản chạy native trên Windows, nên dùng WSL; ThingsBoard trên Windows vẫn
# kết nối qua localhost:9042 (WSL2 forward localhost).

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptWin = Join-Path $repoRoot "scripts\wsl\cassandra.sh"
$scriptWsl = "/mnt/c" + ($scriptWin -replace '\\', '/' -replace '^C:', '')

if (Get-NetTCPConnection -LocalPort 9042 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "Cassandra đã chạy (port 9042)."
    exit 0
}

$process = Start-Process -FilePath "wsl.exe" `
    -ArgumentList "-d", "Ubuntu", "-e", "bash", $scriptWsl, "start" `
    -NoNewWindow -PassThru -RedirectStandardOutput "$env:TEMP\cassandra.out" -RedirectStandardError "$env:TEMP\cassandra.err"
$process.Id | Set-Content "$env:TEMP\tb-cassandra.pid"

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 5
    if (Get-NetTCPConnection -LocalPort 9042 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "Cassandra đã sẵn sàng: localhost:9042 (pid wsl $($process.Id))"
        exit 0
    }
}
throw "Cassandra không khởi động được, xem log: $env:TEMP\cassandra.out và ~/tools/cassandra/logs/system.log trong WSL"
