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
    [switch]$DisableRbac,
    # Dung backend dang chay (neu co) roi khoi dong lai - tien khi vua build lai code Java
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdk25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
$mavenBin = "C:\Users\vthea\tools\apache-maven-3.9.11\bin"
$classes = Join-Path $repoRoot "application\target\classes"
$classpathFile = Join-Path $repoRoot "application\target\classpath.txt"
$RocksDbDir = (Join-Path $repoRoot "application\target\rocksdb") -replace '\\', '/'
$env:JAVA_HOME = $jdk25
if ($env:PATH -notlike "*$mavenBin*") { $env:PATH = "$jdk25\bin;$mavenBin;$env:PATH" }

New-Item -ItemType Directory -Force -Path ($RocksDbDir -replace '/', '\') | Out-Null

if (-not (Test-Path $classpathFile)) {
    throw "Thieu $classpathFile - chay: mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target\classpath.txt"
}

# VS Code Java Language Server co the xoa/recompile lai application\target\classes, hoac lan build truoc
# bi loi giua duong -> tu dong build lai module application truoc khi chay.
$mainClass = Join-Path $classes "org\thingsboard\server\ThingsboardServerApplication.class"
if (-not (Test-Path $mainClass)) {
    Write-Host "==> Thieu $mainClass -> build lai module application (co the mat 1-2 phut)..."
    Push-Location $repoRoot
    try {
        & "$mavenBin\mvn.cmd" -o -B -pl application install "-DskipTests" "-Dskip.ui.build=true" "-Dpkg.skip=true" `
            "-Dlicense.skip=true" "-Duser.home=C:/Users/vthea" "-Dmaven.repo.local=C:/Users/vthea/.m2/repository"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $mainClass)) {
            throw "Build lai module application that bai, xem log ben tren."
        }
    } finally {
        Pop-Location
    }
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    $owners = (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
               Select-Object -ExpandProperty OwningProcess | Sort-Object -Unique)
    if (-not $Force) {
        # Da co backend chay san: kiem tra xem co phai ThingsBoard khong roi bao thanh cong (khong phai loi).
        $healthy = $false
        try {
            $resp = Invoke-WebRequest "http://localhost:8080/api/noauth/whiteLabeling" -UseBasicParsing -TimeoutSec 10
            $healthy = ($resp.StatusCode -eq 200)
        } catch { $healthy = $false }
        if ($healthy) {
            Write-Host "ThingsBoard dang chay san (pid: $($owners -join ', ') -> http://localhost:8080). Khong can start lai."
            Write-Host "Muon restart (sau khi build lai code Java): .\scripts\start-tb.ps1 -Force"
            Write-Host "Muon dung: .\scripts\stop-tb.ps1"
            exit 0
        }
        throw ("Cong 8080 dang bi chiem boi tien trinh khac (pid: " + ($owners -join ", ") +
               ") va khong phai ThingsBoard. Hay dung tien trinh do, hoac chay lai voi -Force.")
    }
    Write-Host "==> Cong 8080 dang ban (pid: $($owners -join ', ')), dang dung lai vi co -Force"
    foreach ($ownerPid in $owners) {
        try { Stop-Process -Id $ownerPid -Force -ErrorAction Stop; Write-Host "    da dung pid $ownerPid" } catch { }
    }
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) { break }
    }
    if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
        throw "Khong giai phong duoc cong 8080, kiem tra lai cac tien trinh Java dang chay."
    }
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

# -WindowStyle Hidden: backend khong phu thuoc cua so terminal dang chay script
# (dong terminal van khong lam chet ThingsBoard), log van ghi ra 2 file ben duoi.
$process = Start-Process -FilePath "$jdk25\bin\java.exe" -ArgumentList "@$argsFile" `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput "$env:TEMP\tb-server.out" -RedirectStandardError "$env:TEMP\tb-server.err"
$process.Id | Set-Content "$env:TEMP\tb-server.pid"

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 5
    if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "ThingsBoard san sang: http://localhost:8080 (pid $($process.Id))"
        Write-Host "UI dev: cd ui-ngx; corepack yarn ng serve --configuration development --port 4200"
        exit 0
    }
    if (($i + 1) % 3 -eq 0) {
        Write-Host "    dang khoi dong... ($([int](($i + 1) * 5)) giay) - log: $env:TEMP\tb-server.out"
    }
}
throw "Backend khong khoi dong duoc, xem log: $env:TEMP\tb-server.out"
