package com.cl2.integration.integration.transformation.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMappingRule(
    String target,
    String sourcePath,
    String transform,
    String defaultValue,
    String type,
    boolean required,
    LookupRule lookup
) {
    public FieldMappingRule {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Field mapping rule must specify a 'target' field name");
        }
    }

    public FieldMappingRule(String target, String sourcePath, String transform, String defaultValue, String type, boolean required) {
        this(target, sourcePath, transform, defaultValue, type, required, null);
    }
}
