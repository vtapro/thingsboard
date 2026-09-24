# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Chay ThingsBoard backend o moi truong dev local (khong Docker), dung cau hinh chuan cua CE:
#   - PostgreSQL cho entities VA timeseries
#   - queue in-memory (khong can Kafka/ZooKeeper)
#   - UI chay rieng bang Angular dev server (ng serve)
#
# Yeu cau: da build bang `mvn -B -T 1C clean install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true`
# va da co application/target/classpath.txt (xem docs/local-dev.md).

param(
    [string]$DatabaseUrl = "jdbc:postgresql://localhost:5432/thingsboard",
    [string]$DatabaseUser = "postgres",
    [string]$DatabasePassword = "postgres",
    [switch]$DisableRbac
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdk25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$classes = Join-Path $repoRoot "application\target\classes"
$classpathFile = Join-Path $repoRoot "application\target\classpath.txt"
$RocksDbDir = (Join-Path $repoRoot "application\target\rocksdb") -replace '\\', '/'

New-Item -ItemType Directory -Force -Path ($RocksDbDir -replace '/', '\') | Out-Null

if (-not (Test-Path $classpathFile)) {
    throw "Thieu $classpathFile - chay: mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target\classpath.txt"
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw "Cong 8080 dang ban. Dung tien trinh Java cu truoc (scripts\stop-tb.ps1)."
}

$cp = ($classes -replace '\\', '/') + ";" + ((Get-Content $classpathFile -Raw).Trim() -replace '\\', '/')
$props = @(
    "-Xmx2G",
    "-Dinstall.data_dir=C:/Users/vthea/tb-data",
    "-Dspring.datasource.url=$DatabaseUrl",
    "-Dspring.datasource.username=$DatabaseUser",
    "-Dspring.datasource.password=$DatabasePassword",
    "-Dqueue.type=in-memory",
    "-Ddatabase.ts.type=sql",
    "-Ddatabase.ts_latest.type=sql",
    # May nay co user.home = C:\ nen mac dinh ${user.home}/.rocksdb bi loi AccessDenied.
    # Chi ro thu muc RocksDB (EDQS + calculated fields) nam trong data dir cua repo.
    "-Dqueue.edqs.local.rocksdb_path=$RocksDbDir/edqs",
    "-Dqueue.calculated_fields.rocks_db_path=$RocksDbDir/cf_states",
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
        Write-Host "ThingsBoard san sang: http://localhost:8080 (pid $($process.Id))"
        Write-Host "UI dev: cd ui-ngx; corepack yarn ng serve --configuration development --port 4200"
        exit 0
    }
}
throw "Backend khong khoi dong duoc, xem log: $env:TEMP\tb-server.out"
