# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Dừng các tiến trình dev local: ThingsBoard backend, Kafka, UI dev server (nginx/ng serve), Cassandra (WSL).

$ErrorActionPreference = "Continue"

function Stop-FromPidFile($pidFile, $name) {
    if (-not (Test-Path $pidFile)) {
        Write-Host "$name : không có pid file"
        return
    }
    $processId = (Get-Content $pidFile).Trim()
    try {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Write-Host "$name : đã dừng (pid $processId)"
    } catch {
        Write-Host "$name : pid $processId không còn chạy"
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

Stop-FromPidFile "$env:TEMP\tb-server.pid" "ThingsBoard"
Stop-FromPidFile "$env:TEMP\tb-kafka.pid" "Kafka"

# UI dev server (node/ng serve) đang giữ cổng 4200
$ui = Get-NetTCPConnection -LocalPort 4200 -State Listen -ErrorAction SilentlyContinue
if ($ui) {
    $ui.OwningProcess | Sort-Object -Unique | ForEach-Object {
        try { Stop-Process -Id $_ -Force -ErrorAction Stop; Write-Host "UI dev server : đã dừng (pid $_)" } catch { }
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptWin = Join-Path $repoRoot "scripts\wsl\cassandra.sh"
$scriptWsl = "/mnt/c" + ($scriptWin -replace '\\', '/' -replace '^C:', '')
wsl.exe -d Ubuntu -e bash $scriptWsl stop
