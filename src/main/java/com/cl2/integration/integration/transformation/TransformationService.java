package com.cl2.integration.integration.transformation;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class TransformationService {
    private static final Logger log = LoggerFactory.getLogger(TransformationService.class);

    private final Map<TransformationEngineType, PayloadTransformer> transformers = new EnumMap<>(TransformationEngineType.class);
    private final ObjectMapper objectMapper;

    public TransformationService(List<PayloadTransformer> transformerList, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (PayloadTransformer transformer : transformerList) {
            this.transformers.put(transformer.getEngineType(), transformer);
        }
    }

    public String transform(String sourcePayload, IntegrationProfile profile) {
        if (profile == null || profile.configuration() == null) {
            return sourcePayload;
        }

        String configJson = profile.configuration().transformation();
        if (configJson == null || configJson.isBlank()) {
            configJson = profile.configuration().mapping();
        }

        if (configJson == null || configJson.isBlank()) {
            return sourcePayload;
        }

        TransformationEngineType engineType = detectEngineType(configJson);
        PayloadTransformer transformer = transformers.getOrDefault(engineType, transformers.get(TransformationEngineType.PASSTHROUGH));

        log.debug("Executing transformation with engine={} for profileId={}", engineType, profile.id());
        return transformer.transform(sourcePayload, configJson);
    }

    public void validate(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        TransformationEngineType engineType = detectEngineType(configJson);
        PayloadTransformer transformer = transformers.getOrDefault(engineType, transformers.get(TransformationEngineType.PASSTHROUGH));
        transformer.validateConfiguration(configJson);
    }

    private TransformationEngineType detectEngineType(String configJson) {
        try {
            JsonNode node = objectMapper.readTree(configJson);
            if (node.has("engine")) {
                return TransformationEngineType.fromString(node.get("engine").asText());
            }
            if (node.has("fields")) {
                return TransformationEngineType.FIELD_MAPPING;
            }
            if (node.has("script")) {
                return TransformationEngineType.JSLT;
            }
        } catch (Exception ignored) {}
        return TransformationEngineType.PASSTHROUGH;
    }
}
