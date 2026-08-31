package com.cl2.integration.integration.transformation;

public record TransformationPreviewResult(String output, String error) {

    public static TransformationPreviewResult success(String output) {
        return new TransformationPreviewResult(output, null);
    }

    public static TransformationPreviewResult failure(String error) {
        return new TransformationPreviewResult(null, error);
    }
}
