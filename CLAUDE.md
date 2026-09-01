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
* `POST /api/order` MUST only accept `productId`, `userId`, `orderAmount` from the request body (camelCase — the `order_amount` spelling in the original spec/ER diagram is SQL column notation, not a JSON API contract; this project's entire JSON surface uses camelCase, no `@JsonProperty` overrides).
    * ❌ Forbidden: client sends `unitPrice` or `totalCost`.
    * ✅ Mandatory: server looks up `unit_price` and `tax_rate` from the DB and computes `totalCost = order_amount * unit_price * (1 + tax_rate)` using `BigDecimal`.
* **Snapshot both price inputs, not just tax rate:** at order creation time, persist both `tax_rate_snapshot` and `unit_price_snapshot` into the Order row. Neither is re-derived later via a live join — otherwise a later price or tax-rate change would silently alter historical order totals, or make `PATCH /api/order/{order_id}` inconsistent (tax rate frozen but price not).
* `PATCH /api/order/{order_id}` only accepts `orderAmount`. The server recomputes `totalCost = orderAmount * unit_price_snapshot * (1 + tax_rate_snapshot)` using the stored snapshots — it never re-queries Product/ProductCategory for this calculation.
* All request bodies validated with `@Valid` / `@NotNull` / `@Positive` etc. DTOs are strictly separate from Entities.

## 2. Transport & Credential Security
* All traffic is served over HTTPS (Cloud Run terminates TLS automatically; do not add a separate reverse proxy).
* `Users.password` is hashed with `BCrypt` (strength ≥ 12) before persistence — never store, log, or return plaintext passwords anywhere, including in test fixtures or logs.
* JWTs are short-lived; refresh tokens are persisted server-side (see Part 3) so they can be revoked.

## 3. Rate Limiting (In-Memory, Single Instance)
* Implement with **Bucket4j + Caffeine Cache**, keyed by `userId` (or IP if unauthenticated). `maximumSize` and TTL MUST be set on the cache to bound memory.
* Target: 5,000 requests/hour per user. Exceeding it returns `429` with `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` headers.
* This deployment runs Cloud Run pinned to a single instance (see Part 10), so an in-memory counter is an accurate global limiter here — there is no multi-instance drift to account for. Still expose it behind a `RateLimiter` interface so a future move to a distributed backing store wouldn't require touching call sites, but do not build that distributed version now.
* **Gotcha — resolving the real client IP behind Cloudflare + Cloud Run:** the custom domain is fronted by Cloudflare, then Cloud Run — two proxy hops, not one. Do not index into `X-Forwarded-For` by position here; each hop appends its own observed source, so "last segment" would resolve to Cloudflare's own edge IP (shared by every visitor), not the real client. Prefer Cloudflare's dedicated `CF-Connecting-IP` header (Cloudflare sets this to the verified visitor IP). Fall back to the last segment of `X-Forwarded-For` (covers direct traffic to Cloud Run's default `*.run.app` URL, which bypasses Cloudflare and has only one hop), then `getRemoteAddr()` (local `docker-compose` development, no proxy at all).
* **Known limitation, deferred (Part 10 scope, not Phase 6):** Cloud Run's default `*.run.app` URL remains directly reachable regardless of the custom domain mapping, unless explicitly locked down. An attacker hitting that URL directly bypasses Cloudflare entirely and can set an arbitrary `CF-Connecting-IP` header themselves (nothing strips or validates it outside Cloudflare's own edge), defeating the IP-based limiter's trust assumption for priority tier 1. Closing this fully requires restricting ingress at deploy time (e.g. Cloudflare Authenticated Origin Pulls / mTLS, or a shared-secret header validated at the app layer) — out of scope for the rate-limiting feature itself; address when Part 10 deployment is actually set up.
* **To verify once actually deployed (Part 10 to-do, not a confirmed gap):** a `@WebMvcTest`/`MockMvc` environment cannot simulate a real reverse proxy appending its own observed source to an existing `X-Forwarded-For` value — it just echoes back whatever header the test sets, single segment or not. This means "does taking the last segment correctly resolve the real client when someone bypasses Cloudflare and forges a single-value `X-Forwarded-For` directly against `*.run.app`" is **not actually confirmed either way from test results alone** — it depends on whether Cloud Run's real frontend appends its own observed IP to an existing client-supplied header (the standard, expected behavior for virtually all reverse proxies, which would make "last segment" resolve correctly even in that bypass case) or passes it through unchanged (which would not). Confirm this against the real deployed Cloud Run service once Part 10 is live — do not treat the MockMvc-observed behavior as proof either way.

## 4. Injection & Input Handling
* **SQL Injection:** Spring Data JPA / named parameters only. No string concatenation into queries.
* **Boundary values:** negative `order_amount`, non-existent `product_id`/`order_id`, and malformed payloads must all return clear 4xx responses, never a 500.
* **No per-resource ownership check is required for this project.** Any authenticated user may read/modify any Order or User record — this is an explicit, documented scope decision (see Part 3), not an oversight. Do not add ownership/ACL logic beyond what's specified here.

---

# 🔑 Part 3: Authentication (No RBAC)

* **Stateless:** JWT only (Access Token + Refresh Token). No OAuth2 for inbound user login — there is no third-party login requirement, and adding OAuth2 here would be over-engineering (see Part 6 for where OAuth2 *does* apply — outbound calls to the tax-rate provider).
* **Login flow:** users authenticate with `account` + `password` (verified against the BCrypt hash, Part 2.2). A soft-deleted user (`delete_at IS NOT NULL`) MUST NOT be able to log in, even with a correct password.
* **No role/permission model.** Any authenticated user can perform any action on any endpoint. This is intentional and matches the current scope — do not introduce `Role`/`Permission` entities, `@PreAuthorize`, or per-field visibility rules unless explicitly asked.
* Refresh tokens are persisted in the database (not an external cache) so logout/revocation works without Redis. **Store only a hash of the token (e.g. SHA-256), never the plaintext value.** Refresh tokens are high-entropy random values, not low-entropy human passwords — BCrypt's salted, non-deterministic output would prevent the fast exact-match DB lookup this needs, so a fast deterministic hash is the correct tool here, distinct from the BCrypt requirement for `Users.password` (Part 2.2).
* **Refresh token rotation:** each successful `POST /api/auth/refresh` revokes the presented refresh token and issues a brand-new access/refresh pair. A stolen-but-unused refresh token becomes worthless the moment the legitimate client uses it next, since the attacker's copy is now revoked too.
* **Gotcha — `requestMatchers` wildcards:** Spring 6's `PathPatternParser` requires `**` to be its own path segment (`/foo/**`, not `/foo**`) — the latter does not match nested subpaths and silently breaks deeper routes. This only shows up against a real running app, not a `@WebMvcTest` slice (see Part 9.3) — a slice can go fully green while the real app 401s on nested paths. Prefer explicit enumeration of exact public paths (Swagger, actuator, etc.) over a broadened wildcard meant to "cover everything at once."
* **Gotcha — `JwtAuthenticationFilter` and the servlet ERROR dispatch:** this filter overrides `shouldNotFilterErrorDispatch()` to return `false`. The default (`true`) would skip re-running the filter on Spring Boot's internal forward to `/error` when no handler matches a request; since `SecurityContext` is re-derived per filter-chain invocation under `STATELESS` policy, that second pass would see an empty context and reject with 401 before the request ever reached the real 404 — i.e. a genuinely nonexistent path would misleadingly read as "not authenticated" instead of "not found." Don't revert this override.

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
Each carries a stable `errorCode`. Adding a new error type means adding one class — the interceptor logic never changes. **This unified format applies even to rejections produced by the Spring Security filter chain itself** (missing/invalid JWT, e.g.) — a custom `AuthenticationEntryPoint` must render the same `ProblemDetail` JSON shape as `@RestControllerAdvice`, not Spring's default error page. A client should never be able to tell, from response shape alone, whether a 401 came from the filter chain or from application code.

## 4. Deletion Semantics — Soft Delete Everywhere
* Every table (`Users`, `ProductCategory`, `Product`, `Order`) carries `create_at`, `update_at`, and a nullable `delete_at` column. Application code never issues a physical `DELETE FROM` — every "delete" operation results in `delete_at` being set to the current UTC timestamp.
* **Enforce this at the ORM layer, not just in Service code:** each entity uses Hibernate's `@SQLDelete(sql = "UPDATE <table> SET delete_at = now() WHERE <pk_column> = ?")` paired with `@SQLRestriction("delete_at IS NULL")`. This makes a standard `repository.delete(entity)` call automatically execute the soft-delete UPDATE instead of a real `DELETE FROM` — Service code never needs to manually set `delete_at`, and an accidental "real delete" call becomes effectively impossible.
* `DELETE /api/order/{order_id}` soft-deletes that Order row via the above mechanism.
* `DELETE /api/user/{userId}` soft-deletes the User row **and** soft-deletes all of that user's Order rows, in the same `@Transactional` service method (calling `repository.delete(...)` for each — no manual timestamp assignment needed). Reading of the original spec: "all user order references need to be deleted" means the *references are cleaned up* (soft-deleted, consistent with this project's uniform soft-delete policy) — it is not an instruction to bypass soft-delete and physically remove rows.
* All read paths (GET/PATCH/list queries) MUST exclude soft-deleted rows by default via `@SQLRestriction("delete_at IS NULL")` on each entity.
* Acting on an already soft-deleted row (PATCH/DELETE) must behave as `404 Not Found`, not silently succeed.
* `create_at` / `update_at` are populated automatically via `@CreationTimestamp` / `@UpdateTimestamp` on every entity — never set manually by Service code.
* **Gotcha when combining with `@Version` (Part 8.3):** Hibernate appends an extra `version` bind parameter to a versioned entity's `@SQLDelete` SQL automatically — the custom SQL string must include a matching `AND version = ?` after the PK condition, or `saveAndFlush`/`delete` will fail at flush time with a parameter-binding error. Any entity that gets `@Version` added later must have its `@SQLDelete` SQL updated to match.
* **Gotcha when other entities reference the row being soft-deleted:** if any other managed entity in the same Hibernate session holds a `@ManyToOne` pointing at a `Users` row that's about to go through `@SQLDelete`, flushing can throw a spurious `TransientObjectException` ("references an unsaved transient instance") even though that entity is fully persisted and untouched — this reproduces regardless of the referencing entity's type, and only shows up with a real DB flush (Mockito can't catch it). When a delete needs to cascade a field change to related rows (e.g. revoking a deleted user's refresh tokens), use a bulk `@Modifying @Query` UPDATE instead of loading the related rows as managed entities into the same transaction — this sidesteps the issue entirely and is more efficient besides. Cover this kind of cascading change with a Testcontainers test, not just Mockito, since the failure mode is DB-flush-specific.

## 5. Entity Boilerplate (Lombok)
* Entities use Lombok's `@Getter` at the class level to remove getter boilerplate. **Do not use `@Data` or `@EqualsAndHashCode`/`@ToString` on entities** — these generate `equals()`/`hashCode()`/`toString()` that touch JPA associations, which can trigger lazy-loading, N+1 queries, or infinite recursion on bidirectional relations.
* `@Setter` is added per-field, only for fields the Service layer legitimately needs to mutate directly. `create_at`, `update_at` (Hibernate-managed via `@CreationTimestamp`/`@UpdateTimestamp`) and `delete_at` (managed via `@SQLDelete`, above) never get a `@Setter` — there is no legitimate reason for application code to set them directly.

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

* **Users:** `user_id (PK)`, `username`, `account` (unique, login identifier), `password` (BCrypt hash), `create_at`, `update_at`, `delete_at` (nullable, soft-delete marker)
* **ProductCategory:** `category_id (PK)`, `category_name`, `tax_rate`, `create_at`, `update_at`, `delete_at` (nullable, soft-delete marker)
* **Product:** `product_id (PK)`, `product_category_id (FK)`, `unit_price`, `create_at`, `update_at`, `delete_at` (nullable, soft-delete marker)
* **Order:** `order_id (PK)`, `user_id (FK)`, `product_id (FK)`, `order_amount`, `tax_rate_snapshot`, `unit_price_snapshot`, `total_cost`, `create_at`, `update_at`, `delete_at` (nullable, soft-delete marker)
* Schema changes go through **Flyway** migrations only. No `ddl-auto: update` in any real environment.

---

# ❗ Part 8: Core Engineering Principles

0. **SOLID, applied pragmatically:**
    - **SRP** — each Service class owns one entity's business logic (`OrderService`, `UserService`, `ProductService`); a method that's doing two unrelated jobs gets split.
    - **OCP** — the exception hierarchy (Part 4.3) and the `RateLimiter` abstraction (Part 2.3) are the intended extension points: add a new subclass/implementation, don't modify existing branching logic to bolt on new cases.
    - **LSP** — any subclass (e.g. a new `ResourceNotFoundException` subtype) must be usable anywhere the parent type is expected, with no surprise behavior.
    - **ISP** — don't force a class to depend on methods it doesn't use; this is a reason to keep Repository interfaces minimal (Part 2 decision: no unused custom query methods), not a reason to slice them into many tiny interfaces.
    - **DIP** — Services depend on Spring Data repository interfaces and on abstractions like `RateLimiter`, never on concrete DB/HTTP client classes directly.
    - **This does not override Rule 1.2 (Simplicity First).** SOLID is not a license to add an interface for every class "just in case," wrap single-implementation classes in unnecessary abstractions, or split a 20-line method into five one-line methods for the sake of SRP theater. If applying a SOLID principle would introduce an abstraction with only one real implementation and no foreseeable second one, don't — that's over-engineering, not good design. When the two rules seem to conflict, Simplicity First wins for structure; SOLID wins for *how a given piece of logic is organized once it exists*.

1. **KISS:** readability over clever one-liners.
2. **Database-backed state:** other than the bounded rate-limit cache (Part 2.3), don't keep business state in unbound in-memory structures.
3. **Optimistic locking:** `Order` has `@Version` (Long, incremented by Hibernate). Scope decision: this protects against two near-simultaneous writes racing within the same short-lived transaction window (the scenario a `PATCH` under real concurrent load hits) — it does **not** implement client-submitted-version staleness detection (e.g. an `If-Match` header or a `version` field in `OrderUpdateRequest` compared against what the client originally read). The Service always re-fetches the entity fresh inside each request before mutating it. Building full client-aware optimistic concurrency is out of scope unless explicitly requested — the current design is a deliberate, documented boundary, not an oversight.
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

3. **Testing (TDD-Mandatory for Business Logic):**
    - Any Service-layer method that contains a calculation, conditional branch, or multi-step business rule (e.g. `totalCost` math, cascading soft-delete, "not found" exception paths) MUST be written test-first: write the failing test, run it to confirm it fails for the right reason (red), then write the minimum implementation to make it pass (green), then refactor if needed. Do not implement the method first and backfill tests afterward.
    - Plain data holders and boilerplate (entities, DTOs, exception classes with no branching logic) don't need this — TDD applies to logic, not to structure.
    - JUnit5 + Mockito for service-layer unit tests, with explicit cases for `totalCost` at zero/negative/large `order_amount`.
    - Testcontainers spins up a real PostgreSQL for repository/integration tests (test-time only — does not imply Docker is used for production deployment).
    - **Gotcha — testing servlet container ERROR dispatch:** `@WebMvcTest`/`MockMvc` does not reproduce Spring Boot's internal forward to `/error` when no handler matches a request. Bugs that only manifest on that ERROR dispatch (e.g. a security filter's `shouldNotFilterErrorDispatch()` behavior) will show a false green in a `@WebMvcTest` slice regardless of whether the underlying issue is actually fixed — the slice never exercises the real servlet container's error-forwarding mechanism. Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate` (plus Testcontainers if the flow touches the DB) for this class of behavior.
    - **Controller tests and the security filter chain:** `@WebMvcTest` classes whose purpose is routing/validation/serialization (e.g. `ProductControllerTest`, `OrderControllerTest`) use `@AutoConfigureMockMvc(addFilters = false)` so the real Spring Security filter chain doesn't intercept them — they should keep testing business-endpoint behavior in isolation from auth. Whether a request is correctly rejected without a valid token, or accepted with one, is verified only in the dedicated authentication test class(es), which do NOT disable filters — that's the one place the real filter chain is meant to run.

---

# 🚀 Part 10: Deployment & CI/CD (GCP Cloud Run + Cloud SQL + Secret Manager)

* **No staging environment.** This project deploys to production only — one Cloud SQL database, one Cloud Run service, one set of Secret Manager entries. Do not provision a second DB/user or a second Cloud Run service "for staging"; that pattern is specific to other projects, not this one.
* **Compute:** Cloud Run, deployed from a hand-maintained **Dockerfile** (multi-stage: Maven build stage → slim JRE runtime stage, non-root user), built and pushed to Artifact Registry, then `gcloud run deploy --image=...`. (Supersedes an earlier Buildpacks-only plan: a Dockerfile is proven from prior real deployment experience on a similar Java/Maven stack; Buildpacks was never actually tested for this project and carries more unknown risk at deploy time.)
* **Instance settings:**
    - `min-instances=1` — keeps one instance always running, avoiding cold starts.
    - `max-instances=1` — **must stay 1, never higher.** The in-memory rate limiter (Part 2.3) and the `@Async` notification design (Part 5) both assume exactly one running instance. Raising this silently breaks those assumptions (e.g. the effective rate limit becomes `configured limit × instance count`).
    - `--no-cpu-throttling` — required so the `@Async` notification (Part 5) can finish running in the background after the HTTP response is returned, instead of being CPU-throttled.
* **Database:** Cloud SQL for PostgreSQL, accessed via `postgres-socket-factory` over a Unix domain socket (`--add-cloudsql-instances`) — never over a public IP.
* **Environment variable names must match `application.yml`'s existing placeholders** — `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` — not generic Spring Boot property names like `SPRING_DATASOURCE_URL`; the app only reads what its own `${...}` placeholders are literally named. `DB_URL` for the Cloud SQL connection takes the socket-factory JDBC form: `jdbc:postgresql:///<db-name>?cloudSqlInstance=<project>:<region>:<instance>&socketFactory=com.google.cloud.sql.postgres.SocketFactory`.
* **Secrets:** DB password and JWT signing secret live in **Secret Manager**, mounted into Cloud Run via `--set-secrets` (never `--set-env-vars`, which would leave them in plaintext in deploy history/logs). Nothing sensitive is ever committed to the repo. **Never provision a "default password" secret** — this project deliberately has no seeded/default credentials (Part 3); every account is created via `POST /api/auth/register`.
* **Health check endpoint:** add `spring-boot-starter-actuator` and expose `/actuator/health`, whitelisted in `SecurityConfig`'s permitAll list (same pattern as the Swagger paths, Part 3). This does not exist yet and must be added before deployment — needed for both the CD pipeline's smoke test and GCP's Uptime Check.
* **GitHub Actions auth:** use **Workload Identity Federation (WIF)**, not a long-lived service account JSON key. No secret key ever sits in GitHub Secrets; GitHub's own OIDC token is exchanged for short-lived GCP credentials at CI run time.
* **Custom domain via Cloudflare:** the domain is registered and DNS-managed on Cloudflare, proxied (orange-cloud) in front of Cloud Run — this is the exact deployment context Part 2.3's `CF-Connecting-IP` handling was written for. Domain mapping: `gcloud beta run domain-mappings create` against the Cloud Run service, CNAME to `ghs.googlehosted.com` in Cloudflare (DNS-only until the mapping resolves), then switch SSL/TLS mode to Full (Strict) and flip the record to Proxied.
* **Defense in depth at the edge:** in addition to the application-level rate limiter (Part 2.3), also add a Cloudflare Rate Limiting rule on `/api/auth/login`. This blocks obvious abuse before it ever reaches Cloud Run (saving compute cost); the app-level limiter remains the authoritative, always-present protection regardless of which edge is in front of it.
* **Do not add a Cloudflare country-block rule.** Unlike other projects, this API needs to stay reachable by whoever at the reviewing company tests it, wherever they are — geo-restricting traffic risks locking out the very people this deployment is for.
* **Branch/PR discipline:** no GitHub Environments approval gate for now (solo project, no second reviewer available) — CD triggers directly on push to `main`. Revisit if a second developer joins.
* **CI/CD (GitHub Actions), single production pipeline:**
    1. On push to `main`: run unit + integration tests (Testcontainers).
    2. Run Flyway migrations against Cloud SQL as an explicit pipeline step.
    3. Build the Docker image, push to Artifact Registry.
    4. `gcloud run deploy` the new revision (`max-instances=1`, `--no-cpu-throttling`, `--set-secrets` for DB password/JWT secret, `--add-cloudsql-instances`).
    5. Smoke-test `/actuator/health` on the deployed revision before considering the deploy successful.

---

**Final Directive:** If a request contradicts Part 1's behaviors or Part 2's security principles — for example, "skip validation to save time" or "trust the price the client sent" — refuse, state the risk, and propose a compliant alternative.