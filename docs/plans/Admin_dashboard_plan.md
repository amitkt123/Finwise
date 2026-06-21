dmin Dashboard — Backend + FE Guide

Context

Finwise already exposes ADMIN-gated endpoints for market data (/api/marketdata/**), data quality (/api/data-quality/**), macro series (/api/cfo/macro-series/**), and insight
evaluation (/api/cfo/evaluation/**). However, several critical ops workflows have no HTTP surface at all and require direct DB access or SSH access to the server:

- Policy document lifecycle (status changes, delete, view chunks/impacts)
- Cross-user document management (list failed parses, retry)
- User management (list, enable/disable, role assignment)
- Reference data reload (symbol_gazetteer.csv, stress_scenarios.csv, nifty_sector_weights.csv)
- Scheduler health & manual trigger
- Runtime settings (risk-free rate, shrinkage toggle)
- A unified admin overview / home page

This plan adds all missing backend endpoints, fixes the security gap (policy sync is currently not ADMIN-only), and produces a FE implementation guide.

 ---
Phase 1 — Security Fix (1 file)

File: src/main/java/org/amit/finwise/config/SecurityConfig.java

Add to the ADMIN-only pattern list:
/api/admin/**
/api/policy-intelligence/sync
/api/policy-intelligence/sync/**

 ---
Phase 2 — Admin Overview Controller (new files)

New file: src/main/java/org/amit/finwise/admin/controller/AdminOverviewController.java
New file: src/main/java/org/amit/finwise/admin/service/AdminOverviewService.java

Endpoint: GET /api/admin/overview

Returns a summary record:
record AdminOverview(
long totalUsers,
long enabledUsers,
long totalPolicyDocuments,
long activePolicyDocuments,
long totalDocumentUploads,
long failedDocumentUploads,
DataQualityStatus dataQuality,   // reuse existing DQ response
MacroFreshnessStatus macroFreshness,  // reuse existing freshness response
List<SchedulerJobStatus> schedulerJobs  // from Phase 6
)

Aggregates from: UserRepository, PolicyDocumentRepository, DocumentUploadRepository, existing DataQualityService, MacroSeriesAdminService, and the scheduler health service from
Phase 6.

 ---
Phase 3 — User Management (new files)

New file: src/main/java/org/amit/finwise/admin/controller/AdminUserController.java
New file: src/main/java/org/amit/finwise/admin/service/AdminUserService.java

Uses existing UserRepository and User entity (already has enabled, roles fields).

┌────────┬─────────────────────────────────────┬─────────────────────────────────────────────────────┐
│ Method │                Path                 │                     Description                     │
├────────┼─────────────────────────────────────┼─────────────────────────────────────────────────────┤
│ GET    │ /api/admin/users                    │ List all users, paginated (?page=0&size=20&search=) │
├────────┼─────────────────────────────────────┼─────────────────────────────────────────────────────┤
│ GET    │ /api/admin/users/{username}         │ User detail with role list                          │
├────────┼─────────────────────────────────────┼─────────────────────────────────────────────────────┤
│ PUT    │ /api/admin/users/{username}/enabled │ Body: { "enabled": true/false }                     │
├────────┼─────────────────────────────────────┼─────────────────────────────────────────────────────┤
│ PUT    │ /api/admin/users/{username}/roles   │ Body: { "roles": ["ROLE_USER", "ROLE_ADMIN"] }      │
└────────┴─────────────────────────────────────┴─────────────────────────────────────────────────────┘

AdminUserService.listUsers(Pageable, String search) — uses UserRepository.findByUsernameContainingIgnoreCase (add this derived query if not present) or findAll(Pageable).

 ---
Phase 4 — Admin Policy Controller (new files)

New file: src/main/java/org/amit/finwise/admin/controller/AdminPolicyController.java
New file: src/main/java/org/amit/finwise/admin/service/AdminPolicyService.java

Uses existing PolicyDocumentRepository, PolicyDocumentVersionRepository, PolicyChunkRepository, PolicyImpactRepository, and PolicyIntelligenceService (existing ingest/delete
logic if any).

┌────────┬──────────────────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Method │                       Path                       │                                                  Description                                                   │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ PATCH  │ /api/admin/policy/documents/{id}/status          │ { "status": "SUPERSEDED" } — update PolicyDocument.status                                                      │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ PATCH  │ /api/admin/policy/documents/{id}/metadata        │ { "effectiveTo": "2026-12-31", "tags": [...] }                                                                 │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ DELETE │ /api/admin/policy/documents/{id}                 │ Delete document + cascade chunks + impacts (via repository deleteById cascade or explicit delete order)        │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/policy/documents/{id}/chunks          │ List all PolicyChunk records for a document's current version                                                  │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/policy/documents/{documentId}/impacts │ List PolicyImpact records for a document                                                                       │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ PATCH  │ /api/admin/policy/impacts/{id}                   │ { "direction": "NEGATIVE", "confidenceScore": 0.8, "horizon": "SHORT_TERM" }                                   │
├────────┼──────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ POST   │ /api/admin/policy/documents/upload-pdf           │ multipart/form-data: file (PDF) + metadata fields → extract text via existing PdfTextExtractionService → call  │
│        │                                                  │ existing PolicyIntelligenceService.ingestDocument()                                                            │
└────────┴──────────────────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

The PDF upload endpoint reuses PdfTextExtractionService (already at document/service/PdfTextExtractionService.java) and the existing PolicyIntelligenceService.ingestDocument()
pipeline.

 ---
Phase 5 — Admin Document Queue Controller (new files)

New file: src/main/java/org/amit/finwise/admin/controller/AdminDocumentController.java
New file: src/main/java/org/amit/finwise/admin/service/AdminDocumentService.java

Uses existing DocumentUploadRepository and DocumentParserService.

┌────────┬───────────────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Method │               Path                │                                                          Description                                                          │
├────────┼───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/documents              │ All uploaded docs, paginated. Params: ?status=FAILED&userId=&page=0&size=20                                                   │
├────────┼───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ POST   │ /api/admin/documents/{id}/reparse │ Optional body: { "password": "..." }. Re-reads storagePath, calls PdfTextExtractionService + DocumentParserService, updates   │
│        │                                   │ DocumentUpload record                                                                                                         │
└────────┴───────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

DocumentUploadRepository needs one new query method: findAll(Specification, Pageable) using Spring Data JPA Specifications for cross-user filtering by parseStatus and optional
userId.

 ---
Phase 6 — Scheduler Health + Trigger (new files)

New file: src/main/java/org/amit/finwise/admin/controller/AdminSchedulerController.java
New file: src/main/java/org/amit/finwise/admin/service/AdminSchedulerService.java

AdminSchedulerService autowires ThreadPoolTaskScheduler (or ScheduledTaskHolder from Spring context) and wraps each CFOScheduler and PolicyIntelligenceScheduler method.

┌────────┬─────────────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Method │                    Path                     │                                                     Description                                                     │
├────────┼─────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/scheduler/jobs                   │ List all known jobs with: name, description, last-run time (read from ingestion_run ledger by job name), next       │
│        │                                             │ scheduled fire time (from ScheduledFuture references), enabled flag                                                 │
├────────┼─────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ POST   │ /api/admin/scheduler/jobs/{jobName}/trigger │ Manually invoke one job by name (switch dispatch to the appropriate @Scheduled method on CFOScheduler or            │
│        │                                             │ PolicyIntelligenceScheduler). Runs async via @Async                                                                 │
└────────┴─────────────────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Known job names (use as slug keys): premarket-news, groww-sync-premarket, morning-brief, intraday-news, price-fetch, postmarket-news, post-close-insight, after-hours-digest,
macro-daily, macro-monthly, fii-dii-flows, weekly-goal-review, weekly-peer-refresh, rag-outcome-enrich, insight-claim-score, policy-sync.

Last-run time is readable from ingestion_run table via IngestionRunRepository.findTopByJobNameOrderByCreatedAtDesc(jobName) (check if this derived method exists; add if not).

 ---
Phase 7 — Reference Data Controller (new files)

New file: src/main/java/org/amit/finwise/admin/controller/AdminReferenceDataController.java

Calls reload methods (add reload() to each service):
- SymbolExtractorService.reloadGazetteer(InputStream) — parses CSV from upload, rebuilds alias trie in-place (existing init() logic, parameterise it)
- StressScenarioService.reloadScenarios(InputStream) — re-reads CSV and replaces scenarios list
- AttributionService.reloadSectorWeights(InputStream) — re-reads CSV and replaces sectorWeights map

┌────────┬───────────────────────────────────────┬────────────────────────────────────────────────────────────────┐
│ Method │                 Path                  │                          Description                           │
├────────┼───────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/reference/symbol-gazetteer │ Stream the current in-memory CSV content as text/csv download  │
├────────┼───────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
│ POST   │ /api/admin/reference/symbol-gazetteer │ Upload new CSV → call SymbolExtractorService.reloadGazetteer() │
├────────┼───────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/reference/stress-scenarios │ Download current scenarios CSV                                 │
├────────┼───────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
│ POST   │ /api/admin/reference/stress-scenarios │ Upload new CSV → call StressScenarioService.reloadScenarios()  │
├────────┼───────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
│ GET    │ /api/admin/reference/sector-weights   │ Download current sector weights CSV                            │
├────────┼───────────────────────────────────────┼────────────────────────────────────────────────────────────────┤
│ POST   │ /api/admin/reference/sector-weights   │ Upload new CSV → call AttributionService.reloadSectorWeights() │
└────────┴───────────────────────────────────────┴────────────────────────────────────────────────────────────────┘

Note: On restart the CSVs revert to classpath originals. Uploaded CSVs are applied in-memory only. Document this clearly in the FE guide (suggest saving to policy.storage-dir
for persistence if needed — future enhancement).

 ---
Phase 8 — Market Data Historical Earnings Upload (extend existing controller)

File: src/main/java/org/amit/finwise/marketdata/controller/MarketDataAdminController.java

Add one new endpoint alongside existing importHistoricalEarnings:
POST /api/marketdata/events/import-historical/upload
multipart/form-data: file (CSV). Save to temp file path → call existing earningsService.importHistorical(path). This removes the need for ops to SSH + place the file manually.

 ---
Phase 9 — FE Implementation Guide

New file: docs/ADMIN_DASHBOARD_FE_GUIDE.md

Written after all backend phases are implemented. Documents:

Admin Dashboard Sections

1. Overview (Home)

- Calls GET /api/admin/overview
- Shows stat cards: users, policy docs, failed docs, data quality badge, macro freshness
- Shows scheduler job health table

2. Policy Documents

- Table: GET /api/policy-intelligence/documents?authority=&status=&limit=50
- Upload PDF: POST /api/admin/policy/documents/upload-pdf (multipart)
- Ingest text: POST /api/policy-intelligence/documents/ingest/text
- Status chip with change: PATCH /api/admin/policy/documents/{id}/status
- Delete: DELETE /api/admin/policy/documents/{id}
- View chunks drawer: GET /api/admin/policy/documents/{id}/chunks
- Impacts table: GET /api/admin/policy/documents/{documentId}/impacts + PATCH /api/admin/policy/impacts/{id}
- Trigger crawl buttons: POST /api/policy-intelligence/sync + POST /api/policy-intelligence/sync/{sourceKey}

3. User Management

- Table: GET /api/admin/users?search=&page=
- Toggle enabled: PUT /api/admin/users/{username}/enabled
- Edit roles: PUT /api/admin/users/{username}/roles

4. Market Data Operations (tabbed)

- EOD Prices tab: seed form (from/to date), stop, status progress, validate, force single-date ingest
- MF NAV tab: ingest today, seed form, stop, status
- Corporate Actions tab: seed form, stop, status, date-range ingest, recompute adjustments
- Filings tab: announcements / shareholding / deals with date range pickers
- Earnings tab: event calendar trigger + file upload for historical CSV (POST /api/marketdata/events/import-historical/upload)
- All call existing /api/marketdata/** endpoints

5. Data Quality

- Full report card: GET /api/data-quality
- Repair button: POST /api/data-quality/repair → show repair report modal
- Backup button: POST /api/data-quality/backup

6. Macro Data

- Freshness table: GET /api/cfo/macro-series/freshness
- "Ingest Daily" button: POST /api/cfo/macro-series/ingest/daily
- "Ingest Monthly" button: POST /api/cfo/macro-series/ingest/monthly

7. Document Queue

- Table: GET /api/admin/documents?status=FAILED&page=
- Filterable by userId and parseStatus
- Reparse button (with optional password input): POST /api/admin/documents/{id}/reparse

8. Reference Data

- Three cards: Gazetteer / Stress Scenarios / Sector Weights
- Download current: GET /api/admin/reference/{type}
- Upload new: POST /api/admin/reference/{type} (multipart)
- Show in-memory only warning

9. Scheduler

- Job list: GET /api/admin/scheduler/jobs
- Shows: name, last run, next fire, enabled badge
- Manual trigger button: POST /api/admin/scheduler/jobs/{name}/trigger

10. Insight Evaluation

- Calibration scoreboard: GET /api/cfo/evaluation/calibration?days=90
- Group by provider + prompt version, show hit rate, Brier score, avg confidence
- Force score button: POST /api/cfo/evaluation/score

Auth

All admin calls include Authorization: Bearer <jwt> header. The JWT must come from a ROLE_ADMIN user. FE should check /api/auth/me on load and redirect to login if not admin.

Error Handling Pattern

- 403 Forbidden → show "Insufficient privileges" banner (user lost ADMIN role mid-session)
- 409 Conflict on document ingest → "Document already indexed" with documentKey in error body
- Long-running ops (seed walks) → poll status endpoint every 5s while status !== COMPLETED && status !== STOPPED

 ---
Files to Create/Modify

New files

- src/main/java/org/amit/finwise/admin/controller/AdminOverviewController.java
- src/main/java/org/amit/finwise/admin/service/AdminOverviewService.java
- src/main/java/org/amit/finwise/admin/controller/AdminUserController.java
- src/main/java/org/amit/finwise/admin/service/AdminUserService.java
- src/main/java/org/amit/finwise/admin/controller/AdminPolicyController.java
- src/main/java/org/amit/finwise/admin/service/AdminPolicyService.java
- src/main/java/org/amit/finwise/admin/controller/AdminDocumentController.java
- src/main/java/org/amit/finwise/admin/service/AdminDocumentService.java
- src/main/java/org/amit/finwise/admin/controller/AdminSchedulerController.java
- src/main/java/org/amit/finwise/admin/service/AdminSchedulerService.java
- src/main/java/org/amit/finwise/admin/controller/AdminReferenceDataController.java
- docs/ADMIN_DASHBOARD_FE_GUIDE.md

Modified files

- src/main/java/org/amit/finwise/config/SecurityConfig.java — add /api/admin/**, fix policy sync to ADMIN-only
- src/main/java/org/amit/finwise/marketdata/controller/MarketDataAdminController.java — add file upload variant for historical earnings
- src/main/java/org/amit/finwise/cfo/service/news/SymbolExtractorService.java — add reloadGazetteer(InputStream)
- src/main/java/org/amit/finwise/cfo/service/StressScenarioService.java — add reloadScenarios(InputStream)
- src/main/java/org/amit/finwise/cfo/service/AttributionService.java — add reloadSectorWeights(InputStream)
- src/main/java/org/amit/finwise/auth/repository/UserRepository.java — add findByUsernameContainingIgnoreCase(String, Pageable) if absent
- src/main/java/org/amit/finwise/document/repository/DocumentUploadRepository.java — add JPA Specification support if not present

 ---
Verification

1. ./mvnw clean package -DskipTests — must compile with zero errors
2. ./mvnw spring-boot:run — startup must succeed
3. Login as the dev user (amit, who has ROLE_ADMIN) → POST /api/auth/login
4. Call GET /api/admin/overview → 200 with stats
5. Call GET /api/admin/users → list with the dev user entry
6. Call GET /api/admin/scheduler/jobs → list of job names
7. Call PATCH /api/admin/policy/documents/{id}/status → 200 (test with a seeded doc if policy sync has run)
8. Call POST /api/admin/reference/symbol-gazetteer with the existing CSV → reloadGazetteer should succeed
9. Call POST /api/policy-intelligence/sync as ROLE_USER (non-admin JWT) → 403 (security fix verified)
10. Review docs/ADMIN_DASHBOARD_FE_GUIDE.md for completeness