package org.amit.finwise.cfo.dto;

import org.amit.finwise.cfo.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        LocalDate transactionDate,
        Transaction.TransactionType transactionType,
        Transaction.TransactionSource source,
        BigDecimal amount,
        String symbol,
        String name,
        BigDecimal quantity,
        BigDecimal pricePerUnit,
        String description,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getTransactionDate(),
                t.getTransactionType(),
                t.getSource(),
                t.getAmount(),
                t.getSymbol(),
                t.getName(),
                t.getQuantity(),
                t.getPricePerUnit(),
                t.getDescription(),
                t.getCreatedAt()
        );
    }
}
