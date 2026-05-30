package org.amit.finwise.document.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

@Value
@Builder
public class ParsedTransaction {
    LocalDate transactionDate;
    BigDecimal amount;
    TransactionDirection direction;
    String description;
    String merchant;
    String sourceHint;

    public enum TransactionDirection {
        DEBIT,
        CREDIT,
        PURCHASE,
        REDEMPTION,
        UNKNOWN
    }
}
