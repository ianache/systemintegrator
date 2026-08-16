package com.cl2.integration.integration.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretResolverTest {
    private InMemorySecretResolver inMemoryResolver;
    private VaultSecretResolver vaultResolver;

    @BeforeEach
    void setup() {
        inMemoryResolver = new InMemorySecretResolver();
        VaultProperties props = new VaultProperties();
        props.setEnabled(false);
        vaultResolver = new VaultSecretResolver(props, inMemoryResolver);
    }

    @Test
    void shouldStoreAndResolveSecretByTenant() {
        UUID tenantId = UUID.randomUUID();
        String ref = "vault:secret/data/tenants/" + tenantId + "/sap";
        ResolvedSecret secret = ResolvedSecret.basic(ref, "sap_user", "sap_pass");

        vaultResolver.putSecret(ref, tenantId, secret);

        ResolvedSecret resolved = vaultResolver.resolve(ref, tenantId);
        assertThat(resolved).isNotNull();
        assertThat(resolved.username()).isEqualTo("sap_user");
        assertThat(resolved.password()).isEqualTo("sap_pass");
        assertThat(resolved.authType()).isEqualTo(AuthType.BASIC);
    }

    @Test
    void shouldThrowExceptionWhenSecretNotFound() {
        UUID tenantId = UUID.randomUUID();
        assertThatThrownBy(() -> vaultResolver.resolve("vault:secret/data/unknown", tenantId))
                .isInstanceOf(SecretNotFoundException.class)
                .hasMessageContaining("vault:secret/data/unknown");
    }
}
