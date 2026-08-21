package com.cl2.integration.integration.transformation.field;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.lookup.application.ValueLookupService;
import com.cl2.integration.integration.transformation.MissingRequiredFieldException;
import com.cl2.integration.integration.transformation.PayloadTransformer;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FieldMappingPayloadTransformer implements PayloadTransformer {
    private final ObjectMapper objectMapper;
    private final ValueLookupService valueLookupService;
    private final ExpressionParser spelParser;
    private final Configuration jsonPathConfig;
    private final Map<String, Expression> spelCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public FieldMappingPayloadTransformer(ObjectMapper objectMapper, ValueLookupService valueLookupService) {
        this.objectMapper = objectMapper;
        this.valueLookupService = valueLookupService;
        this.spelParser = new SpelExpressionParser();
        this.jsonPathConfig = Configuration.defaultConfiguration()
                .addOptions(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL);
    }

    public FieldMappingPayloadTransformer(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    @Override
    public TransformationEngineType getEngineType() {
        return TransformationEngineType.FIELD_MAPPING;
    }

    @Override
    public void validateConfiguration(String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) {
            return;
        }
        try {
            FieldMappingConfiguration config = objectMapper.readValue(configurationJson, FieldMappingConfiguration.class);
            for (FieldMappingRule rule : config.fields()) {
                if (rule.sourcePath() != null && !rule.sourcePath().isBlank()) {
                    JsonPath.compile(rule.sourcePath());
                }
                if (rule.transform() != null && !rule.transform().isBlank()) {
                    spelParser.parseExpression(rule.transform());
                }
            }
        } catch (Exception ex) {
            throw new TransformationException("Invalid FIELD_MAPPING configuration: " + ex.getMessage(), ex);
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
            FieldMappingConfiguration config = objectMapper.readValue(configurationJson, FieldMappingConfiguration.class);
            Object document = jsonPathConfig.jsonProvider().parse(sourcePayload);
            ObjectNode outputNode = objectMapper.createObjectNode();

            UUID tenantId = null;
            try {
                tenantId = TenantContext.requireTenantId();
            } catch (Exception ignored) {
                // tenantId may not be available in non-tenant contexts
            }

            for (FieldMappingRule rule : config.fields()) {
                Object rawValue = extractRawValue(document, rule.sourcePath());

                if (rawValue == null && rule.defaultValue() != null) {
                    rawValue = rule.defaultValue();
                }

                Object transformedValue = rawValue;
                if (rule.transform() != null && !rule.transform().isBlank()) {
                    transformedValue = evaluateSpel(rule.transform(), rawValue);
                }

                if (rule.lookup() != null && valueLookupService != null) {
                    String externalSource = rule.lookup().externalSource() != null
                            ? rule.lookup().externalSource()
                            : config.externalSource();
                    if (transformedValue != null) {
                        String sourceVal = transformedValue.toString();
                        String lookupDefault = rule.lookup().defaultValue();
                        String resolved = valueLookupService.lookup(
                                tenantId,
                                externalSource,
                                rule.lookup().catalogCode(),
                                sourceVal,
                                lookupDefault
                        );
                        transformedValue = resolved != null ? resolved : (lookupDefault != null ? lookupDefault : transformedValue);
                    } else if (rule.lookup().defaultValue() != null) {
                        transformedValue = rule.lookup().defaultValue();
                    }
                }

                if (transformedValue == null && rule.required()) {
                    throw new MissingRequiredFieldException(rule.target(), rule.sourcePath());
                }

                putConvertedValue(outputNode, rule.target(), transformedValue, rule.type());
            }

            return objectMapper.writeValueAsString(outputNode);
        } catch (MissingRequiredFieldException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TransformationException("Field mapping transformation failed: " + ex.getMessage(), ex);
        }
    }

    private Object extractRawValue(Object document, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return JsonPath.using(jsonPathConfig).parse(document).read(path);
        } catch (PathNotFoundException ex) {
            return null;
        }
    }

    private Object evaluateSpel(String expressionStr, Object val) {
        Expression expr = spelCache.computeIfAbsent(expressionStr, spelParser::parseExpression);
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
        context.setVariable("val", val);
        return expr.getValue(context);
    }

    private void putConvertedValue(ObjectNode node, String targetField, Object value, String type) {
        if (value == null) {
            node.putNull(targetField);
            return;
        }

        String declaredType = type != null ? type.toUpperCase() : "STRING";
        switch (declaredType) {
            case "INTEGER", "INT" -> {
                if (value instanceof Number num) node.put(targetField, num.intValue());
                else node.put(targetField, Integer.parseInt(value.toString().trim()));
            }
            case "LONG" -> {
                if (value instanceof Number num) node.put(targetField, num.longValue());
                else node.put(targetField, Long.parseLong(value.toString().trim()));
            }
            case "DOUBLE", "FLOAT" -> {
                if (value instanceof Number num) node.put(targetField, num.doubleValue());
                else node.put(targetField, Double.parseDouble(value.toString().trim()));
            }
            case "BOOLEAN", "BOOL" -> {
                if (value instanceof Boolean bool) node.put(targetField, bool);
                else node.put(targetField, Boolean.parseBoolean(value.toString().trim()));
            }
            case "JSON_OBJECT", "OBJECT" -> {
                try {
                    JsonNode child = objectMapper.readTree(value.toString());
                    node.set(targetField, child);
                } catch (Exception ex) {
                    node.put(targetField, value.toString());
                }
            }
            default -> node.put(targetField, value.toString());
        }
    }
}
