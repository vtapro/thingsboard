#!/bin/sh

#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

set -e

JAR_FILE="$(ls /app/thingsboard-*-boot.jar 2>/dev/null | head -n 1)"
if [ -z "${JAR_FILE}" ]; then
    JAR_FILE="$(ls /app/thingsboard-*.jar 2>/dev/null | grep -v 'sources\|javadoc' | head -n 1)"
fi

if [ -z "${JAR_FILE}" ]; then
    echo "ThingsBoard jar file not found in /app" >&2
    exit 1
fi

echo "Using jar file: ${JAR_FILE}"

if [ "${RUN_INSTALL:-true}" = "true" ]; then
    echo "Installing/upgrading ThingsBoard database schema..."
    java -cp "${JAR_FILE}" \
        -Dloader.main=org.thingsboard.server.ThingsboardInstallApplication \
        org.springframework.boot.loader.launch.PropertiesLauncher \
        || echo "Install step did not complete, continuing with server startup"
fi

exec java ${JAVA_OPTS} -jar "${JAR_FILE}"

