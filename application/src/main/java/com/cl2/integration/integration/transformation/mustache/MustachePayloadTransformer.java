package com.cl2.integration.integration.transformation.mustache;

import com.cl2.integration.integration.transformation.PayloadTransformer;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MustachePayloadTransformer implements PayloadTransformer {

    private final ObjectMapper objectMapper;
    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory();
    private final Map<String, Mustache> compiledTemplates = new ConcurrentHashMap<>();

    public MustachePayloadTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TransformationEngineType getEngineType() {
        return TransformationEngineType.MUSTACHE;
    }

    @Override
    public void validateConfiguration(String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) {
            return;
        }
        try {
            compile(extractScript(configurationJson));
        } catch (Exception ex) {
            throw new TransformationException("Invalid Mustache template: " + ex.getMessage(), ex);
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
            Mustache mustache = compile(template);
            JsonNode inputNode = objectMapper.readTree(sourcePayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> context = objectMapper.convertValue(inputNode, Map.class);
            StringWriter writer = new StringWriter();
            mustache.execute(writer, context).flush();
            return writer.toString();
        } catch (TransformationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TransformationException("Mustache transformation failed: " + ex.getMessage(), ex);
        }
    }

    private Mustache compile(String template) {
        return compiledTemplates.computeIfAbsent(template,
                t -> mustacheFactory.compile(new StringReader(t), "inline-template"));
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
