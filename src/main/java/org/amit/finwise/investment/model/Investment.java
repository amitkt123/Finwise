package org.amit.finwise.investment.model;

import jakarta.persistence.*;
import lombok.*;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.enums.RiskProfile;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "investments", indexes = {
    @Index(name = "idx_investment_user", columnList = "user_id"),
    @Index(name = "idx_investment_type", columnList = "investment_type"),
    @Index(name = "idx_investment_symbol", columnList = "symbol"),
    @Index(name = "idx_investment_purchase_date", columnList = "purchase_date"),
    @Index(name = "idx_investment_active", columnList = "is_active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Investment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "investment_type", nullable = false)
    private InvestmentType type;

    @Column(name = "symbol", length = 50)
    private String symbol;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "platform", length = 100)
    private String platform;

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "cost_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal costPerUnit;

    @Column(name = "total_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCost;

    @Column(name = "current_price", precision = 19, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "current_value", precision = 19, scale = 4)
    private BigDecimal currentValue;

    @Column(name = "unrealized_gain_loss", precision = 19, scale = 4)
    private BigDecimal unrealizedGainLoss;

    @Column(name = "gain_loss_percentage", precision = 10, scale = 4)
    private BigDecimal gainLossPercentage;

    @Column(name = "realized_gain_loss", precision = 19, scale = 4)
    private BigDecimal realizedGainLoss;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "sale_date")
    private LocalDate saleDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_profile")
    private RiskProfile riskProfile;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
