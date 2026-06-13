package org.amit.finwise.marketdata.model;

/**
 * Event-shaped classification for md_corporate_event. Deliberately broad so
 * DF-6's market_event can union over this table without remodeling: forward
 * NSE board-meeting purposes and historical earnings-calendar event types both
 * map here.
 */
public enum CorporateEventType {
    RESULTS,
    DIVIDEND,
    BONUS,
    SPLIT,
    BOARD_MEETING,
    FUND_RAISING,
    OTHER
}
