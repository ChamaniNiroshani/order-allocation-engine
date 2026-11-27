# ADR-0010: No hosted demo on a free tier

Date: 2026-11-27
Status: Accepted

## Context

The plan was a public demo: the app on Render's free web service, the database on Neon's free plan, both in Singapore.
Render's free instance has **512 MB of RAM and 0.1 CPU**, spins down after 15 minutes without inbound traffic, and
cancels a deploy that is not healthy within 15 minutes ([Render docs](https://render.com/docs/free), checked
2026-11-27; [deploy.md](../deploy.md) has the details). So a visitor to a sleeping demo would wait for the instance to
wake and then for the application to start.

Startup was simulated on the loaded development laptop: the production image with [`render.yaml`](../../render.yaml)'s
variables, limited by Docker to 512 MB and 0.1 CPU, against an empty local Postgres. One run per variant, from the same
application code:

| | Before | CDS (in the image) | CDS + lazy initialization |
|---|---|---|---|
| Spring reports "Started" | 562 s | 387 s | 371 s |
| Readiness first answers, from `docker run` | 604 s | 433 s | 422 s |
| Smoke test run right afterwards (all 13 checks passed each time) | 205 s | 237 s | 267 s |
| Memory after the smoke test, of 512 MiB | 307 MiB | 260 MiB | 257 MiB |

Class data sharing (CDS) is in the [`Dockerfile`](../../Dockerfile). Lazy initialization was not adopted: it was ready
11 s sooner and its smoke test then took 30 s longer. With CDS, a cold start is still about **7 minutes**.

These are not measurements of Render, whose 0.1 CPU may behave differently from Docker's limit. Single runs also vary:
an earlier run without CDS, on older code, answered readiness only after about 1,203 s.

## Options considered

1. **Render free web service with Neon free Postgres.** No cost, and a link anyone can try. But a visitor who finds the
   instance asleep would, going by the simulation, wait minutes for a first answer. Pinging it to keep it awake was
   rejected: the expiry job queries the database every 30 seconds, so the database could not suspend either and its
   monthly compute hours would run out.
2. **A paid host or instance** with more CPU. Likely to start much faster (not measured), for a recurring cost.
3. **A recorded demo video**, and one command to run the whole stack locally. Always shows the system working, with its
   dashboards and traces. Nobody can try it without Docker.

## Decision

Do not host the demo: show it in a recorded video, and keep the project ready to deploy.

A live link that takes about 7 minutes to answer would hurt more than help.

## Consequences

- No public URL, so nothing to keep running and no unauthenticated API open to the internet.
- A reader needs the video, or Docker to run it.
- `render.yaml`, the `prod` profile, [deploy.md](../deploy.md) and [`smoke-live.ps1`](../smoke-live.ps1) stay. They
  were exercised only against the local simulation: the Render and Neon steps have never been carried out.
- The CDS work stays: it makes the image start faster in the simulation, at the price of a larger image.

## What would change this decision

- **A paid instance** with enough CPU to start quickly.
- **A GraalVM native image**, which could cut startup time substantially. It has not been tried here: it adds a much
  slower build and needs the reflection used by the libraries to be checked.
