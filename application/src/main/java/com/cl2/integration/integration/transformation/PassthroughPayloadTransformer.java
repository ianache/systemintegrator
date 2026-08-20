package com.cl2.integration.integration.transformation;

import org.springframework.stereotype.Component;

@Component
public class PassthroughPayloadTransformer implements PayloadTransformer {
    @Override
    public TransformationEngineType getEngineType() {
        return TransformationEngineType.PASSTHROUGH;
    }

    @Override
    public String transform(String sourcePayload, String configurationJson) {
        return sourcePayload != null ? sourcePayload : "{}";
    }

    @Override
    public void validateConfiguration(String configurationJson) {
        // No validation needed for passthrough
    }
}
