package com.cl2.integration.integration.transformation.field;

import com.cl2.integration.integration.transformation.MissingRequiredFieldException;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.lookup.application.ValueLookupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FieldMappingPayloadTransformerTest {
    private FieldMappingPayloadTransformer transformer;
    private ValueLookupService valueLookupService;
    private ObjectMapper objectMapper;
    private static final UUID TEST_TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setup() {
        TenantContext.set(TEST_TENANT_ID);
        objectMapper = new ObjectMapper();
        valueLookupService = Mockito.mock(ValueLookupService.class);
        transformer = new FieldMappingPayloadTransformer(objectMapper, valueLookupService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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

    @Test
    void shouldResolveValueLookupWhenRuleContainsLookup() throws Exception {
        when(valueLookupService.lookup(TEST_TENANT_ID, "SIGO", "TIPO_VEHICULO", "AUTO", null))
                .thenReturn("1");

        String source = """
            {
              "Vehiculo": {
                "Tipo": "AUTO"
              }
            }
            """;

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "externalSource": "SIGO",
              "fields": [
                {
                  "target": "vehicleType",
                  "sourcePath": "$.Vehiculo.Tipo",
                  "lookup": {
                    "catalogCode": "TIPO_VEHICULO"
                  }
                }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("vehicleType").asText()).isEqualTo("1");
        verify(valueLookupService).lookup(TEST_TENANT_ID, "SIGO", "TIPO_VEHICULO", "AUTO", null);
    }

    @Test
    void shouldUseLookupDefaultValueWhenLookupEntryDoesNotExist() throws Exception {
        when(valueLookupService.lookup(TEST_TENANT_ID, "SIGO", "TIPO_VEHICULO", "CAMION", "99"))
                .thenReturn("99");

        String source = """
            {
              "Vehiculo": {
                "Tipo": "CAMION"
              }
            }
            """;

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "externalSource": "SIGO",
              "fields": [
                {
                  "target": "vehicleType",
                  "sourcePath": "$.Vehiculo.Tipo",
                  "lookup": {
                    "catalogCode": "TIPO_VEHICULO",
                    "defaultValue": "99"
                  }
                }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("vehicleType").asText()).isEqualTo("99");
    }

    @Test
    void shouldKeepOriginalValueWhenLookupNotFoundAndNoDefault() throws Exception {
        when(valueLookupService.lookup(TEST_TENANT_ID, "SIGO", "TIPO_VEHICULO", "UNKNOWN_TYPE", null))
                .thenReturn(null);

        String source = """
            {
              "Vehiculo": {
                "Tipo": "UNKNOWN_TYPE"
              }
            }
            """;

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "externalSource": "SIGO",
              "fields": [
                {
                  "target": "vehicleType",
                  "sourcePath": "$.Vehiculo.Tipo",
                  "lookup": {
                    "catalogCode": "TIPO_VEHICULO"
                  }
                }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("vehicleType").asText()).isEqualTo("UNKNOWN_TYPE");
    }

    @Test
    void shouldHandleLookupWhenSourceValueIsNullAndLookupDefaultProvided() throws Exception {
        String source = "{\"Vehiculo\": {}}";

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "externalSource": "SIGO",
              "fields": [
                {
                  "target": "vehicleType",
                  "sourcePath": "$.Vehiculo.Tipo",
                  "lookup": {
                    "catalogCode": "TIPO_VEHICULO",
                    "defaultValue": "DEFAULT_TYPE"
                  }
                }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("vehicleType").asText()).isEqualTo("DEFAULT_TYPE");
    }

    @Test
    void shouldAllowRuleToOverrideExternalSource() throws Exception {
        when(valueLookupService.lookup(TEST_TENANT_ID, "OVERRIDDEN_SOURCE", "TIPO_VEHICULO", "AUTO", null))
                .thenReturn("10");

        String source = """
            {
              "Vehiculo": {
                "Tipo": "AUTO"
              }
            }
            """;

        String mappingConfig = """
            {
              "engine": "FIELD_MAPPING",
              "externalSource": "SIGO",
              "fields": [
                {
                  "target": "vehicleType",
                  "sourcePath": "$.Vehiculo.Tipo",
                  "lookup": {
                    "catalogCode": "TIPO_VEHICULO",
                    "externalSource": "OVERRIDDEN_SOURCE"
                  }
                }
              ]
            }
            """;

        String resultJson = transformer.transform(source, mappingConfig);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("vehicleType").asText()).isEqualTo("10");
        verify(valueLookupService).lookup(TEST_TENANT_ID, "OVERRIDDEN_SOURCE", "TIPO_VEHICULO", "AUTO", null);
    }
}
