# ADR-0007: Flyway owns the schema; Hibernate only validates it

Date: 2026-11-13 (written 2026-11-27 from the code and commit history)
Status: Accepted

## Context

The schema was first produced by Hibernate's `ddl-auto=update`. Schema changes need to be versioned and reviewable,
and `update` has limits: it cannot add a `NOT NULL` column to a table that already has rows, and it had mapped the
audit log's before and after states to `oid` instead of text.

## Options

1. **Keep `ddl-auto=update`.** No migration files, but changes are implicit, unreviewed, and limited as above.
2. **Flyway with plain SQL migrations**, and Hibernate set to `validate`.
3. **Liquibase.** Also versioned, with changesets that can be written independently of the database. This project
   targets only PostgreSQL.

## Decision

Use Flyway (option 2): migrations live in `src/main/resources/db/migration`, they run when the application starts,
and `spring.jpa.hibernate.ddl-auto=validate`.

## Consequences

- Each schema change is a SQL file reviewed in a pull request (`V1` is the baseline, `V2` to `V5` follow).
- With `validate`, the application does not start if the entities and the schema disagree in a way Hibernate checks.
- Constraints and indexes are written by hand. Some repeat Java enums (order status, strategy, customer tier), so a
  new enum value also needs a migration.
- Demo data is a repeatable migration in `db/seed`, loaded only by the `dev` profile.
- `baseline-on-migrate` is not set: a database previously created by `ddl-auto` is not adopted automatically.
- Migrations that have been applied must not be edited; a correction is a new migration.
- Tests run the same migrations against a throwaway PostgreSQL (Testcontainers).
