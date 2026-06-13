package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The realized market reaction to an event (DF-6). For every cluster older than
 * 1/5/20 trading days, the nightly enrichment job joins the cluster's mentioned
 * instruments to their actual back-adjusted returns from {@code md_eod_price}
 * and the Nifty over the same window, and persists the excess return.
 *
 * Because DF-1 prices are market-wide, this works for ANY mentioned stock, not
 * just holdings — the corpus learns from the whole market. This is what turns
 * the system prompt's "last time crude spiked…" promise into a literal lookup.
 */
@Entity
@Table(name = "cfo_event_outcome",
        indexes = {
                @Index(name = "idx_event_outcome_cluster", columnList = "cluster_id"),
                @Index(name = "idx_event_outcome_instrument", columnList = "instrument_id")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_event_outcome",
                columnNames = {"cluster_id", "instrument_id", "horizon"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventOutcome {

    /** Trading-day horizons over which the reaction is measured. */
    public enum Horizon {
        H1D(1), H5D(5), H20D(20);

        public final int tradingDays;
        Horizon(int tradingDays) { this.tradingDays = tradingDays; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    /** Symbol as of the event — denormalised for cheap evidence-pack rendering. */
    @Column(length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Horizon horizon;

    /** The cluster's first-seen date — the baseline trading day for the return. */
    @Column(name = "event_date")
    private LocalDate eventDate;

    /** Back-adjusted price return of the instrument over the horizon. */
    @Column(name = "raw_return")
    private Double rawReturn;

    /** Return minus the Nifty return over the same window — the signal. */
    @Column(name = "excess_return_vs_nifty")
    private Double excessReturnVsNifty;

    @Column(name = "computed_at")
    private LocalDateTime computedAt;
}
