package com.cl2.integration.integration.transformation;

public record MappingDryRunResult(String output, String error) {

    public static MappingDryRunResult success(String output) {
        return new MappingDryRunResult(output, null);
    }

    public static MappingDryRunResult failure(String error) {
        return new MappingDryRunResult(null, error);
    }
}
