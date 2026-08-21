package com.cl2.integration.integration.lookup;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.lookup.adapter.in.web.dto.CreateValueLookupRequest;
import com.cl2.integration.integration.lookup.application.ValueLookupService;
import com.cl2.integration.integration.transformation.TransformationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ValueLookupIntegrationTest {

    @Autowired
    private ValueLookupService valueLookupService;

    @Autowired
    private TransformationService transformationService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("End-to-End: Save lookups and transform using JSLT with lookup() function")
    void shouldTransformUsingJsltWithLookupFunctionEndToEnd() throws Exception {
        // 1. Seed lookups via ValueLookupService
        valueLookupService.save(tenantId, new CreateValueLookupRequest(
                UUID.randomUUID(), "SAP", "VEHICLE_TYPE", "CAR_SEDAN", "1", "Sedan Vehicle", true
        ));
        valueLookupService.save(tenantId, new CreateValueLookupRequest(
                UUID.randomUUID(), "SAP", "VEHICLE_TYPE", "TRUCK_HEAVY", "2", "Heavy Truck", true
        ));

        // 2. Setup IntegrationProfile with JSLT script
        String jslt = """
            {
              "engine": "JSLT",
              "script": "{ \\"vehicleId\\": .id, \\"typeCode\\": lookup(\\"VEHICLE_TYPE\\", .rawType, \\"99\\", \\"SAP\\") }"
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sap", "sap-adapter", "http://sap", null, null, jslt, null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "Vehicle", "SAP", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config
        );

        // 3. Test matching lookup
        String source1 = "{\"id\":\"V-001\",\"rawType\":\"CAR_SEDAN\"}";
        String transformed1 = transformationService.transform(source1, profile);
        JsonNode result1 = objectMapper.readTree(transformed1);

        assertThat(result1.get("vehicleId").asText()).isEqualTo("V-001");
        assertThat(result1.get("typeCode").asText()).isEqualTo("1");

        // 4. Test fallback to default value when not found
        String source2 = "{\"id\":\"V-002\",\"rawType\":\"MOTORBIKE\"}";
        String transformed2 = transformationService.transform(source2, profile);
        JsonNode result2 = objectMapper.readTree(transformed2);

        assertThat(result2.get("vehicleId").asText()).isEqualTo("V-002");
        assertThat(result2.get("typeCode").asText()).isEqualTo("99");
    }

    @Test
    @DisplayName("End-to-End: Save batch lookups and transform using FIELD_MAPPING with lookup rule")
    void shouldTransformUsingFieldMappingWithLookupEndToEnd() throws Exception {
        // 1. Seed batch lookups
        valueLookupService.saveBatch(tenantId, List.of(
                new CreateValueLookupRequest(UUID.randomUUID(), "SIGO", "STATUS_MAP", "ACT", "ACTIVE", "Active status", true),
                new CreateValueLookupRequest(UUID.randomUUID(), "SIGO", "STATUS_MAP", "INA", "INACTIVE", "Inactive status", true)
        ));

        // 2. Setup IntegrationProfile with FIELD_MAPPING
        String fieldMapping = """
            {
              "engine": "FIELD_MAPPING",
              "externalSource": "SIGO",
              "fields": [
                {
                  "target": "accountStatus",
                  "sourcePath": "$.estado",
                  "lookup": {
                    "catalogCode": "STATUS_MAP",
                    "defaultValue": "UNKNOWN"
                  }
                }
              ]
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-adapter", "http://sigo", null, fieldMapping, null, null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "Account", "SIGO", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config
        );

        // 3. Test matching lookup
        String source1 = "{\"estado\":\"ACT\"}";
        String transformed1 = transformationService.transform(source1, profile);
        JsonNode result1 = objectMapper.readTree(transformed1);
        assertThat(result1.get("accountStatus").asText()).isEqualTo("ACTIVE");

        // 4. Test fallback lookup
        String source2 = "{\"estado\":\"PEND\"}";
        String transformed2 = transformationService.transform(source2, profile);
        JsonNode result2 = objectMapper.readTree(transformed2);
        assertThat(result2.get("accountStatus").asText()).isEqualTo("UNKNOWN");
    }
}
