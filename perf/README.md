# OnETA isolated APM lab — Stage 1

This branch only prepares an **isolated local Docker lab** (OnETA + MySQL + Redis).
JMeter, Prometheus, Grafana and SQL test scenarios belong to later stages.
Do not merge this branch to main without separately reviewing the changes.

## Safety model

- Compose uses a Docker `internal: true` network, with no outbound network route.
- Application HTTP port is bound to the local host only (`127.0.0.1:18080`).
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

The app is reachable only from the same PC at http://127.0.0.1:18080.
An authenticated API test user and mock API responses are not configured yet.

## Stop safely

```powershell
docker compose --env-file perf/.env.apm -f perf/compose.yaml down
```

This retains the local MySQL volume. Use `down -v` **only** if you
intentionally want to permanently delete the lab's local MySQL data.

## Validation status

Code and configuration are prepared in this branch; Docker has **not** been
run on the user's PC. Validate `docker compose config`, container startup,
Flyway migrations, external-egress isolation and all scheduler disabling on
the actual PC before using JMeter or claiming measurements.

## Key source edits

- `OnETAApplication.java`: move scheduling enablement to profile-specific config.
- `SchedulingConfiguration.java`: keep scheduling in non-`apm` profiles.
- `ApmLabSafetyConfiguration.java`: fail-fast checks for isolated settings.
- `application-apm.yml`: local DB/Redis and non-production fake credentials.
