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
