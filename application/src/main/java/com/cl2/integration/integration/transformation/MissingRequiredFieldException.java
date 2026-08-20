package com.cl2.integration.integration.transformation;

public class MissingRequiredFieldException extends TransformationException {
    private final String targetField;
    private final String sourcePath;

    public MissingRequiredFieldException(String targetField, String sourcePath) {
        super("Required field '" + targetField + "' missing from source path: " + sourcePath);
        this.targetField = targetField;
        this.sourcePath = sourcePath;
    }

    public String getTargetField() { return targetField; }
    public String getSourcePath() { return sourcePath; }
}
