# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Chạy ThingsBoard backend ở môi trường dev local (không Docker):
# PostgreSQL (entities) + Kafka (queue) + Cassandra (timeseries) + UI dev server riêng.
#
# Yêu cầu: đã build bằng `mvn -B -T 1C clean install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true`
# và đã tạo application/target/classpath.txt (xem docs/local-dev.md).

param(
    [string]$DatabaseUrl = "jdbc:postgresql://localhost:5432/thingsboard",
    [string]$DatabaseUser = "postgres",
    [string]$DatabasePassword = "postgres",
    [string]$KafkaServers = "localhost:9092",
    [string]$CassandraUrl = "127.0.0.1:9042",
    [string]$TimeseriesType = "cassandra",   # cassandra | sql
    [switch]$DisableRbac
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdk25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$classes = Join-Path $repoRoot "application\target\classes"
$classpathFile = Join-Path $repoRoot "application\target\classpath.txt"

if (-not (Test-Path $classpathFile)) {
    throw "Thiếu $classpathFile — chạy: mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target\classpath.txt"
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw "Cổng 8080 đang bận. Dừng tiến trình Java cũ trước (hoặc tắt container ThingsBoard cũ)."
}

$cp = ($classes -replace '\\', '/') + ";" + ((Get-Content $classpathFile -Raw).Trim() -replace '\\', '/')
$props = @(
    "-Xmx2G",
    "-Dinstall.data_dir=C:/Users/vthea/tb-data",
    "-Dspring.datasource.url=$DatabaseUrl",
    "-Dspring.datasource.username=$DatabaseUser",
    "-Dspring.datasource.password=$DatabasePassword",
    "-Dqueue.type=kafka",
    "-Dqueue.kafka.bootstrap.servers=$KafkaServers",
    "-Ddatabase.ts.type=$TimeseriesType",
    "-Ddatabase.ts_latest.type=$TimeseriesType",
    "-Dcassandra.url=$CassandraUrl",
    "-Dservice.type=monolith"
)
if (-not $DisableRbac) {
    $props += "-Dsecurity.rbac.enabled=true"
}
$props += @("-cp", $cp, "org.thingsboard.server.ThingsboardServerApplication")

$argsFile = "$env:TEMP\tb-server.args"
[System.IO.File]::WriteAllLines($argsFile, $props)

$process = Start-Process -FilePath "$jdk25\bin\java.exe" -ArgumentList "@$argsFile" `
    -NoNewWindow -PassThru -RedirectStandardOutput "$env:TEMP\tb-server.out" -RedirectStandardError "$env:TEMP\tb-server.err"
$process.Id | Set-Content "$env:TEMP\tb-server.pid"

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 5
    if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "ThingsBoard đã sẵn sàng: http://localhost:8080 (pid $($process.Id))"
        Write-Host "UI dev: cd ui-ngx; corepack yarn ng serve --configuration development --port 4200"
        exit 0
    }
}
throw "Backend không khởi động được, xem log: $env:TEMP\tb-server.out"
