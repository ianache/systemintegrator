package com.cl2.integration.integration.security;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.resilience.DistributedRateLimiter;
import com.cl2.integration.integration.resilience.RateLimitResult;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RuntimeSecurityResilienceIntegrationTest {

    @Autowired
    private SecretResolver secretResolver;

    @Autowired
    private DistributedRateLimiter rateLimiter;

    @Autowired
    private ResilienceExecutor resilienceExecutor;

    @Test
    void shouldResolveSecretAndExecuteProtectedOutboundCall() {
        UUID tenantId = UUID.randomUUID();
        String credentialRef = "vault:secret/data/tenants/" + tenantId + "/sap";
        ResolvedSecret secret = ResolvedSecret.bearer(credentialRef, "secure-oauth-token-12345");
        secretResolver.putSecret(credentialRef, tenantId, secret);

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sap", "sap-customer-adapter", "https://sap.corp.internal/api",
                credentialRef, null, null, null, null, null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "Customer", "SAP", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );

        ResolvedSecret resolved = secretResolver.resolve(profile.configuration().credentialRef(), tenantId);
        assertThat(resolved.token()).isEqualTo("secure-oauth-token-12345");

        RateLimitResult rateLimit = rateLimiter.tryAcquire(tenantId, profile.configuration().connector(), 100, "MINUTE");
        assertThat(rateLimit.allowed()).isTrue();

        String callResult = resilienceExecutor.execute(tenantId, profile.configuration().connector(), () -> "SAP_CUSTOMER_SYNCED_200");
        assertThat(callResult).isEqualTo("SAP_CUSTOMER_SYNCED_200");
    }
}
