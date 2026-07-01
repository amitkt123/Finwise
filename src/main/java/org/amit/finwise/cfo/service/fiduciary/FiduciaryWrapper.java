package org.amit.finwise.cfo.service.fiduciary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FiduciaryWrapper {

    private final ConflictDisclosureConfig config;

    public <T> FiduciaryEnvelope<T> wrap(
        T data,
        List<String> dataSources,
        String dataQualityNote,
        String confidenceSummary
    ) {
        return new FiduciaryEnvelope<>(
            data,
            config.getConflictStatement(),
            dataSources,
            dataQualityNote,
            confidenceSummary,
            Instant.now(),
            config.getEngineVersion()
        );
    }
}
