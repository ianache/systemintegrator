package com.cl2.integration.integration.transformation;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("test")
class TransformationServiceIntegrationTest {

    @Autowired
    private TransformationService transformationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldTransformViaProfileWithFieldMapping() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String mapping = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.NumeroChasis", "required": true },
                { "target": "brand", "sourcePath": "$.Marca", "transform": "#val.toUpperCase()" }
              ]
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-adapter", "http://external", null, mapping, null, null, null, null, null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                profileId, tenantId, "Vehicle", "SIGO", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config
        );

        String source = "{\"NumeroChasis\":\"VIN-ABC-123\",\"Marca\":\"nissan\"}";
        String transformed = transformationService.transform(source, profile);

        JsonNode result = objectMapper.readTree(transformed);
        assertThat(result.get("vin").asText()).isEqualTo("VIN-ABC-123");
        assertThat(result.get("brand").asText()).isEqualTo("NISSAN");
    }

    @Test
    void shouldTransformViaProfileWithJslt() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String jslt = """
            {
              "engine": "JSLT",
              "script": "{ \\"customerCode\\": .KUNNR, \\"name\\": .NAME1 }"
            }
            """;

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sap", "sap-adapter", "http://sap", null, null, jslt, null, null, null, null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                profileId, tenantId, "Customer", "SAP", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, config
        );

        String source = "{\"KUNNR\":\"0000100200\",\"NAME1\":\"ACME DISTRIBUCION\"}";
        String transformed = transformationService.transform(source, profile);

        JsonNode result = objectMapper.readTree(transformed);
        assertThat(result.get("customerCode").asText()).isEqualTo("0000100200");
        assertThat(result.get("name").asText()).isEqualTo("ACME DISTRIBUCION");
    }

    @Test
    void shouldPassthroughWhenNoConfigProvided() {
        UUID profileId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IntegrationProfile profile = IntegrationProfile.create(
                profileId, tenantId, "Vehicle", "SIGO", SyncDirection.INBOUND, SourceOfTruth.EXTERNAL, null
        );

        String source = "{\"key\":\"value\"}";
        String transformed = transformationService.transform(source, profile);
        assertThat(transformed).isEqualTo(source);

        String transformedNullProfile = transformationService.transform(source, null);
        assertThat(transformedNullProfile).isEqualTo(source);
    }

    @Test
    void shouldValidateValidConfigurations() {
        String validFieldMapping = """
            {
              "engine": "FIELD_MAPPING",
              "fields": [
                { "target": "vin", "sourcePath": "$.vin" }
              ]
            }
            """;
        assertThatCode(() -> transformationService.validate(validFieldMapping)).doesNotThrowAnyException();

        String validJslt = """
            {
              "engine": "JSLT",
              "script": "{ \\"id\\": .id }"
            }
            """;
        assertThatCode(() -> transformationService.validate(validJslt)).doesNotThrowAnyException();
        assertThatCode(() -> transformationService.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> transformationService.validate("")).doesNotThrowAnyException();
    }
}
