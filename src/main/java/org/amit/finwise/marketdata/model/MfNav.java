package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day's NAV for one AMFI scheme (DF-4). Keyed on (amfi_code, nav_date) so
 * the daily {@code NAVAll.txt} pull and the NAV-history seed are both idempotent
 * upserts. MF holdings mark-to-market and TWRR read from here; the look-through
 * layer uses the NAV drift between disclosure dates.
 */
@Entity
@Table(name = "md_mf_nav",
        indexes = {@Index(name = "idx_md_mf_nav_code_date", columnList = "amfi_code, nav_date DESC")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_mf_nav_code_date",
                columnNames = {"amfi_code", "nav_date"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfNav {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "amfi_code", nullable = false, length = 12)
    private String amfiCode;

    @Column(name = "nav_date", nullable = false)
    private LocalDate navDate;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal nav;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
