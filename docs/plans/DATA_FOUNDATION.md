# Data Foundation Plan ("DF" phases)

> **Revisions (2026-06-13, after DF-0/DF-1 implementation + external review):**
> 1. **Execution order is now DF-0 → DF-1 → DF-2 → DF-6 → DF-3 → DF-4 → DF-5 → DF-7.** Outcome-linked RAG (DF-6) only depends on prices (DF-1) and adjusted closes (DF-2) — not on macro/AMFI — and is the product differentiator, so it moves up. DF-2 remains a hard prerequisite: event outcomes computed on unadjusted closes across splits/bonuses would be wrong.
> 2. **Seed is 6.5 years (2020-01-01 → today),** not 3 — includes COVID crash + full rate cycle.
> 3. **Delivery % + VWAP pulled into DF-1** via `sec_bhavdata_full` (one stable format 2020→today, joined by symbol+date).
> 4. **Sector membership will be effective-dated** (`sector_membership(instrument_id, sector, effective_from, effective_to)`), not a column on instrument — sectors/index membership change; attribution needs as-of-date truth. Applies when index-constituent ingestion lands.
> 5. **DF-2 event tables must be event-shaped** (type + event_date + structured payload) so DF-6's `market_event` can union over them without remodeling.

**Goal:** Invert the current imbalance — institutional-grade math fed by hobby-grade data — by building a market-wide, historically-seeded, multi-source, self-healing data layer for the *entire* Indian market (not just holdings), plus an outcome-linked news RAG. The LLM becomes pure inference over pre-computed, evidence-backed context.

**Approved diagnosis (2026-06-12):** 30-day cold-start starves risk/factor engines; macro mostly hand-typed with two broken Yahoo tickers; Yahoo is a ToS-grey single point of failure for the whole quant stack; RAG retrieves our own classifications instead of realized outcomes; committed Google API key.

---

## Strategic decisions (made, with rationale)

1. **Backbone = official exchange EOD files, not vendor APIs.** NSE bhavcopy + index close files + BSE bhavcopy are official, free, complete (every listed security, every day), and archived for years. They solve full-market coverage, historical seeding, and the second-source problem in one move. Yahoo is demoted to intraday quotes + fundamentals fallback.
2. **Seed 3 years, market-wide.** ~2,000 NSE EQ-series symbols × ~750 trading days ≈ 1.5M OHLCV rows — trivial for Postgres. There is no reason to seed less than 3 years: it's the same downloader, and 3y unlocks 2y-lookback metrics with a 1y buffer for new analytics.
3. **Universe = all of NSE (EQ/BE series) + indices, not holdings.** Holdings are a *view* over the market table, not the table. New positions get instant 3y history; watchlists, peers, and sector scans become free.
4. **Graph: stay in Postgres.** Typed relational edge tables + pgvector hybrid retrieval give 90% of a graph DB with zero new ops surface. Recursive CTEs handle 2-hop queries. Revisit Neo4j/Apache AGE only when a product feature genuinely needs multi-hop traversal (e.g., supply-chain contagion paths). Decision gate, not a default.
5. **No Kafka/Airflow.** At this scale, Spring scheduler + an `ingestion_run` job ledger with gap-detection/auto-repair is the production-grade answer. Orchestration frameworks come when there's a team to feed them.
6. **Compliance flag (important at seed-stage):** free exchange EOD files are fine for internal/personal analytics. *Redistributing* NSE/BSE data to paying customers requires a data license from NSE Data & Analytics / BSE. Budget for this before the product ships market data to users; real-time data definitely requires it.

---

## DF-0 — Security + correctness hotfixes (Day 1)

1. Rotate the Google AI Studio key committed at `application-dev.properties:33`; move to `.env`; scrub git history (`git filter-repo`) or accept-and-rotate; add a pre-commit secret scan.
2. Fix macro tickers: `USDINR` → `USDINR=X`, `^NSEXIT` → `^INDIAVIX` (verify via `macro_snapshot.data_quality_notes` first).
3. Cold-start backfill: in `StockPriceService.fetchAndPersistSymbol`, when a symbol has zero `StockPriceHistory` rows, fetch 730d (the benchmark path + `fetchWithFallbackOverride` already exist). This is the interim fix until DF-1 makes it obsolete.

## DF-1 — Market-wide EOD backbone (Weeks 1–2)

**New module `marketdata/`** (same layering conventions as existing modules).

Schema (monthly-partition `eod_price` if desired; optional at this volume):

```
instrument        (id, isin, nse_symbol, bse_code, name, series, sector, industry,
                   listing_date, status, face_value)
eod_price         (instrument_id, trade_date, open, high, low, close, prev_close, vwap,
                   volume, turnover, trades, deliverable_qty, delivery_pct, source,
                   PK (instrument_id, trade_date))
index_eod         (index_name, trade_date, open, high, low, close, pe, pb, div_yield)
ingestion_run     (job_name, business_date, status, row_count, started_at, finished_at, error)
```

Jobs:
- **Daily bhavcopy** (~18:45 IST): UDiFF common format (post-July-2024) `BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip` from nsearchives; plus `sec_bhavdata_full_DDMMYYYY.csv` for delivery %. NSE requires cookie warm-up (hit homepage first) + browser UA — build one polite, rate-limited `NseArchiveClient` and reuse it everywhere.
- **Daily index file**: `ind_close_all_DDMMYYYY.csv` — every NSE index with OHLC **and daily P/E, P/B, dividend yield**. This single file replaces Yahoo for the benchmark, all 10 factor indices, *and* India VIX close — and its P/E/P/B history fixes the valuation z-score cold start at index/sector level with real multi-year data.
- **Seeder** (one-off, resumable): walk 3 years of archive dates (skip exchange holidays via the NSE holiday calendar), throttled ~1 req/2s, idempotent UPSERT, progress in `ingestion_run`. ~750 files × 3 endpoints, an overnight run.
- **BSE cross-check**: BSE UDiFF bhavcopy for the same dates; nightly reconciliation report flags close-price mismatches > 0.5% as `DataQualityFlag`.

Rewire `ReturnSeriesService`/risk/factor/technicals to read `eod_price` (keep `StockPriceHistory` as a façade or migrate; prefer migrate). Yahoo stays only for: intraday quote in the mid-day insight (fixing the "yesterday's prices presented as today's" bug), and fundamentals until DF-4.

*Exit criteria:* every NSE symbol has ≥ 700 trading days; risk engine and factor model produce valid output for any symbol the day it's first bought; reconciliation job green for 5 consecutive days.

## DF-2 — Corporate actions + own adjusted closes (Weeks 2–3)

- Ingest NSE corporate-actions feed (splits, bonus, dividends, rights) — current + 3y history → `corporate_action` table.
- Compute our own cumulative adjustment factors and back-adjusted close series; stop trusting Yahoo `adjClose`.
- Replace the heuristic gap classifier: a −50% move on a known 1:2 split ex-date is *expected*, not `SUSPECT_GAP` or "lower circuit" — closes the −9.5%..−70% hole, and removes split-day poisoning of day-P&L.
- Ingest NSE event calendar (board meetings with "Results" purpose) → **earnings calendar** (`corporate_event`). The brief can finally say "TCS reports Thursday."

## DF-3 — Macro automation (Week 3)

Generic `macro_series (series_code, obs_date, value, source)` replacing config constants:
- **FBIL** daily G-sec par yield curve (1y/5y/10y + slope) — drives regime detection and Sharpe risk-free rate.
- **RBI** policy rates (repo, SDF, MSF, CRR) — parse RBI's current-rates page / DBIE; changes are rare, dailies cheap.
- **MOSPI via data.gov.in API** (free key): CPI (headline + core), IIP, WPI. Monthly, seeded 3y+.
- USD/INR + India VIX from DF-1's own feeds (index file has VIX; USD/INR from RBI reference rate / FBIL — drop the Yahoo FX call entirely).
- `MacroStateService` reads `macro_series`; staleness nags become per-series SLA checks; regime detection finally runs on real history.

## DF-4 — Funds & filings layer (Weeks 4–5)

- **AMFI NAVs**: daily `NAVAll.txt` (all schemes, official, trivially parseable) + 3y history via AMFI's NAV-history endpoint. `mf_scheme` (AMFI code ↔ ISIN) + `mf_nav`. MF holdings get daily mark-to-market and TWRR; LookThroughService gets NAV-weighted drift. (Seed history only for ~equity/hybrid categories first — full AMFI universe is ~40k codes; tier it.)
- **Monthly MF portfolio disclosures** for the user's schemes → automates the look-through CSV import (start manual-assisted: download + parse the AMC Excel disclosures; full automation later).
- **NSE corporate announcements** feed → `announcement` (the properties file already sketches this TODO).
- **Shareholding patterns** (quarterly): promoter/pledge/FII/DII per stock — among the highest-signal datasets in India.
- **Bulk/block deals** (daily CSVs).
- Fundamentals: keep Yahoo `quoteSummary` for ratios, but now compute *true* trailing P/E z-scores from DF-1's 3y price history + quarterly EPS — kills the `MIN_HISTORY_FOR_ZSCORE=60` self-recorded-snapshot crutch. (Market-wide XBRL results parsing from NSE filings is the eventual Yahoo-free path; defer.)

## DF-5 — Ingestion platform hardening (Week 5)

- **Job ledger + gap repair**: on startup and nightly, diff expected business dates vs `ingestion_run` successes per job; auto-enqueue repair backfills. Outage holes (macro, FII/DII, prices) become self-healing instead of silent.
- Retry with exponential backoff; email alert on a critical job failing after retries (reuse `EmailNotificationService`).
- `/api/data-quality` endpoint: per-dataset freshness, row counts, last success, open gaps — the ops dashboard.
- Postgres ops baseline: nightly `pg_dump` to off-box storage, basic disk/connection monitoring. (You have analytics worth protecting now.)

## DF-6 — News RAG v2: chronological, outcome-linked knowledge base (Weeks 6–7)

The corpus becomes an **event memory of the Indian market**, not a pile of labeled articles.

Schema additions:
```
news_cluster      (id, canonical_article_id, first_seen, centroid_embedding, title)
article_entity    (article_id, entity_type{INSTRUMENT|SECTOR|INDEX|MACRO_THEME|POLICY},
                   entity_id, confidence, source{GAZETTEER|LLM})
market_event      (id, cluster_id, event_type{RATE_DECISION|RESULTS|REGULATORY|M&A|
                   GUIDANCE|MACRO_PRINT|...}, event_date, structured_payload jsonb)
event_outcome     (cluster_id, instrument_id, horizon{1d,5d,20d}, raw_return,
                   excess_return_vs_nifty, computed_at)
```

Pipeline changes:
1. **Dedup at ingest**: embedding cosine ≥ ~0.92 against last-72h articles → attach to existing cluster instead of creating a new "independent" article. Fixes sentiment inflation and wasted Ollama refinement. Also: give HTML-fallback-scraped articles embeddings (currently skipped → invisible to RAG), and pin pub-date parsing to IST.
2. **Entity resolution** writes typed `article_entity` edges (this *is* the knowledge graph — in Postgres).
3. **Outcome enrichment job** (nightly, after bhavcopy lands): for every cluster older than 1/5/20 trading days, join related instruments to realized **excess returns vs Nifty** from `eod_price` and persist. Because DF-1 is market-wide, this works for *any* mentioned stock, not just holdings — the corpus learns from the whole market.
4. **Retrieval service** for brief generation: hybrid score = vector similarity × recency decay × entity overlap with the portfolio; returns *evidence packs*: "similar event (cluster, date) → these stocks moved X% excess over 5d." The system prompt's "last time crude spiked…" promise becomes literally true.
5. Refinement-loop fixes while in there: remove the flat `relevanceScore +10` for LLM-touched articles; apply the `confidence ≥ 0.6` gate to symbol/sector merges; `getPortfolioSymbols` reads the `investments` table; use Ollama `format: json` (and provider-native schema enforcement) instead of 80 lines of regex recovery.

## DF-7 — Inference integration + evaluation loop (Week 8)

- **ContextAssemblyService**: per-section token budgets with priority ordering (risk → movers → evidence packs → news → goals), deterministic truncation, total budget per provider. The LLM does zero data work — pure inference over assembled evidence.
- **Split provider routing**: strong API model for briefs, Ollama for refinement/embeddings (the `LLMProvider` abstraction already supports it; bind two beans by purpose).
- **Insight evaluation loop**: persist each brief's actionable claims (symbol, direction, horizon, confidence) → nightly job scores them against realized `eod_price` returns → calibration report per provider/prompt-version. No prompt tuning without this scoreboard. Feed `tier2_overridden` corrections back into gazetteer rules monthly.

---

## Deliberately out of scope (for now)
- Tick/real-time data, options chains (Phase 10 stays parked until DF is done — GARCH/options on this foundation would be noise on noise).
- Neo4j/graph DB (gated decision, see above), Kafka/Airflow, microservice split.
- Market-wide XBRL fundamentals parsing (tier after DF-4 proves the filings client).

## Sequencing logic
DF-1 is the keystone: outcomes (DF-6), true z-scores (DF-4), CA detection (DF-2), and evaluation (DF-7) all consume the market-wide price table. Everything else can interleave, but nothing useful happens before the backbone exists.

## Verification per phase
Each DF phase lands with: idempotency test (re-run same date → no dupes), a seeded-history row-count assertion, a reconciliation/quality check wired into `/api/data-quality`, and `ingestion_run` coverage. Endpoint URLs above are current as of mid-2024 NSE UDiFF migration — re-verify each at implementation time; NSE changes formats without notice, which is exactly why the job ledger + alerting exists.
