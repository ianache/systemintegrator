package com.cl2.integration.integration.transformation;

public enum TransformationEngineType {
    FIELD_MAPPING,
    JSLT,
    PASSTHROUGH;

    public static TransformationEngineType fromString(String value) {
        if (value == null || value.isBlank()) {
            return PASSTHROUGH;
        }
        try {
            return TransformationEngineType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PASSTHROUGH;
        }
    }
}
