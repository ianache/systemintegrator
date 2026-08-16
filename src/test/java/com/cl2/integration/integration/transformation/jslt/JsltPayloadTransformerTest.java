package com.cl2.integration.integration.transformation.jslt;

import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsltPayloadTransformerTest {
    private JsltPayloadTransformer transformer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        transformer = new JsltPayloadTransformer(objectMapper);
    }

    @Test
    @DisplayName("Should return JSLT engine type")
    void shouldReturnJsltEngineType() {
        assertThat(transformer.getEngineType()).isEqualTo(TransformationEngineType.JSLT);
    }

    @Test
    @DisplayName("Should transform nested JSON and array filtering with JSLT")
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

    @Test
    @DisplayName("Should transform with raw script string directly")
    void shouldTransformWithRawScriptDirectly() throws Exception {
        String source = "{\"msg\": \"hello world\"}";
        String rawScript = "{ \"message\": uppercase(.msg) }";

        String resultJson = transformer.transform(source, rawScript);
        JsonNode result = objectMapper.readTree(resultJson);

        assertThat(result.get("message").asText()).isEqualTo("HELLO WORLD");
    }

    @Test
    @DisplayName("Should handle empty and blank source payload")
    void shouldHandleEmptyAndBlankSourcePayload() {
        assertThat(transformer.transform(null, "{}")).isEqualTo("{}");
        assertThat(transformer.transform("", "{}")).isEqualTo("{}");
        assertThat(transformer.transform("   ", "{}")).isEqualTo("{}");
    }

    @Test
    @DisplayName("Should return source payload when configuration is empty or blank")
    void shouldReturnSourceWhenConfigurationBlank() {
        String source = "{\"id\": 123}";
        assertThat(transformer.transform(source, null)).isEqualTo(source);
        assertThat(transformer.transform(source, "")).isEqualTo(source);
        assertThat(transformer.transform(source, "   ")).isEqualTo(source);
    }

    @Test
    @DisplayName("Should throw TransformationException on invalid script execution")
    void shouldThrowTransformationExceptionOnInvalidExecution() {
        String source = "{\"id\": 123}";
        String badScriptConfig = "{\"script\": \".foo + \"}";

        assertThatThrownBy(() -> transformer.transform(source, badScriptConfig))
            .isInstanceOf(TransformationException.class)
            .hasMessageStartingWith("JSLT transformation failed:");
    }

    @Test
    @DisplayName("Should validate correct JSLT configuration successfully")
    void shouldValidateCorrectConfiguration() {
        String jsltConfig = "{\"script\": \"{ \\\"id\\\": .id }\"}";
        transformer.validateConfiguration(jsltConfig);

        // Null and blank configurations should pass validation silently
        transformer.validateConfiguration(null);
        transformer.validateConfiguration("");
        transformer.validateConfiguration("   ");
    }

    @Test
    @DisplayName("Should throw TransformationException on invalid JSLT syntax during validation")
    void shouldThrowTransformationExceptionOnInvalidSyntaxValidation() {
        String invalidConfig = "{\"script\": \".id + \"}";

        assertThatThrownBy(() -> transformer.validateConfiguration(invalidConfig))
            .isInstanceOf(TransformationException.class)
            .hasMessageStartingWith("Invalid JSLT script:");
    }
}
