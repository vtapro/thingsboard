#!/bin/sh
#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Entrypoint shared by every ThingsBoard service image.
#
# Default behaviour: run the boot jar of the service. When the container is used by the schema
# install job (RUN_INSTALL=true), the installer runs first and, with RUN_INSTALL_ONLY=true, the
# container exits right after the migration instead of starting the service.

set -e

JAR_FILE="${APP_JAR:-/app/app.jar}"

if [ ! -f "${JAR_FILE}" ]; then
    echo "ThingsBoard jar not found at ${JAR_FILE}" >&2
    exit 1
fi

echo "Using jar file: ${JAR_FILE}"

if [ "${RUN_INSTALL:-false}" = "true" ]; then
    echo "Installing/upgrading the ThingsBoard database schema..."
    if ! java -cp "${JAR_FILE}" \
        -Dinstall.data_dir="${INSTALL_DATA_DIR:-/app/data}" \
        -Dloader.main=org.thingsboard.server.ThingsboardInstallApplication \
        org.springframework.boot.loader.launch.PropertiesLauncher; then
        if [ "${RUN_INSTALL_ONLY:-false}" = "true" ]; then
            echo "Install step failed" >&2
            exit 1
        fi
        echo "Install step did not complete, continuing with service startup" >&2
    fi
fi

if [ "${RUN_INSTALL_ONLY:-false}" = "true" ]; then
    echo "Install only run completed, the service is not started"
    exit 0
fi

exec java ${JAVA_OPTS} -jar "${JAR_FILE}"
