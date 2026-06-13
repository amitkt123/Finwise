package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Master record for a listed security, keyed on ISIN (stable across symbol
 * changes, present in every bhavcopy row). The symbol/name reflect the most
 * recently seen bhavcopy values.
 */
@Entity
@Table(name = "md_instrument",
        indexes = {@Index(name = "idx_md_instrument_symbol", columnList = "symbol")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_instrument_isin", columnNames = {"isin"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String isin;

    @Column(nullable = false, length = 30)
    private String symbol;

    /** NSE series of the latest sighting: EQ, BE, ... */
    @Column(length = 4)
    private String series;

    /** Company name — only present in UDiFF-era files; nullable. */
    @Column(length = 120)
    private String name;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
