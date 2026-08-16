package com.cl2.integration.integration.transformation.field;

import com.cl2.integration.integration.transformation.MissingRequiredFieldException;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
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
    void shouldReturnCorrectEngineType() {
        assertThat(transformer.getEngineType()).isEqualTo(TransformationEngineType.FIELD_MAPPING);
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

    @Test
    void shouldHandleEmptyOrNullInputsGracefully() {
        assertThat(transformer.transform(null, "{}")).isEqualTo("{}");
        assertThat(transformer.transform("", "{}")).isEqualTo("{}");
        assertThat(transformer.transform("{\"a\":1}", null)).isEqualTo("{\"a\":1}");
        assertThat(transformer.transform("{\"a\":1}", "")).isEqualTo("{\"a\":1}");
    }

    @Test
    void shouldConvertVariousTypesCorrectly() throws Exception {
        String source = """
            {
              "longVal": "1234567890123",
              "doubleVal": "45.67",
              "boolVal": "true",
              "objVal": "{\\"nested\\": \\"ok\\"}"
            }
            """;

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "numLong", "sourcePath": "$.longVal", "type": "LONG" },
                { "target": "numDouble", "sourcePath": "$.doubleVal", "type": "DOUBLE" },
                { "target": "flag", "sourcePath": "$.boolVal", "type": "BOOLEAN" },
                { "target": "subObj", "sourcePath": "$.objVal", "type": "OBJECT" }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("numLong").asLong()).isEqualTo(1234567890123L);
        assertThat(result.get("numDouble").asDouble()).isEqualTo(45.67);
        assertThat(result.get("flag").asBoolean()).isTrue();
        assertThat(result.get("subObj").get("nested").asText()).isEqualTo("ok");
    }

    @Test
    void shouldValidateValidAndInvalidConfigurations() {
        String validConfig = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.Vehiculo.NumeroChasis", "transform": "#val.trim()" }
              ]
            }
            """;
        transformer.validateConfiguration(validConfig);
        transformer.validateConfiguration(null);
        transformer.validateConfiguration("");

        String invalidSpelConfig = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.Vehiculo.NumeroChasis", "transform": "invalid syntax +++" }
              ]
            }
            """;

        assertThatThrownBy(() -> transformer.validateConfiguration(invalidSpelConfig))
                .isInstanceOf(TransformationException.class);
    }
}
