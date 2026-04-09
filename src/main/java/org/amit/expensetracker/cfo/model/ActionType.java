package org.amit.expensetracker.cfo.model;

/**
 * Categorizes what action an investor should consider based on a news article.
 * Assigned deterministically by PersonalizedRelevanceScorer based on article signals,
 * portfolio state, and market conditions.
 */
public enum ActionType {

    /** No immediate action needed — stay informed */
    WATCH,

    /** Portfolio concentration has shifted; consider trimming the overweight sector/stock */
    REBALANCE,

    /** Quality stock is down on temporary macro noise; potential entry point */
    BUY_DIP,

    /** LTCG/STCG tax-harvesting opportunity (most relevant in March, year-end) */
    TAX_ACTION,

    /** Dividend, bonus, rights, or split — record date approaching; action required */
    CORPORATE_ACTION_REQUIRED
}
