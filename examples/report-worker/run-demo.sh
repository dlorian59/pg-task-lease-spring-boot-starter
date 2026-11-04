#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Install the core and starter in Maven Local before running this script.
# Установите базовую библиотеку и стартер в Maven Local до запуска скрипта.
container_id=$(docker run --rm -d --tmpfs /var/lib/postgresql/data:rw,size=268435456 \
  -e POSTGRES_DB=reports_demo -e POSTGRES_USER=report_demo \
  -e POSTGRES_PASSWORD=report_demo_only -p 127.0.0.1::5432 postgres:17-alpine)
trap 'docker stop "$container_id" >/dev/null' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for attempt in {1..60}; do
  if docker exec "$container_id" pg_isready -U report_demo -d reports_demo >/dev/null 2>&1; then break; fi
  if [[ "$attempt" == 60 ]]; then echo 'PostgreSQL not ready' >&2; exit 1; fi
  sleep 1
done
# Migrate the fresh demo database explicitly; the starter never runs schema DDL.
# Явно создаём таблицу в новой базе примера; стартер никогда не выполняет DDL схемы.
docker exec -i "$container_id" psql -U report_demo -d reports_demo -v ON_ERROR_STOP=1 < schema.sql
port=$(docker port "$container_id" 5432/tcp)
export TASKS_R2DBC_URL="r2dbc:postgresql://127.0.0.1:${port##*:}/reports_demo"
export TASKS_DB_USER=report_demo TASKS_DB_PASSWORD=report_demo_only
mvn -q package
java -jar target/report-worker-0.1.0.jar
