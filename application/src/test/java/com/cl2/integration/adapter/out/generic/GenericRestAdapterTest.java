package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.OAuth2TokenCacheManager;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenericRestAdapterTest {

    private static final Instant WATERMARK = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROFILE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private WireMockServer wireMockServer;
    private String baseUrl;
    private final AdapterFactory adapterFactory = new AdapterFactory();

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        baseUrl = wireMockServer.baseUrl();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should GET customers with watermark substitution, limit, and JSONPath extraction")
    void shouldGetCustomersWithWatermarkSubstitutionLimitAndJsonPathExtraction() {
        GenericRestAdapter adapter = adapterFactory.create();
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/customers", "test-token");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = adapter.extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should send Basic authentication in Authorization header")
    void shouldSendBasicAuthenticationHeader() {
        GenericRestAdapter adapter = adapterFactory.create();
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/basic",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.basic("vault:secret/data/basic", "user-1", "pass-1");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user-1:pass-1".getBytes(StandardCharsets.UTF_8));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/basic"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo(expectedAuth))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = adapter.extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/basic"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo(expectedAuth)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should send Bearer token in Authorization header")
    void shouldSendBearerAuthenticationHeader() {
        GenericRestAdapter adapter = adapterFactory.create();
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/bearer",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/bearer", "test-token");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/bearer"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = adapter.extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/bearer"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should send API Key header")
    void shouldSendApiKeyHeader() {
        GenericRestAdapter adapter = adapterFactory.create();
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/api-key",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.apiKey("vault:secret/data/api-key", "test-api-key");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/api-key"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("X-API-Key", equalTo("test-api-key"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = adapter.extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/api-key"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("X-API-Key", equalTo("test-api-key")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should use OAuth2 token cache boundary and forward configured headers")
    void shouldUseOAuth2TokenCacheBoundaryAndForwardConfiguredHeaders() {
        OAuth2TokenCacheManager tokenCacheManager = mock(OAuth2TokenCacheManager.class);
        GenericRestAdapter adapter = adapterFactory.create(tokenCacheManager);
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/oauth2",
                Map.of("X-Client-App", "inbound-sync", "X-Source-System", "crm"),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.oauth2(
                "vault:secret/data/oauth2",
                "https://auth.example.com/token",
                "client-123",
                "secret-123",
                "read:customers"
        );

        when(tokenCacheManager.getAccessToken(
                eq(TENANT_ID.toString()),
                eq("https://auth.example.com/token"),
                eq("client-123"),
                eq("secret-123"),
                eq("read:customers")
        )).thenReturn("oauth-access-token");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/oauth2"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer oauth-access-token"))
                .withHeader("X-Client-App", equalTo("inbound-sync"))
                .withHeader("X-Source-System", equalTo("crm"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = adapter.extract(profile, config, secret, WATERMARK);

        verify(tokenCacheManager).getAccessToken(
                eq(TENANT_ID.toString()),
                eq("https://auth.example.com/token"),
                eq("client-123"),
                eq("secret-123"),
                eq("read:customers")
        );
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/oauth2"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer oauth-access-token"))
                .withHeader("X-Client-App", equalTo("inbound-sync"))
                .withHeader("X-Source-System", equalTo("crm")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    private static final class AdapterFactory {
        GenericRestAdapter create() {
            return create(mock(OAuth2TokenCacheManager.class));
        }

        GenericRestAdapter create(OAuth2TokenCacheManager tokenCacheManager) {
            return new GenericRestAdapter(
                    RestClient.builder(),
                    new ObjectMapper(),
                    tokenCacheManager,
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
            );
        }
    }

    private IntegrationProfile restProfile() {
        IntegrationProfileConfiguration configuration = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "generic-rest",
                "generic-rest",
                baseUrl,
                "vault:secret/data/rest",
                null,
                null,
                null,
                null,
                null,
                null
        );

        return IntegrationProfile.create(
                PROFILE_ID,
                TENANT_ID,
                "customers",
                "external-api",
                SyncDirection.INBOUND,
                SourceOfTruth.EXTERNAL,
                configuration
        );
    }

    private ExtractionConfig extractionConfig(String path, Map<String, String> headers, Map<String, String> queryParams) {
        return new ExtractionConfig(
                null,
                "lastSyncWithBuffer",
                "customerId",
                100,
                "GET",
                path,
                queryParams,
                headers,
                "$.items[*]",
                "ISO_8601",
                "customerId",
                "updatedAt"
        );
    }
}
