# Deploying: Render (app) and Neon (database)

> **Status: tested locally at 512 MB / 0.1 CPU, not hosted.** The production image and
> [`smoke-live.ps1`](smoke-live.ps1) were run in a local simulation ([below](#startup-time-on-a-fraction-of-a-cpu)).
> The Render and Neon steps themselves have **never been carried out**: they come from the providers' documentation.
> Why there is no hosted demo: [ADR-0010](adr/0010-no-free-tier-hosting.md).

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
with `render.yaml`'s variables, limited by Docker to 512 MB and 0.1 CPU, against an empty local Postgres. One run per
variant, back to back, from the same application code. Readiness was polled with a 5 s limit per request, like Render's
health check.

| | Without CDS | **CDS (in the image)** | CDS + lazy initialization |
|---|---|---|---|
| Spring reports "Started" | 562 s | **387 s** | 371 s |
| Readiness first answers, from `docker run` | 604 s | **433 s** | 422 s |
| Smoke test run right afterwards (all 13 checks passed each time) | 205 s | 237 s | 267 s |
| Memory after the smoke test, of 512 MiB | 307 MiB | **260 MiB** | 257 MiB |

- **Class data sharing (CDS)** is built into the [`Dockerfile`](../Dockerfile): a training run during the image build
  saves the classes the application loads. It makes the image larger (556 MB to 672 MB in `docker images`).
- **Lazy initialization is not used.** It was ready 11 s sooner, but the smoke test then took 30 s longer.
- The target of starting in about 3 minutes was **not** reached.

This is **not** a measurement of Render: a hard Docker CPU limit on a busy laptop may be much slower or faster than
Render's 0.1 CPU. Single runs also vary: an earlier run without CDS, on older code, took 700 s to start and about
1,203 s to answer readiness, which is over Render's 15-minute limit. Only the deploy on Render will show the real time.

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
| Swagger UI on | meant for a demo, so the API can be explored |

## Limits of such a demo

- **No authentication.** If you deploy it, anyone with the URL can create data, which can use up the database's storage or compute
  hours. A real service would put the API and its documentation behind authentication.
- The payment gateway is a fake that keeps its state in memory: it forgets every charge when the instance restarts.
- No demo data is loaded. Create warehouses and batches through the API first.
- No dashboards or traces: Prometheus, Grafana and Jaeger run only in the local stack.
