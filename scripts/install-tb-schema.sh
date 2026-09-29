#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Cai schema ThingsBoard vao PostgreSQL (chay 1 lan, va moi khi co migration moi).
#
# Installer doc CA `sql/` VA `json/` tu cung mot `install.data_dir`, nhung hai thu muc nay nam o
# hai module khac nhau (dao/src/main/resources/sql va application/src/main/data), nen script gom
# chung vao application/target/tb-data truoc khi chay.
#
# Dung: ./scripts/install-tb-schema.sh [--demo|--no-demo]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LOAD_DEMO=true
for arg in "$@"; do
    case "$arg" in
        --demo) LOAD_DEMO=true ;;
        --no-demo) LOAD_DEMO=false ;;
        -h|--help) sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Tham so khong hop le: $arg (dung -h de xem huong dan)" >&2; exit 2 ;;
    esac
done

# shellcheck source=scripts/tb-local.env
source "$REPO_ROOT/scripts/tb-local.env"

DATA_DIR="$REPO_ROOT/application/target/tb-data"
CLASSPATH_FILE="$REPO_ROOT/application/target/classpath.txt"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Khong tim thay JDK tai JAVA_HOME=$JAVA_HOME" >&2
    exit 1
fi

if [[ ! -d "$REPO_ROOT/application/target/classes" ]]; then
    echo "Chua build. Chay truoc:" >&2
    echo "  mvn -B -T 1C clean install -DskipTests -Dpkg.skip=true -Dskip.ui.build=true -Dlicense.skip=true" >&2
    exit 1
fi

if [[ ! -f "$CLASSPATH_FILE" ]]; then
    echo "==> Thieu $CLASSPATH_FILE -> dang tao..."
    mvn -B -q -pl application dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
fi

echo "==> Gom data dir vao $DATA_DIR"
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"
for d in json certs lwm2m-registry resources; do
    [[ -d "$REPO_ROOT/application/src/main/data/$d" ]] && cp -R "$REPO_ROOT/application/src/main/data/$d" "$DATA_DIR/"
done
cp -R "$REPO_ROOT/dao/src/main/resources/sql" "$DATA_DIR/"

CP="$REPO_ROOT/application/target/classes:$(tr -d '\n' < "$CLASSPATH_FILE")"

echo "==> Cai schema vao $SPRING_DATASOURCE_URL (load_demo=$LOAD_DEMO)"
"$JAVA_HOME/bin/java" \
    "-Dspring.datasource.url=$SPRING_DATASOURCE_URL" \
    "-Dspring.datasource.username=$SPRING_DATASOURCE_USERNAME" \
    "-Dspring.datasource.password=$SPRING_DATASOURCE_PASSWORD" \
    "-Ddatabase.ts.type=$DATABASE_TS_TYPE" \
    "-Ddatabase.ts_latest.type=$DATABASE_TS_LATEST_TYPE" \
    "-Dqueue.type=$TB_QUEUE_TYPE" \
    "-Dinstall.data_dir=$DATA_DIR" \
    "-Dinstall.load_demo=$LOAD_DEMO" \
    "-Dservice.type=monolith" \
    -cp "$CP" org.thingsboard.server.ThingsboardInstallApplication

echo "==> Xong. Tai khoan mac dinh: sysadmin@thingsboard.org/sysadmin, tenant@thingsboard.org/tenant"
