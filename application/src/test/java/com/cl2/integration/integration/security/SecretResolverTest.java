package com.cl2.integration.integration.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretResolverTest {
    private InMemorySecretResolver inMemoryResolver;
    private VaultSecretResolver vaultResolver;
    private WireMockServer wireMockServer;

    @BeforeEach
    void setup() {
        inMemoryResolver = new InMemorySecretResolver();
        VaultProperties props = new VaultProperties();
        props.setEnabled(false);
        vaultResolver = new VaultSecretResolver(props, inMemoryResolver);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
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
    void shouldStoreAndResolveOAuth2SecretInMemory() {
        UUID tenantId = UUID.randomUUID();
        String ref = "vault:secret/data/tenants/" + tenantId + "/oauth2";
        ResolvedSecret secret = ResolvedSecret.oauth2(
                ref,
                "https://auth.example.com/realms/master/protocol/openid-connect/token",
                "client-id-123",
                "client-secret-456",
                "openid profile"
        );

        vaultResolver.putSecret(ref, tenantId, secret);

        ResolvedSecret resolved = vaultResolver.resolve(ref, tenantId);
        assertThat(resolved).isNotNull();
        assertThat(resolved.authType()).isEqualTo(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        assertThat(resolved.tokenUrl()).isEqualTo("https://auth.example.com/realms/master/protocol/openid-connect/token");
        assertThat(resolved.clientId()).isEqualTo("client-id-123");
        assertThat(resolved.clientSecret()).isEqualTo("client-secret-456");
        assertThat(resolved.scope()).isEqualTo("openid profile");
    }

    @Test
    void shouldResolveOAuth2SecretFromVaultWhenEnabled() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        VaultProperties props = new VaultProperties();
        props.setEnabled(true);
        props.setUri("http://localhost:" + wireMockServer.port());
        props.setToken("test-token");
        props.setCacheTtlSeconds(300);

        VaultSecretResolver enabledVaultResolver = new VaultSecretResolver(props, inMemoryResolver, RestClient.builder());

        UUID tenantId = UUID.randomUUID();
        String ref = "vault:secret/data/tenants/" + tenantId + "/keycloak";

        wireMockServer.stubFor(get(urlEqualTo("/v1/secret/data/tenants/" + tenantId + "/keycloak"))
                .withHeader("X-Vault-Token", equalTo("test-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "data": {
                                      "tokenUrl": "http://localhost:8080/realms/master/protocol/openid-connect/token",
                                      "clientId": "units-client",
                                      "clientSecret": "super-secret",
                                      "scope": "units:write"
                                    }
                                  }
                                }
                                """)));

        ResolvedSecret resolved = enabledVaultResolver.resolve(ref, tenantId);
        assertThat(resolved).isNotNull();
        assertThat(resolved.authType()).isEqualTo(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        assertThat(resolved.tokenUrl()).isEqualTo("http://localhost:8080/realms/master/protocol/openid-connect/token");
        assertThat(resolved.clientId()).isEqualTo("units-client");
        assertThat(resolved.clientSecret()).isEqualTo("super-secret");
        assertThat(resolved.scope()).isEqualTo("units:write");
    }

    @Test
    void shouldResolveOAuth2SecretWithSnakeCaseKeysFromVault() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        VaultProperties props = new VaultProperties();
        props.setEnabled(true);
        props.setUri("http://localhost:" + wireMockServer.port());
        props.setToken("test-token");
        props.setCacheTtlSeconds(300);

        VaultSecretResolver enabledVaultResolver = new VaultSecretResolver(props, inMemoryResolver, RestClient.builder());

        UUID tenantId = UUID.randomUUID();
        String ref = "vault:secret/data/tenants/" + tenantId + "/keycloak-snake";

        wireMockServer.stubFor(get(urlEqualTo("/v1/secret/data/tenants/" + tenantId + "/keycloak-snake"))
                .withHeader("X-Vault-Token", equalTo("test-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "data": {
                                      "token_url": "http://localhost:8080/realms/master/protocol/openid-connect/token",
                                      "client_id": "units-client-2",
                                      "client_secret": "super-secret-2"
                                    }
                                  }
                                }
                                """)));

        ResolvedSecret resolved = enabledVaultResolver.resolve(ref, tenantId);
        assertThat(resolved).isNotNull();
        assertThat(resolved.authType()).isEqualTo(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        assertThat(resolved.tokenUrl()).isEqualTo("http://localhost:8080/realms/master/protocol/openid-connect/token");
        assertThat(resolved.clientId()).isEqualTo("units-client-2");
        assertThat(resolved.clientSecret()).isEqualTo("super-secret-2");
        assertThat(resolved.scope()).isNull();
    }

    @Test
    void shouldThrowExceptionWhenSecretNotFound() {
        UUID tenantId = UUID.randomUUID();
        assertThatThrownBy(() -> vaultResolver.resolve("vault:secret/data/unknown", tenantId))
                .isInstanceOf(SecretNotFoundException.class)
                .hasMessageContaining("vault:secret/data/unknown");
    }
}
