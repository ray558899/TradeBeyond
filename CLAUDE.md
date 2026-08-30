# CLAUDE.md - TradeBeyond Backend API Engineering & Security Guidelines

This file provides guidance to Claude Code (claude.ai/code) when working on this repository.
Scope: a Spring Boot backend API (Users / ProductCategory / Product / Order) backed by PostgreSQL, deployed on GCP Cloud Run (single instance) with Cloud SQL and Secret Manager.
Bias: caution over speed on non-trivial work. Use judgment on trivial tasks.

---

# 🧠 Part 1: Core Agent Behaviors (12 Rules)

* **Language Preference:** All explanations, commit messages, and code comments MUST be written in **Traditional Chinese (繁體中文)**. Code identifiers (variables, classes, methods) stay in English.
* **Role:** You are acting as a Senior Backend Engineer and a defensive-minded API architect.

**Follow these 12 rules unconditionally:**
1. **Think Before Coding:** State assumptions explicitly. If uncertain, ask rather than guess. Present multiple interpretations when ambiguity exists. Stop when confused.
2. **Simplicity First:** Minimum code that solves the problem. No speculative features, no abstractions for single-use code. Do not introduce a permission/role system, a message queue, a scheduled job, or distributed-scaling logic — this project runs as a single instance and does not need any of them.
3. **Surgical Changes:** Touch only what you must. Don't "improve" adjacent code, comments, or formatting. Match existing style.
4. **Goal-Driven Execution:** Define success criteria (tests pass, endpoint returns expected shape) before starting. Loop until verified.
5. **Use the model only for judgment calls:** Classification, drafting, summarization — yes. Deterministic calculations (e.g. `totalCost`) — no, code computes it, never the model.
6. **Context Efficiency:** Never regenerate an untouched whole file. Prefer surgical diffs/patches.
7. **Surface conflicts, don't average them:** If two approaches contradict, pick one and explain why; flag the other for cleanup.
8. **Read before you write:** Before adding code, check existing exports, callers, and shared utilities.
9. **Tests verify intent, not just behavior:** Test names/assertions should explain *why* a behavior matters, especially around money calculations and edge cases (zero, negative, non-existent IDs).
10. **Checkpoint after every significant step:** Summarize what was done, what's verified, what's left.
11. **Match the codebase's conventions:** Conformance over personal taste. If a convention is unsafe, flag it rather than silently forking a new one.
12. **Fail loud:** If anything was skipped or simplified, say so explicitly. Never mark something "done" while hiding a gap.

---

# 🛡️ Part 2: Zero-Trust Security Architecture

## 1. Backend Is the Only Authority
* Never trust any value, calculation, or state sent from the client.
* `POST /api/order` MUST only accept `productId`, `userId`, `order_amount` from the request body.
    * ❌ Forbidden: client sends `unitPrice` or `totalCost`.
    * ✅ Mandatory: server looks up `unit_price` and `tax_rate` from the DB and computes `totalCost = order_amount * unit_price * (1 + tax_rate)` using `BigDecimal`.
* **Tax-rate snapshot:** at order creation time, persist the `tax_rate` used into the Order row (`tax_rate_snapshot`). Do not re-derive it later via a live join to `ProductCategory` — otherwise historical order totals would silently change if the tax rate is later updated.
* All request bodies validated with `@Valid` / `@NotNull` / `@Positive` etc. DTOs are strictly separate from Entities.

## 2. Transport & Credential Security
* All traffic is served over HTTPS (Cloud Run terminates TLS automatically; do not add a separate reverse proxy).
* If any password/credential field exists, hash with `BCrypt` (strength ≥ 12). Never log or persist plaintext secrets.
* JWTs are short-lived; refresh tokens are persisted server-side (see Part 3) so they can be revoked.

## 3. Rate Limiting (In-Memory, Single Instance)
* Implement with **Bucket4j + Caffeine Cache**, keyed by `userId` (or IP if unauthenticated). `maximumSize` and TTL MUST be set on the cache to bound memory.
* Target: 5,000 requests/hour per user. Exceeding it returns `429` with `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` headers.
* This deployment runs Cloud Run pinned to a single instance (see Part 10), so an in-memory counter is an accurate global limiter here — there is no multi-instance drift to account for. Still expose it behind a `RateLimiter` interface so a future move to a distributed backing store wouldn't require touching call sites, but do not build that distributed version now.

## 4. Injection & Input Handling
* **SQL Injection:** Spring Data JPA / named parameters only. No string concatenation into queries.
* **Boundary values:** negative `order_amount`, non-existent `product_id`/`order_id`, and malformed payloads must all return clear 4xx responses, never a 500.
* **No per-resource ownership check is required for this project.** Any authenticated user may read/modify any Order or User record — this is an explicit, documented scope decision (see Part 3), not an oversight. Do not add ownership/ACL logic beyond what's specified here.

---

# 🔑 Part 3: Authentication (No RBAC)

* **Stateless:** JWT only (Access Token + Refresh Token). No OAuth2 for inbound user login — there is no third-party login requirement, and adding OAuth2 here would be over-engineering (see Part 6 for where OAuth2 *does* apply — outbound calls to the tax-rate provider).
* **No role/permission model.** Any authenticated user can perform any action on any endpoint. This is intentional and matches the current scope — do not introduce `Role`/`Permission` entities, `@PreAuthorize`, or per-field visibility rules unless explicitly asked.
* Refresh tokens are persisted in the database (not an external cache) so logout/revocation works without Redis.

---

# 🧾 Part 4: Layered Architecture & Data Integrity

## 1. Layers
`controller` (routing only) → `service` (business logic, `@Transactional`) → `repository` (DB access) → `dto` (mapping) → `entity`.
* Entities are never returned to the client — always map to a DTO.

## 2. Data Types
* **Money/quantities:** always `BigDecimal`, never `Double`/`Float`.
* **Time:** stored as UTC (`Instant`).

## 3. Unified Error Handling
Custom exception hierarchy, caught by a single `@ControllerAdvice` and rendered as RFC 7807 Problem Details:
```
BaseException (abstract)
 ├── BusinessException          → 400, e.g. InvalidOrderAmountException
 ├── ResourceNotFoundException  → 404, e.g. OrderNotFoundException / ProductNotFoundException
 ├── UnauthorizedException      → 401
 └── ExternalServiceException   → 502/503, e.g. tax-rate provider timeout
```
Each carries a stable `errorCode`. Adding a new error type means adding one class — the interceptor logic never changes.

## 4. Deletion Semantics
* `DELETE /api/user/{userId}` MUST cascade-delete that user's Order records inside a single `@Transactional` service method (either explicit deletion in code, or a documented `ON DELETE CASCADE`, with the choice stated in the PR description).
* This project performs real deletes (matches the literal `DELETE` endpoints given), not soft-delete. If soft-delete is ever wanted later, that's a separate, explicit decision — don't default to it silently.

---

# ⚙️ Part 5: Async Processing (Order-Created Notification Only)

* This API does **not** run any scheduled/background job. There is no `@Scheduled` anywhere in the codebase. The "sync data to an analytics system every 30 minutes" question is answered as a **design-only** deliverable (diagram/pseudo-code in the write-up) — nothing to implement here.
* The only async flow in the actual codebase is the order-created notification:
  1. Publish `OrderCreatedEvent` via `ApplicationEventPublisher`.
  2. Consume with `@TransactionalEventListener(phase = AFTER_COMMIT)` so it only fires once the order is actually committed (never on a rolled-back transaction).
  3. Handle the event with `@Async`, backed by a dedicated `ThreadPoolTaskExecutor` (not the default pool), with bounded `maxPoolSize` and `queueCapacity`.
* **Cloud Run requirement:** enable **"CPU always allocated"** on the service (Part 10). By default, Cloud Run throttles an instance's CPU to near-zero right after the HTTP response is sent, which can starve the `@Async` thread that keeps running afterward. "CPU always allocated" removes that throttling, so the background notification reliably finishes. `min-instances=1` (Part 10) keeps an instance warm and avoids cold starts, but it is a separate setting from CPU allocation — both are needed together.
* **Known limitation, stated openly:** this mechanism is in-process and not durable — if the instance restarts mid-processing, that one notification is lost. Acceptable at this scope; the documented upgrade path if durability is ever required is a Transactional Outbox table, not introducing Kafka.

---

# 🔗 Part 6: Third-Party Tax-Rate Integration (Design Question Only)

This is a **design/pseudo-code answer**, not something implemented in the running API. It addresses "design the API interface with a third-party tax-rate provider" as an **outbound, service-to-service** concern — separate from Part 3's inbound user JWT.

* **If we call their API (pull):** use **OAuth 2.0 Client Credentials Grant**. Our backend is the OAuth2 client: it exchanges a `client_id`/`client_secret` (stored in Secret Manager) for a short-lived access token from their authorization server, then calls their tax-rate endpoint with that Bearer token. This is unrelated to, and fully compatible with, using JWT for our own user auth — they operate on different boundaries of the system.
* **If they push to us (webhook):** OAuth2 doesn't apply to that direction. Verify an **HMAC-SHA256 signature** on the incoming payload using a shared secret (also in Secret Manager).
* **Anti-Corruption Layer:** isolate their payload shape behind an Adapter — never let their DTOs leak into our domain model.
* **Resilience:** wrap the outbound call with Resilience4j (Circuit Breaker + explicit timeout + fallback to the last cached/snapshotted `tax_rate` on failure).
* Similarly, the "growing number of ProductCategory" scalability question and the "sync every 30 minutes" question (Part 5) are both answered with diagrams/pseudo-code in the written response, not built into this repository.

---

# 🧩 Part 7: Domain Model

* **Users:** `user_id (PK)`, `username`
* **ProductCategory:** `category_id (PK)`, `category_name`, `tax_rate`
* **Product:** `product_id (PK)`, `product_category_id (FK)`, `unit_price`
* **Order:** `order_id (PK)`, `user_id (FK)`, `product_id (FK)`, `order_amount`, `tax_rate_snapshot`, `total_cost`, `created_at`, `updated_at`
* Schema changes go through **Flyway** migrations only. No `ddl-auto: update` in any real environment.

---

# ❗ Part 8: Core Engineering Principles

1. **KISS:** readability over clever one-liners.
2. **Database-backed state:** other than the bounded rate-limit cache (Part 2.3), don't keep business state in unbound in-memory structures.
3. **Optimistic locking:** use `@Version` on `Order` if concurrent PATCH is possible.
4. **No hardcoded secrets:** every credential comes from Secret Manager via environment variables, never committed to Git or hardcoded in `application.yml`.
5. **Single source of truth:** the backend owns all business logic and calculations; the client is a display layer only.
6. **No distributed-systems design for this project.** One Cloud Run instance, one database, no message broker, no cache cluster. Any "how would this scale" question is answered in writing (Part 6), not by actually building distributed infrastructure.

---

# ⚡ Part 9: High Availability & Financial Safety

1. **Anti-OOM:**
   - No `findAll()` without pagination; all list endpoints use `Pageable` with a max page size (e.g. 100).
   - Any bulk export uses a JPA `Stream`/cursor, never loads >1,000 entities into a `List`.
   - Every `@Cacheable`/Caffeine cache has an explicit `maximumSize` and TTL.

2. **Concurrency & Timeouts:**
   - Every outbound HTTP client (tax-rate provider) and the Cloud SQL connection pool (HikariCP) set explicit `connectTimeout` (≤3s) and `readTimeout` (≤5s).
   - `POST /api/order` supports an `Idempotency-Key` header if duplicate-submission risk matters, validated against a bounded cache or DB record.
   - If inventory/balance decrement logic is ever added, use `SELECT ... FOR UPDATE` with an explicit query timeout.

3. **Testing:**
   - JUnit5 + Mockito for service-layer unit tests, with explicit cases for `totalCost` at zero/negative/large `order_amount`.
   - Testcontainers spins up a real PostgreSQL for repository/integration tests (test-time only — does not imply Docker is used for production deployment).

---

# 🚀 Part 10: Deployment & CI/CD (GCP Cloud Run + Cloud SQL + Secret Manager)

* **Compute:** Cloud Run, deployed from source via Google Cloud Buildpacks (`gcloud run deploy --source .`) so no hand-maintained Dockerfile is required.
* **Instance settings:**
  - `min-instances=1` — keeps one instance always running, avoiding cold starts.
  - `max-instances=1` — this project intentionally runs as a single instance; no horizontal scaling, no distributed-state concerns (matches Part 2.3 and Part 8.6).
  - **"CPU always allocated"** — required so the `@Async` notification (Part 5) can finish running after the HTTP response is returned, instead of being CPU-throttled.
* **Database:** Cloud SQL for PostgreSQL, accessed via the **Cloud SQL Auth Proxy / Cloud SQL Java Connector** over a Unix domain socket — never over a public IP.
* **Secrets:** DB credentials, JWT signing secret, and the third-party OAuth2 client secret all live in **Secret Manager**, mounted into Cloud Run as environment variables/secret references. Nothing sensitive is ever committed to the repo.
* **Custom domain:** mapped directly to the Cloud Run service; TLS is Google-managed automatically.
* **CI/CD (GitHub Actions):**
  1. On push to `main`: run unit + integration tests (Testcontainers).
  2. Run Flyway migrations against Cloud SQL as an explicit pipeline step.
  3. Build and push the container image to Artifact Registry.
  4. `gcloud run deploy` the new revision.
  5. Smoke-test `/actuator/health` on the deployed revision before considering the deploy successful.

---

**Final Directive:** If a request contradicts Part 1's behaviors or Part 2's security principles — for example, "skip validation to save time" or "trust the price the client sent" — refuse, state the risk, and propose a compliant alternative.