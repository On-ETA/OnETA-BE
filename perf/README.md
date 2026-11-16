# OnETA isolated APM lab — Stages 1 and 2

This branch prepares an **isolated local Docker APM lab** (OnETA + MySQL + Redis + Prometheus + Grafana).
JMeter and SQL performance test scenarios belong to the next stage.
Do not merge this branch to main without separately reviewing the changes.

## Safety model

- Only OnETA, MySQL and Redis are on a Docker `internal: true` network (no outbound route).
- A dedicated Nginx proxy bridges local ingress to the isolated app. Only the proxy\n  joins the host-facing bridge, binding `127.0.0.1:18080`; the app has **no** public network.
- MySQL and Redis have no published ports. Never use an RDS URL or real data.
- Only the `apm` profile is supported. A BeanFactoryPostProcessor rejects
  unexpected DB addresses / Redis hosts / Firebase activation before database initialization.
- All `@Scheduled` jobs are disabled by excluding `@EnableScheduling` for `apm`.
  The regular `prod`, `dev` and `local` profiles retain scheduling behavior.
- Firebase is disabled. Mail is pointed to loopback without an SMTP server.
  External HTTP calls are blocked; configurable services use deliberately invalid
  mock addresses. **Functional API mocks are not implemented in Stage 1.**
- Dockerfile copies selected source/resources only, excluding production
  profile files, local credentials and Firebase credential files.

## Requirements

- Windows Docker Desktop with Linux containers and Compose V2.
- Enough free memory/disk to build a Java 17 Spring Boot application and run MySQL.
- Check the production RDS MySQL *major version* before performance comparison.
  This lab currently uses MySQL 8.0.44 only as a starting image.

## Setup (PowerShell from the repository root)

```powershell
git switch chore/apm-lab-stage1-20261116
powershell -NoProfile -ExecutionPolicy Bypass -File perf/prepare.ps1
docker compose --env-file perf/.env.apm -f perf/compose.yaml config --quiet
docker compose --env-file perf/.env.apm -f perf/compose.yaml up -d --build
docker compose --env-file perf/.env.apm -f perf/compose.yaml ps
docker compose --env-file perf/.env.apm -f perf/compose.yaml logs --tail=100 app
```

Do not add any real service keys, Google OAuth credentials, production DB values,
FCM tokens, or user data to the lab. Only synthetic/test data is permitted.

Verify the Spring Boot startup log and inspect `flyway_schema_history`
in the **local MySQL container**:

```powershell
docker compose --env-file perf/.env.apm -f perf/compose.yaml exec mysql mysql -uoneta_lab -p oneta_apm
# In the mysql shell:
# SELECT installed_rank, version, success FROM flyway_schema_history ORDER BY installed_rank;
```

The isolated app is reachable through the localhost-only Nginx gateway at http://127.0.0.1:18080.
An authenticated API test user and mock API responses are not configured yet.

## Stop safely

```powershell
docker compose --env-file perf/.env.apm -f perf/compose.yaml down
```

This retains the local MySQL volume. Use `down -v` **only** if you
intentionally want to permanently delete the lab's local MySQL data.

## Stage 2: monitoring

- Grafana: http://127.0.0.1:13000 (ONLY local loopback).
- Username: `admin`.
- Password: generated only on the machine in `perf/.env.apm` (do not commit or share).
- To copy just the password to the Windows clipboard:
  `((Get-Content perf/.env.apm | Where-Object { $_ -like "LAB_GRAFANA_PASSWORD=*" }) -split "=",2)[1] | Set-Clipboard`
  Run this from PowerShell in the repo root. Paste only in your Grafana login.
- Open **Dashboards → OnETA Lab → OnETA | Local APM**.
- The Actuator endpoint is on app port 9091 inside `lab_internal` ONLY.
  `http://127.0.0.1:18080/actuator/prometheus` must NOT expose metrics.
- Prometheus has no host-mapped port; Grafana reads it inside Docker.
- Nginx explicitly returns HTTP 404 for `/actuator/**` on the local API port.
- Run `powershell -NoProfile -ExecutionPolicy Bypass -File perf/verify-stage2.ps1` to verify health, Grafana provisioning and the Prometheus target.
- Prometheus retains three days of monitoring samples.
- Grafana and Prometheus have separate named volumes; `down` preserves them.

Check the Prometheus scrape target from within its container:
```powershell
docker compose --env-file perf/.env.apm -f perf/compose.yaml exec prometheus wget -qO- "http://localhost:9090/api/v1/targets"
```
The `oneta-apm` target must report `health: up`. If the app has not
received any HTTP requests yet, some request metrics will be absent.
The provisioned dashboard reflects only real data, not sample numbers.

## Validation status

Stage 1 previously passed local Docker startup and Flyway v22 checks on the user's PC.
For Stage 2, verify management scraping, Prometheus target health, Grafana
provisioning and port/network isolation before using JMeter or claiming measurements.

## Key source edits

- `OnETAApplication.java`: move scheduling enablement to profile-specific config.
- `SchedulingConfiguration.java`: keep scheduling in non-`apm` profiles.
- `ApmLabSafetyConfiguration.java`: fail-fast checks for isolated settings.
- `application-apm.yml`: local DB/Redis and non-production fake credentials.

- `ApmLabMonitoringSecurityConfiguration.java`: allow read-only scraping on isolated management port.
- `perf/prometheus/`, `perf/grafana/`: service provisioning, baseline metrics dashboard.
