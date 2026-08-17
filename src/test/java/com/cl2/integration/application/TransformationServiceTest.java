package com.cl2.integration.application;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TransformationServiceTest {

    @Test
    void shouldTransformSapRowToCanonicalMap() {
        TransformationService service = new TransformationService();
        String mappingJson = "{\"customerId\":\"KUNNR\",\"taxId\":\"STCD1\",\"legalName\":\"NAME1\"}";
        Map<String, Object> sapRow = Map.of("KUNNR", "100023", "STCD1", "20555555551", "NAME1", "ACME CORP");

        Map<String, Object> result = service.transform(sapRow, mappingJson);

        assertThat(result.get("customerId")).isEqualTo("100023");
        assertThat(result.get("taxId")).isEqualTo("20555555551");
        assertThat(result.get("legalName")).isEqualTo("ACME CORP");
    }
}
