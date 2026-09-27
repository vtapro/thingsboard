#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Reference manifests: ThingsBoard microservices HA on k3s

These manifests describe the **production** topology of this fork: one image, several
deployments, each one started with a different `TB_SERVICE_TYPE`. Local development keeps
using PostgreSQL + `in-memory` queue (see `docs/local-dev.md`); Kafka, ZooKeeper and Cassandra
are required here only.

Only PostgreSQL is a **managed cluster on a dedicated server outside k3s** (TLS enabled, public
host name configured in `01-config.yaml`). Cassandra, Kafka and ZooKeeper run **inside the
cluster** (`04-kafka.yaml`, `05-zookeeper.yaml`, `06-cassandra.yaml`), so telemetry never leaves
the cluster: the CQL traffic used to cross the public network to a managed Cassandra, which added
a WAN round trip (and TLS) to every telemetry read and write. ZooKeeper (and Kafka, unless an
existing broker is used — see `docs/production-k3s.md` §3.1b) run **inside the cluster**
(`04-kafka.yaml`, `05-zookeeper.yaml`) together with the ThingsBoard services. There is no Redis:
the cache is in-process (caffeine) with short TTLs.

The production domain of this deployment is **`app.greeniq.vn`** (see `30-ingress.yaml`);
the MQTT endpoint is the `LoadBalancer` of `tb-mqtt-transport` on port `1883`.

Target cluster: **1 k3s server (control plane) + 2 workers**, plus one dedicated data server.
Every Deployment spreads its replicas over the workers with `topologySpreadConstraints`; sizing
numbers and the small-cluster tuning are in `docs/production-k3s.md` section 3.3.

| Directory/file | Content |
|---|---|
| `00-namespace.yaml` | `thingsboard` namespace |
| `01-config.yaml` | `ConfigMap` with the shared (non secret) environment |
| `02-secret.example.yaml` | template of the `Secret` — create the real one with `kubectl`, never commit values |
| `04-kafka.yaml` | in-cluster Kafka (KRaft, single broker, local-path PVC) |
| `05-zookeeper.yaml` | in-cluster ZooKeeper (discovery of the ThingsBoard cluster) |
| `06-cassandra.yaml` | in-cluster Cassandra (telemetry + latest telemetry, local-path PVC) |
| `06b-cassandra-init.yaml` | one shot job that creates the `greeniq` keyspace |
| `09-cache-specs.yaml` | cache specs shared by the services (no Redis) |
| `10-install-job.yaml` | one shot schema install/upgrade (`RUN_INSTALL_ONLY=true`) |
| `20-tb-core.yaml` | REST API + entity/telemetry handling, 2 replicas, HPA |
| `21-tb-rule-engine.yaml` | rule engine, 2 replicas, HPA |
| `22-tb-mqtt-transport.yaml` | MQTT transport, 2 replicas + `LoadBalancer` |
| `23-tb-http-transport.yaml` | HTTP transport, 2 replicas |
| `24-protocol-transports.yaml` | optional CoAP / LwM2M / SNMP transports |
| `25-tb-web-ui.yaml` | web UI |
| `26-tb-js-executor.yaml` | JS executor (rule engine scripts) |
| `30-haproxy.yaml` | HAProxy: `app.greeniq.vn` (HTTP/HTTPS) and the public MQTT port |
| `40-hpa.yaml` | horizontal pod autoscalers (CPU based, min 1 pod) |
| `30-ingress.yaml` | ingress for the web UI and `/api` |

The managed PostgreSQL must accept the public IPs of the k3s nodes (whitelist them in the
provider console) and is reached over TLS. See `docs/production-k3s.md` section 3 for the
credentials, the TLS switches and the connectivity check.

Before applying anything, read `docs/production-k3s.md`: it lists the image build (GHCR), the
required configuration, sizing, and the known upstream CE limitation with Cassandra timeseries.

## Usage

```bash
# namespace + configuration
kubectl apply -f deploy/k3s/00-namespace.yaml
kubectl apply -f deploy/k3s/01-config.yaml

# in-cluster infrastructure: ZooKeeper, and Kafka unless an existing broker is used
kubectl apply -f deploy/k3s/05-zookeeper.yaml
kubectl apply -f deploy/k3s/04-kafka.yaml
kubectl apply -f deploy/k3s/06-cassandra.yaml
kubectl -n thingsboard rollout status statefulset/tb-zookeeper --timeout=5m
kubectl -n thingsboard rollout status statefulset/tb-kafka --timeout=5m
kubectl -n thingsboard rollout status statefulset/tb-cassandra --timeout=10m

# keyspace of the telemetry database (idempotent, once per cluster)
kubectl apply -f deploy/k3s/06b-cassandra-init.yaml
kubectl -n thingsboard wait --for=condition=complete job/tb-cassandra-init --timeout=5m

# schema / system data of an empty database (first installation)
kubectl apply -f deploy/k3s/10-install-job.yaml
kubectl -n thingsboard wait --for=condition=complete job/tb-install --timeout=20m
kubectl -n thingsboard delete job tb-install

# schema / system data upgrade of an existing database: the installer creates the system
# administrator unconditionally, so the install mode fails with "already present".
# Use the upgrade mode (INSTALL_UPGRADE=true) instead:
#   powershell -File scripts\deploy-k3s.ps1 -InstallUpgrade     (or -SkipInstall when the
#   database schema of the release is already applied)

# secrets (never committed) — copy 02-secret.example.yaml and replace every value
kubectl -n thingsboard create secret generic tb-secrets \
  --from-literal=SPRING_DATASOURCE_USERNAME=postgres \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<password>' \
  --from-literal=REDIS_PASSWORD='<password>'

# the manifests already point to ghcr.io/vtapro/greeniq-thingsboard:v4.4.0.0;
# to roll a specific build of that image over every deployment:
for d in tb-core tb-rule-engine tb-mqtt-transport tb-http-transport; do
  kubectl -n thingsboard set image deployment/$d \
    $(kubectl -n thingsboard get deploy/$d -o jsonpath='{.spec.template.spec.containers[0].name}')=ghcr.io/vtapro/greeniq-thingsboard:v4.4.0.0
done

# schema install/upgrade, then the services
kubectl apply -f deploy/k3s/10-install-job.yaml
kubectl -n thingsboard wait --for=condition=complete job/tb-install --timeout=15m
kubectl apply -f deploy/k3s/20-tb-core.yaml
kubectl apply -f deploy/k3s/21-tb-rule-engine.yaml
kubectl apply -f deploy/k3s/22-tb-mqtt-transport.yaml
kubectl apply -f deploy/k3s/23-tb-http-transport.yaml
kubectl apply -f deploy/k3s/30-ingress.yaml
```

`kubectl apply -k deploy/k3s` works too (see `kustomization.yaml`), but the secret has to exist
first, and the image tag has to be set in every manifest.
