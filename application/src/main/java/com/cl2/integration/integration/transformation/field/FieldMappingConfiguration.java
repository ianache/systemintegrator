package com.cl2.integration.integration.transformation.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMappingConfiguration(
    String engine,
    String externalSource,
    List<FieldMappingRule> fields
) {
    public FieldMappingConfiguration(String engine, List<FieldMappingRule> fields) {
        this(engine, null, fields);
    }

    public FieldMappingConfiguration {
        if (fields == null) {
            fields = List.of();
        }
    }
}
