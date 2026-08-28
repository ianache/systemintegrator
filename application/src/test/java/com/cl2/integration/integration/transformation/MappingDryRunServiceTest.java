package com.cl2.integration.integration.transformation;

import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.model.IntegrationProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MappingDryRunServiceTest {

    private final IntegrationProfileService profileService = mock(IntegrationProfileService.class);
    private final TransformationService transformationService = mock(TransformationService.class);
    private final MappingDryRunService service = new MappingDryRunService(profileService, transformationService);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    private IntegrationProfileView view(IntegrationProfileConfiguration configuration) {
        return new IntegrationProfileView(profileId, tenantId, "units", "comsatel-unidad-api",
                SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, configuration, true,
                Instant.parse("2026-08-14T12:00:00Z"), Instant.parse("2026-08-14T12:00:00Z"), 0);
    }

    @Test
    void runsTheRealTransformationEngineAndReturnsItsOutput() {
        when(profileService.get(tenantId, profileId)).thenReturn(view(null));
        when(transformationService.transform(eq("{\"a\":1}"), any())).thenReturn("{\"a\":1}");

        MappingDryRunResult result = service.run(tenantId, profileId, "{\"a\":1}", "{\"engine\":\"PASSTHROUGH\"}");

        assertThat(result.output()).isEqualTo("{\"a\":1}");
        assertThat(result.error()).isNull();
    }

    @Test
    void usesTheProvidedTransformationJsonRatherThanTheSavedOne() {
        IntegrationProfileConfiguration saved = new IntegrationProfileConfiguration(
                null, null, null, null, null, null, "{\"engine\":\"PASSTHROUGH\"}", null, null, null, null);
        when(profileService.get(tenantId, profileId)).thenReturn(view(saved));
        when(transformationService.transform(any(), any())).thenReturn("transformed");

        service.run(tenantId, profileId, "{}", "{\"engine\":\"FIELD_MAPPING\",\"fields\":[]}");

        ArgumentCaptor<IntegrationProfile> captor = ArgumentCaptor.forClass(IntegrationProfile.class);
        verify(transformationService).transform(eq("{}"), captor.capture());
        assertThat(captor.getValue().configuration().transformation()).isEqualTo("{\"engine\":\"FIELD_MAPPING\",\"fields\":[]}");
    }

    @Test
    void returnsTheRealEngineErrorInsteadOfThrowing() {
        when(profileService.get(tenantId, profileId)).thenReturn(view(null));
        when(transformationService.transform(any(), any()))
                .thenThrow(new MissingRequiredFieldException("vin", "$.vin"));

        MappingDryRunResult result = service.run(tenantId, profileId, "{}", "{\"engine\":\"FIELD_MAPPING\"}");

        assertThat(result.output()).isNull();
        assertThat(result.error()).contains("vin").contains("$.vin");
    }
}
