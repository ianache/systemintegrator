package com.cl2.integration.integration.transformation.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LookupRule(
    String catalogCode,
    String defaultValue,
    String externalSource
) {
    public LookupRule(String catalogCode, String defaultValue) {
        this(catalogCode, defaultValue, null);
    }
}
