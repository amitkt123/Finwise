package org.amit.finwise.cfo.service.fiduciary;

import java.time.Instant;
import java.util.List;

public record FiduciaryEnvelope<T>(
    T data,
    String conflictStatement,
    List<String> dataSources,
    String dataQualityNote,
    String confidenceSummary,
    Instant generatedAt,
    String engineVersion
) {}
