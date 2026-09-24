#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#
# Quản lý Cassandra 5.0 chạy trong WSL Ubuntu (môi trường dev local, không cần Docker).
# Dùng: cassandra.sh start|stop|status|setup

set -e

JAVA_HOME="$HOME/tools/jdk17"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
CASSANDRA_HOME="$HOME/tools/cassandra"

case "$1" in
  start)
    if pgrep -f CassandraDaemon >/dev/null 2>&1; then
      echo "Cassandra đang chạy (pid $(pgrep -f CassandraDaemon | head -1))"
      exit 0
    fi
    cd "$CASSANDRA_HOME"
    exec ./bin/cassandra -f
    ;;
  stop)
    "$CASSANDRA_HOME/bin/nodetool" drain >/dev/null 2>&1 || true
    pkill -f CassandraDaemon && echo "đã dừng Cassandra" || echo "Cassandra không chạy"
    ;;
  status)
    "$CASSANDRA_HOME/bin/nodetool" status
    ;;
  setup)
    echo "JAVA_HOME=$JAVA_HOME"
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1
    echo "log: $CASSANDRA_HOME/logs/system.log"
    grep -E "^cluster_name|^data_file_directories|^    - |^commitlog_directory|^saved_caches_directory|^hints_directory" \
      "$CASSANDRA_HOME/conf/cassandra.yaml"
    ;;
  *)
    echo "Dùng: $0 start|stop|status|setup"
    exit 1
    ;;
esac
