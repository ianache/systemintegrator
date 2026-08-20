package com.cl2.integration.integration.transformation.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMappingRule(
    String target,
    String sourcePath,
    String transform,
    String defaultValue,
    String type,
    boolean required
) {
    public FieldMappingRule {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Field mapping rule must specify a 'target' field name");
        }
    }
}
