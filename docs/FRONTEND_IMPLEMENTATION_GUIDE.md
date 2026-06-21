# Finwise Frontend Implementation Guide

> This document is the single source of truth for building the Finwise frontend. It is derived from the Phase 4 productionisation plan and a full audit of every backend controller, model, and DTO as they exist today.

---

## 1. Design Philosophy

Finwise's analytical moat is institutional-grade: GARCH volatility forecasts, Ledoit-Wolf covariance shrinkage, Brinson-Fachler attribution, VaR backtests, calibrated insight cards with confidence scores. Most competitors show P&L tables. The frontend must **make the analytics visible and understandable** to a retail investor — not hide them.

**Three UI principles:**

1. **Numbers come from Java, not prose.** Every `InsightCard` carries `Computation[]` — label, value, method, inputs, window. Always render these alongside the narrative. Never paraphrase a number.
2. **Confidence is a first-class value.** Every insight card has `effectiveConfidence` (0–1). Show it as a progress bar or badge — "73% confident" — not hidden metadata.
3. **Severity drives layout priority.** Cards are `ALERT > ACTION > WATCH > INFO`. Render the highest-severity items first, above the fold.

---

## 2. Recommended Tech Stack

| Concern | Choice | Reason |
|---|---|---|
| Framework | **Next.js 15 (App Router)** | SSR for dashboard SEO, API routes for BFF if needed, file-based routing matches screen structure |
| Language | **TypeScript** | Type the full API surface from the backend shapes |
| UI Library | **shadcn/ui + Tailwind CSS** | Accessible headless components; composable |
| Charts | **Recharts** | React-native, good for allocation pies + time-series; Tremor wraps it nicely |
| State | **Zustand** (global) + **TanStack Query v5** (server state) | Query handles caching/stale/refetch; Zustand holds UI + auth state |
| Forms | **React Hook Form + Zod** | Match bean-validation constraints from backend DTOs |
| Auth | **Custom JWT** (no NextAuth needed — the backend issues tokens) | |
| API Client | **OpenAPI-generated from `/swagger-ui`** or hand-typed `fetch` wrappers | See §4 |
| Testing | **Vitest + React Testing Library + Playwright** | |

### Directory Layout

```
finwise-web/
├── app/
│   ├── (auth)/
│   │   ├── login/page.tsx
│   │   └── register/page.tsx
│   ├── (app)/
│   │   ├── layout.tsx            # app shell + nav
│   │   ├── dashboard/page.tsx
│   │   ├── portfolio/
│   │   │   ├── page.tsx          # holdings table
│   │   │   ├── risk/page.tsx     # VaR, factor model, drawdown
│   │   │   ├── attribution/page.tsx
│   │   │   └── look-through/page.tsx
│   │   ├── insights/
│   │   │   ├── page.tsx          # insight card feed
│   │   │   └── [type]/page.tsx   # single insight by type
│   │   ├── goals/
│   │   │   ├── page.tsx
│   │   │   └── [id]/page.tsx
│   │   ├── expenses/page.tsx
│   │   ├── budgets/page.tsx
│   │   ├── chat/page.tsx
│   │   ├── news/page.tsx
│   │   └── settings/page.tsx
│   └── api/                      # optional Next.js route handlers for BFF
├── lib/
│   ├── api/                      # typed fetch wrappers per module
│   ├── auth/                     # token storage, axios/fetch interceptor
│   ├── hooks/                    # useAuth, useDashboard, useInsights, …
│   └── types/                    # TypeScript types (see §5)
├── components/
│   ├── ui/                       # shadcn primitives
│   ├── charts/                   # AllocationPie, TimeSeriesLine, VaRBar, …
│   ├── insight-card/             # InsightCard, ComputationRow, ConfidenceBar
│   └── layout/                   # Sidebar, Header, PageShell
```

---

## 3. Authentication Flow

The backend issues HS256 JWTs with configurable TTL. There is no refresh token endpoint yet (Phase 4 calls it optional).

### Register
```
POST /api/auth/register
Body: { username, email, password, name? }
Response: { token: string, message: string }
```

### Login
```
POST /api/auth/login
Body: { username, password }
Response: { token: string, message: string }
```

### Current User
```
GET /api/auth/me   (Bearer required)
Response: { username, email, roles: string[] }
```

### Token Storage and Injection

Store the JWT in `localStorage` (or `sessionStorage` for stricter security). Inject via a custom `fetch` wrapper:

```typescript
// lib/auth/client.ts
export function authedFetch(input: RequestInfo, init?: RequestInit) {
  const token = localStorage.getItem('finwise_token');
  return fetch(input, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
}
```

Use TanStack Query with this client so all server-state hooks automatically include the token and share the 401 → redirect-to-login behaviour:

```typescript
// lib/api/queryClient.ts
queryClient.setDefaultOptions({
  queries: {
    retry: (failureCount, error) => {
      if ((error as ApiError).status === 401) return false;
      return failureCount < 2;
    },
  },
});
```

### Protected Route Guard

In `app/(app)/layout.tsx`, read the token from store; if absent, `redirect('/login')`.

---

## 4. API Reference

All endpoints require `Authorization: Bearer <token>` unless marked **public**.

The base URL in dev is `http://localhost:8080`. Configure via `NEXT_PUBLIC_API_BASE`.

### 4.1 Auth (`/api/auth`)

| Method | Path | Body / Params | Response |
|---|---|---|---|
| POST | `/api/auth/register` | `RegisterRequest` | `AuthResponse` |
| POST | `/api/auth/login` | `LoginRequest` | `AuthResponse` |
| GET | `/api/auth/me` | — | `MeResponse` |

### 4.2 Dashboard (`/api/finance`)

| Method | Path | Params | Response |
|---|---|---|---|
| GET | `/api/finance/dashboard` | — | `FinancialDashboard` |

`FinancialDashboard` fields:
```
portfolioValue, portfolioGains, portfolioGainPercent,
monthlyBudget, monthlySpent, monthlyPercentSpent,
activeGoals, atRiskGoals, budgetAlerts, goalAlerts,
annualizedTwrrPercent, xirrPercent, absoluteReturnPercent,
maxDrawdownPercent, calmarRatio
```
All monetary values are `string` (JSON serialization of `BigDecimal`). Treat as number via `parseFloat`.

### 4.3 Portfolio & CFO (`/api/cfo`)

| Method | Path | Params | Response |
|---|---|---|---|
| GET | `/api/cfo/portfolio` | — | `PortfolioSnapshot` |
| POST | `/api/cfo/sync/groww` | — | `PortfolioSnapshot` |
| GET | `/api/cfo/holdings` | — | `HoldingSummary[]` |
| GET | `/api/cfo/look-through` | — | `LookThroughResult` |
| GET | `/api/cfo/attribution` | — | `AttributionReport` |
| GET | `/api/cfo/insight-cards` | — | `InsightCard[]` |
| GET | `/api/cfo/insight-cards/marginal-add` | `?symbol=HDFCBANK` | `InsightCard` |
| GET | `/api/cfo/brief` | — | `InsightResponse` |
| GET | `/api/cfo/insights` | `?page=0&size=20` | `Page<InsightResponse>` |
| GET | `/api/cfo/insights/{type}` | — | `InsightResponse` |
| GET | `/api/cfo/news` | `?limit=20&daysBack=1` | `NewsArticle[]` |
| GET | `/api/cfo/news/personalized` | `?limit=20&daysBack=1` | `PersonalizedNewsResponse` |
| GET | `/api/cfo/transactions` | `?page=0&size=50` | `Page<TransactionResponse>` |
| GET | `/api/cfo/transactions/recent` | `?days=30` | `TransactionResponse[]` |
| GET | `/api/cfo/goals/advice` | — | `AiInsight` |
| POST | `/api/cfo/chat` | `{ message: string }` | `{ response: string }` |
| GET | `/api/cfo/investor/behavior` | — | `InvestorBehaviorProfile` |
| POST | `/api/cfo/investor/behavior/refresh` | — | `InvestorBehaviorProfile` |
| GET | `/api/cfo/investor/questionnaire` | — | `RiskQuestionnaire` |
| PUT | `/api/cfo/investor/questionnaire` | `RiskQuestionnaire` | `RiskQuestionnaire` |
| GET | `/api/cfo/profile` | — | `UserProfile` |
| PUT | `/api/cfo/profile` | `UserProfileRequest` | `UserProfile` |
| PUT | `/api/cfo/auth/groww/token` | `{ token: string }` | `{ status }` |
| POST | `/api/cfo/prices/sync` | — | `{ status }` |

**InsightType enum values** (used in `/api/cfo/insights/{type}`):
`DAILY_BRIEF`, `AFTER_HOURS_BRIEF`, `PORTFOLIO_ALERT`, `GOAL_ADVICE`, `NEWS_SUMMARY`,
`REBALANCE_ALERT`, `MARKET_INSIGHT`, `EXPENSE_ALERT`, `CHAT_RESPONSE`

### 4.4 Investments (`/api/finance`)

| Method | Path | Params | Response |
|---|---|---|---|
| POST | `/api/finance/investment` | `AddInvestmentRequest` (body) | `InvestmentResponse` |
| PUT | `/api/finance/investment/{id}/price` | `?currentPrice=` | `InvestmentResponse` |
| GET | `/api/finance/investment/active` | — | `InvestmentResponse[]` |
| GET | `/api/finance/portfolio/analysis` | — | `PortfolioAnalysis` |
| GET | `/api/finance/portfolio/rebalance` | — | `RebalancingRecommendation` |

`AddInvestmentRequest` fields: `type` (InvestmentType enum), `symbol`, `name`, `quantity`, `costPerUnit`, `platform?`

**InvestmentType enum**: `STOCK`, `MUTUAL_FUND`, `ETF`, `BOND`, `CRYPTOCURRENCY`, `REAL_ESTATE`, `GOLD`, `COMMODITY`, `FIXED_DEPOSIT`, `POST_OFFICE_SCHEME`, `PPF`, `INSURANCE_POLICY`, `OTHER`

### 4.5 Expenses (`/api/finance`)

| Method | Path | Params | Response |
|---|---|---|---|
| POST | `/api/finance/expense` | `RecordExpenseRequest` (body) | `ExpenseResponse` |
| POST | `/api/finance/expense/sms` | raw string body | `ExpenseResponse` |
| GET | `/api/finance/expenses` | `?page=0&size=20` | `Page<ExpenseResponse>` |
| GET | `/api/finance/expense/range` | `?startDate&endDate&page&size` | `Page<ExpenseResponse>` |
| GET | `/api/finance/expense/summary` | `?year&month` | `MonthlySpendingSummary` |
| GET | `/api/finance/expense/trend` | `?months=6` | `Map<String, BigDecimal>` |
| GET | `/api/finance/expenses/source/{source}` | `?page&size` | `Page<ExpenseResponse>` |

`RecordExpenseRequest`: `amount` (positive number), `category` (ExpenseCategory enum), `description`

**ExpenseCategory enum**: `FOOD_DINING`, `GROCERIES`, `TRANSPORTATION`, `UTILITIES`, `ENTERTAINMENT`, `SHOPPING`, `HEALTHCARE`, `INSURANCE`, `SUBSCRIPTION`, `INVESTMENT`, `SAVINGS`, `EDUCATION`, `PERSONAL_CARE`, `HOME_MAINTENANCE`, `TRAVEL`, `BUSINESS`, `CHARITY`, `DEBT_PAYMENT`, `TAXES`, `OTHER`

### 4.6 Budgets (`/api/finance`)

| Method | Path | Params | Response |
|---|---|---|---|
| POST | `/api/finance/budget` | `?month=2026-06&category=&limit=` | `Budget` |
| GET | `/api/finance/budget/current` | — | `BudgetStatus` |
| GET | `/api/finance/budget/alerts` | `?month=2026-06` | `BudgetAlert[]` |
| GET | `/api/finance/budget/suggest` | `?months=3` | `Map<category, amount>` |
| GET | `/api/finance/insights` | `?months=6` | `SpendingInsights` |

### 4.7 Goals (`/api/finance`)

| Method | Path | Params | Response |
|---|---|---|---|
| POST | `/api/finance/goal` | `?name&description&type&targetAmount&targetDate&priority` | `FinancialGoal` |
| PUT | `/api/finance/goal/{id}/progress` | `?amountAdded=` | `FinancialGoal` |
| GET | `/api/finance/goal/{id}/analysis` | — | `GoalAnalysis` |
| GET | `/api/finance/goal/{id}/simulate` | `?monthlySip=` | `GoalSimulationResult` |
| GET | `/api/finance/goal/active` | — | `FinancialGoal[]` |
| GET | `/api/finance/goal/alerts` | — | `GoalAlert[]` |

**GoalType enum**: `SAVINGS`, `INVESTMENT`, `DEBT_PAYOFF`, `PURCHASE`, `EMERGENCY_FUND`, `EDUCATION`, `RETIREMENT`, `WEALTH_BUILDING`, `PROPERTY`, `VACATION`, `OTHER`

**GoalStatus enum**: `ON_TRACK`, `AT_RISK`, `OFF_TRACK`, `ACHIEVED`, `ABANDONED`, `PAUSED`

**GoalPriority enum**: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`

### 4.8 Policy Intelligence (`/api/policy-intelligence`)

| Method | Path | Params | Response |
|---|---|---|---|
| GET | `/api/policy-intelligence/documents` | `?authority&status&limit=20` | `PolicyDocumentSummary[]` |
| GET | `/api/policy-intelligence/search` | `?query&limit=10` | `PolicySearchResult` |
| GET | `/api/policy-intelligence/context` | `?message&limit=6` | `AdvisorPolicyContext` |

### 4.9 RAG Evidence (`/api/cfo/rag`)

| Method | Path | Params | Response |
|---|---|---|---|
| GET | `/api/cfo/rag/evidence` | `?q=query&limit=5` | `EvidencePack[]` |

---

## 5. TypeScript Type Definitions

Place these in `lib/types/`. These are derived 1:1 from the backend models.

```typescript
// lib/types/auth.ts
export interface AuthResponse { token: string; message: string; }
export interface MeResponse { username: string; email: string; roles: string[]; }

// lib/types/dashboard.ts
export interface FinancialDashboard {
  portfolioValue: string | null;
  portfolioGains: string | null;
  portfolioGainPercent: string | null;
  monthlyBudget: string | null;
  monthlySpent: string | null;
  monthlyPercentSpent: string | null;
  activeGoals: number;
  atRiskGoals: number;
  budgetAlerts: number;
  goalAlerts: number;
  annualizedTwrrPercent: string | null;
  xirrPercent: string | null;
  absoluteReturnPercent: string | null;
  maxDrawdownPercent: string | null;
  calmarRatio: string | null;
}

// lib/types/portfolio.ts
export interface HoldingSummary {
  symbol: string;
  name: string;
  sector: string | null;
  type: InvestmentType | null;
  quantity: string;
  avgPrice: string;
  totalCost: string;
  currentPrice: string | null;
  currentValue: string | null;
  unrealizedPnl: string | null;
  pnlPercent: string | null;
  exposurePercent: number;
  platform: string | null;
}

export interface PortfolioSnapshot {
  id: number;
  userId: string;
  snapshotTime: string;
  source: string;
  totalInvested: string | null;
  currentValue: string | null;
  unrealizedPnl: string | null;
  dayPnl: string | null;
  dayPnlPercent: string | null;
  overallPnlPercent: string | null;
  holdingsCount: number | null;
}

export interface PortfolioAnalysis {
  totalCost: string;
  currentValue: string;
  unrealizedGains: string;
  gainLossPercentage: string;
  totalHoldings: number;
  assetAllocation: Record<InvestmentType, string>;
  sectorAllocation: Record<string, string>;
  diversificationScore: number;
  topPerformers: InvestmentPerformance[];
  underperformers: InvestmentPerformance[];
  estimatedTaxIfSoldToday: string;
  netGainsAfterTax: string;
  taxBreakdown: TaxEstimate;
}

export interface InvestmentPerformance {
  symbol: string;
  name: string;
  gainLossPercentage: string;
  gainLossAmount: string;
}

export interface RebalancingRecommendation {
  currentAllocation: Record<string, string>;
  targetAllocation: Record<string, string>;
  rebalancingNeeded: boolean;
  actions: RebalancingAction[];
  lastRebalanceDate: string | null;
  nextRecommendedDate: string | null;
}

export interface RebalancingAction {
  assetClass: string;
  currentPercent: string;
  targetPercent: string;
  driftPercent: string;
  action: 'BUY' | 'SELL';
  amount: string;
  costNote: string | null;
}

export type InvestmentType =
  | 'STOCK' | 'MUTUAL_FUND' | 'ETF' | 'BOND' | 'CRYPTOCURRENCY'
  | 'REAL_ESTATE' | 'GOLD' | 'COMMODITY' | 'FIXED_DEPOSIT'
  | 'POST_OFFICE_SCHEME' | 'PPF' | 'INSURANCE_POLICY' | 'OTHER';

// lib/types/insight.ts
export type InsightType =
  | 'DAILY_BRIEF' | 'AFTER_HOURS_BRIEF' | 'PORTFOLIO_ALERT' | 'GOAL_ADVICE'
  | 'NEWS_SUMMARY' | 'REBALANCE_ALERT' | 'MARKET_INSIGHT' | 'EXPENSE_ALERT'
  | 'CHAT_RESPONSE';

export interface InsightResponse {
  id: number;
  insightDate: string;
  insightType: InsightType;
  title: string;
  content: string; // Markdown — render with react-markdown
  modelUsed: string | null;
  read: boolean;
  createdAt: string;
}

export type InsightCardCategory =
  | 'RISK_BUDGET' | 'CONCENTRATION' | 'VOL_REGIME' | 'FACTOR_TILT'
  | 'SKILL' | 'ATTRIBUTION' | 'TAX' | 'GOAL' | 'LOOKTHROUGH'
  | 'MARGINAL_ADD' | 'VAR_BACKTEST' | 'STRESS';

export type InsightCardSeverity = 'INFO' | 'WATCH' | 'ACTION' | 'ALERT';

export interface Computation {
  label: string;
  value: string;     // Java-formatted string — render verbatim
  method: string | null;
  inputs: string | null;
  window: string | null;
}

export interface InsightCard {
  id: string;
  category: InsightCardCategory;
  severity: InsightCardSeverity;
  title: string;
  actionVerb: string | null;
  symbol: string | null;
  computations: Computation[];
  caveats: string[];
  rawConfidence: number;          // 0–1
  calibratedConfidence: number | null;
  trackRecord: string | null;
  narrative: string | null;       // Markdown, may be null
  effectiveConfidence: number;    // = calibratedConfidence ?? rawConfidence
}

// lib/types/risk.ts
export interface RiskDecomposition {
  includedSymbols: string[];
  excludedSymbols: string[];
  excludedWeightPct: number;
  dataQualityNotes: string[];
  observationCount: number;
  seriesFrom: string;
  seriesTo: string;
  isLowConfidence: boolean;
  annualizedVolatility: number;
  dailyVolatility: number;
  shrinkageIntensity: number | null;
  portfolioBeta: number;
  perHoldingBeta: Record<string, number>;
  var95Parametric: number;
  var99Parametric: number;
  var95CornishFisher: number;
  var99CornishFisher: number;
  var95Historical: number;
  cvar95: number;
  returnSkewness: number;
  returnExcessKurtosis: number;
  riskContributors: RiskContributor[];
  diversificationRatio: number;
  effectiveNumberOfBets: number;
  nameHHI: number;
  sectorHHI: number;
  sharpeRatio: number;
  sortinoRatio: number;
  trackingErrorVsNifty: number;
  maxDrawdown: number;    // ≤ 0; multiply by 100 to get %
  calmarRatio: number;
  headline: string;
}

export interface RiskContributor {
  symbol: string;
  weight: number;
  beta: number;
  marginalContributionToRisk: number;
  componentContributionToRisk: number;
  percentContributionToRisk: number;  // 0–1
}

export interface VarBacktestReport {
  method: string;
  window: number;
  confidenceLevel: number;
  observations: number;
  breaches: number;
  expectedBreachRate: number;
  actualBreachRate: number;
  kupiecLR: number;
  kupiecPValue: number;
  kupiecReject: boolean;
  christoffersenLR: number;
  christoffersenPValue: number;
  clusteringDetected: boolean;
  conditionalCoverageLR: number;
  verdict: string;
}

// lib/types/attribution.ts
export interface SectorAttribution {
  sector: string;
  allocation: number;
  selection: number;
  interaction: number;
  total: number;
}

export interface AttributionReport {
  benchmarkAsOf: string;
  windowStart: string;
  windowEnd: string;
  buckets: number;
  portfolioReturn: number;
  benchmarkReturn: number;
  excessReturn: number;
  allocationEffect: number;
  selectionEffect: number;
  interactionEffect: number;
  residual: number;
  sectors: SectorAttribution[];
  lowConfidence: boolean;
  dataQualityNotes: string[];
  headline: string;
}

// lib/types/expense.ts
export type ExpenseCategory =
  | 'FOOD_DINING' | 'GROCERIES' | 'TRANSPORTATION' | 'UTILITIES'
  | 'ENTERTAINMENT' | 'SHOPPING' | 'HEALTHCARE' | 'INSURANCE'
  | 'SUBSCRIPTION' | 'INVESTMENT' | 'SAVINGS' | 'EDUCATION'
  | 'PERSONAL_CARE' | 'HOME_MAINTENANCE' | 'TRAVEL' | 'BUSINESS'
  | 'CHARITY' | 'DEBT_PAYMENT' | 'TAXES' | 'OTHER';

export interface ExpenseResponse {
  id: number;
  amount: string;
  category: ExpenseCategory;
  description: string;
  source: string;
  expenseDate: string;
  merchant: string | null;
  paymentMethod: string | null;
  isRecurring: boolean;
  createdAt: string;
}

// lib/types/goal.ts
export type GoalType =
  | 'SAVINGS' | 'INVESTMENT' | 'DEBT_PAYOFF' | 'PURCHASE'
  | 'EMERGENCY_FUND' | 'EDUCATION' | 'RETIREMENT' | 'WEALTH_BUILDING'
  | 'PROPERTY' | 'VACATION' | 'OTHER';

export type GoalStatus = 'ON_TRACK' | 'AT_RISK' | 'OFF_TRACK' | 'ACHIEVED' | 'ABANDONED' | 'PAUSED';
export type GoalPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface FinancialGoal {
  id: number;
  name: string;
  description: string | null;
  type: GoalType;
  targetAmount: string;
  currentAmount: string;
  targetDate: string;
  progressPercentage: string;
  requiredMonthlyAmount: string | null;
  status: GoalStatus;
  priority: GoalPriority;
  expectedAnnualReturn: string | null;
  inflationRate: string;
  startDate: string;
  offTrackReason: string | null;
}

export interface GoalSimulationResult {
  // Monte Carlo results — fields depend on MonteCarloGoalService
  goalId: number;
  successProbability: number;
  medianFinalValue: number;
  p10FinalValue: number;
  p90FinalValue: number;
  recommendedMonthlySip: number | null;
  simulationPaths?: number[][];  // subset for chart rendering
}

// lib/types/budget.ts
export interface Budget {
  id: number;
  budgetMonth: string;        // "2026-06"
  category: ExpenseCategory;
  budgetLimit: string;
  spent: string;
  remaining: string | null;
  percentSpent: string | null;
  isOverBudget: boolean;
  status: 'ON_TRACK' | 'WARNING' | 'OVER_BUDGET';
}

export interface BudgetStatus {
  totalBudget: string;
  totalSpent: string;
  totalPercentSpent: string;
  budgets: Budget[];
}

// lib/types/user.ts
export type RiskAppetite = 'CONSERVATIVE' | 'MODERATE' | 'AGGRESSIVE';

export interface UserProfile {
  id: number;
  userId: string;
  name: string | null;
  email: string | null;
  monthlyIncome: string | null;
  monthlyFixedExpenses: string | null;
  riskAppetite: RiskAppetite;
  investmentHorizonYears: number;
  targetMonthlySavings: string | null;
  primaryGoalDescription: string | null;
  additionalContext: string | null;
}
```

---

## 6. Screen Inventory & Routing

| Route | Screen | Primary Data |
|---|---|---|
| `/login` | Login | — |
| `/register` | Register | — |
| `/dashboard` | Home Dashboard | `GET /api/finance/dashboard` |
| `/portfolio` | Holdings Table | `GET /api/cfo/holdings` |
| `/portfolio/risk` | Risk & VaR | `GET /api/cfo/insight-cards` (risk cards) |
| `/portfolio/attribution` | Brinson-Fachler | `GET /api/cfo/attribution` |
| `/portfolio/look-through` | MF Look-Through | `GET /api/cfo/look-through` |
| `/insights` | Insight Card Feed | `GET /api/cfo/insight-cards` |
| `/insights/brief` | Daily Brief | `GET /api/cfo/brief` |
| `/insights/[type]` | Insight by Type | `GET /api/cfo/insights/{type}` |
| `/expenses` | Expense List + Entry | `GET /api/finance/expenses`, `POST /api/finance/expense` |
| `/budgets` | Budget Monitor | `GET /api/finance/budget/current` |
| `/goals` | Goal List | `GET /api/finance/goal/active` |
| `/goals/[id]` | Goal Detail + Monte Carlo | `GET /api/finance/goal/{id}/simulate` |
| `/news` | Personalized News | `GET /api/cfo/news/personalized` |
| `/chat` | CFO Chat | `POST /api/cfo/chat` |
| `/settings` | Profile + Groww Token | `GET /api/cfo/profile`, `PUT /api/cfo/profile` |

---

## 7. Screen Implementation Guide

### 7.1 Dashboard (`/dashboard`)

**Purpose:** One-glance health check. The investor should see their net position, monthly burn, and any active alerts in under 5 seconds.

**Data source:** `GET /api/finance/dashboard` → `FinancialDashboard`

**Layout (3-column grid on desktop, stacked on mobile):**

```
┌─────────────────────────────────────────────────────────────┐
│ Portfolio Value   ₹12,45,832       ▲ ₹1,23,456  (+11.0%)  │
│ XIRR: 14.2%  TWRR: 13.8%  MaxDD: −8.4%  Calmar: 1.64     │
├────────────────┬────────────────┬────────────────────────────┤
│ Monthly Budget  │   Goals        │  Alerts                    │
│ ₹45,000 limit   │ 4 active       │ ⚠ 2 budget alerts          │
│ ₹31,200 spent   │ 1 at-risk      │ ⚠ 1 goal at risk           │
│ 69% used       │               │                            │
└────────────────┴────────────────┴────────────────────────────┘
```

**Component checklist:**
- `<MetricCard>` — label, value, sub-value with colour (red/green)
- `<AlertBanner>` — for `budgetAlerts + goalAlerts > 0`; links to detail pages
- `<ReturnMetrics>` — XIRR, TWRR, maxDrawdown, Calmar in a horizontal stat row
- Numbers that are `null` render as `—` (data not available yet, not an error)
- Polling: re-fetch every 5 minutes (`staleTime: 5 * 60 * 1000` in TanStack Query)

### 7.2 Holdings Table (`/portfolio`)

**Data sources:**
- `GET /api/cfo/holdings` → `HoldingSummary[]`
- `GET /api/finance/portfolio/analysis` → `PortfolioAnalysis`

**Layout:**

Top row: `PortfolioAnalysis` summary cards (total cost, current value, gains, tax-adjusted gains).

Allocation pies: two `<AllocationPie>` charts — asset allocation and sector allocation from `assetAllocation` / `sectorAllocation` maps.

Holdings table columns: Symbol, Name, Type, Qty, Avg Price, Current Price, Current Value, P&L (₹), P&L (%), Exposure (%), Platform.

- Colour code P&L: green positive, red negative.
- Exposure % bar: `<ProgressBar value={exposurePercent} max={100} />` — highlights concentration above 20%.
- `topPerformers` / `underperformers` from `PortfolioAnalysis` as a side panel.

Add investment: `<AddInvestmentDialog>` — `POST /api/finance/investment` with `AddInvestmentRequest`.

Sync Groww button: `POST /api/cfo/sync/groww` → refreshes data.

### 7.3 Risk & VaR (`/portfolio/risk`)

**The analytical differentiator. Give this screen real space.**

**Data source:** `GET /api/cfo/insight-cards` filtered to categories `RISK_BUDGET`, `CONCENTRATION`, `VOL_REGIME`, `VAR_BACKTEST`, `STRESS`, `FACTOR_TILT`.

For each `InsightCard`:
- **Header strip:** severity badge (colour-coded: ALERT=red, ACTION=orange, WATCH=yellow, INFO=blue), category, title.
- **Confidence bar:** `<ConfidenceBar value={card.effectiveConfidence} trackRecord={card.trackRecord} />`
- **Computation table:** For each `Computation` in `card.computations`:
  ```
  │ 1-Day VaR 95% (Cornish-Fisher)  │  ₹48,230  │ z·σ·V  │ 740d │
  ```
  Always render the `value` field verbatim — it is Java-formatted with INR symbols and rounding.
- **Narrative block:** `<ReactMarkdown>{card.narrative}</ReactMarkdown>` — only shown when not null.
- **Caveats:** Collapsible list of `card.caveats` strings.

**VaR summary panel** (from RISK_BUDGET card computations):
- Show parametric, Cornish-Fisher, Historical VaR at 95% and 99%
- CVaR 95%
- Use a horizontal bar chart where all VaR methods are compared side-by-side

**Risk contributors table** (from CONCENTRATION card):
- Symbol, weight, beta, % contribution to risk
- Sort by `percentContributionToRisk` descending
- Top contributor highlighted

**VaR Backtest card** (`VAR_BACKTEST` category):
- Show `kupiecReject` and `clusteringDetected` as pass/fail indicators
- Verdict string prominently
- p-values in a details row

### 7.4 Attribution (`/portfolio/attribution`)

**Data source:** `GET /api/cfo/attribution` → `AttributionReport`

**Key visual: the waterfall / bar chart decomposition**

```
Excess Return:  +3.2%
└─ Allocation:  +1.8%
└─ Selection:   +1.1%
└─ Interaction: +0.4%
└─ Residual:    −0.1%
```

Use a horizontal stacked bar or waterfall chart (Recharts `ComposedChart`).

**Sector breakdown table:**
| Sector | Allocation | Selection | Interaction | Total |
|---|---|---|---|---|
| Financials | +0.4% | +0.8% | +0.1% | +1.3% |
| IT | −0.2% | +0.3% | −0.1% | 0.0% |

**Data quality:** If `lowConfidence === true`, show a `<WarningBanner>` quoting `dataQualityNotes`.

**Window / benchmark info:** Display `windowStart` → `windowEnd`, benchmark as of `benchmarkAsOf`.

### 7.5 MF Look-Through (`/portfolio/look-through`)

**Data source:** `GET /api/cfo/look-through` → `LookThroughResult`

Show:
- MF coverage gauge: `mfCoverage * 100`% with a threshold indicator at 70% (`feedsModel` boundary)
- `unmappedResidue` as a warning if > 10%
- Before / After HHI comparison: `directNameHHI` vs `effectiveNameHHI`, `directSectorHHI` vs `effectiveSectorHHI`
- Effective exposure table: merged view of `effectiveWeights` sorted by weight descending
- Sector effective allocation pie: `sectorEffective`
- Data quality notes from `dataQualityNotes`

### 7.6 Insight Cards (`/insights`)

**The honest-insights layer. This is the user's daily signal.**

**Data source:** `GET /api/cfo/insight-cards` → `InsightCard[]`

**Feed layout:** Cards sorted by severity (`ALERT` first), then by `effectiveConfidence` descending within the same severity.

**`<InsightCard>` component anatomy:**
```
┌──────────────────────────────────────────────────────┐
│ [ALERT] RISK_BUDGET                    73% confident  │
│ Trim HDFCBANK by ₹48,000                            │
├──────────────────────────────────────────────────────┤
│ 1-Day VaR 95%  ₹48,230   Cornish-Fisher   740d     │
│ Risk budget    ₹40,000   5% of portfolio  —         │
│ Excess         ₹8,230    over budget      —         │
├──────────────────────────────────────────────────────┤
│ [narrative markdown, collapsible if long]            │
├──────────────────────────────────────────────────────┤
│ ⚠ Caveats (2)  ▾                                    │
└──────────────────────────────────────────────────────┘
```

**Severity colour scheme:**
- `ALERT` → red (`bg-red-50 border-red-400`)
- `ACTION` → orange (`bg-orange-50 border-orange-400`)
- `WATCH` → yellow (`bg-yellow-50 border-yellow-400`)
- `INFO` → blue (`bg-blue-50 border-blue-400`)

**`effectiveConfidence` bar:** 0–100% linear. Threshold markers at 50% (unreliable) and 80% (high confidence). If `trackRecord` is populated, show it as a tooltip on the bar.

### 7.7 Daily Brief (`/insights/brief`)

**Data source:** `GET /api/cfo/brief` → `InsightResponse`

- `content` is Markdown — render with `react-markdown` + `remark-gfm`
- Show date badge (`insightDate`), model name (`modelUsed`) in the header
- "Mark as read" button — no API for this yet; optimistic local state
- "Refresh Brief" button → re-calls `GET /api/cfo/brief` (triggers server-side LLM generation)

### 7.8 Expenses (`/expenses`)

**Layout:** Split pane — list on left, quick-add form on right (or modal on mobile).

**Expense list:**
- Paginated (`GET /api/finance/expenses?page=0&size=20`)
- Filter by date range (`GET /api/finance/expense/range`)
- Filter by source (`SMS`, `MANUAL`, `BANK_PDF`, etc.)
- Category badge with icon, amount, date, description

**Quick-add form (`POST /api/finance/expense`):**
```
Amount:      [₹ ___________]
Category:    [Dropdown — ExpenseCategory enum]
Description: [____________]
[Add Expense]
```

**Spending trend chart:**
- `GET /api/finance/expense/trend?months=6` → `Record<string, string>` (month → total)
- Render as a `LineChart` (x = month label, y = ₹ amount)

**Monthly summary:**
- `GET /api/finance/expense/summary?year=2026&month=6` → category breakdown pie chart

**SMS parse:** A text area input + `POST /api/finance/expense/sms` with raw string body (Content-Type: text/plain).

### 7.9 Budgets (`/budgets`)

**Data source:** `GET /api/finance/budget/current` → `BudgetStatus`

**Layout:** Category budget cards in a grid.

Each `Budget` card:
```
┌──────────────────────────────────────┐
│ 🍔 Food & Dining                     │
│ ₹8,200 / ₹10,000                    │
│ ████████████░░░░  82%  ⚠ WARNING    │
└──────────────────────────────────────┘
```
- Status colour: `ON_TRACK` → green, `WARNING` → yellow, `OVER_BUDGET` → red
- Budget suggestion: `GET /api/finance/budget/suggest?months=3` → show AI-suggested limits vs current

**Add budget form:**
- `POST /api/finance/budget?month=2026-06&category=FOOD_DINING&limit=10000`

**Alerts panel:**
- `GET /api/finance/budget/alerts?month=2026-06` — show active alerts in a dismissible list

### 7.10 Goals (`/goals`)

**Goal list:**
- `GET /api/finance/goal/active` → goal cards
- Status badge: `ON_TRACK` green, `AT_RISK` yellow, `OFF_TRACK` red, `ACHIEVED` blue
- Progress bar: `progressPercentage`
- Required monthly: `requiredMonthlyAmount`
- Alert if `GET /api/finance/goal/alerts` returns entries

**Create goal form:**
```
Name, Description, Type (GoalType dropdown), Target Amount (₹),
Target Date (date picker), Priority (GoalPriority radio)
→ POST /api/finance/goal (query params)
```

Note: The backend takes these as `@RequestParam`, not a JSON body. Send as URL query string.

**Goal detail (`/goals/[id]`):**

**Monte Carlo simulation panel:**
- `GET /api/finance/goal/{id}/simulate?monthlySip=5000` → `GoalSimulationResult`
- Success probability gauge
- Percentile fan chart: p10 / median / p90 paths over time
- SIP slider (if `monthlySip` param changes, re-fetch — debounce 500ms)

**Goal analysis:**
- `GET /api/finance/goal/{id}/analysis` → `GoalAnalysis`
- Show months remaining, current trajectory, gap to target

### 7.11 CFO Chat (`/chat`)

**Data source:** `POST /api/cfo/chat` → `{ response: string }`

**Layout:** Chat bubble UI — messages array in local state (not persisted to DB).

```
[User]   What's my biggest risk right now?
[CFO]    Your HDFCBANK position contributes 34% of portfolio
         VaR... [Markdown response rendered inline]
```

- `response` is Markdown — render with `react-markdown`
- Show a spinner between send and response
- Clear conversation button (local state only)
- Pre-seeded prompt chips: "Summarise my portfolio", "What should I rebalance?", "Explain my risk"

### 7.12 Personalized News (`/news`)

**Data source:** `GET /api/cfo/news/personalized?limit=20&daysBack=1`

Each `PersonalizedNewsItem`:
- Headline, source, published time
- Relevance score badge (if exposed in `PersonalizedNewsResponse`)
- Symbol tags (stocks mentioned) — link to that stock's holding if owned

### 7.13 Settings (`/settings`)

**Profile form (`GET` + `PUT /api/cfo/profile`):**
- Name, email, monthly income, fixed expenses, risk appetite (radio: CONSERVATIVE / MODERATE / AGGRESSIVE), investment horizon (years slider), target monthly savings, primary goal description, additional context

**Groww token form:**
- `PUT /api/cfo/auth/groww/token` with `{ token: string }`
- Show last sync time from `PortfolioSnapshot.snapshotTime`
- Manual sync button → `POST /api/cfo/sync/groww`

**Price sync button:** `POST /api/cfo/prices/sync`

---

## 8. State Management

### Global Auth Store (Zustand)

```typescript
interface AuthStore {
  token: string | null;
  user: MeResponse | null;
  setToken: (token: string) => void;
  logout: () => void;
}
```

Persist `token` to `localStorage` via `persist` middleware.

### Server State (TanStack Query)

One query key convention per resource:
```typescript
['dashboard']
['holdings']
['portfolio-analysis']
['insight-cards']
['insights', 'brief']
['insights', type]
['goals', 'active']
['goals', id, 'simulate', monthlySip]
['expenses', page, size]
['budget', 'current']
['attribution']
['look-through']
```

Use `useQuery` for GET endpoints, `useMutation` for POST/PUT.

Stale times:
- Dashboard, holdings: 5 minutes
- Insight cards, brief: 15 minutes (LLM generation is expensive)
- Goals, expenses, budgets: 2 minutes (user-modified)
- Attribution, risk: 15 minutes (computed from price history)

### Optimistic Updates

For expense and budget mutations: update the local cache immediately, roll back on error.

---

## 9. Pagination Handling

Spring Page responses have this shape:
```typescript
interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;    // current page (0-indexed)
  size: number;
  last: boolean;
  first: boolean;
}
```

Use a shared `<Paginator>` component that reads `totalPages` / `number` from the response.

For infinite scroll (expense list, insight history): use TanStack Query `useInfiniteQuery` with `getNextPageParam: (last) => last.last ? undefined : last.number + 1`.

---

## 10. Number Formatting

All monetary values come as `string` (Java `BigDecimal` serializes as string). Parse and format in one place:

```typescript
// lib/utils/format.ts
export function inr(value: string | null | undefined): string {
  if (!value) return '—';
  const num = parseFloat(value);
  if (isNaN(num)) return '—';
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(num);
}

export function pct(value: string | number | null | undefined, decimals = 2): string {
  if (value === null || value === undefined) return '—';
  const num = typeof value === 'string' ? parseFloat(value) : value;
  if (isNaN(num)) return '—';
  return `${num >= 0 ? '+' : ''}${num.toFixed(decimals)}%`;
}

export function pnlColour(value: string | number | null): string {
  if (!value) return 'text-muted-foreground';
  const num = typeof value === 'string' ? parseFloat(value) : value;
  return num >= 0 ? 'text-green-600' : 'text-red-600';
}
```

**Special rule for `InsightCard.computations`:** Never reformat the `value` field — it is already formatted by Java (e.g. `"₹48,230"`, `"−8.4%"`). Render it verbatim.

---

## 11. Error Handling

The backend (Phase 2) returns RFC-7807 `ProblemDetail` for errors:
```json
{ "title": "Validation Error", "status": 400, "detail": "amount must be positive" }
```

Global API error handler:
```typescript
async function handleResponse<T>(res: Response): Promise<T> {
  if (res.ok) return res.json();
  const body = await res.json().catch(() => ({}));
  if (res.status === 401) { authStore.logout(); redirect('/login'); }
  throw new ApiError(res.status, body.detail ?? body.message ?? 'Unknown error');
}
```

Show errors via a `<Toaster>` (shadcn/ui) — success toasts for mutations, error toasts for failures.

---

## 12. CORS

The backend `SecurityConfig` must allow `http://localhost:3000` (or your prod domain). Confirm with the backend team before deploying. A typical Next.js dev proxy in `next.config.ts` can also avoid CORS in development:

```typescript
// next.config.ts
rewrites: async () => [
  { source: '/api/:path*', destination: 'http://localhost:8080/api/:path*' }
]
```

---

## 13. Priority Build Order

Build in this sequence — each step is demo-able:

1. **Auth screens** (login, register, token storage, route guard) — gating everything else
2. **Dashboard** — first impression; verifies auth + data pipeline
3. **Holdings table + portfolio analysis** — the portfolio core
4. **Insight cards feed** — the analytical differentiator; high visual impact
5. **Daily brief** — Markdown render + LLM integration proof
6. **Risk & VaR screen** — VaR table + backtest pass/fail
7. **Attribution screen** — Brinson-Fachler waterfall
8. **Goals CRUD + Monte Carlo** — planning layer
9. **Expenses CRUD** — operational layer
10. **Budgets** — monitoring layer
11. **Chat** — conversational layer
12. **News feed, settings, look-through** — polish

---

## 14. OpenAPI Code Generation (Optional)

Once the backend has `springdoc-openapi-starter-webmvc-ui` (Phase 2), the spec is at:
```
GET http://localhost:8080/v3/api-docs
```

Generate a typed TS client:
```bash
npx @openapitools/openapi-generator-cli generate \
  -i http://localhost:8080/v3/api-docs \
  -g typescript-fetch \
  -o src/lib/api/generated
```

Until Phase 2 is complete, use the hand-typed wrappers in `lib/api/`.

---

## 15. Key Implementation Warnings

1. **`/api/finance/goal` uses `@RequestParam`, not `@RequestBody`** — send as URL query parameters, not JSON body.
2. **`/api/finance/expense/sms` takes a raw string body** — set `Content-Type: text/plain`.
3. **`/api/cfo/mf-portfolio/import` is `multipart/form-data`** — use `FormData`, not JSON.
4. **`InsightCard.computations[].value` must not be reformatted** — Java already formatted it.
5. **`RiskDecomposition.maxDrawdown` is ≤ 0** — multiply by 100 and show as positive for display (e.g. `−0.084` → "8.4% max drawdown").
6. **`FinancialDashboard` fields can be `null`** when there are no investments/budgets yet — render `—` not `0` or `NaN`.
7. **All `BigDecimal` fields serialize as strings in JSON** — always `parseFloat()` before arithmetic.
8. **Insight card `narrative` may be `null`** — do not assume it is always populated.
9. **`Page<T>` page number is 0-indexed** from the backend — convert to 1-indexed only in display.