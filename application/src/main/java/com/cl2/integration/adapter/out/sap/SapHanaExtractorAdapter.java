package com.cl2.integration.adapter.out.sap;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class SapHanaExtractorAdapter {

    public List<Map<String, Object>> extractMockRecords(String lastSyncAt) {
        return List.of(
            Map.of(
                "KUNNR", "CLI-9901",
                "STCD1", "20123456789",
                "NAME1", "SAP CUSTOMER IMPORTED",
                "STRAS", "AV. SAP 456",
                "LAND1", "PE"
            )
        );
    }
}
