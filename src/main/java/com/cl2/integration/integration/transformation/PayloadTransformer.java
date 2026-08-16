package com.cl2.integration.integration.transformation;

public interface PayloadTransformer {
    TransformationEngineType getEngineType();
    String transform(String sourcePayload, String configurationJson);
    void validateConfiguration(String configurationJson);
}
