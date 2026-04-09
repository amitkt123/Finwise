package org.amit.finwise.cfo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
/**
 * Author: Amit Tiwari
 * Date: 03/04/26
 * Time: 8:58 pm
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record GrowwHoldingsResponse(
        @JsonProperty("status") String status,
        @JsonProperty("payload") GrowwPayload payload
) {}

