# Architecture decision records

Each record: context → options → decision → consequences. This is the format to use when an interviewer asks "why did you do X?".

---

## ADR-1: Local Postgres in Docker now, Supabase later

**Context.** We need users, ratings and chat eventually. Supabase is the target, but building against a hosted service from day one couples learning the data model to learning a vendor.

**Options.** (a) Supabase cloud now, (b) Firebase, (c) plain Postgres locally, migrate later, (d) local Supabase stack (`supabase start`, ~10 containers).

**Decision.** (c). Supabase *is* Postgres, so the same schema moves over with minimal change. Table names avoid Supabase's reserved `auth.users` (ours is `app_user`), and chat tables already have the shape Realtime + row-level security expects.

**Consequences.** + one-container DB, fast resets (`docker compose down -v`), SQL skills transfer. − no auth or realtime yet; we add them when moving to Supabase.

## ADR-2: A backend between Android and the database

**Context.** Android cannot safely hold DB credentials, and MangaDex/AniList calls need rate limiting and caching.

**Decision.** Spring Boot REST API. Android and the web console both talk only to the API.

**Why Spring Boot and Java.** The Android app is Java, so one language across the stack; Spring Boot is the most common Java backend in industry, which also helps interviews.

**Why Spring Boot 4.1 / Java 21.** Current stable line at time of writing; Java 21 is an LTS release and gives records, pattern matching and `Math.clamp`.

## ADR-3: Build Java inside Docker (multi-stage)

**Context.** The host machine has no JDK on PATH.

**Decision.** `backend/Dockerfile` compiles with `maven:3.9-eclipse-temurin-21` and runs on `eclipse-temurin:21-jre`.

**Consequences.** + reproducible build on any machine with Docker; unit tests run on every build. − first build is slow (~3 min) while Maven downloads dependencies; layer caching makes later builds fast.

## ADR-4: Plain SQL init scripts instead of Flyway (for now)

**Decision.** `infra/db/init/*.sql` run by the Postgres image on an empty volume.

**Trade-off.** Simple and visible, but no migration history. Switch to Flyway as soon as there is data worth keeping or a second environment. This is a deliberate "not yet", not an oversight.

## ADR-5: `JdbcClient` instead of JPA/Hibernate

**Decision.** Spring's `JdbcClient` with hand-written SQL.

**Why.** The queries use Postgres arrays (`text[]`), GIN indexes and `ON CONFLICT` upserts; plain SQL shows exactly what runs and avoids ORM mapping for arrays. The domain is small enough that JPA's benefits (dirty tracking, relations) don't pay off yet.

## ADR-6: Source catalog vs. source adapter

**Context.** The knowledge base showed extension indexes list thousands of sites. Listing a site and being able to read from it are different things.

**Decision.** Two tables. `source_catalog` = imported metadata (1,778 rows). `source_adapter` = code that can actually fetch chapters (currently only MangaDex, enabled).

**Why.** Keeps the UI honest (users see which sources work), and mirrors how Mihon separates "repo index" from "installed extension".

## ADR-7: MangaDex as the first and only reading adapter

**Decision.** Official public API, data-saver images, credit MangaDex and scanlation groups on every chapter view.

**Why.** It is legal to use under its terms (credit, no ads, no paid features), has structured JSON, and covers manga, manhwa and manhua. See [05-sources-and-scraping.md](05-sources-and-scraping.md) for why unlicensed mirror scrapers were not written.

## ADR-8: Recommender V1 = genre score + TF-IDF, research = LSTM + MLP + RL

**Decision.** Ship a transparent, training-free recommender now; run the neural/RL work as a separate research track with its own simulator and evaluation.

**Why.** V1 works with one rating and explains itself. The neural models need data we don't have yet; the simulator lets us develop them without blocking the app. Details: [06-recommender-v1.md](06-recommender-v1.md), [research proposal](research/recommender-research-proposal.md).

## ADR-9: Event log designed for ML from day one

**Decision.** `interaction_event` stores every impression, click, progress, rating and drop with a timestamp. The synthetic `events.csv` uses the same columns plus slate position and logging propensity.

**Why.** The LSTM needs ordered events; the RL re-ranker needs rewards; off-policy evaluation needs propensities. Retrofitting logging later loses months of data.

## ADR-10: NSFW excluded at ingest time

**Decision.** `ingest_sources.py` drops sources flagged NSFW unless `--include-nsfw` is passed; API queries also filter `nsfw = FALSE`.

**Why.** Product decision to defer; filtering at two layers means a mistake in one doesn't leak content.
