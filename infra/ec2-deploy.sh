#!/usr/bin/env bash
# Loads the shipped backend image, generates server-only secrets on first run, and starts the stack.
# Postgres and the backend stay on the private network; only Caddy publishes 80/443.
set -euo pipefail
cd "$(dirname "$0")"

rand() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

if [ -f aniga-backend.tar ]; then
  echo "Loading backend image..."
  docker load -i aniga-backend.tar
fi

if [ ! -f .env ]; then
  : "${FIREBASE_PROJECT_ID:?export FIREBASE_PROJECT_ID before the first deploy}"
  : "${FIREBASE_API_KEY:?export FIREBASE_API_KEY before the first deploy}"
  : "${ANIGA_SITE_ADDRESS:?export ANIGA_SITE_ADDRESS before the first deploy (example: your.host.example, localhost)}"
  echo "Generating secrets (.env)..."
  cat > .env <<EOF
POSTGRES_DB=aniga
POSTGRES_USER=aniga
POSTGRES_PASSWORD=$(rand 24)
FIREBASE_PROJECT_ID=${FIREBASE_PROJECT_ID}
FIREBASE_API_KEY=${FIREBASE_API_KEY}
ANIGA_SESSION_SECRET=$(rand 32)
ANIGA_ADMIN_TOKEN=$(rand 24)
ANIGA_SITE_ADDRESS=${ANIGA_SITE_ADDRESS}
EOF
  chmod 600 .env
  echo "Wrote .env (kept out of git)."
fi

if ! grep -q '^ANIGA_SITE_ADDRESS=.\+' .env; then
  echo "Add ANIGA_SITE_ADDRESS to .env before deploy (example: your.host.example, localhost)" >&2
  exit 1
fi

docker compose -f docker-compose.ec2.yml up -d
sleep 6
docker compose -f docker-compose.ec2.yml ps
