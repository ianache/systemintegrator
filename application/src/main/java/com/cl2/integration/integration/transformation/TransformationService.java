package com.cl2.integration.integration.transformation;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class TransformationService {
    private static final Logger log = LoggerFactory.getLogger(TransformationService.class);

    private final Map<TransformationEngineType, PayloadTransformer> transformers = new EnumMap<>(TransformationEngineType.class);
    private final ObjectMapper objectMapper;
    private final IntegrationMetrics metrics;

    public TransformationService(List<PayloadTransformer> transformerList, ObjectMapper objectMapper) {
        this(transformerList, objectMapper, null);
    }

    @Autowired
    public TransformationService(List<PayloadTransformer> transformerList, ObjectMapper objectMapper, @Autowired(required = false) IntegrationMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
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
        long startNanos = System.nanoTime();
        String result = transformer.transform(sourcePayload, configJson);
        double durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        if (metrics != null) {
            String tenantId = profile.tenantId() != null ? profile.tenantId().toString() : "unknown";
            String domain = profile.businessDomain() != null ? profile.businessDomain() : "unknown";
            String engineName = engineType != null ? engineType.name() : "PASSTHROUGH";
            metrics.recordTransformation(tenantId, domain, engineName, durationSeconds);
        }

        return result;
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
