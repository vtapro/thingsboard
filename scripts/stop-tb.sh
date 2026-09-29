#!/bin/bash
#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$REPO_ROOT/application/target/tb-server.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "Khong co $PID_FILE -> ThingsBoard khong chay boi start-tb.sh."
    exit 0
fi

PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then
    echo "Tien trinh $PID khong con chay. Xoa pid file."
    rm -f "$PID_FILE"
    exit 0
fi

echo "==> Dung ThingsBoard (pid $PID)"
kill "$PID"
for _ in $(seq 1 30); do
    sleep 1
    if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "Da dung."
        exit 0
    fi
done

echo "Khong dung duoc trong 30 giay, gui SIGKILL."
kill -9 "$PID" 2>/dev/null || true
rm -f "$PID_FILE"
echo "Da dung."
