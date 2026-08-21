package com.cl2.integration.integration.lookup.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record CreateValueLookupRequest(
        UUID id,
        @NotBlank(message = "externalSource is required")
        String externalSource,
        @NotBlank(message = "catalogCode is required")
        String catalogCode,
        @NotBlank(message = "sourceValue is required")
        String sourceValue,
        @NotBlank(message = "targetValue is required")
        String targetValue,
        String description,
        Boolean active
) {
}
