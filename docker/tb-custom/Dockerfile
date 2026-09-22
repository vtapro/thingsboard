# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0

FROM eclipse-temurin:25-jdk AS builder

ARG MAVEN_VERSION=3.9.11

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git patch python3 make g++ \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz \
 && mkdir -p /opt/maven \
 && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 \
 && rm /tmp/maven.tar.gz

ENV PATH="/opt/maven/bin:${PATH}"
ENV MAVEN_OPTS="-Xmx4g"

WORKDIR /build
COPY . .

# Build all modules without packaging (deb/rpm/zip/bootjar are not needed for the docker image)
RUN --mount=type=cache,target=/root/.m2 mvn -B -T 1C clean install -Dmaven.test.skip=true -Dpkg.skip=true

# Produce the executable Spring Boot jar of the application module
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl application package \
 -Dmaven.test.skip=true \
 -Dpkg.skip.deb=true \
 -Dpkg.skip.rpm=true \
 -Dpkg.skip.zip=true

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder /build/application/target/thingsboard-*.jar /app/
COPY --from=builder /build/application/src/main/data /app/data
COPY --from=builder /build/dao/src/main/resources/sql /app/data/sql
COPY --from=builder /build/dao/src/main/resources/cassandra /app/data/cassandra
COPY docker/tb-custom/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080 1883 7070 5683/udp

ENTRYPOINT ["/app/entrypoint.sh"]
