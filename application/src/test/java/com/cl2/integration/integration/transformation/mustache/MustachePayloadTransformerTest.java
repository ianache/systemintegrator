package com.cl2.integration.integration.transformation.mustache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MustachePayloadTransformerTest {

    private MustachePayloadTransformer transformer;

    @BeforeEach
    void setup() {
        transformer = new MustachePayloadTransformer(new ObjectMapper());
    }

    @Test
    @DisplayName("Should return MUSTACHE engine type")
    void shouldReturnMustacheEngineType() {
        assertThat(transformer.getEngineType()).isEqualTo(TransformationEngineType.MUSTACHE);
    }

    @Test
    @DisplayName("Should interpolate top-level and nested fields")
    void shouldInterpolateTopLevelAndNestedFields() {
        String source = "{\"placa\":\"ABC-123\",\"propietario\":{\"nombre\":\"Transportes Andina SAC\"}}";
        String config = "{\"engine\":\"MUSTACHE\",\"script\":\"Unidad {{placa}} dada de baja. Titular: {{propietario.nombre}}\"}";

        String result = transformer.transform(source, config);

        assertThat(result).isEqualTo("Unidad ABC-123 dada de baja. Titular: Transportes Andina SAC");
    }

    @Test
    @DisplayName("Should support raw template string directly")
    void shouldTransformWithRawScriptDirectly() {
        String source = "{\"msg\":\"hello\"}";
        String result = transformer.transform(source, "value={{msg}}");

        assertThat(result).isEqualTo("value=hello");
    }

    @Test
    @DisplayName("Should render sections over arrays")
    void shouldRenderSectionsOverArrays() {
        String source = "{\"items\":[{\"serie\":\"U-001\"},{\"serie\":\"U-002\"}]}";
        String config = "{\"script\":\"{{#items}}[{{serie}}]{{/items}}\"}";

        String result = transformer.transform(source, config);

        assertThat(result).isEqualTo("[U-001][U-002]");
    }

    @Test
    @DisplayName("Should handle empty and blank source payload")
    void shouldHandleEmptyAndBlankSourcePayload() {
        assertThat(transformer.transform(null, "{{x}}")).isEqualTo("");
        assertThat(transformer.transform("", "{{x}}")).isEqualTo("");
    }

    @Test
    @DisplayName("Should return source payload when configuration is empty or blank")
    void shouldReturnSourceWhenConfigurationBlank() {
        String source = "{\"id\":123}";
        assertThat(transformer.transform(source, null)).isEqualTo(source);
        assertThat(transformer.transform(source, "")).isEqualTo(source);
    }

    @Test
    @DisplayName("Should validate a correct template without throwing")
    void shouldValidateCorrectConfiguration() {
        transformer.validateConfiguration("{\"script\":\"{{a}}\"}");
        transformer.validateConfiguration(null);
        transformer.validateConfiguration("");
    }

    @Test
    @DisplayName("Should throw TransformationException on invalid template syntax")
    void shouldThrowTransformationExceptionOnInvalidSyntax() {
        String invalidConfig = "{\"script\":\"{{#unclosed}}\"}";

        assertThatThrownBy(() -> transformer.validateConfiguration(invalidConfig))
                .isInstanceOf(TransformationException.class)
                .hasMessageStartingWith("Invalid Mustache template:");
    }
}
