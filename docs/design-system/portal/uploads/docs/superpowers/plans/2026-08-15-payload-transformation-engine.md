# Dynamic Payload Transformation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a pluggable, high-performance, and secure Dynamic Payload Transformation Engine supporting Field Mapping (JSONPath + Sandboxed SpEL) and JSLT scripting to transform heterogeneous payloads to and from canonical domain models based on tenant `IntegrationProfile` configurations.

**Architecture:** Strategy pattern with `PayloadTransformer` implementations (`FieldMappingPayloadTransformer`, `JsltPayloadTransformer`, `PassthroughPayloadTransformer`) orchestrated by `TransformationService`. SpEL evaluation is sandboxed via `SimpleEvaluationContext` and JSLT expressions are compiled and cached for performance.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Jackson (`JsonNode`, `ObjectMapper`), Jayway JsonPath, Schibsted JSLT (`0.1.14`), Spring Expression Language (SpEL), JUnit 5, AssertJ, Mockito.

## Global Constraints

- Java 21 with records, sealed classes/pattern matching, and standard formatting.
- Security Sandboxing: SpEL evaluation must exclusively use `SimpleEvaluationContext.forReadOnlyDataBinding()` to prevent arbitrary JVM execution.
- Extensibility: Support both `FIELD_MAPPING` and `JSLT` engines with graceful fallback to `PASSTHROUGH`.
- Clean error reporting: Throw specific `MissingRequiredFieldException` or `TransformationException` with field and engine context.
- All tests must pass with `mvn test`.

---

### Task 1: Dependencies & Core Strategy Interfaces

**Files:**
- Modify: `pom.xml` (or `application/pom.xml`)
- Create: `src/main/java/com/cl2/integration/integration/transformation/TransformationEngineType.java`
- Create: `src/main/java/com/cl2/integration/integration/transformation/PayloadTransformer.java`
- Create: `src/main/java/com/cl2/integration/integration/transformation/TransformationException.java`
- Create: `src/main/java/com/cl2/integration/integration/transformation/MissingRequiredFieldException.java`
- Create: `src/main/java/com/cl2/integration/integration/transformation/PassthroughPayloadTransformer.java`
- Test: `src/test/java/com/cl2/integration/integration/transformation/PassthroughPayloadTransformerTest.java`

**Interfaces:**
- Produces: `TransformationEngineType`, `PayloadTransformer`, `TransformationException`, `MissingRequiredFieldException`, `PassthroughPayloadTransformer`.

- [ ] **Step 1: Add JSLT and JsonPath dependencies in `application/pom.xml`**

Add to `application/pom.xml` dependencies:
```xml
        <dependency>
            <groupId>com.schibsted.spt.data</groupId>
            <artifactId>jslt</artifactId>
            <version>0.1.14</version>
        </dependency>
        <dependency>
            <groupId>com.jayway.jsonpath</groupId>
            <artifactId>json-path</artifactId>
        </dependency>
```

- [ ] **Step 2: Create Core Types and Exceptions**

Create `src/main/java/com/cl2/integration/integration/transformation/TransformationEngineType.java`:
```java
package com.cl2.integration.integration.transformation;

public enum TransformationEngineType {
    FIELD_MAPPING,
    JSLT,
    PASSTHROUGH;

    public static TransformationEngineType fromString(String value) {
        if (value == null || value.isBlank()) {
            return PASSTHROUGH;
        }
        try {
            return TransformationEngineType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PASSTHROUGH;
        }
    }
}
```

Create `src/main/java/com/cl2/integration/integration/transformation/TransformationException.java`:
```java
package com.cl2.integration.integration.transformation;

public class TransformationException extends RuntimeException {
    public TransformationException(String message) {
        super(message);
    }
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `src/main/java/com/cl2/integration/integration/transformation/MissingRequiredFieldException.java`:
```java
package com.cl2.integration.integration.transformation;

public class MissingRequiredFieldException extends TransformationException {
    private final String targetField;
    private final String sourcePath;

    public MissingRequiredFieldException(String targetField, String sourcePath) {
        super("Required field '" + targetField + "' missing from source path: " + sourcePath);
        this.targetField = targetField;
        this.sourcePath = sourcePath;
    }

    public String getTargetField() { return targetField; }
    public String getSourcePath() { return sourcePath; }
}
```

Create `src/main/java/com/cl2/integration/integration/transformation/PayloadTransformer.java`:
```java
package com.cl2.integration.integration.transformation;

public interface PayloadTransformer {
    TransformationEngineType getEngineType();
    String transform(String sourcePayload, String configurationJson);
    void validateConfiguration(String configurationJson);
}
```

Create `src/main/java/com/cl2/integration/integration/transformation/PassthroughPayloadTransformer.java`:
```java
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
```

- [ ] **Step 3: Write unit test `PassthroughPayloadTransformerTest.java`**

Create `src/test/java/com/cl2/integration/integration/transformation/PassthroughPayloadTransformerTest.java`:
```java
package com.cl2.integration.integration.transformation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PassthroughPayloadTransformerTest {
    private final PassthroughPayloadTransformer transformer = new PassthroughPayloadTransformer();

    @Test
    void shouldReturnSamePayloadUnaltered() {
        String input = "{\"vin\":\"12345\"}";
        String result = transformer.transform(input, null);
        assertThat(result).isEqualTo(input);
    }

    @Test
    void shouldReturnEmptyObjectWhenNull() {
        String result = transformer.transform(null, null);
        assertThat(result).isEqualTo("{}");
    }

    @Test
    void shouldMatchEngineType() {
        assertThat(transformer.getEngineType()).isEqualTo(TransformationEngineType.PASSTHROUGH);
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn test -Dtest=PassthroughPayloadTransformerTest`
Expected: PASS
```bash
git add application/pom.xml src/main/java/com/cl2/integration/integration/transformation/ src/test/java/com/cl2/integration/integration/transformation/
git commit -m "feat(transformation): add core interfaces, types, and passthrough transformer"
```

---

### Task 2: Field Mapping Engine (`FIELD_MAPPING` - JSONPath + Sandboxed SpEL)

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingRule.java`
- Create: `src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingConfiguration.java`
- Create: `src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformer.java`
- Test: `src/test/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformerTest.java`

**Interfaces:**
- Consumes: `PayloadTransformer`, Jayway `JsonPath`, Spring SpEL `ExpressionParser`, `SimpleEvaluationContext`
- Produces: `FieldMappingPayloadTransformer` executing declarative field-level extractions, SpEL transformations, and type coercions.

- [ ] **Step 1: Create Field Mapping Models**

Create `src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingRule.java`:
```java
package com.cl2.integration.integration.transformation.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMappingRule(
    String target,
    String sourcePath,
    String transform,
    String defaultValue,
    String type,
    boolean required
) {
    public FieldMappingRule {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Field mapping rule must specify a 'target' field name");
        }
    }
}
```

Create `src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingConfiguration.java`:
```java
package com.cl2.integration.integration.transformation.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMappingConfiguration(
    String engine,
    List<FieldMappingRule> fields
) {
    public FieldMappingConfiguration {
        if (fields == null) {
            fields = List.of();
        }
    }
}
```

- [ ] **Step 2: Create `FieldMappingPayloadTransformer.java`**

Create `src/main/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformer.java`:
```java
package com.cl2.integration.integration.transformation.field;

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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FieldMappingPayloadTransformer implements PayloadTransformer {
    private final ObjectMapper objectMapper;
    private final ExpressionParser spelParser;
    private final Configuration jsonPathConfig;
    private final Map<String, Expression> spelCache = new ConcurrentHashMap<>();

    public FieldMappingPayloadTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.spelParser = new SpelExpressionParser();
        this.jsonPathConfig = Configuration.defaultConfiguration()
                .addOptions(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL);
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

            for (FieldMappingRule rule : config.fields()) {
                Object rawValue = extractRawValue(document, rule.sourcePath());

                if (rawValue == null && rule.defaultValue() != null) {
                    rawValue = rule.defaultValue();
                }

                if (rawValue == null && rule.required()) {
                    throw new MissingRequiredFieldException(rule.target(), rule.sourcePath());
                }

                Object transformedValue = rawValue;
                if (rule.transform() != null && !rule.transform().isBlank()) {
                    transformedValue = evaluateSpel(rule.transform(), rawValue);
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
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
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
```

- [ ] **Step 3: Write unit test `FieldMappingPayloadTransformerTest.java`**

Create `src/test/java/com/cl2/integration/integration/transformation/field/FieldMappingPayloadTransformerTest.java`:
```java
package com.cl2.integration.integration.transformation.field;

import com.cl2.integration.integration.transformation.MissingRequiredFieldException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldMappingPayloadTransformerTest {
    private FieldMappingPayloadTransformer transformer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        transformer = new FieldMappingPayloadTransformer(objectMapper);
    }

    @Test
    void shouldTransformFieldsWithJsonPathAndSpel() throws Exception {
        String source = """
            {
              "Vehiculo": {
                "NumeroChasis": "VIN-9999",
                "Marca": "toyota",
                "Anio": "2024",
                "Activo": "1"
              }
            }
            """;

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.Vehiculo.NumeroChasis", "required": true },
                { "target": "brandCode", "sourcePath": "$.Vehiculo.Marca", "transform": "#val.toUpperCase()" },
                { "target": "modelYear", "sourcePath": "$.Vehiculo.Anio", "type": "INTEGER" },
                { "target": "active", "sourcePath": "$.Vehiculo.Activo", "transform": "#val == '1'", "type": "BOOLEAN" }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("vin").asText()).isEqualTo("VIN-9999");
        assertThat(result.get("brandCode").asText()).isEqualTo("TOYOTA");
        assertThat(result.get("modelYear").asInt()).isEqualTo(2024);
        assertThat(result.get("active").asBoolean()).isTrue();
    }

    @Test
    void shouldApplyDefaultValueWhenFieldMissing() throws Exception {
        String source = "{\"Vehiculo\": {}}";
        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "brandCode", "sourcePath": "$.Vehiculo.Marca", "defaultValue": "DEFAULT_BRAND" }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);
        assertThat(result.get("brandCode").asText()).isEqualTo("DEFAULT_BRAND");
    }

    @Test
    void shouldThrowExceptionWhenRequiredFieldMissing() {
        String source = "{\"Vehiculo\": {}}";
        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.Vehiculo.NumeroChasis", "required": true }
              ]
            }
            """;

        assertThatThrownBy(() -> transformer.transform(source, mappingConfig))
                .isInstanceOf(MissingRequiredFieldException.class)
                .hasMessageContaining("vin");
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn test -Dtest=FieldMappingPayloadTransformerTest`
Expected: PASS
```bash
git add src/main/java/com/cl2/integration/integration/transformation/field/ src/test/java/com/cl2/integration/integration/transformation/field/
git commit -m "feat(transformation): implement JSONPath and SpEL field mapping transformer"
```

---

### Task 3: JSLT Script Engine (`JSLT`)

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformer.java`
- Test: `src/test/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformerTest.java`

**Interfaces:**
- Consumes: `PayloadTransformer`, `com.schibsted.spt.data.jslt.Parser`, `com.schibsted.spt.data.jslt.Expression`
- Produces: `JsltPayloadTransformer` compiling and caching JSLT scripts for complex JSON transformations.

- [ ] **Step 1: Create `JsltPayloadTransformer.java`**

Create `src/main/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformer.java`:
```java
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
```

- [ ] **Step 2: Write unit test `JsltPayloadTransformerTest.java`**

Create `src/test/java/com/cl2/integration/integration/transformation/jslt/JsltPayloadTransformerTest.java`:
```java
package com.cl2.integration.integration.transformation.jslt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JsltPayloadTransformerTest {
    private JsltPayloadTransformer transformer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        transformer = new JsltPayloadTransformer(objectMapper);
    }

    @Test
    void shouldTransformNestedJsonAndArrayWithJslt() throws Exception {
        String source = """
            {
              "sap_customer": {
                "header": {
                  "id": "CUST-100",
                  "company_name": "acme corp"
                },
                "addresses": [
                  { "type": "BILLING", "street": "Main St 1" },
                  { "type": "SHIPPING", "street": "Warehouse Ave 2" }
                ]
              }
            }
            """;

        String jsltConfig = """
            {
              "engine": "JSLT",
              "script": "{ \\"customerId\\": .sap_customer.header.id, \\"name\\": uppercase(.sap_customer.header.company_name), \\"shippingAddresses\\": [for (.sap_customer.addresses) .street if (.type == \\"SHIPPING\\")] }"
            }
            """;

        String resultJson = transformer.transform(source, jsltConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("customerId").asText()).isEqualTo("CUST-100");
        assertThat(result.get("name").asText()).isEqualTo("ACME CORP");
        assertThat(result.get("shippingAddresses").isArray()).isTrue();
        assertThat(result.get("shippingAddresses").get(0).asText()).isEqualTo("Warehouse Ave 2");
    }
}
```

- [ ] **Step 3: Run tests and commit**

Run: `mvn test -Dtest=JsltPayloadTransformerTest`
Expected: PASS
```bash
git add src/main/java/com/cl2/integration/integration/transformation/jslt/ src/test/java/com/cl2/integration/integration/transformation/jslt/
git commit -m "feat(transformation): implement JSLT script payload transformer"
```

---

### Task 4: TransformationService Orchestrator & Integration Flow

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/transformation/TransformationService.java`
- Test: `src/test/java/com/cl2/integration/integration/transformation/TransformationServiceIntegrationTest.java`
- Create: `docs/test-cases/test-cases-payload-transformation.md`

**Interfaces:**
- Consumes: `List<PayloadTransformer>`, `IntegrationProfile` / `IntegrationProfileConfiguration`
- Produces: `TransformationService.transform(sourcePayload, profile)` resolving strategy dynamically and executing transformation with error handling.

- [ ] **Step 1: Create `TransformationService.java`**

Create `src/main/java/com/cl2/integration/integration/transformation/TransformationService.java`:
```java
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
```

- [ ] **Step 2: Write Integration Test `TransformationServiceIntegrationTest.java`**

Create `src/test/java/com/cl2/integration/integration/transformation/TransformationServiceIntegrationTest.java`:
```java
package com.cl2.integration.integration.transformation;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.ProtocolType;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TransformationServiceIntegrationTest {

    @Autowired
    private TransformationService transformationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldTransformViaProfileWithFieldMapping() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String mapping = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.NumeroChasis", "required": true },
                { "target": "brand", "sourcePath": "$.Marca", "transform": "#val.toUpperCase()" }
              ]
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                ProtocolType.REST, "sigo", "sigo-adapter", "http://external", null, mapping, null, null, null, null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                tenantId, "Vehicle", "SIGO", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config
        );

        String source = "{\"NumeroChasis\":\"VIN-ABC-123\",\"Marca\":\"nissan\"}";
        String transformed = transformationService.transform(source, profile);

        JsonNode result = objectMapper.readTree(transformed);
        assertThat(result.get("vin").asText()).isEqualTo("VIN-ABC-123");
        assertThat(result.get("brand").asText()).isEqualTo("NISSAN");
    }

    @Test
    void shouldTransformViaProfileWithJslt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String jslt = """
            {
              "engine": "JSLT",
              "script": "{ \\"customerCode\\": .KUNNR, \\"name\\": .NAME1 }"
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                ProtocolType.REST, "sap", "sap-adapter", "http://sap", null, null, jslt, null, null, null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                tenantId, "Customer", "SAP", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config
        );

        String source = "{\"KUNNR\":\"0000100200\",\"NAME1\":\"ACME DISTRIBUCION\"}";
        String transformed = transformationService.transform(source, profile);

        JsonNode result = objectMapper.readTree(transformed);
        assertThat(result.get("customerCode").asText()).isEqualTo("0000100200");
        assertThat(result.get("name").asText()).isEqualTo("ACME DISTRIBUCION");
    }
}
```

- [ ] **Step 3: Create documentation `docs/test-cases/test-cases-payload-transformation.md`**

- [ ] **Step 4: Run full project test suite and commit**

Run: `mvn clean test`
Expected: 100% tests PASS with zero errors.
```bash
git add src/main/java/com/cl2/integration/integration/transformation/ src/test/java/com/cl2/integration/integration/transformation/ docs/test-cases/
git commit -m "feat(transformation): implement TransformationService orchestrator with profile integration"
```
