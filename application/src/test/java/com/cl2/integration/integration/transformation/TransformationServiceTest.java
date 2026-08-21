package com.cl2.integration.integration.transformation;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
import com.cl2.integration.integration.transformation.field.FieldMappingPayloadTransformer;
import com.cl2.integration.integration.transformation.jslt.JsltPayloadTransformer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TransformationServiceTest {

    private TransformationService transformationService;
    private IntegrationMetrics metrics;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        metrics = mock(IntegrationMetrics.class);
        objectMapper = new ObjectMapper();
        List<PayloadTransformer> transformers = List.of(
                new PassthroughPayloadTransformer(),
                new FieldMappingPayloadTransformer(objectMapper),
                new JsltPayloadTransformer(objectMapper)
        );
        transformationService = new TransformationService(transformers, objectMapper, metrics);
    }

    @Test
    @DisplayName("Should transform payload using FieldMapping and record metric")
    void shouldTransformFieldMappingAndRecordMetric() {
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

        assertThat(transformed).contains("VIN-ABC-123");
        verify(metrics).recordTransformation(eq(tenantId.toString()), eq("Vehicle"), eq("FIELD_MAPPING"), anyDouble());
    }

    @Test
    @DisplayName("Should transform payload using JSLT and record metric")
    void shouldTransformJsltAndRecordMetric() {
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

        assertThat(transformed).contains("0000100200");
        verify(metrics).recordTransformation(eq(tenantId.toString()), eq("Customer"), eq("JSLT"), anyDouble());
    }
}
