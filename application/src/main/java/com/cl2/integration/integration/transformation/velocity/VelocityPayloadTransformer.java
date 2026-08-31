package com.cl2.integration.integration.transformation.velocity;

import com.cl2.integration.integration.transformation.PayloadTransformer;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringWriter;
import java.util.Map;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.springframework.stereotype.Component;

@Component
public class VelocityPayloadTransformer implements PayloadTransformer {

    private final ObjectMapper objectMapper;
    private final VelocityEngine velocityEngine;

    public VelocityPayloadTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Velocity Engine 2.4+ logs through SLF4J by default (the old LogChute SPI
        // is gone) and this app already has an SLF4J backend wired via Spring Boot,
        // so no custom logging setup is needed — templates are always inline
        // strings here (never files on disk) either way.
        this.velocityEngine = new VelocityEngine();
        this.velocityEngine.init();
    }

    @Override
    public TransformationEngineType getEngineType() {
        return TransformationEngineType.VELOCITY;
    }

    @Override
    public void validateConfiguration(String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) {
            return;
        }
        try {
            String template = extractScript(configurationJson);
            velocityEngine.evaluate(new VelocityContext(), new StringWriter(), "validate", template);
        } catch (Exception ex) {
            throw new TransformationException("Invalid Velocity template: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String transform(String sourcePayload, String configurationJson) {
        if (sourcePayload == null || sourcePayload.isBlank()) {
            return "";
        }
        if (configurationJson == null || configurationJson.isBlank()) {
            return sourcePayload;
        }

        try {
            String template = extractScript(configurationJson);
            JsonNode inputNode = objectMapper.readTree(sourcePayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.convertValue(inputNode, Map.class);

            VelocityContext context = new VelocityContext();
            context.put("root", root);
            root.forEach(context::put);

            StringWriter writer = new StringWriter();
            velocityEngine.evaluate(context, writer, "transform", template);
            return writer.toString();
        } catch (Exception ex) {
            throw new TransformationException("Velocity transformation failed: " + ex.getMessage(), ex);
        }
    }

    private String extractScript(String configurationJson) {
        try {
            JsonNode node = objectMapper.readTree(configurationJson);
            if (node.has("script")) {
                return node.get("script").asText();
            }
            return configurationJson; // allow passing a raw template string as fallback
        } catch (Exception ex) {
            return configurationJson;
        }
    }
}
