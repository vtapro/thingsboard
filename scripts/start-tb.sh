#!/bin/bash
#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

usage() {
    cat <<'USAGE'
Chay ThingsBoard backend dev local tren macOS (khong Docker).

Dung: ./scripts/start-tb.sh [tuy chon]
  -f, --force   restart neu backend dang chay
  -h, --help    hien huong dan nay

Yeu cau: da build (xem docs/local-dev-macos.md) va co application/target/classpath.txt.
USAGE
}

FORCE=0
for arg in "$@"; do
    case "$arg" in
        -f|--force) FORCE=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Tham so khong hop le: $arg (dung -h de xem huong dan)" >&2; exit 2 ;;
    esac
done

# shellcheck source=scripts/tb-local.env
source "$REPO_ROOT/scripts/tb-local.env"

CLASSES="$REPO_ROOT/application/target/classes"
CLASSPATH_FILE="$REPO_ROOT/application/target/classpath.txt"
LOG_OUT="$REPO_ROOT/application/target/tb-server.out"
LOG_ERR="$REPO_ROOT/application/target/tb-server.err"
PID_FILE="$REPO_ROOT/application/target/tb-server.pid"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Khong tim thay JDK tai JAVA_HOME=$JAVA_HOME" >&2
    echo "Tai Temurin 25: https://api.adoptium.net/v3/binary/latest/25/ga/mac/\$(uname -m)/jdk/hotspot/normal/eclipse" >&2
    exit 1
fi

if [[ ! -f "$CLASSPATH_FILE" ]]; then
    echo "==> Thieu $CLASSPATH_FILE -> dang tao..."
    mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
fi

# IDE co the xoa/recompile lai application/target/classes, hoac lan build truoc bi loi giua duong
# -> tu dong build lai module application truoc khi chay.
if [[ ! -f "$CLASSES/org/thingsboard/server/ThingsboardServerApplication.class" ]]; then
    echo "==> Thieu ThingsboardServerApplication.class -> build lai module application (1-2 phut)..."
    mvn -B -pl application install -DskipTests -Dskip.ui.build=true -Dpkg.skip=true -Dlicense.skip=true
fi

mkdir -p "$TB_ROCKSDB_DIR"

# --- Cong 8080 dang ban? ---
port_owner() { lsof -nP -iTCP:"$HTTP_BIND_PORT" -sTCP:LISTEN -t 2>/dev/null | head -1; }

if [[ -n "$(port_owner)" ]]; then
    if [[ "$FORCE" -eq 0 ]]; then
        # Da co backend chay san: kiem tra xem co phai ThingsBoard khong roi bao thanh cong (khong phai loi).
        if curl -sf -m 10 "http://localhost:$HTTP_BIND_PORT/api/noauth/whiteLabeling" >/dev/null 2>&1; then
            echo "ThingsBoard dang chay san (pid: $(port_owner) -> http://localhost:$HTTP_BIND_PORT). Khong can start lai."
            echo "Muon restart (sau khi build lai code Java): ./scripts/start-tb.sh --force"
            echo "Muon dung: ./scripts/stop-tb.sh"
            exit 0
        fi
        echo "Cong $HTTP_BIND_PORT dang bi chiem boi tien trinh khac (pid: $(port_owner)) va khong phai ThingsBoard." >&2
        echo "Hay dung tien trinh do, hoac chay lai voi --force, hoac doi cong: HTTP_BIND_PORT=8081 ./scripts/start-tb.sh" >&2
        exit 1
    fi
    echo "==> Cong $HTTP_BIND_PORT dang ban (pid: $(port_owner)), dang dung lai vi co --force"
    kill "$(port_owner)" 2>/dev/null || true
    for _ in $(seq 1 20); do
        sleep 0.5
        [[ -z "$(port_owner)" ]] && break
    done
    if [[ -n "$(port_owner)" ]]; then
        echo "Khong giai phong duoc cong $HTTP_BIND_PORT, kiem tra lai cac tien trinh Java dang chay." >&2
        exit 1
    fi
fi

CP="$CLASSES:$(tr -d '\n' < "$CLASSPATH_FILE")"

JAVA_OPTS=(
    -Xmx2G
    "-Dserver.port=$HTTP_BIND_PORT"
    "-Dspring.datasource.url=$SPRING_DATASOURCE_URL"
    "-Dspring.datasource.username=$SPRING_DATASOURCE_USERNAME"
    "-Dspring.datasource.password=$SPRING_DATASOURCE_PASSWORD"
    "-Dredis.host=$REDIS_HOST"
    "-Dredis.port=$REDIS_PORT"
    "-Dredis.password=$REDIS_PASSWORD"
    "-Dqueue.type=$TB_QUEUE_TYPE"
    "-Ddatabase.ts.type=$DATABASE_TS_TYPE"
    "-Ddatabase.ts_latest.type=$DATABASE_TS_LATEST_TYPE"
    "-Dcache.type=$CACHE_TYPE"
    "-Dmanagement.health.redis.enabled=$MANAGEMENT_HEALTH_REDIS_ENABLED"
    "-Dsecurity.rbac.enabled=$SECURITY_RBAC_ENABLED"
    "-Dqueue.edqs.local.rocksdb_path=$TB_ROCKSDB_DIR/edqs"
    "-Dqueue.calculated_fields.rocks_db_path=$TB_ROCKSDB_DIR/cf_states"
    "-Dservice.type=monolith"
)

echo "==> Khoi dong ThingsBoard (log: $LOG_OUT)"
nohup "$JAVA_HOME/bin/java" "${JAVA_OPTS[@]}" -cp "$CP" \
    org.thingsboard.server.ThingsboardServerApplication \
    >"$LOG_OUT" 2>"$LOG_ERR" &
echo $! > "$PID_FILE"

for i in $(seq 1 30); do
    sleep 5
    if [[ -n "$(port_owner)" ]]; then
        echo "ThingsBoard san sang: http://localhost:$HTTP_BIND_PORT (pid $(cat "$PID_FILE"))"
        echo "UI dev: cd ui-ngx && corepack yarn ng serve --configuration development --port 4200"
        exit 0
    fi
    if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "Tien trinh da thoat. 30 dong cuoi cua log:" >&2
        tail -30 "$LOG_OUT" >&2
        exit 1
    fi
    (( i % 3 == 0 )) && echo "    dang khoi dong... ($(( i * 5 )) giay) - log: $LOG_OUT"
done

echo "Backend khong khoi dong duoc sau 150 giay, xem log: $LOG_OUT" >&2
exit 1
