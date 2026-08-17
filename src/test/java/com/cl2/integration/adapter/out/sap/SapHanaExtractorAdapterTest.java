package com.cl2.integration.adapter.out.sap;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SapHanaExtractorAdapterTest {

    @Test
    void shouldExtractDeltaRecordsFromSapMock() {
        SapHanaExtractorAdapter adapter = new SapHanaExtractorAdapter();
        List<Map<String, Object>> mockResults = adapter.extractMockRecords("2026-08-16T20:00:00Z");

        assertThat(mockResults).isNotEmpty();
        assertThat(mockResults.get(0)).containsKey("KUNNR");
    }
}
