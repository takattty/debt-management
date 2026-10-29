#!/usr/bin/env bash
set -euo pipefail
# Direct PostgreSQL fallback for Cloud executors without Docker.
# Install PostgreSQL 18 with the executor's supported setup workflow first.
: "${PG_BIN:?Set PG_BIN to PostgreSQL 18 bin directory}"
PGDATA="${PGDATA:-$PWD/work/pgdata}"
PGPORT="${PGPORT:-5432}"
mkdir -p work
if [ ! -f "$PGDATA/PG_VERSION" ]; then
  "$PG_BIN/initdb" -D "$PGDATA" --auth-local=trust --auth-host=scram-sha-256 --username=debt --pwfile=<(printf '%s\n' "${DB_PASSWORD:-debt_local}")
fi
"$PG_BIN/pg_ctl" -D "$PGDATA" -l "$PWD/work/postgres.log" -o "-h 127.0.0.1 -p $PGPORT" start
PGPASSWORD="${DB_PASSWORD:-debt_local}" "$PG_BIN/createdb" -h 127.0.0.1 -p "$PGPORT" -U debt debt
