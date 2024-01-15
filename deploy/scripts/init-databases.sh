#!/bin/sh
# ---------------------------------------------------------------------------
# Creates one database per service in the shared local PostgreSQL container.
#
# Database-per-service is a real boundary even on one instance: no service can
# query another's tables, and each has its own Flyway history. Production uses
# separate instances — see docs/ENVIRONMENTS.md.
#
# Runs once, on first container start, from docker-entrypoint-initdb.d.
# ---------------------------------------------------------------------------
set -eu

for db in dinehub_menu dinehub_orders dinehub_payments dinehub_kitchen dinehub_notifications; do
  echo "Creating database ${db}"
  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<SQL
    CREATE DATABASE ${db};
    GRANT ALL PRIVILEGES ON DATABASE ${db} TO ${POSTGRES_USER};
SQL
done

echo "All service databases created."
