#
# SPDX-FileCopyrightText: Copyright The Thingsboard Authors
# SPDX-License-Identifier: Apache-2.0
#

# Reference manifests: ThingsBoard microservices HA on k3s

These manifests describe the **production** topology of this fork: one image, several
deployments, each one started with a different `TB_SERVICE_TYPE`. Local development keeps
using PostgreSQL + `in-memory` queue (see `docs/local-dev.md`); Kafka, ZooKeeper and Cassandra
are required here only.

The data plane (PostgreSQL, Cassandra, Kafka, ZooKeeper, Redis/Valkey) runs on a **dedicated
server outside the cluster** and is linked in with `03-external-data-plane.yaml`.

The production domain of this deployment is **`app.greeniq.vn`** (see `30-ingress.yaml`);
the MQTT endpoint is the `LoadBalancer` of `tb-mqtt-transport` on port `1883`.

| Directory/file | Content |
|---|---|
| `00-namespace.yaml` | `thingsboard` namespace |
| `01-config.yaml` | `ConfigMap` with the shared (non secret) environment |
| `02-secret.example.yaml` | template of the `Secret` — create the real one with `kubectl`, never commit values |
| `03-external-data-plane.yaml` | maps `tb-postgres` / `tb-cassandra` / `tb-kafka` / `tb-zookeeper` / `tb-redis` to the IP of the data server — **edit the IPs first** |
| `10-install-job.yaml` | one shot schema install/upgrade (`RUN_INSTALL_ONLY=true`) |
| `20-tb-core.yaml` | REST API + entity/telemetry handling, 2 replicas, HPA |
| `21-tb-rule-engine.yaml` | rule engine, 2 replicas, HPA |
| `22-tb-mqtt-transport.yaml` | MQTT transport, 2 replicas + `LoadBalancer` |
| `23-tb-http-transport.yaml` | HTTP transport, 2 replicas |
| `30-ingress.yaml` | ingress for the web UI and `/api` |

The data server has to expose PostgreSQL `5432`, Cassandra `9042`, Kafka `9092`, ZooKeeper `2181`
and Redis `6379` to the k3s pod/service CIDR (`10.42.0.0/16`, `10.43.0.0/16` by default) and
Kafka has to advertise an address the pods can reach. See `docs/production-k3s.md` section 3 for
the per component requirements and the connectivity check.

Before applying anything, read `docs/production-k3s.md`: it lists the image build (GHCR), the
required configuration, sizing, and the known upstream CE limitation with Cassandra timeseries.

## Usage

```bash
# namespace + configuration
kubectl apply -f deploy/k3s/00-namespace.yaml
kubectl apply -f deploy/k3s/01-config.yaml

# link the external data server (edit the IPs inside the file first)
kubectl apply -f deploy/k3s/03-external-data-plane.yaml

# secrets (never committed) — copy 02-secret.example.yaml and replace every value
kubectl -n thingsboard create secret generic tb-secrets \
  --from-literal=SPRING_DATASOURCE_USERNAME=postgres \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<password>' \
  --from-literal=CASSANDRA_USERNAME=cassandra \
  --from-literal=CASSANDRA_PASSWORD='<password>' \
  --from-literal=REDIS_PASSWORD='<password>'

# the manifests already point to ghcr.io/vtapro/greeniq-backend:v4.4.0.0;
# to roll a specific build of that image over every deployment:
for d in tb-core tb-rule-engine tb-mqtt-transport tb-http-transport; do
  kubectl -n thingsboard set image deployment/$d \
    $(kubectl -n thingsboard get deploy/$d -o jsonpath='{.spec.template.spec.containers[0].name}')=ghcr.io/vtapro/greeniq-backend:v4.4.0.0
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
