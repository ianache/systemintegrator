package com.cl2.integration.integration.transformation.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VelocityPayloadTransformerTest {

    private VelocityPayloadTransformer transformer;

    @BeforeEach
    void setup() {
        transformer = new VelocityPayloadTransformer(new ObjectMapper());
    }

    @Test
    @DisplayName("Should return VELOCITY engine type")
    void shouldReturnVelocityEngineType() {
        assertThat(transformer.getEngineType()).isEqualTo(TransformationEngineType.VELOCITY);
    }

    @Test
    @DisplayName("Should render top-level fields via $root and bare variable access")
    void shouldRenderTopLevelFieldsViaRootAndBareAccess() {
        String source = "{\"placa\":\"ABC-123\",\"marca\":\"toyota\"}";
        String config = "{\"engine\":\"VELOCITY\",\"script\":\"<Placa>$root.placa</Placa><Marca>$marca</Marca>\"}";

        String result = transformer.transform(source, config);

        assertThat(result).isEqualTo("<Placa>ABC-123</Placa><Marca>toyota</Marca>");
    }

    @Test
    @DisplayName("Should support raw template string directly")
    void shouldTransformWithRawScriptDirectly() {
        String source = "{\"msg\":\"hello\"}";
        String result = transformer.transform(source, "value=$msg");

        assertThat(result).isEqualTo("value=hello");
    }

    @Test
    @DisplayName("Should support #foreach over a nested array")
    void shouldSupportForeachOverNestedArray() {
        String source = "{\"unidades\":[{\"serie\":\"U-001\"},{\"serie\":\"U-002\"}]}";
        String config = "{\"script\":\"#foreach($u in $unidades)[$u.serie]#end\"}";

        String result = transformer.transform(source, config);

        assertThat(result).isEqualTo("[U-001][U-002]");
    }

    @Test
    @DisplayName("Should handle empty and blank source payload")
    void shouldHandleEmptyAndBlankSourcePayload() {
        assertThat(transformer.transform(null, "$x")).isEqualTo("");
        assertThat(transformer.transform("", "$x")).isEqualTo("");
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
        transformer.validateConfiguration("{\"script\":\"$a\"}");
        transformer.validateConfiguration(null);
        transformer.validateConfiguration("");
    }

    @Test
    @DisplayName("Should throw TransformationException on invalid template syntax")
    void shouldThrowTransformationExceptionOnInvalidSyntax() {
        String invalidConfig = "{\"script\":\"#foreach($x in $items)no end\"}";

        assertThatThrownBy(() -> transformer.validateConfiguration(invalidConfig))
                .isInstanceOf(TransformationException.class)
                .hasMessageStartingWith("Invalid Velocity template:");
    }
}
