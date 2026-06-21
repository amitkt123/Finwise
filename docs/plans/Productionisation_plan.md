Productization Plan — Multi-User Finwise

▎ On approval, this document is saved to docs/plans/PRODUCTIZATION_MULTI_USER.md (matching the existing docs/plans/ convention) as execution step 0.

Context

Finwise today is a sophisticated single-user personal-CFO backend. The analytical core (GARCH MLE, Ledoit-Wolf shrinkage, factor model, VaR backtests, Brinson-Fachler
attribution, calibrated insight cards) is institutional-grade and well-tested (281 tests). The gap to a Univest-style product is entirely in the non-analytical plumbing:

- There is no authentication of any kind. Every endpoint is open. The only "identity" is a single cfo.user.id env var ("amit" in dev) @Value-injected into 10 classes.
- Several controllers (Investment, Goal, Budget, Dashboard, parts of Expense) accept @RequestParam String userId — any caller can read any user's data.
- Controllers return JPA entities directly; there is no DTO layer, no bean validation, and no global exception handling.
- External data calls (Yahoo, NSE, AMFI, AlphaVantage) have ad-hoc retry but no circuit breaker or cache.
- Schema is managed by Hibernate ddl-auto=update; no Flyway/Liquibase.

Intended outcome: a multi-tenant, secured, productized backend that multiple registered users can use safely, with a clean API contract a frontend can consume — without
touching the analytical engine.

Key architectural decisions (confirmed)

1. userId stays String; the authenticated username is the userId. Every entity already stores String userId (VARCHAR). A new User auth entity uses that same string as its
   business key. No data migration of existing userId columns. Existing "amit" data is preserved by seeding a User with username amit.
2. Stateless JWT auth (spring-boot-starter-security + jjwt), with a OncePerRequestFilter populating the SecurityContext. SPA/mobile-friendly.
3. Per-user data is derived from the principal, never from a request param. Removing @RequestParam userId is a security fix, not just a refactor.
4. Global/market-wide services stay global. Market data, macro, news, corporate actions, policy crawling are NOT per-user and must not be fanned out.

 ---
Phase 1 — Authentication & Multi-Tenancy (foundational)

Everything else depends on this. Low risk (data model already uniformly String userId), but broad reach (10 injection sites + 6 controllers).

1.1 Dependencies (pom.xml)

Add:
- spring-boot-starter-security
- io.jsonwebtoken:jjwt-api, jjwt-impl, jjwt-jackson (runtime) — pin a current 0.12.x.
- spring-boot-starter-validation (also used in Phase 2).

spring-security-crypto is already present (used by GrowwAuthService AES); BCryptPasswordEncoder comes from the full starter.

1.2 New auth domain (org.amit.finwise.auth)

New package with:
- User entity (table = users): Long id (surrogate PK), String username (unique, this equals the existing userId), String email (unique), String passwordHash, Set<Role> (enum
  ROLE_USER, ROLE_ADMIN via @ElementCollection), boolean enabled, LocalDateTime createdAt/updatedAt. Reuse the @CreationTimestamp/@UpdateTimestamp convention from existing
  entities.
- UserRepository: findByUsername, findByEmail, existsByUsername, existsByEmail, findAllByEnabledTrue() (needed for scheduler fan-out).
- AppUserDetailsService implements UserDetailsService: loads User → Spring UserDetails.
- JwtService: issue/parse/validate HS256 tokens; subject = username; configurable secret + TTL from properties (security.jwt.secret, security.jwt.ttl). Secret sourced via
  .env like all other secrets (DotenvConfig).
- JwtAuthenticationFilter extends OncePerRequestFilter: reads Authorization: Bearer, validates, sets UsernamePasswordAuthenticationToken in SecurityContextHolder.
- SecurityConfig (@EnableWebSecurity, @EnableMethodSecurity): stateless session policy; permitAll on /api/auth/** and actuator health; /api/**/admin/** and the existing
  admin/ops controllers (MarketDataAdminController, MacroSeriesAdminController, DataQualityController, EvaluationController) require ROLE_ADMIN; everything else
  authenticated(). Register JwtAuthenticationFilter before UsernamePasswordAuthenticationFilter. Define PasswordEncoder (BCrypt) and AuthenticationManager beans.
- AuthController (/api/auth): POST /register (creates User + a paired UserProfile row keyed on the same userId), POST /login (returns JWT), GET /me.
- CurrentUser resolver: a small helper CurrentUserProvider.userId() reading SecurityContextHolder, plus a @CurrentUserId Spring HandlerMethodArgumentResolver (or just use
  @AuthenticationPrincipal) so controllers get the userId without a request param.

1.3 Replace the 10 @Value("${cfo.user.id}") sites

Two categories:

A. Web-request context (derive from principal — security-critical):
- cfo/controller/CFOController.java — replace the defaultUserId field; each of the 14+ endpoints takes the resolved current userId.
- cfo/controller/RagController.java, policy/controller/PolicyIntelligenceController.java — same.
- expense/controller/ExpenseController.java, investment/controller/InvestmentController.java, goal/controller/GoalController.java, budget/controller/BudgetController.java,
  dashboard/controller/DashboardController.java — delete the @RequestParam String userId and derive from principal (closes the cross-tenant read hole).
- document/controller/DocumentController.java + document/service/DocumentParserService.java — derive ownership from principal; pass userId into the service rather than
  @Value.
- expense/service/ExpenseDocumentIngestionService.java — accept userId as a method parameter from its (now authenticated) caller.

B. Background/scheduler context (no SecurityContext available — pass userId explicitly):
- cfo/service/CFOAdvisorService.java, cfo/service/ingestion/GrowwConnector.java, cfo/service/ingestion/NewsAggregatorService.java, cfo/service/llm/LlmRefinementService.java,
  cfo/service/GrowwAuthService.java. Most already accept userId as a method parameter internally — remove the @Value field and require the caller (scheduler, see 1.4) to pass
  it.

▎ NewsAggregatorService is architecturally global but currently uses defaultUserId only to bias symbol relevance. Decouple: news ingestion runs globally; personalization
▎ happens per-user at read time (it already has PersonalizedRelevanceScorer).

1.4 Scheduler fan-out (cfo/scheduler/CFOScheduler.java)

The per-user scheduled methods currently run once for defaultUserId. Wrap each per-user block in a loop over userRepository.findAllByEnabledTrue():
- Per-user (fan out): syncPreMarket, generateMorningBrief, syncMarketOpen, syncMidSessionAndInsight, syncPreClose, syncMarketClose, syncAfterSettlement, fetchStockPrices (the
  fetchAndPersistPrices(userId) part only), generatePostCloseInsight, generateAfterHoursDigest, weeklyPeerUniverseRefresh, weeklyGoalReview.
- Keep global (do NOT loop): all of MarketDataScheduler, refreshMacroState/Monthly, fetchInstitutionalFlows, refreshMarketContext, enrichNewsOutcomes, scoreInsightClaims,
  news fetches (fetchPreMarketNews/fetchIntradayNews/fetchPostMarketNews), and the benchmark/index price fetches in fetchStockPrices.
- Resilience: wrap each user's iteration in try/catch so one user's failure (e.g. expired Groww token) doesn't abort the batch; log and continue.
- Email: EmailNotificationService currently sends to a single cfo.user.email. Change sendDailyBrief/sendAfterHoursInsight to take a recipient address resolved from the user's
  User.email/UserProfile.email.

▎ Scale note (acknowledged, deferred): the only Executor is llmRefinementExecutor (core=1) and the scheduler is single-threaded. Sequential per-user fan-out is correct and
▎ safe for small N. A dedicated bounded TaskExecutor for per-user briefs + job queueing is a Phase 3 concern, not Phase 1.

1.5 Config & seeding

- application.properties / application-dev.properties: keep cfo.user.id only as a seed/admin default; add security.jwt.*.
- A CommandLineRunner (dev profile) seeds an admin User with username amit (matching existing data) if absent, so current data remains accessible.

1.6 Phase 1 tests

- AuthControllerTest (register/login/me happy + dup-username/bad-password paths).
- JwtServiceTest (issue/parse/expiry/tamper).
- SecurityConfigTest (@WebMvcTest + Spring Security test): unauthenticated → 401; user hitting admin route → 403; authenticated → 200.
- Cross-tenant regression test: user A's token cannot read user B's investments/goals/budget.
- Update existing controller tests that passed ?userId= to authenticate via @WithMockUser/JWT instead.

 ---
Phase 2 — API Hardening

Parallelizable once Phase 1 lands. No analytics changes.

2.1 DTO layer (*/dto per module, or a shared api/dto)

- Introduce request/response records; stop returning Investment, Expense, AiInsight, PortfolioSnapshot entities directly from controllers. The inline records already present
  (HoldingSummary, service-layer records) are a good template.
- Map with explicit hand mappers or MapStruct (add dependency if chosen). Strips lazy-loading serialization risk and schema leakage.
- Priority targets (entities currently leaked): CFOController, ExpenseController, InvestmentController.

2.2 Bean validation

- Annotate DTOs (@NotNull, @Positive, @Email, @Size); add @Valid on @RequestBody/@RequestParam (currently zero @Valid in the codebase).

2.3 Global exception handling

- Add @RestControllerAdvice GlobalExceptionHandler returning a consistent error body (RFC-7807-style ProblemDetail, supported in Spring 6). Map: validation → 400, auth →
  401/403, not-found → 404, existing client exceptions (PriceProviderException, NseUnavailableException, AmfiFetchException) → 502/503. Replaces the lone inline try/catch in
  CFOController.fetchPriceForSymbol.

2.4 OpenAPI

- Add springdoc-openapi-starter-webmvc-ui. Annotate the JWT security scheme so the Swagger UI supports bearer auth. This spec is the contract the Phase 4 frontend consumes.

2.5 Resilience around external calls

- Add resilience4j-spring-boot3. Wrap the RestClient/HttpClient calls in price providers (YahooFinancePriceProvider, AlphaVantagePriceProvider, NSEIndiaPriceProvider) and
  marketdata/client/* with circuit breaker + retry + time limiter, replacing the hand-rolled one-retry logic. Keep the existing provider fallback chain in
  StockPriceService.fetchWithFallback() — wrap each provider, don't remove the chain.
- Add @Cacheable (Caffeine) on hot, slow, read-mostly endpoints (e.g. latest portfolio snapshot, macro state) with short TTLs.

2.6 Phase 2 tests

- GlobalExceptionHandlerTest, DTO mapping tests, validation rejection tests, resilience4j fallback test (provider throws → circuit opens → fallback used).

 ---
Phase 3 — Operational Readiness

3.1 DB migrations (do before any real deployment)

- Add flyway-core. Generate a baseline V1__baseline.sql from the current Hibernate-generated schema (dump from a fresh update run). Add V2__users_and_roles.sql for the Phase
  1 auth tables.
- Flip spring.jpa.hibernate.ddl-auto to validate (keep update only in a local dev profile). This is mandatory — update will eventually fail or silently drift on a production
  schema.

3.2 Per-user rate limiting

- resilience4j RateLimiter (or a bucket filter) on expensive LLM/analytics endpoints (/api/cfo/chat, brief generation, insight cards), keyed on principal.

3.3 Observability

- spring-boot-starter-actuator + Micrometer; expose health/metrics; add counters/timers around external fetches and brief generation. Health probes for DB + scheduler
  liveness.

3.4 Secrets & deploy

- Move secrets out of .env to environment/secret manager for non-dev. Document required env vars. Externalize JWT secret.
- Per-user broker tokens are already AES-encrypted (AuthToken + GrowwAuthService) — verify the encryption key is externalized too.

3.5 Phase 3 tests / checks

- Flyway migration test (clean DB → migrate → validate passes).
- Rate-limit integration test (N+1 request → 429).

 ---
Phase 4 — Frontend

Out-of-process from the backend, consuming the Phase 2 OpenAPI contract.

- Stack: React + Vite (or Next.js) SPA, served separately; CORS configured in SecurityConfig.
- Auth flow: login → store JWT → attach Authorization header; refresh strategy (short access token; optional refresh token endpoint added to AuthController if sessions need
  longevity).
- Priority screens (lead with the analytical moat):
  a. Portfolio dashboard (holdings, P&L, snapshot) — /api/dashboard, /api/cfo/holdings.
  b. Risk & attribution (VaR, factor exposures, Brinson-Fachler, stress scenarios) — this is stronger than competitors; make it visual.
  c. Calibrated insight cards with confidence — the honesty layer is the differentiator.
  d. Goals, budgets, expenses CRUD.
  e. CFO chat (/api/cfo/chat).
- Codegen: generate the TS client from the OpenAPI spec to stay in sync.

 ---
Cross-cutting risks & notes

- Biggest reach, lowest risk: Phase 1's 10-site swap is mechanical because userId is already uniformly String and repositories already take it as a parameter. The genuine
  design work is scheduler fan-out + per-user email, not the query layer.
- Security hole closed in Phase 1: removing @RequestParam userId from Investment/Goal/Budget/Dashboard/Expense controllers. Until then, treat the app as fully open.
- Data moat unchanged: productizing does not fix reliance on scraped/free EOD sources. Paying users will expect intraday/real-time; flag as a separate strategic track.
- Do not fan out global services. Re-running market-data/macro/news ingestion per user would multiply external load and risk IP bans.
- Uncommitted work: there is in-flight TradingCostService work on the branch — commit or stash before starting Phase 1, and /clear for a clean context.

 ---
Verification (end-to-end)

After Phase 1:
1. ./mvnw clean test — all existing 281 tests + new auth tests green.
2. ./mvnw spring-boot:run; POST /api/auth/register two users (A, B); POST /api/auth/login → JWTs.
3. With no token: any /api/** → 401. With user A's token: A's data returns; attempting B's data (no longer possible via param) returns only A's. Admin route with user token
   → 403.
4. Trigger a manual brief/price-fetch endpoint and confirm it scopes to the caller.
5. Confirm seeded amit user still owns all pre-existing data.

After Phase 2: Swagger UI loads at /swagger-ui.html, bearer auth works; invalid request body → 400 with ProblemDetail; kill network to Yahoo → circuit opens → fallback
provider used, no 500.

After Phase 3: fresh DB → flyway:migrate → app boots with ddl-auto=validate; actuator /health UP; rate-limited endpoint returns 429 past threshold.
