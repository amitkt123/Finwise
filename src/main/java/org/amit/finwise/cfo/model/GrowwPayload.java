package org.amit.finwise.cfo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GrowwPayload(
        @JsonProperty("holdings") List<GrowwHolding> holdings
) {}
