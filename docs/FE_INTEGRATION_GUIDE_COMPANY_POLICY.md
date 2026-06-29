# FE Integration Guide — Company Intelligence View + Policy RAG

**Audience:** AI agent working in the Finwise FE repo  
**Backend branch:** master (as of 2026-06-24)  
**Base URL:** `https://<host>/api`  
**Auth:** Bearer JWT in `Authorization` header (except where noted as public)

---

## 1. Company Intelligence View

### 1.1 Endpoint

```
GET /api/company/{symbol}
Authorization: Bearer <token>
```

- `symbol` — NSE ticker, case-insensitive (`TCS`, `INFY`, `RELIANCE`)
- Returns the complete six-card company profile in a **single call** — no need for multiple requests.
- Unauthenticated calls are allowed; `riskFit.portfolioFit` will be `null` (portfolio fit requires user's holdings).

### 1.2 Full TypeScript shape

```ts
// ── Top-level ────────────────────────────────────────────────────────────────
interface CompanyProfile {
  symbol: string;
  asOf: string;             // ISO date "2026-06-24"
  knownSymbol: boolean;     // false → show "symbol not found" empty state
  hasPriceHistory: boolean; // false → most numeric cards will be null
  sector: string | null;

  quoteContext: QuoteContext | null;          // Card 1
  corporateActions: CorporateActionsCard | null; // Card 2
  ownership: OwnershipCard | null;           // Card 3
  fundamentals: FundamentalsCard | null;     // Card 4
  riskFit: RiskFitCard | null;               // Card 5
  newsPolicy: NewsPolicyCard | null;         // Card 6

  // Phase 4 — one plain-English sentence per card; null when LLM is off
  beginnerNarrations: {
    quote: string;
    corporateActions: string;
    ownership: string;
    fundamentals: string;
    riskFit: string;
    newsPolicy: string;
  } | null;

  dataGaps: string[]; // list of fields that could not be populated — show as "data unavailable"
}

// ── Card 1 — Quote & Index Context ───────────────────────────────────────────
interface QuoteContext {
  lastClose: number | null;
  dayChangePercent: number | null;
  hitCircuitToday: boolean;
  circuitType: 'UPPER' | 'LOWER' | null;
  week52High: number | null;
  week52Low: number | null;
  pricePercentileInRange: number | null; // 0..1; multiply ×100 to show as %
  volumeZScore: number | null;           // >2 = abnormally high volume
  relativeStrength: RelativeStrength[];
}

interface RelativeStrength {
  window: '1M' | '3M' | '1Y';
  stockReturnPct: number | null;
  niftyReturnPct: number | null;
  vsNiftyPct: number | null;             // positive = outperforming Nifty
  sectorIndex: string | null;            // e.g. "NIFTYIT"
  sectorReturnPct: number | null;
  vsSectorPct: number | null;
}

// ── Card 2 — Corporate Actions & Events ──────────────────────────────────────
interface CorporateActionsCard {
  pastActions: PastAction[];
  forwardEvents: ForwardEvent[];
  postResultsDrift: EventStudyResult | null; // null when < 3 past results events
}

interface PastAction {
  actionType: string;          // "DIVIDEND" | "SPLIT" | "BONUS" | "BUYBACK" | ...
  subject: string | null;
  exDate: string | null;       // ISO date
  recordDate: string | null;
  dividendAmount: number | null;
  ratioNew: number | null;     // split/bonus ratio numerator
  ratioOld: number | null;     // split/bonus ratio denominator
  dividendYieldPct: number | null;
}

interface ForwardEvent {
  eventType: string;           // "EX_DIVIDEND" | "BOARD_MEETING" | "RESULTS" | "AGM" | ...
  eventDate: string;           // ISO date
  description: string | null;
  daysUntil: number;           // countdown — show as badge "in N days"
}

interface EventStudyResult {
  symbol: string;
  eventCount: number;          // number of past events used
  windowDays: number;          // post-event window (typically 5)
  meanCar: number;             // decimal e.g. 0.018
  meanCarPercent: number;      // same as percentage e.g. 1.8
  aar: number[];               // avg abnormal return per post-event day (decimal)
  summary: string;             // ready-made human sentence e.g. "TCS drifts +1.8% in 3 days post-results"
}

// ── Card 3 — Ownership & Smart-Money ─────────────────────────────────────────
interface OwnershipCard {
  shareholdingTrend: ShareholdingQuarter[];  // newest first, up to 4 quarters
  latestPromoterPledgePct: number | null;
  ownershipMomentum: -1 | 0 | 1;
  ownershipMomentumLabel: string;            // "Buying" | "Neutral" | "Selling"
  recentDeals: RecentDeal[];
}

interface ShareholdingQuarter {
  periodEnded: string;        // ISO date — quarter end
  promoterPct: number | null;
  promoterPledgePct: number | null;
  fiiPct: number | null;
  diiPct: number | null;
  publicPct: number | null;
  promoterQoqDelta: number | null;  // QoQ change in percentage points
  fiiQoqDelta: number | null;
  diiQoqDelta: number | null;
}

interface RecentDeal {
  dealDate: string;
  dealType: 'BULK' | 'BLOCK';
  clientName: string | null;
  side: 'BUY' | 'SELL';
  quantity: number | null;
  price: number | null;
}

// ── Card 4 — Fundamentals & Valuation ────────────────────────────────────────
interface FundamentalsCard {
  fundamentals: StockFundamentals | null;  // see existing StockDeepDive types
  peerVerdicts: string[];  // pre-formatted strings e.g. "P/E 28 — pricier than 70% of IT peers"
}

// ── Card 5 — Risk & Portfolio Fit ─────────────────────────────────────────────
interface RiskFitCard {
  portfolioFit: PortfolioFit | null;   // null when unauthenticated
  riskMetrics: RiskMetrics | null;
}
// PortfolioFit and RiskMetrics shapes come from existing StockDeepDive types
// already in the FE. Key fields you will display:
//   portfolioFit.verdict: "BUY" | "AVOID" | "DIVERSIFY"
//   riskMetrics.maxDrawdownPct, riskMetrics.volatilityPct, riskMetrics.beta

// ── Card 6 — News & Policy Catalysts ─────────────────────────────────────────
interface NewsPolicyCard {
  news: NewsArticle[];         // existing type — title, url, publishedAt, sentiment, ...
  policyExposure: PolicyEventCard[];  // see Section 2 for full type
}
```

### 1.3 Rendering guidelines per card

| Card | Key UI moments |
|------|---------------|
| **Quote** | 52-week range bar: `pricePercentileInRange` drives the thumb position. Volume z-score >2 → orange badge "Unusually high volume". Circuit badge if `hitCircuitToday`. |
| **Corporate Actions** | Separate tabs: Past / Upcoming. Upcoming: countdown pill "in N days" using `daysUntil`. `postResultsDrift.summary` is display-ready — put it in an insight chip below the table. |
| **Ownership** | Stacked bar chart per quarter. `ownershipMomentumLabel` → badge colour: Buying=green, Selling=red, Neutral=grey. Highlight `latestPromoterPledgePct` in red when >25. Bulk/block deal table with BUY/SELL colour. |
| **Fundamentals** | Render `peerVerdicts` as bullet list under each metric. Existing component for `StockFundamentals` can be reused. |
| **Risk & Fit** | Verdict chip: BUY=green, AVOID=red, DIVERSIFY=amber. Show `maxDrawdownPct` as a risk bar. |
| **News & Policy** | Two sections: news feed (existing) + policy cards list (see Section 2 rendering). |

### 1.4 `beginnerNarrations` usage

When `beginnerNarrations` is non-null, show one sentence per card in a highlighted callout at the top of each card — designed for beginner users. These are already LLM-generated and display-ready; do not transform them.

### 1.5 Graceful degradation

- If `knownSymbol === false`, show a full-page "Symbol not found" state.
- Check `dataGaps` — if it contains a card name (e.g. `"ownership"`), show a muted "Data not available" placeholder for that card.
- Individual fields can be `null` — always guard before rendering numbers.

---

## 2. Policy Intelligence

All policy endpoints live under `/api/policy-intelligence`.

### 2.1 Shared type: `PolicyEventCard`

This is the core data unit across search, advisor context, and company Card 6.

```ts
interface PolicyEventCard {
  eventId: string;              // "policy-impact-{id}" — stable, use as React key
  impactId: number;
  documentId: number;
  documentTitle: string;
  authority: PolicyAuthority;
  documentType: PolicyDocumentType;
  bindingLevel: PolicyBindingLevel;
  policyArea: PolicyArea;
  sourceReference: string | null;
  sourceUrl: string | null;
  publishedDate: string | null;   // ISO date
  effectiveFrom: string | null;
  effectiveTo: string | null;
  actionType: PolicyActionType;
  subjectType: PolicySubjectType;
  subjectKey: string;            // e.g. "IT", "capital-gains", "repo-rate"
  subjectLabel: string | null;
  affectedParty: string | null;
  transmissionChannel: PolicyTransmissionChannel;
  direction: 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL' | 'MIXED';
  horizon: 'IMMEDIATE' | 'SHORT_TERM' | 'MEDIUM_TERM' | 'LONG_TERM';
  surpriseClassification: 'SURPRISE' | 'IN_LINE' | 'ANTICIPATED';
  legalForceRank: number;        // 0–100; higher = more binding
  marketMovingPower: number;     // 0–100; use for sort / intensity ring
  confidenceScore: number | null;// 0–1
  impactSummary: string | null;  // one-paragraph impact description — display directly
  implementationSummary: string | null;
  reasoningNote: string | null;
  falsificationSignal: string | null;
  citation: PolicyCitation;
  tags: string[];
}

interface PolicyCitation {
  authority: PolicyAuthority;
  reference: string | null;
  title: string;
  url: string | null;
  publishedDate: string | null;
  effectiveFrom: string | null;
}

// Enum literals (all string enums from the backend)
type PolicyAuthority = 'RBI' | 'SEBI' | 'PIB' | 'MCA' | 'IRDAI' | 'PFRDA' | 'AMFI' | 'NSE' | 'BSE' | 'UNKNOWN';
type PolicyDocumentType = 'CIRCULAR' | 'NOTIFICATION' | 'PRESS_RELEASE' | 'MASTER_DIRECTION' | 'RULE' | 'REGULATION' | 'ACT' | 'MONETARY_POLICY' | 'ANNOUNCEMENT' | 'POLICY_STATEMENT' | 'GUIDELINES' | 'REPORT' | 'AMENDMENT' | 'ORDER';
type PolicyBindingLevel = 'LAW' | 'RULE' | 'REGULATION' | 'CIRCULAR_NOTIFICATION' | 'BINDING_COMPLIANCE_CHANGE' | 'BINDING' | 'MARKET_MOVING_GUIDANCE' | 'AUTHORITATIVE_GUIDANCE' | 'ADVISORY' | 'INDUSTRY_BODY' | 'INFORMATIONAL';
type PolicyArea = 'MONETARY_POLICY' | 'FISCAL_POLICY' | 'BANKING_REGULATION' | 'SECURITIES_MARKET' | 'TAXATION' | 'FOREIGN_INVESTMENT' | 'INSURANCE' | 'PENSION' | 'MUTUAL_FUNDS' | 'CORPORATE_LAW' | 'REAL_ESTATE' | 'CRYPTOCURRENCY' | 'PAYMENTS' | 'INFRASTRUCTURE' | 'OTHER';
type PolicyActionType = 'RATE_CHANGE' | 'LIMIT_CHANGE' | 'COMPLIANCE_REQUIREMENT' | 'TAX_RATE_CHANGE' | 'TDS_CHANGE' | 'FDI_LIMIT_CHANGE' | 'MARKET_STRUCTURE_CHANGE' | 'LIQUIDITY_OPERATION' | 'REPORTING_REQUIREMENT' | 'PRODUCT_APPROVAL' | 'PRODUCT_BAN' | 'MERGER_ACQUISITION_RULE' | 'OTHER';
type PolicySubjectType = 'SECTOR' | 'ASSET_CLASS' | 'FACTOR' | 'TAX_TOPIC' | 'MARKET_STRUCTURE' | 'THEME' | 'STOCK';
type PolicyTransmissionChannel = 'INTEREST_RATE' | 'CREDIT' | 'EXCHANGE_RATE' | 'EQUITY_VALUATION' | 'FISCAL' | 'REGULATORY_COST' | 'SENTIMENT' | 'LIQUIDITY' | 'DIRECT_MANDATE';
```

### 2.2 Document list

```
GET /api/policy-intelligence/documents
  ?authority=RBI        (optional, enum)
  ?status=ACTIVE        (optional: ACTIVE | SUPERSEDED | DRAFT | ARCHIVED)
  ?limit=20             (default 20)
```

**Response:** `PolicyDocumentSummary[]`

```ts
interface PolicyDocumentSummary {
  id: number;
  documentKey: string;
  authority: PolicyAuthority;
  documentType: PolicyDocumentType;
  bindingLevel: PolicyBindingLevel;
  policyArea: PolicyArea;
  status: 'ACTIVE' | 'SUPERSEDED' | 'DRAFT' | 'ARCHIVED';
  title: string;
  sourceUrl: string | null;
  sourceReference: string | null;
  latestVersionNumber: number | null;
  publishedDate: string | null;   // ISO date
  effectiveFrom: string | null;
  effectiveTo: string | null;
  tags: string[];
  affectedSectors: string[];
  summary: string | null;
  updatedAt: string;              // ISO datetime
}
```

### 2.3 Search

```
GET /api/policy-intelligence/search?query=repo+rate&limit=10
```

No auth required. `query` is a free-text string — routed through hybrid lexical+vector retrieval.

**Response:** `PolicySearchResult`

```ts
interface PolicySearchResult {
  documents: PolicyDocumentSummary[];
  chunks: PolicyChunkMatch[];
  eventCards: PolicyEventCard[];  // also accessible as .impacts()
}

interface PolicyChunkMatch {
  chunkId: number;
  documentId: number;
  documentTitle: string;
  authority: PolicyAuthority;
  chunkIndex: number;
  heading: string | null;
  sectionPath: string | null;
  citationLabel: string | null;
  pageNumberStart: number | null;
  pageNumberEnd: number | null;
  content: string;             // ~1800 chars; render in a collapsed expandable
}
```

**UI pattern:** Show `eventCards` as the primary result surface (they carry the pre-extracted impact verdict). `chunks` power a "show source text" drill-down. `documents` support a document-level filter sidebar.

### 2.4 Advisor context (personalised)

```
GET /api/policy-intelligence/context
  ?message=what+affects+my+IT+portfolio
  ?limit=6
Authorization: Bearer <token>
```

Matches policies against the authenticated user's holdings + goals + free-text message. Uses hybrid RAG internally.

**Response:** `AdvisorPolicyContext`

```ts
interface AdvisorPolicyContext {
  documents: PolicyDocumentSummary[];
  eventCards: PolicyEventCard[];
  chunks: PolicyChunkMatch[];
}
```

This endpoint powers the "Policies relevant to you" section on the dashboard / CFO chat. Call with a user's message when they ask a policy question.

### 2.5 Policy timeline

```
GET /api/policy-intelligence/timeline            → string[] (all tracked subjectKeys)
GET /api/policy-intelligence/timeline/{subjectKey}  → PolicyTimeline
```

`subjectKey` examples: `repo-rate`, `capital-gains`, `cpi-inflation`, `fdi-it`, `sebi-f-o`.

**Response:**

```ts
interface PolicyTimeline {
  subjectKey: string;
  entries: TimelineEntry[];       // newest first
  historicalHitRate: number | null; // 0..1 — what % of past predictions proved correct
}

interface TimelineEntry {
  changeId: number;
  documentId: number;
  documentKey: string;
  documentTitle: string;
  authority: string | null;
  fromVersionNumber: number | null;
  toVersionNumber: number;
  issuedAt: string;               // ISO datetime
  effectiveFrom: string | null;   // ISO date
  changeSummary: string | null;   // concise LLM diff e.g. "CRR 4.0% → 4.5%"
  changeNarrative: string | null; // longer explanation
  recordedAt: string;             // ISO datetime
}
```

**UI pattern:** Render as a vertical timeline (newest at top). Each node shows `authority` badge + `issuedAt` date + `changeSummary`. Expand to `changeNarrative`. Show `historicalHitRate` as a confidence meter at the top.

---

## 3. Admin-only endpoints

These do not need FE UI surfaces for regular users. Expose them only on an admin panel behind a role check.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/policy-intelligence/sync` | Trigger full crawler sync (RBI/SEBI/PIB) |
| `POST` | `/api/policy-intelligence/sync/{sourceKey}` | Sync one source |
| `POST` | `/api/policy-intelligence/embeddings/backfill?limit=500` | Backfill pgvector embeddings |
| `POST` | `/api/policy-intelligence/falsification/check` | Run prediction accuracy check |
| `POST` | `/api/policy-intelligence/documents/ingest/text` | Manually ingest a document |

---

## 4. Suggested page/component tree

```
/company/:symbol
  ├── CompanyHeader         (symbol, sector, knownSymbol guard)
  ├── BeginnerCallout       (beginnerNarrations — conditional)
  ├── QuoteCard             (Card 1 — price, 52w range, RS table)
  ├── CorporateActionsCard  (Card 2 — past/upcoming tabs, drift chip)
  ├── OwnershipCard         (Card 3 — stacked bar, deal table)
  ├── FundamentalsCard      (Card 4 — metric grid, peer verdicts)
  ├── RiskFitCard           (Card 5 — verdict chip, drawdown bar)
  └── NewsPolicyCard        (Card 6 — news feed + PolicyEventList)

/policy
  ├── PolicySearch          (GET /search)
  ├── PolicyDocumentList    (GET /documents + authority/status filters)
  ├── PolicyTimeline        (GET /timeline → subject picker → /timeline/:key)
  └── PolicyAdvisorContext  (GET /context — shown in CFO chat sidebar)

PolicyEventCard component    (shared — used in Card 6 and /policy pages)
  ├── direction badge (POSITIVE=green / NEGATIVE=red / NEUTRAL=grey / MIXED=amber)
  ├── horizon pill
  ├── marketMovingPower ring (0–100 scale)
  ├── impactSummary text
  └── citation link → sourceUrl
```

---

## 5. Key field-level notes

### `marketMovingPower` (0–100)
- ≥90: RBI rate/liquidity decision — render as "High Impact" in red
- 70–89: SEBI market structure / tax changes
- 45–69: Press releases, announcements
- Use as sort key when displaying a list of policy cards.

### `direction` colour convention
`POSITIVE` → green, `NEGATIVE` → red, `MIXED` → amber, `NEUTRAL` → grey

### `legalForceRank` (0–100)
Higher = more binding. Use to decide label:
- ≥90 → "Law/Rule"
- 75–89 → "Binding"
- 55–74 → "Authoritative Guidance"
- <55 → "Advisory"

### `pricePercentileInRange` (0..1)
Position in 52-week range. Render as a range slider thumb:
- <0.2 → near 52w low (possibly oversold)
- >0.8 → near 52w high (possibly overbought)

### `volumeZScore`
Standard deviations from 20-day mean volume:
- >2 → "High volume" badge
- <−1 → "Low volume" badge

### `ownershipMomentum` (-1 / 0 / 1)
Derived from sign(ΔFII + ΔDII). Use `ownershipMomentumLabel` for display text.

### `postResultsDrift` (Card 2)
`null` when the stock has fewer than 3 past results events with adequate price history. Render the pre-built `summary` string directly — it is display-safe ("TCS drifts +1.8% in 3 trading days post-results").

---

## 6. Error handling

| HTTP | Meaning | FE action |
|------|---------|-----------|
| 200 with `knownSymbol: false` | Ticker not in the system | Show "Symbol not found" |
| 200 with `dataGaps` populated | Partial data | Render what's available; grey out missing cards |
| 401 | Expired/missing JWT | Redirect to login |
| 500 | Backend error | Generic error banner; do not retry automatically |

---

## 7. Auth note

The company profile endpoint reads `userId` from the JWT principal to populate `riskFit.portfolioFit` and Card 6 policy exposure. If the user is not authenticated, pass the request without a token — the endpoint returns everything except portfolio-specific fields (which will be `null`).

`GET /api/policy-intelligence/context` **requires** auth — it personalises results against the user's holdings.

All other policy endpoints are unauthenticated.
