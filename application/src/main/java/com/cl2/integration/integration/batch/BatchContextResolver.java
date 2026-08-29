package com.cl2.integration.integration.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class BatchContextResolver {

    private static final String BATCH_EVENT_SUFFIX = ".batch.upserted";

    private final ObjectMapper objectMapper;

    public BatchContextResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BatchContext recoverFromEvent(String eventType, String payload) {
        if (!isBatchEventType(eventType)) {
            return BatchContext.unitary();
        }
        return BatchContext.batch(requireNonEmptyArray(payload).size());
    }

    public boolean shouldBypassTransformation(
            String eventType,
            String payload,
            BatchContext batchContext) {
        if (!isBatchEventType(eventType) || batchContext == null || !batchContext.batchMode()) {
            return false;
        }
        if (batchContext.batchSize() <= 0) {
            throw new IllegalArgumentException("Batch context size must be positive");
        }

        int actualSize = requireNonEmptyArray(payload).size();
        if (actualSize != batchContext.batchSize()) {
            throw new IllegalArgumentException(
                    "Batch context expected " + batchContext.batchSize()
                            + " elements but found " + actualSize);
        }
        return true;
    }

    private boolean isBatchEventType(String eventType) {
        return eventType != null
                && eventType.toLowerCase(Locale.ROOT).endsWith(BATCH_EVENT_SUFFIX);
    }

    private JsonNode requireNonEmptyArray(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isArray() || node.isEmpty()) {
                throw new IllegalArgumentException("Batch event payload must be a non-empty JSON array");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Batch event payload must be a non-empty JSON array", exception);
        }
    }
}
