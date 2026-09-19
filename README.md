# TicketFlow — Backend

> **IBM Presales demo application** — showcases [Instana](https://www.ibm.com/products/instana) 100 % distributed tracing and [Turbonomic](https://www.ibm.com/products/turbonomic) resource-action intelligence on a realistic Spring Boot → Cassandra workload.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Technology Stack](#technology-stack)
4. [Project Structure](#project-structure)
5. [API Reference](#api-reference)
6. [Configuration](#configuration)
7. [Logging](#logging)
8. [Building](#building)
9. [Docker Image](#docker-image)
10. [Kubernetes Deployment](#kubernetes-deployment)
11. [OpenShift Deployment](#openshift-deployment)
12. [Health & Observability Endpoints](#health--observability-endpoints)
13. [Instana Integration](#instana-integration)
14. [Turbonomic Integration](#turbonomic-integration)
15. [Noisy-Neighbour Demo](#noisy-neighbour-demo)

---

## Overview

TicketFlow simulates a concert-ticket booking platform. A **load driver** sends a continuous stream of `POST /api/event/{action}/{outcome}/{iteration}` requests to the backend. The URL encodes the action (`write` or `search`), the expected outcome (`success` or `failure`), and a globally unique iteration counter. The backend either persists data to Cassandra or deliberately returns an HTTP 500, giving Instana a rich mix of successful traces, database spans, and error traces to visualise — and giving Turbonomic real CPU/memory pressure to act on.

---

## Architecture

```
Load Driver
    │
    │  POST /api/event/{action}/{outcome}/{iteration}  (JSON body)
    ▼
┌─────────────────────────────────┐
│  ticketflow-backend             │  Spring Boot 3 · Java 17
│  ─────────────────────────────  │
│  EventController                │
│    ├─ WRITE  → TicketEvent repo ├──► Cassandra (ticketflow keyspace)
│    ├─ SEARCH → findAll()        ├──► Cassandra (ticketflow keyspace)
│    └─ FAILURE → HTTP 500        │
└─────────────────────────────────┘
         │                │
    Instana Agent    Turbonomic Agent
    (auto-inject)    (resource actions)
```

Both the backend pods and the Cassandra StatefulSet run inside the `ticketflow` Kubernetes namespace.

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.5 |
| Database | Apache Cassandra | 4.1 |
| Data access | Spring Data Cassandra | (managed by Boot parent) |
| Logging | Apache Log4j2 | (managed by Boot parent) |
| Build | Apache Maven | 3.x |
| Container | eclipse-temurin Alpine | JDK 17 (build) / JRE 17 (runtime) |
| Orchestration | Kubernetes | 1.27+ |

---

## Project Structure

```
ticketflow-backend/
│
├── pom.xml                                         # Maven build descriptor
├── Dockerfile                                      # Multi-stage build → minimal JRE image
├── .dockerignore
├── build-and-push.sh                               # Helper: mvn package + docker buildx push
│
├── src/main/java/com/ibm/ticketflow/
│   ├── TicketFlowApplication.java                  # Spring Boot entry point
│   ├── controller/
│   │   └── EventController.java                    # POST /api/event/{action}/{outcome}/{iteration} handler
│   ├── model/
│   │   ├── EventRequest.java                       # Request payload record + enums
│   │   └── TicketEvent.java                        # Cassandra entity (@Table ticket_events)
│   └── repository/
│       └── TicketEventRepository.java              # Spring Data Cassandra CRUD interface
│
├── src/main/resources/
│   ├── application.yml                             # App config + Cassandra + Actuator
│   └── log4j2.xml                                  # Console appender with traceId in pattern
│
└── k8s/
    ├── namespace.yaml                              # ticketflow namespace
    ├── configmap.yaml                              # Non-secret Cassandra env vars
    ├── cassandra-secret.yaml                       # Cassandra credentials (Opaque Secret)
    ├── cassandra.yaml                              # Cassandra StatefulSet + headless Service
    ├── deployment.yaml                             # Backend Deployment (2 replicas)
    └── service.yaml                                # ClusterIP Service (port 80 → 8080)
```

---

## API Reference

### `POST /api/event/{action}/{outcome}/{iteration}`

`action`, `outcome`, and `iteration` are **path variables**. The full `EventRequest` JSON is still sent as the request body — the path variables and body fields are redundant by design so that Instana can group traces by the URL pattern automatically.

#### Path variables

| Variable | Values | Description |
|---|---|---|
| `{action}` | `write` \| `search` | Case-insensitive. `write` inserts a row; `search` reads all rows |
| `{outcome}` | `success` \| `failure` | Case-insensitive. `failure` bypasses Cassandra and forces an HTTP 500 |
| `{iteration}` | `int` | Globally unique, 1-based request counter from the load driver |

#### Request body

```json
{
  "iteration":       1,
  "sentAt":          "2024-06-01T12:00:00Z",
  "action":          "WRITE",
  "expectedOutcome": "SUCCESS"
}
```

| Field | Type | Description |
|---|---|---|
| `iteration` | `int` | Globally unique, 1-based request counter from the load driver |
| `sentAt` | `string` | ISO-8601 timestamp set by the load driver at request creation time |
| `action` | `WRITE` \| `SEARCH` | Must match the `{action}` path variable (uppercase in body) |
| `expectedOutcome` | `SUCCESS` \| `FAILURE` | Must match the `{outcome}` path variable (uppercase in body) |

#### Response matrix

| `action` | `expectedOutcome` | HTTP | Body |
|---|---|---|---|
| `WRITE` | `SUCCESS` | 200 | `{ "status":"SUCCESS", "action":"WRITE", "iteration":N, "id":"<uuid>" }` |
| `SEARCH` | `SUCCESS` | 200 | `{ "status":"SUCCESS", "action":"SEARCH", "iteration":N, "count":N }` |
| any | `FAILURE` | 500 | `{ "status":"FAILURE", "iteration":N, "message":"Intentional failure…" }` |

#### Example — successful write

```bash
curl -s -X POST http://localhost:8080/api/event/write/success/1 \
  -H "Content-Type: application/json" \
  -d '{"iteration":1,"sentAt":"2024-06-01T12:00:00Z","action":"WRITE","expectedOutcome":"SUCCESS"}'
```

```json
{
  "status": "SUCCESS",
  "action": "WRITE",
  "iteration": 1,
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

#### Example — intentional failure

```bash
curl -s -X POST http://localhost:8080/api/event/write/failure/2 \
  -H "Content-Type: application/json" \
  -d '{"iteration":2,"sentAt":"2024-06-01T12:00:01Z","action":"WRITE","expectedOutcome":"FAILURE"}'
```

```json
{
  "status": "FAILURE",
  "iteration": 2,
  "message": "Intentional failure as requested by load driver"
}
```

#### Example — search

```bash
curl -s -X POST http://localhost:8080/api/event/search/success/101 \
  -H "Content-Type: application/json" \
  -d '{"iteration":101,"sentAt":"2024-06-01T12:00:05Z","action":"SEARCH","expectedOutcome":"SUCCESS"}'
```

```json
{
  "status": "SUCCESS",
  "action": "SEARCH",
  "iteration": 101,
  "count": 98
}
```

#### Instana URL grouping

Because the iteration counter is a path segment, Instana automatically groups all calls into exactly these four patterns:

| Pattern | Meaning |
|---|---|
| `POST /api/event/write/success/{iteration}` | Normal write operations |
| `POST /api/event/write/failure/{iteration}` | Intentional write errors |
| `POST /api/event/search/success/{iteration}` | Normal search operations |
| `POST /api/event/search/failure/{iteration}` | Intentional search errors |

---

## Configuration

All Cassandra coordinates are injected via environment variables with safe defaults for local development.

| Environment variable | Default | Description |
|---|---|---|
| `CASSANDRA_CONTACT_POINTS` | `cassandra` | Hostname(s) of the Cassandra node(s) |
| `CASSANDRA_PORT` | `9042` | CQL native transport port |
| `CASSANDRA_KEYSPACE` | `ticketflow` | Keyspace name (auto-created on first boot) |
| `CASSANDRA_DATACENTER` | `datacenter1` | Local datacenter for load-balancing policy |
| `CASSANDRA_USERNAME` | `cassandra` | CQL username (injected from K8s Secret) |
| `CASSANDRA_PASSWORD` | `cassandra` | CQL password (injected from K8s Secret) |

`schema-action: CREATE_IF_NOT_EXISTS` in [`application.yml`](src/main/resources/application.yml) means the `ticket_events` table is created automatically on the first boot — no manual CQL is required.

### Cassandra table schema (auto-generated)

```cql
CREATE TABLE ticketflow.ticket_events (
    id           uuid PRIMARY KEY,
    iteration    int,
    sent_at      text,
    action       text,
    outcome      text,
    processed_at timestamp
);
```

---

## Logging

Logging is handled by **Apache Log4j2** ([`log4j2.xml`](src/main/resources/log4j2.xml)). Logback is explicitly excluded from the Maven dependencies so there is no classpath conflict.

### Log pattern

```
yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger iteration=%X{iteration} traceId=%X{traceId} – message
```

The `traceId` MDC key is populated automatically by the Instana Java agent, correlating every log line to its distributed trace in the Instana UI.

### Log levels

| Logger | Level | Rationale |
|---|---|---|
| `com.ibm.ticketflow` | `DEBUG` | Full visibility of every trace hop for demo purposes |
| `org.springframework` | `INFO` | Reduce framework noise |
| `com.datastax.oss.driver` | `INFO` | Reduce Cassandra driver noise |
| Root | `WARN` | Suppress all other libraries |

---

## Building

### Prerequisites

- Java 17+
- Apache Maven 3.8+

### Compile and package

```bash
mvn package -DskipTests
# → target/ticketflow-backend-1.0.0.jar
```

### Run locally (requires a reachable Cassandra instance)

```bash
CASSANDRA_CONTACT_POINTS=localhost \
  java -jar target/ticketflow-backend-1.0.0.jar
```

---

## Docker Image

The [`Dockerfile`](Dockerfile) uses a **two-stage build**:

| Stage | Base image | Purpose |
|---|---|---|
| `builder` | `maven:3.9-eclipse-temurin-17` | JDK 17 + Maven pre-installed — no `apt install` needed |
| runtime | `eclipse-temurin:17-jre` | Minimal JRE, non-root user, layered Spring Boot |

The `pom.xml` is copied and dependencies are downloaded (`mvn dependency:go-offline`) **before** `src/` is copied. This means the dependency layer is cached by Podman/Docker and only re-downloaded when `pom.xml` actually changes — not on every source code edit.

JVM flags applied at runtime:
- `-XX:+UseContainerSupport` — respects the cgroup CPU/memory limits set by Kubernetes
- `-XX:MaxRAMPercentage=75.0` — heap is capped at 75 % of the container memory limit

### Build and push manually

```bash
./build-and-push.sh                   # tag 1.0.0, no deploy
./build-and-push.sh 1.1.0             # custom tag, no deploy
./build-and-push.sh --deploy          # tag 1.0.0 + rolling restart
./build-and-push.sh 1.1.0 --deploy    # custom tag + rolling restart
```

The script builds a **multi-arch image** (`linux/amd64` + `linux/arm64`) and pushes two tags:

| Tag | Description |
|---|---|
| `lehnerj1207/ticketflow:backend-<version>` | Immutable versioned tag |
| `lehnerj1207/ticketflow:backend-latest` | Floating convenience tag |

### Ensuring the cluster picks up a new image without changing the tag

The Deployment uses `imagePullPolicy: Always`, so every new Pod start pulls the latest image from the registry. However, `kubectl apply` on an **unchanged manifest does nothing** — no new Pod is started, no new image is pulled.

Use one of these approaches:

| Approach | Command | When to use |
|---|---|---|
| `--deploy` flag | `./build-and-push.sh --deploy` | Easiest — push + restart in one step |
| Manual rollout restart | `kubectl rollout restart deployment/ticketflow-backend -n ticketflow` | When you pushed separately |
| Watch rollout | `kubectl rollout status deployment/ticketflow-backend -n ticketflow` | Verify the new pods are up |

---

## Kubernetes Deployment

All manifests live in [`k8s/`](k8s/) and target the `ticketflow` namespace.

### Apply order

```bash
kubectl apply -f k8s/namespace.yaml        # 1. Create namespace first
kubectl apply -f k8s/cassandra-secret.yaml # 2. Credentials
kubectl apply -f k8s/configmap.yaml        # 3. Non-secret config
kubectl apply -f k8s/cassandra.yaml        # 4. Database (StatefulSet + headless Service)
kubectl apply -f k8s/deployment.yaml       # 5. Backend pods
kubectl apply -f k8s/service.yaml          # 6. ClusterIP Service
```

> **Tip:** wait for Cassandra to become Ready before the backend starts:
> ```bash
> kubectl rollout status statefulset/cassandra -n ticketflow
> ```

### Resource summary

| Resource | Kind | Replicas | CPU req/limit | Memory req/limit |
|---|---|---|---|---|
| `ticketflow-backend` | Deployment | 2 | 250m / 1000m | 256Mi / 512Mi |
| `cassandra` | StatefulSet | 1 | 500m / 1000m | 768Mi / 1Gi |

### Cassandra StatefulSet

- **Headless Service** (`clusterIP: None`) is required for Cassandra peer discovery within the StatefulSet.
- A **5 Gi PersistentVolumeClaim** (`cassandra-data`) is provisioned per replica for durable storage.
- Readiness/liveness probes run `cqlsh -e "describe keyspaces"` to verify CQL connectivity before traffic is accepted.

### Secrets

[`k8s/cassandra-secret.yaml`](k8s/cassandra-secret.yaml) stores the CQL credentials as a Kubernetes `Opaque` Secret. **Replace the default values before applying to any non-demo cluster.**

```bash
kubectl create secret generic cassandra-secret \
  --from-literal=username=<your-user> \
  --from-literal=password=<your-password> \
  -n ticketflow \
  --dry-run=client -o yaml | kubectl apply -f -
```

---


## OpenShift Deployment

OpenShift enforces stricter security defaults than vanilla Kubernetes (via **Security Context Constraints**, or SCCs). All manifests in [`k8s/`](k8s/) have been updated to be fully compatible with the default `restricted-v2` SCC. The changes and the reasons behind them are documented below.

### What is different vs vanilla Kubernetes

| Area | Vanilla K8s | OpenShift (restricted-v2) |
|---|---|---|
| Namespace | `Namespace` manifest | `Project` object — use [`k8s/project.yaml`](k8s/project.yaml) |
| External access | `LoadBalancer` / `NodePort` | `Route` object — use [`k8s/route.yaml`](k8s/route.yaml) |
| Container UID | Any UID | Must be non-zero; numeric `USER` in Dockerfile required |
| Root filesystem | Allowed | Blocked unless SCC is relaxed |
| Privilege escalation | Allowed | Must be explicitly disabled (`allowPrivilegeEscalation: false`) |
| Linux capabilities | Inherited | Must drop `ALL` |

### Apply order on OpenShift

```bash
# 1. Create the project (OpenShift equivalent of a namespace)
oc apply -f k8s/project.yaml
# or: oc new-project ticketflow

# 2. Credentials and config
oc apply -f k8s/cassandra-secret.yaml
oc apply -f k8s/configmap.yaml

# 3. Database
oc apply -f k8s/cassandra.yaml
oc rollout status statefulset/cassandra -n ticketflow

# 4. Backend
oc apply -f k8s/deployment.yaml
oc apply -f k8s/service.yaml

# 5. Expose externally via Route
oc apply -f k8s/route.yaml

# Get the auto-assigned public URL
oc get route ticketflow-backend -n ticketflow -o jsonpath='{.spec.host}'
```

### Dockerfile — numeric USER

OpenShift assigns an arbitrary UID from the project's UID range at runtime. The `USER 1001` directive in the [`Dockerfile`](Dockerfile) satisfies the `runAsNonRoot` constraint without relying on a named OS user that may not exist in the runtime environment. OpenShift overrides this UID with its own assigned value — the container still starts because the image does not declare a named user that must match.

### Cassandra on OpenShift

The stock `cassandra:4.1` image runs its daemon as UID 999. Two changes make it OpenShift-compatible:

1. **`fsGroup: 999`** on the pod `securityContext` — ensures the PVC mounted at `/var/lib/cassandra` is group-writable by GID 999, regardless of the runtime UID assigned by OpenShift.
2. **`initContainers` chown** — a `busybox` init container runs briefly as root to `chown -R 999:999 /var/lib/cassandra` before Cassandra starts. The main container itself is non-root.

> **Note:** the init container declares `runAsUser: 0`. On a default OpenShift cluster this will be blocked by `restricted-v2`. You have two options:
> - Grant the `anyuid` SCC to the `cassandra` service account: `oc adm policy add-scc-to-user anyuid -z default -n ticketflow`
> - Use a community Cassandra image already hardened for OpenShift (e.g. `bitnami/cassandra` which runs as UID 1001 natively)

### Route and TLS

[`k8s/route.yaml`](k8s/route.yaml) creates an **edge-terminated TLS Route**. The OpenShift HAProxy router handles the TLS certificate (using the cluster's wildcard cert by default) and forwards plain HTTP internally to the `ticketflow-backend` Service on port 80. HTTP traffic is auto-redirected to HTTPS.

To use a custom hostname, uncomment and set `spec.host` in [`k8s/route.yaml`](k8s/route.yaml):

```yaml
spec:
  host: ticketflow.apps.<your-cluster-domain>
```

---


## Health & Observability Endpoints

Exposed by Spring Boot Actuator on port `8080`:

| Endpoint | Description | Used by |
|---|---|---|
| `GET /actuator/health/liveness` | JVM is alive | K8s liveness probe |
| `GET /actuator/health/readiness` | App + Cassandra ready | K8s readiness probe |
| `GET /actuator/health` | Full health detail | Instana health monitoring |
| `GET /actuator/prometheus` | Prometheus metrics scrape | Turbonomic / monitoring stack |

---

## Instana Integration

The Instana Java agent auto-instruments the application **without any code changes**. The pod annotation in [`k8s/deployment.yaml`](k8s/deployment.yaml) tells the agent to trace the `com.ibm.ticketflow` package with full SDK instrumentation:

```yaml
annotations:
  instana.io/agent-configuration: |
    com.instana.plugin.javatrace:
      instrumentation:
        sdk:
          packages:
            - 'com.ibm.ticketflow'
```

What Instana captures out of the box:

- **Every HTTP span** on `POST /api/event/{action}/{outcome}/{iteration}` — including the intentional 500s, which appear as error traces
- **Every Cassandra span** — table name, CQL query type, latency
- **Log correlation** — the `traceId` MDC field in every log line links directly to the trace in the Instana UI
- **Service map** — Load Driver → ticketflow-backend → Cassandra topology is automatically discovered

---

## Turbonomic Integration

Turbonomic discovers the `ticketflow-backend` pods via its Kubernetes probe and uses the Prometheus metrics scraped from `/actuator/prometheus` to build a resource supply-chain model. During a load-driver burst it will:

1. Detect CPU saturation on the backend pods
2. Recommend (or automatically execute, if in automation mode) a **vertical scale-up** action
3. Detect Cassandra heap pressure and recommend a **memory resize** on the StatefulSet

This gives presales the live story: *"We can see the problem in Instana, and Turbonomic is already telling us how to fix the resource allocation."*

---

## Noisy-Neighbour Demo

[`k8s/noisy-neighbour.yaml`](k8s/noisy-neighbour.yaml) deploys a `stress-ng` pod that burns CPU on the **same node** as `ticketflow-backend` (enforced by `podAffinity`). This creates realistic resource contention without touching the application code.

### How it works

```
Node A
├── ticketflow-backend   ← real workload
└── noisy-neighbour      ← stress-ng, co-located by podAffinity
        burns ~1.6 cores → starves the backend → response time rises
```

When Turbonomic detects the contention it recommends (or executes) a **pod move**:

```
Before                         After Turbonomic action
──────────────────────         ──────────────────────────────
Node A  ticketflow-backend  →  Node B  ticketflow-backend  ← stable
Node A  noisy-neighbour        Node A  noisy-neighbour     ← stays behind
```

### Demo step-by-step

```bash
# 1. Make sure the load driver is running and Instana shows baseline response times

# 2. Deploy the noisy neighbour — CPU contention starts immediately
kubectl apply -f k8s/noisy-neighbour.yaml

# 3. Verify co-location (both pods must be on the same node)
kubectl get pods -n ticketflow -o wide

# 4. Watch response times rise in Instana
#    POST /api/event/write/success/{iteration} latency should increase visibly

# 5. In Turbonomic: observe the "Move Pod" action for ticketflow-backend
#    Either let Turbonomic execute it automatically (if in automation mode)
#    or approve it manually in the Actions view

# 6. After the pod move: response times return to baseline on the new node

# 7. Clean up the stressor when the demo is done
kubectl delete -f k8s/noisy-neighbour.yaml
```

### Tuning the load

Edit the `stress-ng` command in [`k8s/noisy-neighbour.yaml`](k8s/noisy-neighbour.yaml) to match your cluster:

| Parameter | Default | Effect |
|---|---|---|
| `--cpu 2` | 2 workers | Number of CPU stressor threads |
| `--cpu-load 80` | 80 % | Target utilisation per worker (~1.6 cores total) |

Increase `--cpu` if your nodes have many cores and the default load is not enough to affect response times. Increase `--cpu-load` towards 100 % for more aggressive saturation.

> **Note on `requests` vs actual usage:** The stressor pod is deliberately configured with a low CPU *request* (`100m`) so the scheduler places it freely next to the backend. The CPU *limit* is `2000m` — the actual burn. This mismatch is exactly what creates the "noisy neighbour" effect that Turbonomic is designed to detect.
