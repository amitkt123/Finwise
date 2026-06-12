package org.amit.finwise.cfo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_stock_scorecard_snapshot", indexes = {
        @Index(name = "idx_scorecard_snapshot_symbol_date", columnList = "symbol, scorecard_date DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockScorecardSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false)
    private LocalDate scorecardDate;

    @Column(nullable = false)
    private double totalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockScorecard.Recommendation recommendation;

    @Column(nullable = false)
    private int confidence;

    @Column(length = 30)
    private String expectedReturnBand;

    @Lob
    @Column(nullable = false)
    private String scorecardJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
