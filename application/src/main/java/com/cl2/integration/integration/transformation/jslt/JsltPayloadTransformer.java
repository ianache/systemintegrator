package com.cl2.integration.integration.transformation.jslt;

import com.cl2.integration.integration.transformation.PayloadTransformer;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JsltPayloadTransformer implements PayloadTransformer {
    private final ObjectMapper objectMapper;
    private final Map<String, Expression> compiledScripts = new ConcurrentHashMap<>();

    public JsltPayloadTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TransformationEngineType getEngineType() {
        return TransformationEngineType.JSLT;
    }

    @Override
    public void validateConfiguration(String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) {
            return;
        }
        try {
            String script = extractScript(configurationJson);
            Parser.compileString(script);
        } catch (Exception ex) {
            throw new TransformationException("Invalid JSLT script: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String transform(String sourcePayload, String configurationJson) {
        if (sourcePayload == null || sourcePayload.isBlank()) {
            return "{}";
        }
        if (configurationJson == null || configurationJson.isBlank()) {
            return sourcePayload;
        }

        try {
            String script = extractScript(configurationJson);
            Expression compiled = compiledScripts.computeIfAbsent(script, Parser::compileString);
            JsonNode inputNode = objectMapper.readTree(sourcePayload);
            JsonNode outputNode = compiled.apply(inputNode);
            return objectMapper.writeValueAsString(outputNode);
        } catch (Exception ex) {
            throw new TransformationException("JSLT transformation failed: " + ex.getMessage(), ex);
        }
    }

    private String extractScript(String configurationJson) {
        try {
            JsonNode node = objectMapper.readTree(configurationJson);
            if (node.has("script")) {
                return node.get("script").asText();
            }
            return configurationJson; // allow passing raw script string as fallback
        } catch (Exception ex) {
            return configurationJson;
        }
    }
}
