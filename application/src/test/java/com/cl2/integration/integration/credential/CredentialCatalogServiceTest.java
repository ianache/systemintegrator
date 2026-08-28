package com.cl2.integration.integration.credential;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.security.AuthType;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretNotFoundException;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.security.VaultProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CredentialCatalogServiceTest {

    private final IntegrationProfileRepository profileRepository = mock(IntegrationProfileRepository.class);
    private final SecretResolver secretResolver = mock(SecretResolver.class);
    private final VaultProperties vaultProperties = new VaultProperties();
    private final CredentialCatalogService service =
            new CredentialCatalogService(profileRepository, secretResolver, vaultProperties);

    private final UUID tenantId = UUID.randomUUID();

    private IntegrationProfile profile(String domain, String source, String credentialRef, boolean active) {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "connector", "adapter", "https://example.test", credentialRef,
                null, null, null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, domain, source, SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );
        return active ? profile : profile.deactivate();
    }

    @Test
    void groupsProfilesBySharedCredentialRef() {
        when(profileRepository.findAll(tenantId, false)).thenReturn(List.of(
                profile("units", "comsatel-unidad-api", "secret/cl2/shared-cred", true),
                profile("orders", "erp", "secret/cl2/shared-cred", true),
                profile("brands", "cl2-core", "secret/cl2/other-cred", true)
        ));
        when(secretResolver.resolve(any(), eq(tenantId))).thenThrow(new SecretNotFoundException("x"));

        List<CredentialSummary> result = service.list(tenantId);

        assertThat(result).hasSize(2);
        CredentialSummary shared = result.stream().filter(c -> c.ref().equals("secret/cl2/shared-cred")).findFirst().orElseThrow();
        assertThat(shared.usedBy()).containsExactlyInAnyOrder("units · comsatel-unidad-api", "orders · erp");
    }

    @Test
    void ignoresProfilesWithoutACredentialRef() {
        when(profileRepository.findAll(tenantId, false)).thenReturn(List.of(
                profile("units", "comsatel-unidad-api", null, true)
        ));

        assertThat(service.list(tenantId)).isEmpty();
    }

    @Test
    void marksACredentialAsVigenteWhenItResolvesSuccessfully() {
        when(profileRepository.findAll(tenantId, false)).thenReturn(List.of(
                profile("units", "comsatel-unidad-api", "secret/cl2/valid-cred", true)
        ));
        when(secretResolver.resolve("secret/cl2/valid-cred", tenantId))
                .thenReturn(ResolvedSecret.bearer("secret/cl2/valid-cred", "token"));

        CredentialSummary summary = service.list(tenantId).get(0);

        assertThat(summary.state()).isEqualTo("VIGENTE");
        assertThat(summary.type()).isEqualTo(AuthType.BEARER.name());
    }

    @Test
    void marksACredentialAsUnverifiedWhenItCannotBeResolved() {
        when(profileRepository.findAll(tenantId, false)).thenReturn(List.of(
                profile("units", "comsatel-unidad-api", "secret/cl2/missing-cred", true)
        ));
        when(secretResolver.resolve("secret/cl2/missing-cred", tenantId)).thenThrow(new SecretNotFoundException("secret/cl2/missing-cred"));

        CredentialSummary summary = service.list(tenantId).get(0);

        assertThat(summary.state()).isEqualTo("SIN_VERIFICAR");
        assertThat(summary.type()).isNull();
    }

    @Test
    void doesNotAttemptVaultMetadataLookupWhenVaultIsDisabled() {
        vaultProperties.setEnabled(false);
        when(profileRepository.findAll(tenantId, false)).thenReturn(List.of(
                profile("units", "comsatel-unidad-api", "secret/cl2/valid-cred", true)
        ));
        when(secretResolver.resolve(any(), eq(tenantId))).thenReturn(ResolvedSecret.bearer("secret/cl2/valid-cred", "token"));

        CredentialSummary summary = service.list(tenantId).get(0);

        assertThat(summary.rotatedAt()).isNull();
    }
}
