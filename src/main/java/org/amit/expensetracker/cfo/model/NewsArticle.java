package org.amit.expensetracker.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_news_articles", indexes = {
    @Index(name = "idx_news_date",        columnList = "published_date DESC"),
    @Index(name = "idx_news_relevance",   columnList = "relevance_score DESC"),
    @Index(name = "idx_news_source",      columnList = "source"),
    @Index(name = "idx_news_actionable",  columnList = "actionability_score DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String url;

    private LocalDate publishedDate;
    private LocalDateTime publishedAt;

    // ── Relevance ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RelevanceLevel relevanceLevel = RelevanceLevel.LOW;

    @Builder.Default
    private Integer relevanceScore = 0;

    // ── Entity extraction (Tier 2 / gazetteer) ────────────────────────────────

    /** Comma-separated NSE symbols — e.g. "HDFCBANK,ICICIBANK" */
    @Column(length = 500)
    private String relatedSymbols;

    /** Comma-separated sector names — e.g. "Banking,IT" */
    @Column(name = "related_sectors")
    private String relatedSectors;

    // ── Market-impact classification ──────────────────────────────────────────

    /**
     * Overall market sentiment for an Indian retail equity investor.
     * This is IMPACT-based, not tone-based:
     *   "IMF warns of financial crisis" → NEGATIVE (not neutral/positive)
     *   "Crude oil surges" → NEGATIVE (India is a net importer)
     *   "RBI cuts repo rate" → POSITIVE (good for equities)
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Sentiment sentiment = Sentiment.NEUTRAL;

    /** Primary event category */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Category category = Category.MARKET;

    /**
     * Who/what is directly impacted — narrows the scope of the sentiment.
     *   MACRO  = entire market / economy
     *   SECTOR = one or more sectors
     *   STOCK  = specific company
     *   POLICY = regulatory / government action
     *   REGULATORY = SEBI, RBI circular with no clear market directional
     *   MIXED  = multiple conflicting impacts
     */
    @Enumerated(EnumType.STRING)
    private ImpactType impactType;

    /**
     * How quickly this event is expected to move markets.
     *   SHORT_TERM  = price move likely within 1-5 trading days
     *   MEDIUM_TERM = impact plays out over weeks to 1 quarter
     *   LONG_TERM   = structural / thematic, takes months/years
     */
    @Enumerated(EnumType.STRING)
    private ImpactHorizon impactHorizon;

    /**
     * Per-sector directional impact — comma-separated key:value pairs.
     * e.g. "Banking:NEGATIVE,Aviation:POSITIVE,FMCG:NEGATIVE"
     * Populated by Tier 1 for known patterns, refined by LLM.
     */
    @Column(columnDefinition = "TEXT")
    private String sectorImpact;

    // ── Signal strength ───────────────────────────────────────────────────────

    /**
     * How actionable this news is for portfolio decisions (0–100).
     *   >= 70 → review positions now
     *   40–69 → worth tracking
     *   < 40  → informational only
     * Computed from: relevanceScore + impactHorizon + symbolMatch + sentimentStrength.
     */
    @Builder.Default
    private Integer actionabilityScore = 0;

    // ── LLM enrichment (Tier 3) ───────────────────────────────────────────────

    /** 1-line portfolio impact note written by the LLM, used in the CFO brief */
    @Column(name = "llm_impact_note", length = 500)
    private String llmImpactNote;

    /**
     * Entity-level sentiment map (JSON text).
     * e.g. {"TATASTEEL":"POSITIVE","Pharma":"NEGATIVE","HDFCBANK":"POSITIVE"}
     * Bloomberg-grade: one article can be POSITIVE for Steel and NEGATIVE for Pharma.
     * The top-level `sentiment` field is the *market/price catalyst* view.
     */
    @Column(name = "entity_sentiments", columnDefinition = "TEXT")
    private String entitySentiments;

    /**
     * Fundamental sentiment: what does this mean for the underlying business reality?
     * Distinct from `sentiment` which is the market/price catalyst.
     *
     * Example — "Textile industry urges cotton duty waiver":
     *   sentiment (market)      = POSITIVE  (duty waiver is a price catalyst)
     *   fundamentalSentiment    = NEGATIVE  (underlying reason is surging cotton prices hurting margins)
     *
     * NULL means not yet differentiated (single-sentiment mode).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "fundamental_sentiment")
    private Sentiment fundamentalSentiment;

    @Column(name = "llm_reviewed")
    @Builder.Default
    private boolean llmReviewed = false;

    // ── Tier 1 signals ────────────────────────────────────────────────────────

    @Column(name = "tier1_confidence")
    private double tier1Confidence;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum RelevanceLevel { HIGH, MEDIUM, LOW }

    public enum Sentiment { POSITIVE, NEGATIVE, NEUTRAL }

    public enum Category {
        // Corporate events
        EARNINGS, IPO, CORPORATE_ACTION,
        // Policy & regulation
        POLICY, REGULATORY,
        // Market-level
        MARKET, SECTOR,
        // Macro / economy
        MACRO, MACRO_RISK,
        // External
        GEOPOLITICAL, GLOBAL,
        // Research
        ANALYST_RECOMMENDATION,
        // Catch-all
        ECONOMY, CORPORATE
    }

    public enum ImpactType {
        MACRO, SECTOR, STOCK, POLICY, REGULATORY, MIXED
    }

    public enum ImpactHorizon {
        SHORT_TERM, MEDIUM_TERM, LONG_TERM
    }
}
