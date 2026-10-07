# Aniga

Android client and API for anime metadata, manga / manhwa / manhua reading, and taste-based recommendations.

| Path | Role |
|------|------|
| `app/` | Android client. Screens live under `ui/` (home, for you, discover, reader, library, people). `data/` is the API client and models. |
| `backend/` | Spring Boot API. One deployable, packages by feature: `auth`, `chat`, `people`, `read`, `recommend`, `media`, `search`, `source`, `user`. |
| `backend/src/main/resources/static/` | Browser console: `index.html` (markup), `console.css` (styles), `js/console.js` (behavior). |
| `infra/` | Local Compose, server Compose, Caddy, SQL init, deploy script. |
| `ml/` | Synthetic users and recommender training. Generated datasets stay on the machine (`ml/data` is ignored). |
| `tools/` | Catalog ingest, AniList sync, console checks. |
| `learning/` | Notes on what was built and why. |

## Configuration

Secrets and the live API host are not in source. Each machine keeps its own files:

| File | Used by |
|------|---------|
| `infra/.env` (from `infra/.env.example`) | Compose: database password, Firebase project, API key, session secret, admin token, public site address |
| `aniga.local.properties` (from `aniga.local.properties.example`) | Android build: `aniga.apiBaseUrl` |
| `ANIGA_API_BASE_URL` | Same Android setting, when the properties file is absent (CI) |

Local Compose will start without Firebase filled in. Sign-in stays off until `FIREBASE_PROJECT_ID` and `FIREBASE_API_KEY` are set. The server Compose file refuses to start unless those values, the session secret, the admin token, and `ANIGA_SITE_ADDRESS` are present.

The Android build writes `BuildConfig.API_BASE_URL` and a network security config from that host. The default, with no local file, is the emulator address `http://10.0.2.2:8080/`.

## Run locally

```powershell
cd infra
copy .env.example .env
docker compose up -d --build
```

Open http://localhost:8080. Adminer is on http://localhost:8081 (server `postgres`, database `aniga`, user `aniga`, password from `.env`, default `aniga_dev`).

Init SQL runs only on an empty database volume. After schema changes: `docker compose down -v` and up again.

## Android

```powershell
copy aniga.local.properties.example aniga.local.properties
.\gradlew.bat :app:installDebug
```

## Server deploy

`infra/docker-compose.ec2.yml` publishes only Caddy (ports 80 and 443). Postgres and the API stay on the Docker network. `infra/ec2-deploy.sh` loads a backend image tar if one is present, writes `.env` on first run from variables you export, and starts that Compose file.

A pipeline can wrap the same seam later: test and package `backend/`, publish the image, then run the server Compose with secrets from the host. That workflow is not wired up yet.
