package org.amit.finwise.marketdata.provider;

import java.time.Instant;
import java.util.Optional;

public record DataEnvelope<T>(
    T value,
    String source,
    Instant fetchedAt,
    DataQuality quality,
    String fallbackNote
) {
    public static <T> DataEnvelope<T> of(T value, String source, DataQuality quality) {
        return new DataEnvelope<>(value, source, Instant.now(), quality, null);
    }

    public static <T> DataEnvelope<T> missing(String source, String reason) {
        return new DataEnvelope<>(null, source, Instant.now(), DataQuality.MISSING, reason);
    }

    public Optional<T> valueOpt() {
        return Optional.ofNullable(value);
    }

    public boolean isPresent() { return value != null; }
}
