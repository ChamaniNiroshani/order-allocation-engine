# Contributing / ways of working

## Workflow
1. One GitHub issue per small task.
2. One branch per issue, named with the same type as the PR: `fix/failed-orders-persist`, `feat/optimistic-retry`.
3. Open a PR (the template fills in the description), with `Closes #<issue>` at the bottom.
4. Squash-merge. The PR title becomes the single commit on `main`.

## PR title = commit message
Format: `<type>(<scope>): <short summary>`

| Type | Use for |
|---|---|
| `feat` | new feature |
| `fix` | bug fix |
| `test` | tests only |
| `docs` | README, ADRs, docs |
| `chore` | setup, config, dependencies |
| `ci` | GitHub Actions |
| `refactor` | code change with no behaviour change |
| `perf` | performance work, benchmarks |

Scopes (optional): `concurrency`, `saga`, `idempotency`, `allocation`, `observability`, `devops`.

Rules: lowercase · no full stop · imperative ("add", not "added") · under 72 characters.

Examples:
```
chore(devops): run Postgres via Spring Boot Docker Compose, use Java 21
fix(allocation): persist FAILED orders when stock is insufficient
feat(concurrency): add optimistic locking with bounded retry
docs: add ADR template and first two ADRs
```

## Local setup
- JDK 25 (or 21). Spring Boot 3.5 does not support JDK 26.
- Start Docker Desktop, then run the app. Spring Boot starts Postgres from `compose.yaml` automatically.
