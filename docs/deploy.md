# Deploying the public demo: Render (app) and Neon (database)

Both in Singapore, both on free plans. The app is built from the [`Dockerfile`](../Dockerfile) as described by
[`render.yaml`](../render.yaml), and runs with the `prod` profile.

## What the free plans mean

From the providers' pages, checked 2026-11-27. Terms change: check them again before relying on them.

| | Free plan | Source |
|---|---|---|
| Render web service | 512 MB RAM, 0.1 CPU. Spins down after 15 minutes without inbound traffic; waking "takes about one minute", before the app itself has started. 750 instance hours per workspace per month | [Render docs](https://render.com/docs/free) |
| Neon Postgres | 1 GB per project, 100 compute-unit hours per month, suspends after 5 minutes of inactivity. When the hours run out, compute is suspended until the next billing period | [Neon pricing](https://neon.com/pricing), [plans](https://neon.com/docs/introduction/plans) |

**Do not add a keep-awake ping.** While the app is awake, the expiry job queries the database every 30 seconds, so
Neon cannot suspend. At 0.25 compute units, 100 compute-unit hours are about 400 awake hours: less than a month.

## 1. Database (Neon)

1. Sign up at <https://console.neon.tech>.
2. **New Project**: name `order-allocation-engine`, Postgres version **18** (if asked), region
   **AWS Asia Pacific (Singapore)**.
3. Click **Connect**. In "Connect to your branch", turn **Connection pooling off**: Flyway should use the direct
   connection, and this app has its own small pool. The host name must **not** contain `-pooler`.
4. Neon shows a string like `postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require&channel_binding=require`.
   Java needs it in three parts:

| Variable | Value |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://HOST/DATABASE?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | `USER` |
| `SPRING_DATASOURCE_PASSWORD` | `PASSWORD` |

The URL must start with `jdbc:` and must not contain the user or the password. Type the values only into Render:
never into a file in this repository, an issue or a chat.

## 2. App (Render)

1. Sign up at <https://dashboard.render.com> with GitHub and give Render access to this repository.
2. **New > Blueprint**, then **Connect** next to the repository.
3. Name the Blueprint and choose the branch that contains `render.yaml`.
4. Render lists one web service on the free plan in Singapore and asks for the three `SPRING_DATASOURCE_*` values.
   It asks **only when the Blueprint is first created**; later, change them on the service's **Environment** page.
5. **Deploy Blueprint**, then watch the service's logs. Flyway creates the schema on the first start. The deploy is
   live when `/actuator/health/readiness` answers; Render cancels a deploy that is not healthy within 15 minutes.

New commits on the linked branch deploy after CI passes. CI runs on pull requests and on pushes to `main`, so a
feature branch without a pull request has no checks to wait for: use **Manual Deploy** there. If the service was linked
to a feature branch, change the branch to `main` after merging, before deleting the feature branch.

### Startup time on a fraction of a CPU

Startup is the main risk on this plan. Simulated on 2026-11-27 on the loaded development laptop: the production image
with `render.yaml`'s variables, limited by Docker to 512 MB and 0.1 CPU, against an empty local Postgres.

| | Result |
|---|---|
| Spring reports "Started" | after 700 s (including the five migrations of an empty database) |
| First successful readiness answer | about 1,203 s after the container started |
| Readiness once warm | about 1 s |
| Memory after the smoke test | 297 MiB of 512 MiB; not killed, no restarts |
| Smoke test | all checks passed |

This is **not** a measurement of Render: a hard Docker CPU limit on a busy laptop may be much slower or faster than
Render's 0.1 CPU. If it were representative, a first deploy would exceed Render's 15-minute limit.

## 3. Check it

```powershell
.\docs\smoke-live.ps1 -BaseUrl https://<your-service>.onrender.com
```

It waits for readiness, checks Swagger UI, makes one order, one checkout and one fulfilment, and checks that
`/actuator/prometheus` and the other actuator endpoints answer 404. For a cold-start time, run it after the service
has had no traffic for more than 15 minutes.

## What the prod profile changes

[`application-prod.properties`](../src/main/resources/application-prod.properties):

| Setting | Why |
|---|---|
| Datasource only from `SPRING_DATASOURCE_*` | no fallback to the local development database or password |
| Pool of at most 5 connections, 10 s connection timeout | small instance and free database; time for the database to wake |
| Actuator exposes `health` only | no public metrics; readiness is `/actuator/health/readiness` |
| `reservations.active` refresh off | nothing reads it here, and it queried the database every 5 seconds |
| JSON logs, trace export off | no tracing backend is set up |
| Swagger UI on | this is a public demo |

## Limits of this demo

- **No authentication.** Anyone with the URL can create data, which can use up the database's storage or compute
  hours. A real service would put the API and its documentation behind authentication.
- The payment gateway is a fake that keeps its state in memory: it forgets every charge when the instance restarts.
- No demo data is loaded. Create warehouses and batches through the API first.
- No dashboards or traces: Prometheus, Grafana and Jaeger run only in the local stack.
