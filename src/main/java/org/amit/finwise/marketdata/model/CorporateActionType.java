package org.amit.finwise.marketdata.model;

/**
 * Classification of an NSE corporate-action subject line. Only SPLIT, BONUS
 * and DIVIDEND drive price back-adjustment; RIGHTS is recorded but not
 * auto-adjusted (premium-dependent), and OTHER covers buy-backs, AGMs, etc.
 */
public enum CorporateActionType {
    SPLIT,
    BONUS,
    RIGHTS,
    DIVIDEND,
    OTHER
}
