# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Khởi động Kafka 4.0 (KRaft, single node) cho môi trường dev local.
# Lần đầu cần format storage (xem docs/local-dev.md).

$ErrorActionPreference = "Stop"

$kafkaHome = "C:\Users\vthea\tools\kafka"
$jdk21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$logOut = "$env:TEMP\kafka.out"
$logErr = "$env:TEMP\kafka.err"

if (-not (Test-Path $kafkaHome)) {
    throw "Không tìm thấy Kafka tại $kafkaHome"
}
if (Get-NetTCPConnection -LocalPort 9092 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "Kafka đã chạy (port 9092)."
    exit 0
}

$env:JAVA_HOME = $jdk21
$env:PATH = "$jdk21\bin;$env:PATH"
# Bắt buộc: bản .bat của Kafka gọi wmic để đoán kiến trúc OS, wmic đã bị Windows mới xoá.
$env:KAFKA_HEAP_OPTS = "-Xmx1G -Xms1G"

$process = Start-Process -FilePath "$kafkaHome\bin\windows\kafka-server-start.bat" `
    -ArgumentList "$kafkaHome\config\server.properties" `
    -NoNewWindow -PassThru -RedirectStandardOutput $logOut -RedirectStandardError $logErr
$process.Id | Set-Content "$env:TEMP\tb-kafka.pid"

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 3
    if (Get-NetTCPConnection -LocalPort 9092 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "Kafka đã sẵn sàng: localhost:9092 (pid $($process.Id))"
        exit 0
    }
}
throw "Kafka không khởi động được, xem log: $logOut"
