package com.cl2.integration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class TransformationService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> transform(Map<String, Object> sourceData, String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return sourceData;
        }
        try {
            Map<String, String> mappingRules = objectMapper.readValue(mappingJson, new TypeReference<>() {});
            Map<String, Object> canonicalResult = new HashMap<>();

            for (Map.Entry<String, String> entry : mappingRules.entrySet()) {
                String canonicalKey = entry.getKey();
                String sourceKey = entry.getValue();
                if (sourceData.containsKey(sourceKey)) {
                    canonicalResult.put(canonicalKey, sourceData.get(sourceKey));
                }
            }
            return canonicalResult;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid mapping configuration: " + e.getMessage(), e);
        }
    }
}
