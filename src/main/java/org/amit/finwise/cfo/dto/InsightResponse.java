package org.amit.finwise.cfo.dto;

import org.amit.finwise.cfo.model.AiInsight;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record InsightResponse(
        Long id,
        LocalDate insightDate,
        AiInsight.InsightType insightType,
        String title,
        String content,
        String modelUsed,
        Boolean read,
        LocalDateTime createdAt
) {
    public static InsightResponse from(AiInsight a) {
        return new InsightResponse(
                a.getId(),
                a.getInsightDate(),
                a.getInsightType(),
                a.getTitle(),
                a.getContent(),
                a.getModelUsed(),
                a.getRead(),
                a.getCreatedAt()
        );
    }
}
