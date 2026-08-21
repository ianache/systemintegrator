package com.cl2.integration.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SensitiveDataRedactor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> SENSITIVE_KEY_PATTERNS = Set.of(
            "password", "secret", "token", "apikey", "api_key", "authorization",
            "clientsecret", "client_secret", "access_token", "refreshtoken",
            "privatekey", "private_key", "credential"
    );

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "[REDACTED]";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 3) {
            return trimmed + "[REDACTED]";
        }
        return trimmed.substring(0, 3) + "[REDACTED]";
    }

    public static Map<String, String> redactHeaders(Map<String, String> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String lowerKey = key.toLowerCase();
            if (isSensitiveKey(lowerKey)) {
                if (value != null && (lowerKey.equals("authorization") || lowerKey.contains("auth"))) {
                    String valTrim = value.trim();
                    if (valTrim.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        result.put(key, "Bearer " + redact(valTrim.substring(7)));
                    } else if (valTrim.regionMatches(true, 0, "Basic ", 0, 6)) {
                        result.put(key, "Basic " + redact(valTrim.substring(6)));
                    } else {
                        result.put(key, redact(value));
                    }
                } else {
                    result.put(key, redact(value));
                }
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    public static String redactJsonPayload(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            redactNode(root);
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ex) {
            return json;
        }
    }

    private static void redactNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode child = entry.getValue();

                if (isSensitiveKey(fieldName.toLowerCase())) {
                    if (child.isTextual()) {
                        objectNode.put(fieldName, redact(child.asText()));
                    } else if (child.isNumber() || child.isBoolean()) {
                        objectNode.put(fieldName, "[REDACTED]");
                    }
                } else {
                    redactNode(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode item : arrayNode) {
                redactNode(item);
            }
        }
    }

    private static boolean isSensitiveKey(String keyName) {
        String normalized = keyName.toLowerCase().replace("-", "").replace("_", "");
        for (String pattern : SENSITIVE_KEY_PATTERNS) {
            String normPattern = pattern.replace("-", "").replace("_", "");
            if (normalized.contains(normPattern)) {
                return true;
            }
        }
        return false;
    }
}
