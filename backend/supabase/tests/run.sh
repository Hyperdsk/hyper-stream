#!/usr/bin/env bash
# Tests schema.sql on a plain Postgres: loads the Supabase stand-ins, runs the schema twice (it
# must be safe to run again), then the checks. Needs psql; point it at a server with the usual
# PG* variables (PGHOST, PGPORT, PGUSER...). Uses, and drops, a database named hs_schema_test.
set -euo pipefail
cd "$(dirname "$0")"
psql -q -v ON_ERROR_STOP=1 -d postgres -c 'drop database if exists hs_schema_test' -c 'create database hs_schema_test'
run() { psql -q -v ON_ERROR_STOP=1 -d hs_schema_test "$@"; }
run -f supabase_stub.sql
run -f ../schema.sql
run -f ../schema.sql
run -f schema_test.sql
psql -q -d postgres -c 'drop database hs_schema_test'
