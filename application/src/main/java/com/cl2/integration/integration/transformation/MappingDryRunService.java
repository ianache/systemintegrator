package com.cl2.integration.integration.transformation;

import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.IntegrationProfileView;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MappingDryRunService {

    private final IntegrationProfileService profileService;
    private final TransformationService transformationService;

    public MappingDryRunService(IntegrationProfileService profileService, TransformationService transformationService) {
        this.profileService = profileService;
        this.transformationService = transformationService;
    }

    public MappingDryRunResult run(UUID tenantId, UUID profileId, String payload, String transformationJson) {
        IntegrationProfileView view = profileService.get(tenantId, profileId);
        IntegrationProfileConfiguration baseConfig = view.configuration();

        IntegrationProfileConfiguration dryRunConfig = new IntegrationProfileConfiguration(
                baseConfig != null ? baseConfig.protocol() : null,
                baseConfig != null ? baseConfig.connector() : null,
                baseConfig != null ? baseConfig.adapter() : null,
                baseConfig != null ? baseConfig.endpoint() : null,
                baseConfig != null ? baseConfig.credentialRef() : null,
                baseConfig != null ? baseConfig.mapping() : null,
                transformationJson,
                baseConfig != null ? baseConfig.syncPolicy() : null,
                baseConfig != null ? baseConfig.retryPolicy() : null,
                baseConfig != null ? baseConfig.rateLimitPolicy() : null,
                baseConfig != null ? baseConfig.extractionConfig() : null
        );

        IntegrationProfile dryRunProfile = IntegrationProfile.create(
                view.id(), view.tenantId(), view.businessDomain(), view.externalSource(),
                view.direction(), view.sourceOfTruth(), dryRunConfig
        );

        try {
            String output = transformationService.transform(payload, dryRunProfile);
            return MappingDryRunResult.success(output);
        } catch (Exception ex) {
            return MappingDryRunResult.failure(ex.getMessage());
        }
    }
}
